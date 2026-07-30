import logging
import os
import threading
import time
from datetime import datetime
from typing import Callable, Literal, Optional, Protocol

from app.models.frame import FramePacket

logger = logging.getLogger(__name__)

# RTSP 拉流走 TCP + 5s socket 超时（微秒）。cv2.VideoCapture 默认无超时，
# 飞行中图传/推流抖动会让 open/read 静默卡住数分钟——实测出现过 4 分钟检测盲区，
# 火点飞越窗口正好落在盲区里就"识别不到"。加超时后卡住变快速失败，交给重连逻辑。
# （.env 里的同名变量不会被导出到进程环境，必须在这里设置才生效。）
os.environ.setdefault(
    "OPENCV_FFMPEG_CAPTURE_OPTIONS",
    "rtsp_transport;tcp|timeout;5000000",
)


class VideoSourceError(Exception):
    """Base class for VideoSource errors."""


class VideoSourceOpenError(VideoSourceError):
    """Raised when the underlying stream cannot be opened."""


class VideoSourceNotOpenError(VideoSourceError):
    """Raised when read() is called before open()."""


class VideoSource(Protocol):
    def open(self) -> None:
        """Initialize the underlying stream resource."""

    def read(self) -> Optional[FramePacket]:
        """Return the next FramePacket, or None on end-of-stream."""

    def close(self) -> None:
        """Release the underlying stream resource."""


CaptureFactory = Callable[[str], "_CaptureLike"]


class _CaptureLike(Protocol):
    def isOpened(self) -> bool: ...

    def read(self) -> "tuple[bool, object]": ...

    def release(self) -> None: ...


class OpenCvVideoSource:
    """VideoSource backed by cv2.VideoCapture.

    Accepts any URL cv2.VideoCapture supports (RTSP, RTMP over HTTP, file path,
    device index as str). The cv2 import is deferred to open() so this module
    remains importable even when opencv-python-headless has not been installed
    into the current environment.
    """

    def __init__(
        self,
        url: str,
        channel: Literal["visible", "thermal"],
        capture_factory: Optional[CaptureFactory] = None,
        clock: Callable[[], float] = time.time,
        max_reads_before_reopen: int = 8,
    ) -> None:
        self._url = url
        self._channel = channel
        self._capture_factory = capture_factory
        self._clock = clock
        self._max_reads_before_reopen = int(max_reads_before_reopen)
        self._reads_since_open = 0
        self._cap: Optional[_CaptureLike] = None

    @property
    def url(self) -> str:
        return self._url

    @property
    def channel(self) -> Literal["visible", "thermal"]:
        return self._channel

    def open(self) -> None:
        if self._cap is not None:
            return
        factory = self._capture_factory or _default_capture_factory()
        cap = factory(self._url)
        if not cap.isOpened():
            cap.release()
            raise VideoSourceOpenError(f"video-source-open-failed:{self._url}")
        self._cap = cap
        self._reads_since_open = 0

    def read(self) -> Optional[FramePacket]:
        if self._cap is None:
            raise VideoSourceNotOpenError("video-source-not-open")
        if (
            self._max_reads_before_reopen > 0
            and self._reads_since_open >= self._max_reads_before_reopen
        ):
            self.close()
            self.open()
        ok, frame = self._cap.read()
        if not ok or frame is None:
            return None
        self._reads_since_open += 1
        shape = getattr(frame, "shape", None)
        if shape is not None and len(shape) >= 2:
            height, width = int(shape[0]), int(shape[1])
        else:
            height, width = 0, 0
        source_ts = int(self._clock() * 1000)
        frame = _stamp_capture_time(frame, source_ts)
        return FramePacket(
            source_ts=source_ts,
            channel=self._channel,
            frame=frame,
            width=width,
            height=height,
        )

    def close(self) -> None:
        if self._cap is not None:
            self._cap.release()
            self._cap = None
            self._reads_since_open = 0


