import time
from datetime import datetime
from typing import Callable, Literal, Optional, Protocol

from app.models.frame import FramePacket


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
