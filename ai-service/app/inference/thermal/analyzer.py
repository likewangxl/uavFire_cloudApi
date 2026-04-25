from typing import Any, Callable, Optional, Protocol, Tuple

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

    def analyze(self, frame: FramePacket) -> float:
        if frame.channel != "thermal" or frame.frame is None:
            return 0.0
        extractor = self._intensity_extractor or _default_intensity_extractor()
        intensity = extractor(frame.frame)
        hot_ratio, total = _hot_ratio(intensity, self._intensity_threshold)
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
