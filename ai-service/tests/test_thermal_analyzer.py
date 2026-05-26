from app.inference.thermal.analyzer import (
    HotSpotThermalAnalyzer,
    StubThermalAnalyzer,
)
from app.models.frame import FramePacket
import numpy as np


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
