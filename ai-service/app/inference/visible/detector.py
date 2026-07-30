import os
from threading import Lock
from typing import Any, Callable, Iterable, Optional, Protocol, Set
from app.models.frame import FramePacket

# torchvision::nms 在 MPS 上未实现，需要 CPU fallback；必须在 torch 首次加载前设置。
# detector 模块在 ultralytics 懒加载之前就会被 import，放这里能保证时序。
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")


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
        device: str = "auto",
        model_factory: Optional[Callable[[str], Any]] = None,
    ) -> None:
        self._model_path = model_path
        self._target_class_names: Set[str] = {name.lower() for name in target_class_names}
        self._confidence_floor = float(confidence_floor)
        self._imgsz = int(imgsz)
        # "auto" 在首次推理时解析：Apple Silicon 上用 MPS（实测 273ms→53ms），否则默认设备。
        self._device = (device or "auto").lower()
        self._resolved_device: Optional[str] = None
        self._device_resolved = False
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
        predict_kwargs: dict = {}
        device = self._resolve_device()
        if device:
            predict_kwargs["device"] = device
        results = model.predict(
            frame.frame, verbose=False, conf=self._confidence_floor, imgsz=self._imgsz, **predict_kwargs
        )
        from app.services.snapshot_writer import boxes_from_yolo_results

        candidates = [
            box
            for box in boxes_from_yolo_results(
                results,
                target_class_names=self._target_class_names,
                confidence_floor=self._confidence_floor,
            )
            # 夜间暗部噪声兜底：fire 框内一个火色像素都没有的检出直接否决
            # （真火必有橙红发光像素；实测黑暗树丛噪声框可打到 0.78）。smoke 类无火色，不校验。
            if _box_passes_fire_color_check(frame.frame, box)
        ]
        self.last_boxes = [
            box for box in candidates if float(box.get("conf", 0.0)) >= self._box_display_floor
        ]
        return max((float(box.get("conf", 0.0)) for box in candidates), default=0.0)

    def _resolve_device(self) -> Optional[str]:
        if self._device != "auto":
            return self._device or None
        if not self._device_resolved:
            self._device_resolved = True
            try:
                import torch

                self._resolved_device = "mps" if torch.backends.mps.is_available() else None
            except Exception:
                self._resolved_device = None
        return self._resolved_device

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


# fire 框内至少要有这么多个火色像素才算真火（防单像素噪声）。
_FIRE_COLOR_MIN_PIXELS = 5


def _box_passes_fire_color_check(frame: Any, box: dict) -> bool:
    if str(box.get("label", "")).lower() != "fire":
        return True
    try:
        import numpy as np

        arr = np.asarray(frame)
        if arr.ndim < 3:
            return True
        height, width = int(arr.shape[0]), int(arr.shape[1])
        x1 = max(0, min(int(box["x1"]), width - 1))
        x2 = max(x1 + 1, min(int(box["x2"]), width))
        y1 = max(0, min(int(box["y1"]), height - 1))
        y2 = max(y1 + 1, min(int(box["y2"]), height))
        crop = arr[y1:y2, x1:x2]
        ratio, total = _fire_colored_ratio(crop, red_min=180, green_min=80, blue_max=120)
        return ratio * total >= _FIRE_COLOR_MIN_PIXELS
    except Exception:
        # 校验自身出错不拦检出——宁可放过误报也不能吞掉真火。
        return True
