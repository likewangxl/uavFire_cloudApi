from threading import Lock
from typing import Any, Callable, Iterable, Optional, Protocol, Set

from app.models.frame import FramePacket


class VisibleDetector(Protocol):
    """Frame-based visible-channel detector returning confidence in [0.0, 1.0]."""

    def detect(self, frame: FramePacket) -> float:
        ...


class StubVisibleDetector:
    """Constant-score detector for tests and no-model environments."""

    def __init__(self, score: float = 0.8) -> None:
        self._score = float(score)

    def detect(self, frame: FramePacket) -> float:
        if frame.channel != "visible" or frame.frame is None:
            return 0.0
        return self._score


class ColorFireVisibleDetector:
    """Heuristic detector for fire-colored regions in BGR/RGB-like frames.

    OpenCV frames are BGR by default. The heuristic looks for orange/red
    pixels, then maps the matching-pixel ratio to [0, 1]. It is deliberately
    simple and deterministic so it can serve as a no-model local smoke test.
    """

    def __init__(
        self,
        red_min: int = 180,
        green_min: int = 80,
        blue_max: int = 120,
        saturation_ratio: float = 0.03,
        array_factory: Optional[Callable[[Any], Any]] = None,
    ) -> None:
        self._red_min = int(red_min)
        self._green_min = int(green_min)
        self._blue_max = int(blue_max)
        self._saturation_ratio = float(saturation_ratio)
        self._array_factory = array_factory

    def detect(self, frame: FramePacket) -> float:
        if frame.channel != "visible" or frame.frame is None:
            return 0.0
        array_factory = self._array_factory or _default_array_factory()
        arr = array_factory(frame.frame)
        fire_ratio, total = _fire_colored_ratio(
            arr,
            red_min=self._red_min,
            green_min=self._green_min,
            blue_max=self._blue_max,
        )
        if total <= 0:
            return 0.0
        if self._saturation_ratio <= 0:
            return 1.0 if fire_ratio > 0 else 0.0
        return round(min(fire_ratio / self._saturation_ratio, 1.0), 3)


class YoloVisibleDetector:
    """YOLO-backed fire/smoke detector for the visible channel.

    `ultralytics` is imported lazily inside `_ensure_model`, so this module
    remains importable in environments where ultralytics is not yet installed.
    Inject `model_factory` to substitute a fake in tests.
    """

    def __init__(
        self,
        model_path: str,
        target_class_names: Iterable[str] = ("fire", "smoke"),
        confidence_floor: float = 0.25,
        imgsz: int = 1280,
        box_display_floor: float = 0.25,
        model_factory: Optional[Callable[[str], Any]] = None,
    ) -> None:
        self._model_path = model_path
        self._target_class_names: Set[str] = {name.lower() for name in target_class_names}
        self._confidence_floor = float(confidence_floor)
        self._imgsz = int(imgsz)
        # 展示阈值与 confidence_floor 解耦：floor(0.05) 保灵敏度供评分/上报，display 只管"画不画"。
        # 0.25 是实测折中：行人误报 0.24 排除，夜间小火焰低谷帧 0.26-0.28 保留（0.35 会把真火滤掉）
        self._box_display_floor = float(box_display_floor)
        self._model_factory = model_factory
        self._model: Optional[Any] = None
        self._model_lock = Lock()
        # 暴露最近一次 detect() 的检测框，供 snapshot_writer 在升级触发时画框。
        self.last_boxes: list[dict] = []

    def detect(self, frame: FramePacket) -> float:
        if frame.channel != "visible" or frame.frame is None:
            self.last_boxes = []
            return 0.0
        model = self._ensure_model()
        results = model.predict(frame.frame, verbose=False, conf=self._confidence_floor, imgsz=self._imgsz)
        from app.services.snapshot_writer import boxes_from_yolo_results

        self.last_boxes = [
            box
            for box in boxes_from_yolo_results(
                results,
                target_class_names=self._target_class_names,
                confidence_floor=self._confidence_floor,
            )
            if float(box.get("conf", 0.0)) >= self._box_display_floor
        ]
        return _peak_target_confidence(
            results,
            target_class_names=self._target_class_names,
            confidence_floor=self._confidence_floor,
        )

    def _ensure_model(self) -> Any:
        if self._model is None:
            with self._model_lock:
                if self._model is None:
                    factory = self._model_factory or _default_model_factory()
                    self._model = factory(self._model_path)
        return self._model


def _default_model_factory() -> Callable[[str], Any]:
    from ultralytics import YOLO

    return lambda path: YOLO(path)


def _default_array_factory() -> Callable[[Any], Any]:
    import numpy as np

    return lambda frame: np.asarray(frame)


def _fire_colored_ratio(
    frame_array: Any,
    red_min: int,
    green_min: int,
    blue_max: int,
) -> tuple[float, int]:
    shape = getattr(frame_array, "shape", None)
    if shape is not None and len(shape) >= 3:
        total = int(shape[0]) * int(shape[1])
        if total <= 0:
            return 0.0, 0
        blue = frame_array[..., 0]
        green = frame_array[..., 1]
        red = frame_array[..., 2]
        mask = (red >= red_min) & (green >= green_min) & (blue <= blue_max) & (red >= green)
        return float(mask.sum()) / total, total

    pixels = []
    try:
        rows = list(frame_array)
        for row in rows:
            pixels.extend(list(row))
    except TypeError:
        return 0.0, 0
    if not pixels:
        return 0.0, 0
    hot = 0
    for pixel in pixels:
        try:
            blue, green, red = pixel[0], pixel[1], pixel[2]
        except (TypeError, IndexError):
            continue
        if red >= red_min and green >= green_min and blue <= blue_max and red >= green:
            hot += 1
    return hot / len(pixels), len(pixels)


def _peak_target_confidence(
    results: Any,
    target_class_names: Set[str],
    confidence_floor: float,
) -> float:
    peak = 0.0
    for result in results or []:
        boxes = getattr(result, "boxes", None)
        if boxes is None:
            continue
        names = getattr(result, "names", {}) or {}
        cls_seq = _to_python_iterable(getattr(boxes, "cls", []))
        conf_seq = _to_python_iterable(getattr(boxes, "conf", []))
        for cls_value, conf_value in zip(cls_seq, conf_seq):
            confidence = float(_unwrap_scalar(conf_value))
            if confidence < confidence_floor:
                continue
            cls_idx = int(_unwrap_scalar(cls_value))
            class_name = str(names.get(cls_idx, "")).lower()
            if class_name not in target_class_names:
                continue
            if confidence > peak:
                peak = confidence
    return peak


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
