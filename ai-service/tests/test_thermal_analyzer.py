from app.inference.thermal.analyzer import (
    HotSpotThermalAnalyzer,
    MaxThermalAnalyzer,
    StubThermalAnalyzer,
    YoloThermalAnalyzer,
)
from app.models.frame import FramePacket
import numpy as np
import os
from pathlib import Path

import pytest


def test_stub_returns_configured_score_for_thermal_frame_with_data():
    analyzer = StubThermalAnalyzer(score=0.33)
    packet = FramePacket(source_ts=1, channel="thermal", frame=object())

    assert analyzer.analyze(packet) == 0.33


def test_stub_returns_zero_for_visible_channel():
    analyzer = StubThermalAnalyzer(score=0.33)
    packet = FramePacket(source_ts=1, channel="visible", frame=object())

    assert analyzer.analyze(packet) == 0.0


def test_stub_returns_zero_when_frame_data_missing():
    analyzer = StubThermalAnalyzer(score=0.33)
    packet = FramePacket(source_ts=1, channel="thermal")

    assert analyzer.analyze(packet) == 0.0


def test_hotspot_returns_saturated_score_when_ratio_meets_saturation():
    analyzer = HotSpotThermalAnalyzer(
        intensity_threshold=200,
        saturation_ratio=0.5,
        intensity_extractor=lambda frame: [0, 50, 100, 210, 220, 230, 240, 250, 250, 250],
    )
    packet = FramePacket(source_ts=1, channel="thermal", frame=object())

    assert analyzer.analyze(packet) == 1.0


def test_hotspot_returns_proportional_score_below_saturation():
    analyzer = HotSpotThermalAnalyzer(
        intensity_threshold=200,
        saturation_ratio=0.5,
        intensity_extractor=lambda frame: [0, 0, 0, 0, 0, 0, 0, 0, 0, 250],
    )
    packet = FramePacket(source_ts=1, channel="thermal", frame=object())

    assert analyzer.analyze(packet) == 0.2


def test_hotspot_returns_zero_when_no_pixels_above_threshold():
    analyzer = HotSpotThermalAnalyzer(
        intensity_threshold=200,
        saturation_ratio=0.5,
        intensity_extractor=lambda frame: [0, 100, 150, 199],
    )
    packet = FramePacket(source_ts=1, channel="thermal", frame=object())

    assert analyzer.analyze(packet) == 0.0


def test_hotspot_returns_zero_for_visible_channel_without_invoking_extractor():
    invocations = {"count": 0}

    def extractor(frame):
        invocations["count"] += 1
        return [255]

    analyzer = HotSpotThermalAnalyzer(intensity_extractor=extractor)
    packet = FramePacket(source_ts=1, channel="visible", frame=object())

    assert analyzer.analyze(packet) == 0.0
    assert invocations["count"] == 0


def test_hotspot_returns_zero_when_frame_data_missing_without_invoking_extractor():
    invocations = {"count": 0}

    def extractor(frame):
        invocations["count"] += 1
        return [255]

    analyzer = HotSpotThermalAnalyzer(intensity_extractor=extractor)
    packet = FramePacket(source_ts=1, channel="thermal", frame=None)

    assert analyzer.analyze(packet) == 0.0
    assert invocations["count"] == 0


def test_hotspot_records_normalized_roi_around_hot_pixels():
    analyzer = HotSpotThermalAnalyzer(intensity_threshold=200, saturation_ratio=0.5)
    frame = np.zeros((100, 200, 3), dtype=np.uint8)
    frame[20:50, 40:100, :] = 230
    packet = FramePacket(source_ts=1, channel="thermal", frame=frame)

    analyzer.analyze(packet)

    assert analyzer.last_measure_roi is not None
    assert analyzer.last_measure_roi.model_dump() == {
        "x": 0.2,
        "y": 0.2,
        "width": 0.3,
        "height": 0.3,
    }


def test_hotspot_records_compact_roi_around_peak_inside_active_thermal_content():
    analyzer = HotSpotThermalAnalyzer(intensity_threshold=200, saturation_ratio=0.5)
    frame = np.zeros((100, 200, 3), dtype=np.uint8)
    frame[:, 50:150, :] = 40
    frame[46:54, 116:124, :] = 240
    packet = FramePacket(source_ts=1, channel="thermal", frame=frame)

    analyzer.analyze(packet)

    assert analyzer.last_measure_roi is not None
    assert analyzer.last_measure_roi.model_dump() == {
        "x": 0.66,
        "y": 0.46,
        "width": 0.08,
        "height": 0.08,
    }


