from app.inference.visible.detector import (
    ColorFireVisibleDetector,
    StubVisibleDetector,
    YoloVisibleDetector,
)
from app.models.frame import FramePacket
from concurrent.futures import ThreadPoolExecutor


def test_stub_returns_configured_score_for_visible_frame_with_data():
    detector = StubVisibleDetector(score=0.42)
    packet = FramePacket(source_ts=1, channel="visible", frame=object())

    assert detector.detect(packet) == 0.42


def test_stub_returns_zero_for_thermal_channel():
    detector = StubVisibleDetector(score=0.42)
    packet = FramePacket(source_ts=1, channel="thermal", frame=object())

    assert detector.detect(packet) == 0.0


def test_stub_returns_zero_when_frame_data_missing():
    detector = StubVisibleDetector(score=0.42)
    packet = FramePacket(source_ts=1, channel="visible")

    assert detector.detect(packet) == 0.0


def test_yolo_detector_returns_top_target_confidence():
    fake_yolo = _FakeYolo(
        results=[
            _FakeResult(
                names={0: "fire", 1: "smoke", 2: "person"},
                classes=[0, 1, 2],
                confidences=[0.55, 0.85, 0.9],
            )
        ]
    )
    detector = YoloVisibleDetector(
        model_path="fake.pt",
        target_class_names=("fire", "smoke"),
        confidence_floor=0.25,
        model_factory=lambda path: fake_yolo,
    )
    packet = FramePacket(source_ts=1, channel="visible", frame=object())

    assert detector.detect(packet) == 0.85
    assert fake_yolo.predict_kwargs["imgsz"] == 1280
    assert fake_yolo.predict_kwargs["conf"] == 0.25


def test_yolo_detector_passes_configured_imgsz_to_model_predict():
    fake_yolo = _FakeYolo(results=[])
    detector = YoloVisibleDetector(
        model_path="fake.pt",
        imgsz=960,
        model_factory=lambda path: fake_yolo,
    )
    packet = FramePacket(source_ts=1, channel="visible", frame=object())

    detector.detect(packet)

    assert fake_yolo.predict_kwargs["imgsz"] == 960


def test_yolo_detector_loads_model_once_when_detect_is_called_concurrently():
    factory_calls = []
    fake_yolo = _FakeYolo(results=[])

    def factory(path):
        factory_calls.append(path)
        return fake_yolo

    detector = YoloVisibleDetector(
        model_path="fake.pt",
        model_factory=factory,
    )
    packet = FramePacket(source_ts=1, channel="visible", frame=object())

    with ThreadPoolExecutor(max_workers=4) as executor:
        list(executor.map(lambda _: detector.detect(packet), range(8)))

    assert factory_calls == ["fake.pt"]


def test_yolo_detector_ignores_non_target_classes():
    detector = YoloVisibleDetector(
        model_path="fake.pt",
        target_class_names=("fire",),
        confidence_floor=0.25,
        model_factory=lambda path: _FakeYolo(
            results=[
                _FakeResult(
                    names={0: "person", 1: "car"},
                    classes=[0, 1],
                    confidences=[0.9, 0.8],
                )
            ]
        ),
    )
    packet = FramePacket(source_ts=1, channel="visible", frame=object())

    assert detector.detect(packet) == 0.0


def test_yolo_detector_drops_predictions_below_confidence_floor():
    detector = YoloVisibleDetector(
        model_path="fake.pt",
        target_class_names=("fire",),
        confidence_floor=0.5,
        model_factory=lambda path: _FakeYolo(
            results=[
                _FakeResult(
                    names={0: "fire"},
                    classes=[0, 0],
                    confidences=[0.49, 0.3],
                )
            ]
        ),
    )
    packet = FramePacket(source_ts=1, channel="visible", frame=object())

    assert detector.detect(packet) == 0.0


def test_yolo_detector_returns_zero_for_thermal_channel_without_invoking_model():
    invocations = {"count": 0}

    def factory(path):
        invocations["count"] += 1
        return _FakeYolo(results=[])

    detector = YoloVisibleDetector(model_path="fake.pt", model_factory=factory)
    packet = FramePacket(source_ts=1, channel="thermal", frame=object())

    assert detector.detect(packet) == 0.0
    assert invocations["count"] == 0


def test_yolo_detector_returns_zero_when_frame_data_missing_without_invoking_model():
    invocations = {"count": 0}

    def factory(path):
        invocations["count"] += 1
        return _FakeYolo(results=[])

    detector = YoloVisibleDetector(model_path="fake.pt", model_factory=factory)
    packet = FramePacket(source_ts=1, channel="visible", frame=None)

    assert detector.detect(packet) == 0.0
    assert invocations["count"] == 0


def test_yolo_detector_caches_model_across_invocations():
    invocations = {"count": 0}

    def factory(path):
        invocations["count"] += 1
        return _FakeYolo(results=[])

    detector = YoloVisibleDetector(model_path="fake.pt", model_factory=factory)
    packet = FramePacket(source_ts=1, channel="visible", frame=object())

    detector.detect(packet)
    detector.detect(packet)
    detector.detect(packet)

    assert invocations["count"] == 1


def test_color_fire_detector_scores_orange_red_pixel_ratio():
    detector = ColorFireVisibleDetector(
        red_min=180,
        green_min=80,
        blue_max=80,
        saturation_ratio=0.25,
        array_factory=lambda frame: frame,
    )
    packet = FramePacket(
        source_ts=1,
        channel="visible",
        frame=[
            [[0, 120, 255], [0, 130, 240]],
            [[255, 0, 0], [0, 0, 0]],
        ],
    )

    assert detector.detect(packet) == 1.0


def test_color_fire_detector_returns_zero_when_no_fire_colored_pixels():
    detector = ColorFireVisibleDetector(
        red_min=180,
        green_min=80,
        blue_max=80,
        saturation_ratio=0.25,
        array_factory=lambda frame: frame,
    )
    packet = FramePacket(
        source_ts=1,
        channel="visible",
        frame=[
            [[255, 0, 0], [255, 255, 255]],
            [[0, 255, 0], [0, 0, 0]],
        ],
    )

    assert detector.detect(packet) == 0.0


class _FakeYolo:
    def __init__(self, results):
        self._results = results
        self.predict_kwargs = {}

    def predict(self, frame, **kwargs):
        self.predict_kwargs = kwargs
        return self._results


class _FakeResult:
    def __init__(self, names, classes, confidences):
        self.names = names
        self.boxes = _FakeBoxes(classes=classes, confidences=confidences)


class _FakeBoxes:
    def __init__(self, classes, confidences):
        self.cls = classes
        self.conf = confidences
