import threading
import time

from app.fusion.service import DualStreamFusionService
from app.models.frame import FramePacket
from app.models.task import TaskCreateRequest, TaskRecord
from app.services.continuous_runner import ContinuousTaskRunner
from app.services.continuous_supervisor import (
    ContinuousTaskSupervisor,
    opencv_source_factory_from_task,
)
from app.services.task_registry import TaskRegistry


def _build_runner(registry):
    return ContinuousTaskRunner(
        registry=registry,
        visible_detector=_FakeDetector(score=0.9),
        thermal_analyzer=_FakeAnalyzer(score=0.0),
        fusion_service=DualStreamFusionService(),
        sleep=lambda _: None,
        poll_interval_s=0.0,
        max_consecutive_read_failures=10000,
    )


def _registry_with_task(task_id: str = "task-CS-1") -> TaskRegistry:
    registry = TaskRegistry()
    registry.create(
        TaskCreateRequest(
            task_id=task_id,
            drone_sn="DRONE-CS-1",
            visible_stream_url="visible",
            thermal_stream_url="thermal",
        )
    )
    return registry


def test_supervisor_starts_thread_and_emits_events_until_stop():
    registry = _registry_with_task()
    runner = _build_runner(registry)
    supervisor = ContinuousTaskSupervisor(runner=runner)

    visible_source = _BlockingVideoSource(
        packets=[FramePacket(source_ts=ts, channel="visible", frame=object()) for ts in range(50)]
    )

    supervisor.start("task-CS-1", visible_source=visible_source, thermal_source=None)
    _wait_until(lambda: _detection_count(registry, "task-CS-1") >= 3, timeout_s=2.0)

    assert supervisor.is_running("task-CS-1")
    assert supervisor.stop("task-CS-1")
    assert not supervisor.is_running("task-CS-1")
    assert _detection_count(registry, "task-CS-1") >= 3
    assert visible_source.opened
    assert visible_source.closed


def test_supervisor_double_start_is_idempotent_for_same_task():
    registry = _registry_with_task()
    runner = _build_runner(registry)
    supervisor = ContinuousTaskSupervisor(runner=runner)

    visible_source_one = _BlockingVideoSource(
        packets=[FramePacket(source_ts=1, channel="visible", frame=object())] * 100
    )
    visible_source_two = _BlockingVideoSource(
        packets=[FramePacket(source_ts=2, channel="visible", frame=object())] * 100
    )

    supervisor.start("task-CS-1", visible_source=visible_source_one, thermal_source=None)
    supervisor.start("task-CS-1", visible_source=visible_source_two, thermal_source=None)

    _wait_until(lambda: _detection_count(registry, "task-CS-1") >= 1, timeout_s=2.0)
    supervisor.stop("task-CS-1")

    assert visible_source_one.opened
    assert not visible_source_two.opened


def test_supervisor_stop_returns_false_for_unknown_task():
    runner = _build_runner(_registry_with_task())
    supervisor = ContinuousTaskSupervisor(runner=runner)

    assert supervisor.stop("unknown") is False


def test_supervisor_shutdown_stops_all_running_tasks():
    registry = TaskRegistry()
    for task_id in ("task-A", "task-B"):
        registry.create(
            TaskCreateRequest(
                task_id=task_id,
                drone_sn=f"DRONE-{task_id}",
                visible_stream_url="visible",
                thermal_stream_url="thermal",
            )
        )
    runner = _build_runner(registry)
    supervisor = ContinuousTaskSupervisor(runner=runner)

    for task_id in ("task-A", "task-B"):
        supervisor.start(
            task_id,
            visible_source=_BlockingVideoSource(
                packets=[FramePacket(source_ts=1, channel="visible", frame=object())] * 100
            ),
            thermal_source=None,
        )

    _wait_until(
        lambda: _detection_count(registry, "task-A") >= 1
        and _detection_count(registry, "task-B") >= 1,
        timeout_s=2.0,
    )
    supervisor.shutdown()

    assert not supervisor.is_running("task-A")
    assert not supervisor.is_running("task-B")


def test_opencv_source_factory_returns_none_for_placeholder_urls():
    task = TaskRecord(
        task_id="t1",
        drone_sn="d1",
        visible_stream_url="visible",
        thermal_stream_url="thermal",
    )

    visible, thermal = opencv_source_factory_from_task(task)

    assert visible is None
    assert thermal is None


