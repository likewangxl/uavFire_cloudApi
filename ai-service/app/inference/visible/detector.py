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
        model_factory: Optional[Callable[[str], Any]] = None,
    ) -> None:
        self._model_path = model_path
        self._target_class_names: Set[str] = {name.lower() for name in target_class_names}
        self._confidence_floor = float(confidence_floor)
        self._model_factory = model_factory
        self._model: Optional[Any] = None

    def detect(self, frame: FramePacket) -> float:
        if frame.channel != "visible" or frame.frame is None:
            return 0.0
        model = self._ensure_model()
        results = model.predict(frame.frame, verbose=False)
        return _peak_target_confidence(
            results,
            target_class_names=self._target_class_names,
            confidence_floor=self._confidence_floor,
        )

    def _ensure_model(self) -> Any:
        if self._model is None:
            factory = self._model_factory or _default_model_factory()
            self._model = factory(self._model_path)
        return self._model


def _default_model_factory() -> Callable[[str], Any]:
    from ultralytics import YOLO

    return lambda path: YOLO(path)


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
