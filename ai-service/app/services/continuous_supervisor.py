import threading
from typing import Callable, Dict, Optional, Tuple, TYPE_CHECKING

from app.models.task import TaskRecord
from app.video.source import VideoSource

if TYPE_CHECKING:
    from app.services.continuous_runner import ContinuousTaskRunner


SourceFactory = Callable[[TaskRecord], Tuple[Optional[VideoSource], Optional[VideoSource]]]


class _Worker:
    __slots__ = ("thread", "stop_event")

    def __init__(self, thread: threading.Thread, stop_event: threading.Event) -> None:
        self.thread = thread
        self.stop_event = stop_event


class ContinuousTaskSupervisor:
    """Owns one background thread per task that drives a ContinuousTaskRunner.

    `start(task_id, visible_source, thermal_source)` spawns a thread; `stop`
    signals the stop event and joins. Designed for cooperative shutdown — the
    runner checks `stop_predicate()` between ticks and at loop top.
    """

    def __init__(
        self,
        runner: "ContinuousTaskRunner",
        thread_factory: Optional[Callable[..., threading.Thread]] = None,
        join_timeout_s: float = 5.0,
    ) -> None:
        self._runner = runner
        self._thread_factory = thread_factory or _default_thread_factory
        self._join_timeout_s = float(join_timeout_s)
        self._workers: Dict[str, _Worker] = {}
        self._lock = threading.RLock()

    def start(
        self,
        task_id: str,
        visible_source: Optional[VideoSource],
        thermal_source: Optional[VideoSource],
    ) -> None:
        with self._lock:
            existing = self._workers.get(task_id)
            if existing is not None and existing.thread.is_alive():
                return
            # 丢弃已死的旧 worker 记录，避免陈旧条目干扰后续 stop/start。
            if existing is not None:
                self._workers.pop(task_id, None)
            stop_event = threading.Event()

            def _target():
                try:
                    self._runner.run(
                        task_id=task_id,
                        visible_source=visible_source,
                        thermal_source=thermal_source,
                        stop_predicate=stop_event.is_set,
                    )
                finally:
                    # 线程自行退出（流失效/失败/被停）时摘除自身记录，保证字典只含存活 worker。
                    with self._lock:
                        current = self._workers.get(task_id)
                        if current is not None and current.stop_event is stop_event:
                            self._workers.pop(task_id, None)

            thread = self._thread_factory(
                target=_target,
                name=f"continuous-runner-{task_id}",
                daemon=True,
            )
            self._workers[task_id] = _Worker(thread=thread, stop_event=stop_event)
            thread.start()

    def stop(self, task_id: str) -> bool:
        with self._lock:
            worker = self._workers.pop(task_id, None)
        if worker is None:
            return False
        worker.stop_event.set()
        worker.thread.join(timeout=self._join_timeout_s)
        return not worker.thread.is_alive()

    def is_running(self, task_id: str) -> bool:
        with self._lock:
            worker = self._workers.get(task_id)
        return worker is not None and worker.thread.is_alive()

    def shutdown(self) -> None:
        with self._lock:
            task_ids = list(self._workers.keys())
        for task_id in task_ids:
            self.stop(task_id)


def _default_thread_factory(target, name, daemon):
    return threading.Thread(target=target, name=name, daemon=daemon)


def opencv_source_factory_from_task(task: TaskRecord) -> Tuple[Optional[VideoSource], Optional[VideoSource]]:
    """Default factory: build latest-frame wrapped OpenCvVideoSource from task URLs.

    URLs that are empty or look like placeholders ("visible", "thermal") yield
    `None` so the runner can degrade to a single channel.

    LatestFrameVideoSource：后台线程满速抓帧，检测循环永不阻塞在网络上
    （FFMPEG 在推流暂停时可无限阻塞，实测造成 453s 检测盲区）。
    内层 max_reads_before_reopen=0：满速消费无缓冲积压，不再需要周期性重开连接。
    """
    from app.video.source import LatestFrameVideoSource, OpenCvVideoSource

    def _wrapped(url: str, channel):
        return LatestFrameVideoSource(
            source_factory=lambda: OpenCvVideoSource(
                url=url, channel=channel, max_reads_before_reopen=0
            ),
            url=url,
            channel=channel,
        )

    visible = (
        _wrapped(task.visible_stream_url, "visible")
        if _looks_like_real_url(task.visible_stream_url)
        else None
    )
    thermal = (
        _wrapped(task.thermal_stream_url, "thermal")
        if _looks_like_real_url(task.thermal_stream_url)
        else None
    )
    return visible, thermal


def _looks_like_real_url(value: str) -> bool:
    if not value:
        return False
    lowered = value.lower()
    if lowered in {"visible", "thermal"}:
        return False
    return any(
        lowered.startswith(scheme)
        for scheme in ("rtsp://", "rtmp://", "http://", "https://", "file://", "/")
    )