def test_opencv_source_factory_returns_visible_for_rtsp_url_and_none_for_empty_thermal():
    task = TaskRecord(
        task_id="t1",
        drone_sn="d1",
        visible_stream_url="rtsp://example.com/live/visible",
        thermal_stream_url="",
    )

    visible, thermal = opencv_source_factory_from_task(task)

    assert visible is not None
    assert visible.url == "rtsp://example.com/live/visible"
    assert visible.channel == "visible"
    assert thermal is None


def test_opencv_source_factory_returns_both_when_both_urls_are_real():
    task = TaskRecord(
        task_id="t1",
        drone_sn="d1",
        visible_stream_url="rtmp://example.com/live/visible",
        thermal_stream_url="http://example.com/thermal.mjpg",
    )

    visible, thermal = opencv_source_factory_from_task(task)

    assert visible is not None and visible.channel == "visible"
    assert thermal is not None and thermal.channel == "thermal"


def test_registry_dispatches_to_continuous_supervisor_when_factory_returns_source():
    registry = _registry_with_task("task-disp-1")
    invocations = {"started_with": None}

    class _RecordingSupervisor:
        def start(self, task_id, visible_source, thermal_source):
            invocations["started_with"] = (task_id, visible_source, thermal_source)

        def stop(self, task_id):
            return True

    visible_marker = object()

    def _factory(task):
        return visible_marker, None

    registry.bind_continuous_supervisor(_RecordingSupervisor(), source_factory=_factory)

    legacy_runner_calls = {"count": 0}

    class _RecordingRunner:
        def run_once(self, task_id):
            legacy_runner_calls["count"] += 1

    registry.bind_runner(_RecordingRunner())

    registry.start("task-disp-1")

    assert invocations["started_with"] == ("task-disp-1", visible_marker, None)
    assert legacy_runner_calls["count"] == 0


def test_registry_falls_back_to_legacy_runner_when_factory_returns_no_sources():
    registry = _registry_with_task("task-disp-2")

    class _RecordingSupervisor:
        def __init__(self):
            self.started = False

        def start(self, task_id, visible_source, thermal_source):
            self.started = True

        def stop(self, task_id):
            return True

    supervisor = _RecordingSupervisor()
    registry.bind_continuous_supervisor(supervisor, source_factory=lambda task: (None, None))

    legacy_runner_calls = {"count": 0}

    class _RecordingRunner:
        def run_once(self, task_id):
            legacy_runner_calls["count"] += 1

    registry.bind_runner(_RecordingRunner())

    registry.start("task-disp-2")

    assert supervisor.started is False
    assert legacy_runner_calls["count"] == 1


def test_registry_stop_invokes_supervisor_stop_when_bound():
    registry = _registry_with_task("task-disp-3")
    stop_calls = {"task_ids": []}

    class _RecordingSupervisor:
        def start(self, task_id, visible_source, thermal_source):
            return None

        def stop(self, task_id):
            stop_calls["task_ids"].append(task_id)
            return True

    registry.bind_continuous_supervisor(_RecordingSupervisor(), source_factory=lambda task: (None, None))

    registry.stop("task-disp-3")

    assert stop_calls["task_ids"] == ["task-disp-3"]


def _wait_until(predicate, timeout_s: float):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.01)
    raise AssertionError(f"predicate did not become true within {timeout_s}s")


def _detection_count(registry: TaskRegistry, task_id: str) -> int:
    return sum(
        1 for e in registry.list_events(task_id) if e.event_type == "detection"
    )


class _FakeDetector:
    def __init__(self, score: float) -> None:
        self._score = score

    def detect(self, frame: FramePacket) -> float:
        return self._score


class _FakeAnalyzer:
    def __init__(self, score: float) -> None:
        self._score = score

    def analyze(self, frame: FramePacket) -> float:
        return self._score


class _BlockingVideoSource:
    def __init__(self, packets):
        self._packets = list(packets)
        self.opened = False
        self.closed = False
        self._lock = threading.Lock()

    def open(self):
        self.opened = True

    def read(self):
        time.sleep(0.01)
        with self._lock:
            if not self._packets:
                return None
            return self._packets.pop(0)

    def close(self):
        self.closed = True
