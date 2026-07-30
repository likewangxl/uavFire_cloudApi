import threading
import time

from app.models.frame import FramePacket
from app.video.source import LatestFrameVideoSource


class FakeInnerSource:
    """可控的内层源：脚本化 read 行为，支持模拟阻塞。"""

    def __init__(self, frames=None, block_event: threading.Event = None):
        self.frames = list(frames or [])
        self.block_event = block_event
        self.opened = False
        self.closed = False

    def open(self):
        self.opened = True

    def read(self):
        if self.block_event is not None:
            # 模拟 FFMPEG 卡死：一直阻塞到事件被置位
            self.block_event.wait()
            return None
        if self.frames:
            return self.frames.pop(0)
        time.sleep(0.01)
        return None

    def close(self):
        self.closed = True


def _packet(ts=1):
    return FramePacket(source_ts=ts, channel="visible", frame=object())


def _wait_until(predicate, timeout_s=2.0):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.01)
    return False


def test_read_returns_latest_frame_from_grabber_thread():
    inner = FakeInnerSource(frames=[_packet(1), _packet(2)])
    source = LatestFrameVideoSource(
        source_factory=lambda: inner, url="rtsp://x", channel="visible"
    )
    source.open()

    assert _wait_until(lambda: source.read() is not None)
    packet = source.read()
    assert packet.source_ts in (1, 2)
    source.close()


def test_read_returns_none_when_frame_is_stale():
    clock_value = [100.0]
    inner = FakeInnerSource(frames=[_packet(1)])
    source = LatestFrameVideoSource(
        source_factory=lambda: inner,
        url="rtsp://x",
        channel="visible",
        stale_after_s=10.0,
        clock=lambda: clock_value[0],
    )
    source.open()
    assert _wait_until(lambda: source.read() is not None)

    clock_value[0] = 111.0  # 帧龄 11s > 10s
    assert source.read() is None
    source.close()


def test_read_returns_none_when_open_fails():
    class FailingSource(FakeInnerSource):
        def open(self):
            raise RuntimeError("stream absent")

    source = LatestFrameVideoSource(
        source_factory=FailingSource, url="rtsp://x", channel="visible"
    )
    source.open()  # 不抛错：线程内失败，流出现后靠上层重开自愈
    time.sleep(0.05)
    assert source.read() is None
    source.close()


def test_reopen_abandons_blocked_grabber_and_recovers():
    block = threading.Event()
    blocked_inner = FakeInnerSource(block_event=block)
    healthy_inner = FakeInnerSource(frames=[_packet(9)])
    inners = [blocked_inner, healthy_inner]
    source = LatestFrameVideoSource(
        source_factory=lambda: inners.pop(0), url="rtsp://x", channel="visible"
    )
    source.open()
    time.sleep(0.05)
    assert source.read() is None  # 抓帧线程卡死，无帧

    # 上层按读失败走 close+open：抛弃卡死线程，新线程接管
    source.close()
    source.open()
    assert _wait_until(lambda: source.read() is not None)
    assert source.read().source_ts == 9

    block.set()  # 解除旧线程阻塞，让它自然退出（代数不符）
    source.close()


def test_open_is_idempotent_while_grabber_healthy():
    inner = FakeInnerSource(frames=[_packet(i) for i in range(50)])
    created = []

    def factory():
        created.append(1)
        return inner

    source = LatestFrameVideoSource(
        source_factory=factory, url="rtsp://x", channel="visible"
    )
    source.open()
    assert _wait_until(lambda: source.read() is not None)
    source.open()  # 线程健康时不应重建
    assert len(created) == 1
    source.close()
