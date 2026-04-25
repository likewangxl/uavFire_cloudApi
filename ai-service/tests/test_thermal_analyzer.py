from app.inference.thermal.analyzer import (
    HotSpotThermalAnalyzer,
    StubThermalAnalyzer,
)
from app.models.frame import FramePacket


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
