from app.fusion.service import DualStreamFusionService
from app.models.task import TaskCreateRequest
from app.services.task_registry import TaskRegistry
from app.services.task_runner import TaskRunner


def test_runner_run_once_generates_detection_event():
    registry = TaskRegistry(backend_client=RecordingBackendClient())
    runner = TaskRunner(
        registry=registry,
        visible_detector=FakeVisibleDetector(score=0.81),
        thermal_analyzer=FakeThermalAnalyzer(score=0.74),
        fusion_service=DualStreamFusionService(),
    )
    registry.create(
        TaskCreateRequest(
            task_id="task-200",
            drone_sn="DRONE-200",
            visible_stream_url="visible",
            thermal_stream_url="thermal",
        )
    )

    runner.run_once("task-200")

    events = registry.list_events("task-200")
    assert events[-1].event_type == "detection"
    assert events[-1].risk_level == "HIGH"


def test_registry_start_runs_bound_runner_once():
    registry = TaskRegistry(backend_client=RecordingBackendClient())
    runner = TaskRunner(
        registry=registry,
        visible_detector=FakeVisibleDetector(score=0.81),
        thermal_analyzer=FakeThermalAnalyzer(score=0.74),
        fusion_service=DualStreamFusionService(),
    )
    registry.bind_runner(runner)
    registry.create(
        TaskCreateRequest(
            task_id="task-201",
            drone_sn="DRONE-201",
            visible_stream_url="visible",
            thermal_stream_url="thermal",
        )
    )

    task = registry.start("task-201")

    events = registry.list_events("task-201")
    assert task.status == "running"
    assert events[-1].event_type == "detection"
    assert len([event for event in events if event.event_type == "detection"]) == 1


class FakeVisibleDetector:
    def __init__(self, score: float) -> None:
        self._score = score

    def detect(self, task_id: str) -> float:
        return self._score


class FakeThermalAnalyzer:
    def __init__(self, score: float) -> None:
        self._score = score

    def analyze(self, task_id: str) -> float:
        return self._score


class RecordingBackendClient:
    def report_event(self, task_id: str, payload: dict) -> None:
        return None
