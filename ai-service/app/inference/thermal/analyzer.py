import logging
from dataclasses import dataclass
from threading import Lock
from typing import Any, Callable, Iterable, Optional, Protocol, Tuple

from app.models.event import ThermalMeasureRoi
from app.models.frame import FramePacket


logger = logging.getLogger(__name__)


class ThermalAnalyzer(Protocol):
    """Frame-based thermal-channel analyzer returning confidence in [0.0, 1.0]."""

    def analyze(self, frame: FramePacket) -> float:
        ...


@dataclass(frozen=True)
class ThermalDetection:
    """Normalized thermal detection box."""

    cx: float
    cy: float
    w: float
    h: float
    conf: float


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


class YoloThermalAnalyzer:
    """ultralytics YOLO thermal fire detector implementing ThermalAnalyzer."""

    def __init__(
        self,
        model_path: str,
        imgsz: int = 640,
        conf_threshold: float = 0.25,
        predictor: Optional[Callable[..., Any]] = None,
    ) -> None:
        self._model_path = model_path
        self._imgsz = int(imgsz)
        self._conf_threshold = float(conf_threshold)
        self._predictor = predictor
        self._model: Optional[Any] = None
        self._model_lock = Lock()
        self._broken = False
        self._last_detections: list[ThermalDetection] = []

    def load(self) -> bool:
        if self._broken:
            return False
        try:
            self._ensure_predictor()
            return True
        except Exception:
            self._mark_broken()
            return False

    def analyze(self, frame: FramePacket) -> float:
        self._last_detections = []
        if frame.channel != "thermal" or frame.frame is None or self._broken:
            return 0.0
        try:
            predictor = self._ensure_predictor()
            results = predictor(
                frame.frame,
                verbose=False,
                conf=self._conf_threshold,
                imgsz=self._imgsz,
            )
            detections = _thermal_detections_from_yolo_results(
                results,
                frame.frame,
                confidence_floor=self._conf_threshold,
            )
            self._last_detections = detections
            if not detections:
                return 0.0
            return max(det.conf for det in detections)
        except Exception:
            self._mark_broken()
            return 0.0

    @property
    def last_detections(self) -> list[ThermalDetection]:
        return list(self._last_detections)

    def _ensure_predictor(self) -> Callable[..., Any]:
        if self._predictor is not None:
            return self._predictor
        with self._model_lock:
            if self._predictor is None:
                factory = _default_thermal_yolo_model_factory()
                self._model = factory(self._model_path)
                self._predictor = self._model.predict
        return self._predictor

    def _mark_broken(self) -> None:
        if not self._broken:
            logger.warning(
                "thermal YOLO inference failed; disabling analyzer model=%s",
                self._model_path,
                exc_info=True,
            )
        self._broken = True
        self._last_detections = []


class MaxThermalAnalyzer:
    """Thermal analyzer composition that returns the maximum child score."""

    def __init__(self, analyzers: Iterable[ThermalAnalyzer]) -> None:
        self._analyzers = list(analyzers)

    def analyze(self, frame: FramePacket) -> float:
        peak = 0.0
        for analyzer in self._analyzers:
            peak = max(peak, float(analyzer.analyze(frame)))
        return peak


def _default_thermal_yolo_model_factory() -> Callable[[str], Any]:
    from ultralytics import YOLO

    return lambda path: YOLO(path)


def _thermal_detections_from_yolo_results(
    results: Any,
    frame: Any,
    confidence_floor: float,
) -> list[ThermalDetection]:
    detections: list[ThermalDetection] = []
    for result in results or []:
        boxes = getattr(result, "boxes", None)
        if boxes is None:
            continue
        names = getattr(result, "names", {}) or {}
        cls_seq = _to_python_iterable(getattr(boxes, "cls", []))
        conf_seq = _to_python_iterable(getattr(boxes, "conf", []))
        xywhn_seq = _to_python_iterable(getattr(boxes, "xywhn", None))
        xywh_seq = _to_python_iterable(getattr(boxes, "xywh", []))
        if xywhn_seq:
            box_seq = xywhn_seq
            normalized = True
        else:
            box_seq = xywh_seq
            normalized = False
        width, height = _result_dimensions(result, frame)
        for cls_value, conf_value, box_value in zip(cls_seq, conf_seq, box_seq):
            confidence = float(_unwrap_scalar(conf_value))
            if confidence < confidence_floor:
                continue
            cls_idx = int(_unwrap_scalar(cls_value))
            if not _is_fire_class(cls_idx, names):
                continue
            box = list(_to_python_iterable(box_value))
            if len(box) < 4:
                continue
            cx = float(_unwrap_scalar(box[0]))
            cy = float(_unwrap_scalar(box[1]))
            w = float(_unwrap_scalar(box[2]))
            h = float(_unwrap_scalar(box[3]))
            if not normalized:
                if width <= 0 or height <= 0:
                    continue
                cx /= width
                w /= width
                cy /= height
                h /= height
            detections.append(
                ThermalDetection(
                    cx=round(_clamp01(cx), 6),
                    cy=round(_clamp01(cy), 6),
                    w=round(_clamp01(w), 6),
                    h=round(_clamp01(h), 6),
                    conf=round(confidence, 6),
                )
            )
    return detections


