import pytest

from app.video.source import (
    OpenCvVideoSource,
    VideoSourceNotOpenError,
    VideoSourceOpenError,
)


def test_read_returns_frame_packet_with_channel_timestamp_and_dimensions():
    frame = _FakeNdarray(height=48, width=64)
    cap = _FakeCap(frames=[frame])
    source = OpenCvVideoSource(
        url="rtsp://example.com/live/stream",
        channel="visible",
        capture_factory=lambda url: cap,
        clock=lambda: 1_700_000_000.0,
    )

    source.open()
    packet = source.read()

    assert packet is not None
    assert packet.channel == "visible"
    assert packet.source_ts == 1_700_000_000_000
    assert packet.width == 64
    assert packet.height == 48
    assert packet.frame is frame


def test_read_returns_none_on_end_of_stream():
    cap = _FakeCap(frames=[])
    source = OpenCvVideoSource(
        url="rtsp://example.com/live/stream",
        channel="visible",
        capture_factory=lambda url: cap,
    )

    source.open()
    assert source.read() is None


def test_open_raises_and_releases_when_capture_not_opened():
    cap = _FakeCap(frames=[], is_opened=False)
    source = OpenCvVideoSource(
        url="rtsp://example.com/live/stream",
        channel="thermal",
        capture_factory=lambda url: cap,
    )

    with pytest.raises(VideoSourceOpenError):
        source.open()
    assert cap.released


def test_read_before_open_raises_not_open_error():
    source = OpenCvVideoSource(
        url="rtsp://example.com",
        channel="visible",
        capture_factory=lambda url: _FakeCap(frames=[]),
    )

    with pytest.raises(VideoSourceNotOpenError):
        source.read()


def test_close_releases_underlying_capture_and_allows_reopen():
    factory_calls = {"count": 0}

    def factory(url):
        factory_calls["count"] += 1
        return _FakeCap(frames=[])

    source = OpenCvVideoSource(
        url="rtsp://example.com",
        channel="visible",
        capture_factory=factory,
    )

    source.open()
    source.close()
    source.open()
    source.close()

    assert factory_calls["count"] == 2


def test_open_is_idempotent_and_does_not_recreate_capture():
    factory_calls = {"count": 0}

    def factory(url):
        factory_calls["count"] += 1
        return _FakeCap(frames=[])

    source = OpenCvVideoSource(
        url="rtsp://example.com",
        channel="visible",
        capture_factory=factory,
    )

    source.open()
    source.open()

    assert factory_calls["count"] == 1


def test_read_passes_through_zero_sized_frame_without_shape_attribute():
    cap = _FakeCap(frames=[object()])
    source = OpenCvVideoSource(
        url="rtsp://example.com",
        channel="thermal",
        capture_factory=lambda url: cap,
        clock=lambda: 1.0,
    )

    source.open()
    packet = source.read()

    assert packet is not None
    assert packet.width == 0
    assert packet.height == 0


def test_module_import_does_not_require_cv2():
    import importlib
    import sys

    sys.modules.pop("cv2", None)
    importlib.import_module("app.video.source")


class _FakeCap:
    def __init__(self, frames, is_opened=True):
        self._frames = list(frames)
        self._is_opened = is_opened
        self.released = False

    def isOpened(self):
        return self._is_opened

    def read(self):
        if not self._frames:
            return False, None
        return True, self._frames.pop(0)

    def release(self):
        self.released = True


class _FakeNdarray:
    def __init__(self, height, width):
        self.shape = (height, width, 3)
