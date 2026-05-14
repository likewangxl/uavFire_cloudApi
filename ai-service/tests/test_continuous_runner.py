from app.fusion.service import DualStreamFusionService
from app.models.frame import FramePacket
from app.models.task import TaskCreateRequest
from app.services.continuous_runner import ContinuousTaskRunner
from app.services.task_registry import TaskRegistry


def _registry_with_task(task_id: str = "task-CR-1") -> TaskRegistry:
    registry = TaskRegistry()
    registry.create(
        TaskCreateRequest(
            task_id=task_id,
            drone_sn="DRONE-CR-1",
            visible_stream_url="visible",
            thermal_stream_url="thermal",
        )
    )
    return registry


def test_tick_emits_event_with_both_scores_when_both_packets_available():
    registry = _registry_with_task()
    runner = ContinuousTaskRunner(
        registry=registry,
        visible_detector=_FakeDetector(score=0.9),
        thermal_analyzer=_FakeAnalyzer(score=0.8),
        fusion_service=DualStreamFusionService(),
    )

    record = runner.tick(
        task_id="task-CR-1",
        visible_source=_StubVideoSource(
            packets=[FramePacket(source_ts=1700, channel="visible", frame=object())]
        ),
        thermal_source=_StubVideoSource(
            packets=[FramePacket(source_ts=1701, channel="thermal", frame=object())]
        ),
    )

    assert record is not None
    assert record.event_type == "detection"
    assert record.source_ts == 1700
    assert record.fusion_score == round(0.9 * 0.6 + 0.8 * 0.4, 3)
    assert record.risk_level == "HIGH"


def test_tick_returns_none_when_both_sources_dry():
    registry = _registry_with_task()
    runner = ContinuousTaskRunner(
        registry=registry,
        visible_detector=_FakeDetector(score=0.9),
        thermal_analyzer=_FakeAnalyzer(score=0.8),
        fusion_service=DualStreamFusionService(),
    )

    record = runner.tick(
        task_id="task-CR-1",
        visible_source=_StubVideoSource(packets=[]),
        thermal_source=_StubVideoSource(packets=[]),
    )

    assert record is None
    assert [event.event_type for event in registry.list_events("task-CR-1")] == ["created"]


def test_tick_emits_event_with_visible_only_when_thermal_source_is_none():
    registry = _registry_with_task()
    runner = ContinuousTaskRunner(
        registry=registry,
        visible_detector=_FakeDetector(score=0.6),
        thermal_analyzer=_FakeAnalyzer(score=0.0),
        fusion_service=DualStreamFusionService(),
    )

    record = runner.tick(
        task_id="task-CR-1",
        visible_source=_StubVideoSource(
            packets=[FramePacket(source_ts=1800, channel="visible", frame=object())]
        ),
        thermal_source=None,
    )

    assert record is not None
    assert record.fusion_score == round(0.6 * 0.6, 3)
    assert record.analysis_channel == "visible"


def test_tick_emits_event_with_thermal_channel_when_only_thermal_packet_available():
    registry = _registry_with_task()
    runner = ContinuousTaskRunner(
        registry=registry,
        visible_detector=_FakeDetector(score=0.0),
        thermal_analyzer=_FakeAnalyzer(score=0.9),
        fusion_service=DualStreamFusionService(),
    )

    record = runner.tick(
        task_id="task-CR-1",
        visible_source=None,
        thermal_source=_StubVideoSource(
            packets=[FramePacket(source_ts=1900, channel="thermal", frame=object())]
        ),
    )

    assert record is not None
    assert record.analysis_channel == "thermal"
    assert record.fusion_score == round(0.9 * 0.4, 3)


def test_tick_swallows_read_exception_and_treats_as_no_packet():
    registry = _registry_with_task()
    runner = ContinuousTaskRunner(
        registry=registry,
        visible_detector=_FakeDetector(score=0.9),
        thermal_analyzer=_FakeAnalyzer(score=0.0),
        fusion_service=DualStreamFusionService(),
    )

    record = runner.tick(
        task_id="task-CR-1",
        visible_source=_RaisingVideoSource(),
        thermal_source=_StubVideoSource(packets=[]),
    )

    assert record is None