class LatestFrameVideoSource:
    """后台抓帧线程持续消费流，read() 只取内存里的最新帧、绝不阻塞在网络上。

    动机（2026-07-28 实飞踩坑）：FFMPEG 的 socket 超时把 RTCP 保活当作活跃流量，
    推流暂停时 av_read_frame 可以无限阻塞——实测造成 453s 的检测盲区且无任何日志。
    本包装的自愈方式不依赖 FFMPEG 内部行为：帧龄超过 stale_after_s 时 read() 返回
    None，上层按读失败走既有重连；close()+open() 直接抛弃卡死的旧线程和旧 capture
    （daemon 线程随进程回收），新建一路。

    附带收益：满速消费不积压解码缓冲，取代了 OpenCvVideoSource 里"每 8 读重开
    连接"的防积压手段（内层应以 max_reads_before_reopen=0 构造）。
    open() 不再因流暂缺而抛错——线程内重试，流出现即自愈（起飞前先开监测也能用）。
    """

    def __init__(
        self,
        source_factory: Callable[[], VideoSource],
        url: str,
        channel: Literal["visible", "thermal"],
        stale_after_s: float = 10.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._source_factory = source_factory
        self._url = url
        self._channel = channel
        self._stale_after_s = float(stale_after_s)
        self._clock = clock
        self._lock = threading.Lock()
        self._latest: Optional[FramePacket] = None
        self._latest_at: float = 0.0
        self._generation = 0
        self._thread: Optional[threading.Thread] = None
        self._thread_started_at: float = 0.0

    @property
    def url(self) -> str:
        return self._url

    @property
    def channel(self) -> Literal["visible", "thermal"]:
        return self._channel

    def open(self) -> None:
        with self._lock:
            thread = self._thread
            if thread is not None and thread.is_alive():
                fresh = self._latest is not None and (self._clock() - self._latest_at) <= self._stale_after_s
                starting = (self._clock() - self._thread_started_at) <= self._stale_after_s
                if fresh or starting:
                    # 现有抓帧线程还健康（有新帧或仍在启动宽限期内），不重复起线程。
                    return
                # 线程卡死（帧龄超限）：换代抛弃，旧线程解除阻塞后见代数不符自行退出。
                self._generation += 1
            generation = self._generation
            self._latest = None
            self._thread_started_at = self._clock()
            thread = threading.Thread(
                target=self._grab_loop,
                args=(generation,),
                name=f"frame-grabber-{self._channel}",
                daemon=True,
            )
            self._thread = thread
        thread.start()

    def read(self) -> Optional[FramePacket]:
        with self._lock:
            packet = self._latest
            age = self._clock() - self._latest_at
        if packet is None or age > self._stale_after_s:
            return None
        return packet

    def close(self) -> None:
        with self._lock:
            self._generation += 1
            self._latest = None
            self._thread = None

    def _grab_loop(self, generation: int) -> None:
        inner = self._source_factory()
        try:
            inner.open()
        except Exception:
            logger.warning(
                "frame-grabber open failed channel=%s url=%s", self._channel, self._url, exc_info=True
            )
            return
        try:
            while True:
                with self._lock:
                    if generation != self._generation:
                        return
                try:
                    packet = inner.read()
                except Exception:
                    logger.warning(
                        "frame-grabber read failed channel=%s url=%s", self._channel, self._url, exc_info=True
                    )
                    return
                if packet is None:
                    # 解码失败/流结束：线程退出，上层按帧龄超限走重连重建。
                    return
                with self._lock:
                    if generation != self._generation:
                        return
                    self._latest = packet
                    self._latest_at = self._clock()
        finally:
            try:
                inner.close()
            except Exception:
                pass


def _default_capture_factory() -> CaptureFactory:
    import cv2

    return lambda url: cv2.VideoCapture(url)


def _stamp_capture_time(frame: object, source_ts: int) -> object:
    shape = getattr(frame, "shape", None)
    if shape is None or len(shape) < 2:
        return frame
    try:
        import cv2

        height = int(shape[0])
        width = int(shape[1])
        if width <= 0 or height <= 0:
            return frame
        caption = datetime.fromtimestamp(source_ts / 1000.0).strftime("%Y-%m-%d %H:%M:%S")
        font = cv2.FONT_HERSHEY_SIMPLEX
        scale = max(min(width / 1280.0, 1.2), 0.55)
        thickness = max(int(round(scale * 2)), 1)
        margin = max(int(round(width * 0.012)), 10)
        text_size, baseline = cv2.getTextSize(caption, font, scale, thickness)
        text_w, text_h = text_size
        x = margin
        y = margin + text_h
        cv2.rectangle(
            frame,
            (max(x - 6, 0), max(y - text_h - 6, 0)),
            (min(x + text_w + 6, width - 1), min(y + baseline + 6, height - 1)),
            (0, 0, 0),
            -1,
        )
        cv2.putText(
            frame,
            caption,
            (x, y),
            font,
            scale,
            (255, 255, 255),
            thickness,
            cv2.LINE_AA,
        )
    except Exception:
        return frame
    return frame