def test_hotspot_measure_roi_tracks_peak_core_not_broad_warm_region():
    analyzer = HotSpotThermalAnalyzer(intensity_threshold=200, saturation_ratio=0.5)
    frame = np.zeros((100, 100, 3), dtype=np.uint8)
    frame[:, :, :] = 40
    frame[20:80, 20:80, :] = 210
    frame[48:52, 58:62, :] = 255
    packet = FramePacket(source_ts=1, channel="thermal", frame=frame)

    analyzer.analyze(packet)

    assert analyzer.last_measure_roi is not None
    assert analyzer.last_measure_roi.model_dump() == {
        "x": 0.56,
        "y": 0.46,
        "width": 0.08,
        "height": 0.08,
    }


def test_hotspot_measure_roi_ignores_large_edge_hot_distractor():
    analyzer = HotSpotThermalAnalyzer(intensity_threshold=200, saturation_ratio=0.5)
    frame = np.zeros((100, 200, 3), dtype=np.uint8)
    frame[:, :, :] = 40
    frame[8:70, 160:198, :] = 255
    frame[48:56, 96:104, :] = 255
    packet = FramePacket(source_ts=1, channel="thermal", frame=frame)

    analyzer.analyze(packet)

    assert analyzer.last_measure_roi is not None
    assert analyzer.last_measure_roi.model_dump() == {
        "x": 0.46,
        "y": 0.48,
        "width": 0.08,
        "height": 0.08,
    }


def test_hotspot_measure_roi_ignores_sparse_osd_like_overlay():
    analyzer = HotSpotThermalAnalyzer(intensity_threshold=200, saturation_ratio=0.5)
    frame = np.zeros((120, 200, 3), dtype=np.uint8)
    frame[:, :, :] = 40
    frame[82:94, 146:158, :] = 255
    frame[52:54, 80:120, :] = 255
    frame[52:78, 80:82, :] = 255
    frame[52:78, 118:120, :] = 255
    frame[76:78, 80:120, :] = 255
    packet = FramePacket(source_ts=1, channel="thermal", frame=frame)

    analyzer.analyze(packet)

    assert analyzer.last_measure_roi is not None
    assert analyzer.last_measure_roi.model_dump() == {
        "x": 0.72,
        "y": 0.6833,
        "width": 0.08,
        "height": 0.1,
    }


def test_hotspot_measure_roi_prefers_scene_hotspot_over_top_timestamp_overlay():
    analyzer = HotSpotThermalAnalyzer(intensity_threshold=200, saturation_ratio=0.5)
    frame = np.zeros((120, 200, 3), dtype=np.uint8)
    frame[:, :, :] = 40
    frame[0:8, 8:76, :] = 255
    frame[96:108, 152:172, :] = 245
    packet = FramePacket(source_ts=1, channel="thermal", frame=frame)

    analyzer.analyze(packet)

    assert analyzer.last_measure_roi is not None
    assert analyzer.last_measure_roi.model_dump() == {
        "x": 0.76,
        "y": 0.8,
        "width": 0.1,
        "height": 0.1,
    }


def test_hotspot_measure_roi_prefers_compact_fire_over_large_bright_foliage():
    analyzer = HotSpotThermalAnalyzer(intensity_threshold=200, saturation_ratio=0.5)
    frame = np.zeros((120, 200, 3), dtype=np.uint8)
    frame[:, :, :] = 40
    frame[35:75, 140:185, :] = 255
    frame[92:104, 96:112, :] = 245
    packet = FramePacket(source_ts=1, channel="thermal", frame=frame)

    analyzer.analyze(packet)

    assert analyzer.last_measure_roi is not None
    assert analyzer.last_measure_roi.model_dump() == {
        "x": 0.48,
        "y": 0.7667,
        "width": 0.08,
        "height": 0.1,
    }


class FakeBoxes:
    def __init__(self, cls, conf, xywh):
        self.cls = cls
        self.conf = conf
        self.xywh = xywh


class FakeResult:
    def __init__(self, boxes, names=None, orig_shape=(100, 200)):
        self.boxes = boxes
        self.names = names if names is not None else {0: "fire"}
        self.orig_shape = orig_shape


def test_yolo_thermal_returns_highest_fire_confidence_and_records_normalized_detections():
    def predictor(frame, *, verbose, conf, imgsz):
        assert frame == "frame-data"
        assert verbose is False
        assert conf == 0.25
        assert imgsz == 640
        return [
            FakeResult(
                FakeBoxes(
                    cls=[0, 0, 1],
                    conf=[0.42, 0.81, 0.95],
                    xywh=[
                        [100, 40, 20, 10],
                        [50, 25, 30, 20],
                        [10, 10, 4, 4],
                    ],
                ),
                names={0: "fire", 1: "person"},
                orig_shape=(100, 200),
            )
        ]

    analyzer = YoloThermalAnalyzer("best.pt", predictor=predictor)
    packet = FramePacket(source_ts=1, channel="thermal", frame="frame-data")

    assert analyzer.analyze(packet) == 0.81
    assert [d.__dict__ for d in analyzer.last_detections] == [
        {"cx": 0.5, "cy": 0.4, "w": 0.1, "h": 0.1, "conf": 0.42},
        {"cx": 0.25, "cy": 0.25, "w": 0.15, "h": 0.2, "conf": 0.81},
    ]


