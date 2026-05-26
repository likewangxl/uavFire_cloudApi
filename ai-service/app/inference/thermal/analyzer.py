from typing import Any, Callable, Optional, Protocol, Tuple

from app.models.event import ThermalMeasureRoi
from app.models.frame import FramePacket


class ThermalAnalyzer(Protocol):
    """Frame-based thermal-channel analyzer returning confidence in [0.0, 1.0]."""

    def analyze(self, frame: FramePacket) -> float:
        ...


class StubThermalAnalyzer:
    """Constant-score thermal analyzer for tests and no-model environments."""

    def __init__(self, score: float = 0.7) -> None:
        self._score = float(score)

    def analyze(self, frame: FramePacket) -> float:
        if frame.channel != "thermal" or frame.frame is None:
            return 0.0
        return self._score


class HotSpotThermalAnalyzer:
    """Heuristic thermal analyzer based on hot-pixel ratio.

    Reduces a frame to a 2D intensity surface (max over color channels by
    default) and computes the fraction of pixels above `intensity_threshold`.
    Score is `hot_ratio / saturation_ratio`, clamped to [0, 1].

    Designed for thermal palette frames where hotspots map to high-luminance
    pixels. Inject `intensity_extractor` to bypass numpy in tests.
    """

    def __init__(
        self,
        intensity_threshold: int = 200,
        saturation_ratio: float = 0.05,
        intensity_extractor: Optional[Callable[[Any], Any]] = None,
    ) -> None:
        self._intensity_threshold = int(intensity_threshold)
        self._saturation_ratio = float(saturation_ratio)
        self._intensity_extractor = intensity_extractor
        self.last_measure_roi: Optional[ThermalMeasureRoi] = None

    def analyze(self, frame: FramePacket) -> float:
        self.last_measure_roi = None
        if frame.channel != "thermal" or frame.frame is None:
            return 0.0
        extractor = self._intensity_extractor or _default_intensity_extractor()
        intensity = extractor(frame.frame)
        hot_ratio, total = _hot_ratio(intensity, self._intensity_threshold)
        self.last_measure_roi = _hot_roi(intensity, self._intensity_threshold)
        if total <= 0:
            return 0.0
        if self._saturation_ratio <= 0:
            return 1.0 if hot_ratio > 0 else 0.0
        return min(hot_ratio / self._saturation_ratio, 1.0)


def _default_intensity_extractor() -> Callable[[Any], Any]:
    import numpy as np

    def _extract(frame_array: Any) -> Any:
        arr = np.asarray(frame_array)
        if arr.ndim >= 3:
            return arr.max(axis=-1)
        return arr

    return _extract


def _hot_ratio(intensity: Any, threshold: int) -> Tuple[float, int]:
    size_attr = getattr(intensity, "size", None)
    if callable(getattr(intensity, "__ge__", None)) and size_attr is not None:
        try:
            mask = intensity >= threshold
            sum_attr = getattr(mask, "sum", None)
            if callable(sum_attr):
                size = int(size_attr) if not callable(size_attr) else int(size_attr())
                if size == 0:
                    return 0.0, 0
                return float(int(sum_attr())) / size, size
        except (TypeError, ValueError):
            pass
    try:
        seq = list(intensity)
    except TypeError:
        return 0.0, 0
    if not seq:
        return 0.0, 0
    hot = sum(1 for v in seq if v >= threshold)
    return hot / len(seq), len(seq)


def _hot_roi(intensity: Any, threshold: int) -> Optional[ThermalMeasureRoi]:
    try:
        import numpy as np

        arr = np.asarray(intensity)
        if arr.ndim != 2 or arr.size == 0:
            return None
        x0, y0, x1, y1 = _active_content_bounds(arr)
        active = arr[y0:y1, x0:x1]
        if active.size == 0:
            return None
        mask = active >= threshold
        if not bool(mask.any()):
            return None
        hot_values = active[mask]
        peak = float(hot_values.max())
        core_threshold = max(float(threshold), peak - HOT_CORE_INTENSITY_DELTA)
        core_mask = active >= core_threshold
        if bool(core_mask.any()):
            mask = core_mask
        mask = _select_measurement_component(active, mask)
        ys, xs = np.where(mask)
        height, width = active.shape
        hot_x1 = int(xs.min())
        hot_x2 = int(xs.max()) + 1
        hot_y1 = int(ys.min())
        hot_y2 = int(ys.max()) + 1
        roi_width = max((hot_x2 - hot_x1) / width, MIN_MEASURE_ROI_SIZE)
        roi_height = max((hot_y2 - hot_y1) / height, MIN_MEASURE_ROI_SIZE)
        center_x = (hot_x1 + hot_x2) / 2 / width
        center_y = (hot_y1 + hot_y2) / 2 / height
        roi_x = min(max(center_x - roi_width / 2, 0.0), max(1.0 - roi_width, 0.0))
        roi_y = min(max(center_y - roi_height / 2, 0.0), max(1.0 - roi_height, 0.0))
        return ThermalMeasureRoi(
            x=round(roi_x, 4),
            y=round(roi_y, 4),
            width=round(roi_width, 4),
            height=round(roi_height, 4),
        )
    except Exception:
        return None


def _select_measurement_component(active: Any, mask: Any) -> Any:
    import numpy as np

    try:
        import cv2
    except Exception:
        return mask
    labels_count, labels, stats, centroids = cv2.connectedComponentsWithStats(mask.astype("uint8"), 8)
    if labels_count <= 2:
        return mask
    height, width = active.shape
    center_x = width / 2
    center_y = height / 2
    best_label: Optional[int] = None
    best_score: Optional[Tuple[float, float, int]] = None
    for label in range(1, labels_count):
        area = int(stats[label, cv2.CC_STAT_AREA])
        if area < MIN_CORE_COMPONENT_AREA:
            continue
        component_mask = labels == label
        peak = float(active[component_mask].max())
        cx, cy = centroids[label]
        distance = ((float(cx) - center_x) / max(width, 1)) ** 2 + ((float(cy) - center_y) / max(height, 1)) ** 2
        score = (peak, -distance, area)
        if best_score is None or score > best_score:
            best_label = label
            best_score = score
    if best_label is None:
        return mask
    return labels == best_label


def _active_content_bounds(intensity: Any) -> Tuple[int, int, int, int]:
    import numpy as np

    arr = np.asarray(intensity)
    height, width = arr.shape
    active_columns = np.where(arr.max(axis=0) > BLACK_BAR_INTENSITY_FLOOR)[0]
    active_rows = np.where(arr.max(axis=1) > BLACK_BAR_INTENSITY_FLOOR)[0]
    if active_columns.size:
        x0 = int(active_columns.min())
        x1 = int(active_columns.max()) + 1
        if (x1 - x0) / width < MIN_ACTIVE_AXIS_RATIO:
            x0, x1 = 0, width
    else:
        x0, x1 = 0, width
    if active_rows.size:
        y0 = int(active_rows.min())
        y1 = int(active_rows.max()) + 1
        if (y1 - y0) / height < MIN_ACTIVE_AXIS_RATIO:
            y0, y1 = 0, height
    else:
        y0, y1 = 0, height
    return x0, y0, x1, y1


BLACK_BAR_INTENSITY_FLOOR = 8
MIN_ACTIVE_AXIS_RATIO = 0.45
MIN_MEASURE_ROI_SIZE = 0.08
HOT_CORE_INTENSITY_DELTA = 20
MIN_CORE_COMPONENT_AREA = 12