def test_run_calls_open_then_close_on_provided_sources_and_stops_via_predicate():
    registry = _registry_with_task()
    runner = ContinuousTaskRunner(
        registry=registry,
        visible_detector=_FakeDetector(score=0.5),
        thermal_analyzer=_FakeAnalyzer(score=0.5),
        fusion_service=DualStreamFusionService(),
        sleep=lambda _: None,
        poll_interval_s=0.0,
    )
    visible_source = _StubVideoSource(
        packets=[FramePacket(source_ts=ts, channel="visible", frame=object()) for ts in (1, 2, 3)]
    )

    def stop_predicate():
        detection_count = sum(
            1 for e in registry.list_events("task-CR-1") if e.event_type == "detection"
        )
        return detection_count >= 2

    runner.run(
        task_id="task-CR-1",
        visible_source=visible_source,
        thermal_source=None,
        stop_predicate=stop_predicate,
    )

    assert visible_source.opened
    assert visible_source.closed
    detection_events = [
        e for e in registry.list_events("task-CR-1") if e.event_type == "detection"
    ]
    assert len(detection_events) == 2


def test_run_breaks_after_max_consecutive_read_failures():
    registry = _registry_with_task()
    runner = ContinuousTaskRunner(
        registry=registry,
        visible_detector=_FakeDetector(score=0.9),
        thermal_analyzer=_FakeAnalyzer(score=0.0),
        fusion_service=DualStreamFusionService(),
        sleep=lambda _: None,
        poll_interval_s=0.0,
        max_consecutive_read_failures=3,
    )
    visible_source = _StubVideoSource(packets=[])

    runner.run(
        task_id="task-CR-1",
        visible_source=visible_source,
        thermal_source=None,
        stop_predicate=lambda: False,
    )

    assert visible_source.read_calls == 3
    assert visible_source.closed


def test_run_resets_consecutive_failure_count_after_a_successful_tick():
    registry = _registry_with_task()
    runner = ContinuousTaskRunner(
        registry=registry,
        visible_detector=_FakeDetector(score=0.9),
        thermal_analyzer=_FakeAnalyzer(score=0.0),
        fusion_service=DualStreamFusionService(),
        sleep=lambda _: None,
        poll_interval_s=0.0,
        max_consecutive_read_failures=2,
    )
    visible_source = _PatternVideoSource(
        pattern=[None, FramePacket(source_ts=10, channel="visible", frame=object()), None, None]
    )

    runner.run(
        task_id="task-CR-1",
        visible_source=visible_source,
        thermal_source=None,
        stop_predicate=lambda: False,
    )

    assert visible_source.read_calls == 4
    detection_events = [
        e for e in registry.list_events("task-CR-1") if e.event_type == "detection"
    ]
    assert len(detection_events) == 1


def test_run_does_not_call_open_or_close_when_a_source_is_none():
    registry = _registry_with_task()
    runner = ContinuousTaskRunner(
        registry=registry,
        visible_detector=_FakeDetector(score=0.0),
        thermal_analyzer=_FakeAnalyzer(score=0.0),
        fusion_service=DualStreamFusionService(),
        sleep=lambda _: None,
        poll_interval_s=0.0,
    )

    runner.run(
        task_id="task-CR-1",
        visible_source=None,
        thermal_source=None,
        stop_predicate=lambda: True,
    )

    assert [e.event_type for e in registry.list_events("task-CR-1")] == ["created"]


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


class _StubVideoSource:
    def __init__(self, packets):
        self._packets = list(packets)
        self.opened = False
        self.closed = False
        self.read_calls = 0

    def open(self):
        self.opened = True

    def read(self):
        self.read_calls += 1
        if not self._packets:
            return None
        return self._packets.pop(0)

    def close(self):
        self.closed = True


class _PatternVideoSource(_StubVideoSource):
    def __init__(self, pattern):
        super().__init__(packets=[])
        self._pattern = list(pattern)

    def read(self):
        self.read_calls += 1
        if not self._pattern:
            return None
        return self._pattern.pop(0)


class _RaisingVideoSource:
    def open(self):
        return None

    def read(self):
        raise RuntimeError("transient-rtsp-glitch")

    def close(self):
        return None