def test_yolo_thermal_returns_zero_and_clears_detections_when_no_fire_detected():
    analyzer = YoloThermalAnalyzer(
        "best.pt",
        predictor=lambda frame, **kwargs: [
            FakeResult(FakeBoxes(cls=[1], conf=[0.99], xywh=[[20, 20, 10, 10]]), names={1: "person"})
        ],
    )

    score = analyzer.analyze(FramePacket(source_ts=1, channel="thermal", frame=object()))

    assert score == 0.0
    assert analyzer.last_detections == []


def test_yolo_thermal_fail_safe_marks_broken_and_does_not_retry_after_exception(caplog):
    calls = {"count": 0}

    def predictor(frame, **kwargs):
        calls["count"] += 1
        raise RuntimeError("boom")

    analyzer = YoloThermalAnalyzer("best.pt", predictor=predictor)
    packet = FramePacket(source_ts=1, channel="thermal", frame=object())

    with caplog.at_level("WARNING"):
        assert analyzer.analyze(packet) == 0.0
        assert analyzer.analyze(packet) == 0.0

    assert calls["count"] == 1
    assert analyzer.last_detections == []
    assert "thermal YOLO inference failed" in caplog.text


def test_yolo_thermal_skips_predictor_for_non_thermal_or_empty_frames():
    calls = {"count": 0}

    def predictor(frame, **kwargs):
        calls["count"] += 1
        return []

    analyzer = YoloThermalAnalyzer("best.pt", predictor=predictor)

    assert analyzer.analyze(FramePacket(source_ts=1, channel="visible", frame=object())) == 0.0
    assert analyzer.analyze(FramePacket(source_ts=1, channel="thermal", frame=None)) == 0.0
    assert calls["count"] == 0


def test_max_thermal_analyzer_returns_larger_score():
    analyzer = MaxThermalAnalyzer(
        [
            StubThermalAnalyzer(score=0.2),
            StubThermalAnalyzer(score=0.7),
        ]
    )

    assert analyzer.analyze(FramePacket(source_ts=1, channel="thermal", frame=object())) == 0.7


THERMAL_YOLO_SMOKE_MODEL = os.getenv("THERMAL_YOLO_SMOKE_MODEL", "")
THERMAL_YOLO_SMOKE_MODEL_PATH = Path(THERMAL_YOLO_SMOKE_MODEL) if THERMAL_YOLO_SMOKE_MODEL else None
THERMAL_DATASET_TEST_IMAGES = Path(
    r"E:\uavfire-training\thermal\datasets\thermal-fireman-pseudo-v2-20260708\images\test"
)


@pytest.mark.skipif(
    THERMAL_YOLO_SMOKE_MODEL_PATH is None or not THERMAL_YOLO_SMOKE_MODEL_PATH.exists(),
    reason="THERMAL_YOLO_SMOKE_MODEL is not set or does not point to a file",
)
def test_thermal_yolo_real_model_smoke_on_test_split(capsys):
    import cv2

    labels_dir = THERMAL_DATASET_TEST_IMAGES.parent.parent / "labels" / "test"
    positives = []
    negatives = []
    for image_path in sorted(THERMAL_DATASET_TEST_IMAGES.glob("*")):
        if image_path.suffix.lower() not in {".jpg", ".jpeg", ".png", ".bmp"}:
            continue
        label_path = labels_dir / f"{image_path.stem}.txt"
        if label_path.exists() and label_path.read_text(encoding="utf-8").strip():
            positives.append(image_path)
        else:
            negatives.append(image_path)
        if len(positives) >= 3 and len(negatives) >= 3:
            break
    assert len(positives) >= 3
    assert len(negatives) >= 3

    threshold = 0.25
    analyzer = YoloThermalAnalyzer(
        str(THERMAL_YOLO_SMOKE_MODEL_PATH),
        conf_threshold=threshold,
    )
    positive_scores = []
    negative_scores = []
    for image_path in positives[:3]:
        frame = cv2.imread(str(image_path))
        assert frame is not None
        score = analyzer.analyze(FramePacket(source_ts=1, channel="thermal", frame=frame))
        positive_scores.append((image_path.name, score))
    for image_path in negatives[:3]:
        frame = cv2.imread(str(image_path))
        assert frame is not None
        score = analyzer.analyze(FramePacket(source_ts=1, channel="thermal", frame=frame))
        negative_scores.append((image_path.name, score))

    print(f"[R4A-PROGRESS] thermal YOLO smoke positives={positive_scores} negatives={negative_scores}")
    captured = capsys.readouterr()
    print(captured.out, end="")
    assert all(score >= threshold for _, score in positive_scores)