def _is_fire_class(cls_idx: int, names: Any) -> bool:
    if isinstance(names, dict):
        if cls_idx in names:
            return str(names[cls_idx]).lower() == "fire"
        str_key = str(cls_idx)
        if str_key in names:
            return str(names[str_key]).lower() == "fire"
        return cls_idx == 0
    try:
        return str(names[cls_idx]).lower() == "fire"
    except Exception:
        return cls_idx == 0


def _result_dimensions(result: Any, frame: Any) -> Tuple[int, int]:
    orig_shape = getattr(result, "orig_shape", None)
    if orig_shape is not None and len(orig_shape) >= 2:
        return int(orig_shape[1]), int(orig_shape[0])
    shape = getattr(frame, "shape", None)
    if shape is not None and len(shape) >= 2:
        return int(shape[1]), int(shape[0])
    return 0, 0


def _clamp01(value: float) -> float:
    return min(max(value, 0.0), 1.0)


def _to_python_iterable(value: Any) -> Iterable[Any]:
    if value is None:
        return []
    tolist = getattr(value, "tolist", None)
    if callable(tolist):
        try:
            return tolist()
        except Exception:
            pass
    return list(value)


def _unwrap_scalar(value: Any) -> Any:
    item = getattr(value, "item", None)
    if callable(item):
        try:
            return item()
        except Exception:
            pass
    return value


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
    best_score: Optional[Tuple[float, float, float, float, int]] = None
    for label in range(1, labels_count):
        area = int(stats[label, cv2.CC_STAT_AREA])
        if area < MIN_CORE_COMPONENT_AREA:
            continue
        left = int(stats[label, cv2.CC_STAT_LEFT])
        top = int(stats[label, cv2.CC_STAT_TOP])
        component_width = max(int(stats[label, cv2.CC_STAT_WIDTH]), 1)
        component_height = max(int(stats[label, cv2.CC_STAT_HEIGHT]), 1)
        fill_ratio = area / float(component_width * component_height)
        if fill_ratio < MIN_COMPONENT_FILL_RATIO:
            continue
        component_mask = labels == label
        peak = float(active[component_mask].max())
        cx, cy = centroids[label]
        if _is_top_overlay_component(
            left=left,
            top=top,
            centroid_y=float(cy),
            component_height=component_height,
            frame_width=width,
            frame_height=height,
        ):
            continue
        distance = ((float(cx) - center_x) / max(width, 1)) ** 2 + ((float(cy) - center_y) / max(height, 1)) ** 2
        lower_bias = float(cy) / max(height, 1)
        edge_penalty = _edge_penalty(
            left=left,
            top=top,
            component_width=component_width,
            component_height=component_height,
            frame_width=width,
            frame_height=height,
        )
        peak_band = int(peak // HOT_COMPONENT_PEAK_BAND)
        capped_area = min(area * fill_ratio, MAX_COMPONENT_AREA_SCORE) * _edge_area_multiplier(edge_penalty)
        score = (peak_band, lower_bias, fill_ratio, capped_area, -distance - edge_penalty)
        if best_score is None or score > best_score:
            best_label = label
            best_score = score
    if best_label is None:
        return mask
    return labels == best_label


def _is_top_overlay_component(
    *,
    left: int,
    top: int,
    centroid_y: float,
    component_height: int,
    frame_width: int,
    frame_height: int,
) -> bool:
    del left, frame_width
    top_band = frame_height * TOP_OVERLAY_IGNORE_RATIO
    compact_height = max(frame_height * TOP_OVERLAY_MAX_HEIGHT_RATIO, 1.0)
    return top <= 1 and centroid_y <= top_band and component_height <= compact_height


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


def _edge_penalty(
    *,
    left: int,
    top: int,
    component_width: int,
    component_height: int,
    frame_width: int,
    frame_height: int,
) -> float:
    right = left + component_width
    bottom = top + component_height
    touches_edge = (
        left <= EDGE_COMPONENT_MARGIN_PX
        or top <= EDGE_COMPONENT_MARGIN_PX
        or right >= frame_width - EDGE_COMPONENT_MARGIN_PX
        or bottom >= frame_height - EDGE_COMPONENT_MARGIN_PX
    )
    return 0.2 if touches_edge else 0.0


def _edge_area_multiplier(edge_penalty: float) -> float:
    return 0.02 if edge_penalty > 0 else 1.0


BLACK_BAR_INTENSITY_FLOOR = 8
MIN_ACTIVE_AXIS_RATIO = 0.45
MIN_MEASURE_ROI_SIZE = 0.08
HOT_CORE_INTENSITY_DELTA = 20
MIN_CORE_COMPONENT_AREA = 12
MIN_COMPONENT_FILL_RATIO = 0.18
TOP_OVERLAY_IGNORE_RATIO = 0.12
TOP_OVERLAY_MAX_HEIGHT_RATIO = 0.10
EDGE_COMPONENT_MARGIN_PX = 3
HOT_COMPONENT_PEAK_BAND = 32.0
MAX_COMPONENT_AREA_SCORE = 256.0
