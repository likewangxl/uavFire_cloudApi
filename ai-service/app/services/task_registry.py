from typing import Callable, Dict, List, Optional, Protocol, Tuple, TYPE_CHECKING

from app.clients.backend_client import BackendClient
from app.config.settings import Settings
from app.models.event import DualStreamEvent, EventRecord
from app.models.task import DualStreamTaskStatus, TaskCreateRequest, TaskRecord
from app.video.source import VideoSource


class SupportsBackendEventReporting(Protocol):
    def report_event(self, task_id: str, payload: Dict[str, object]) -> None:
        ...


SourceFactory = Callable[[TaskRecord], Tuple[Optional[VideoSource], Optional[VideoSource]]]


class TaskRegistry:
    def __init__(self, backend_client: Optional[SupportsBackendEventReporting] = None) -> None:
        self._tasks: Dict[str, TaskRecord] = {}
        self._events: Dict[str, List[EventRecord]] = {}
        self._backend_client = backend_client
        self._runner: Optional["TaskRunner"] = None
        self._continuous_supervisor: Optional["ContinuousTaskSupervisor"] = None
        self._source_factory: Optional[SourceFactory] = None

    def bind_runner(self, runner: "TaskRunner") -> None:
        self._runner = runner

    def bind_continuous_supervisor(
        self,
        supervisor: "ContinuousTaskSupervisor",
        source_factory: SourceFactory,
    ) -> None:
        self._continuous_supervisor = supervisor
        self._source_factory = source_factory

    def create(self, payload: TaskCreateRequest) -> TaskRecord:
        task = TaskRecord(**payload.model_dump())
        self._tasks[task.task_id] = task
        self._events[task.task_id] = [
            EventRecord(
                task_id=task.task_id,
                event_type="created",
                status=task.status,
            )
        ]
        return task

    def start(self, task_id: str) -> TaskRecord:
        task = self.get(task_id)
        task.status = DualStreamTaskStatus.RUNNING
        self._events[task_id].append(
            EventRecord(
                task_id=task_id,
                event_type="started",
                status=task.status,
            )
        )
        if self._dispatch_to_continuous_supervisor(task):
            return task
        if self._runner is not None:
            self._runner.run_once(task_id)
        return task

    def stop(self, task_id: str) -> TaskRecord:
        task = self.get(task_id)
        task.status = DualStreamTaskStatus.STOPPED
        self._events[task_id].append(
            EventRecord(
                task_id=task_id,
                event_type="stopped",
                status=task.status,
            )
        )
        if self._continuous_supervisor is not None:
            self._continuous_supervisor.stop(task_id)
        return task

    def _dispatch_to_continuous_supervisor(self, task: TaskRecord) -> bool:
        if self._continuous_supervisor is None or self._source_factory is None:
            return False
        visible_source, thermal_source = self._source_factory(task)
        if visible_source is None and thermal_source is None:
            return False
        self._continuous_supervisor.start(
            task_id=task.task_id,
            visible_source=visible_source,
            thermal_source=thermal_source,
        )
        return True

    def get(self, task_id: str) -> TaskRecord:
        task = self._tasks.get(task_id)
        if task is None:
            raise KeyError(task_id)
        return task

    def record_detection_event(self, task_id: str, event: DualStreamEvent) -> EventRecord:
        task = self.get(task_id)
        record = EventRecord(
            task_id=task_id,
            event_type="detection",
            status=task.status,
            drone_sn=task.drone_sn,
            source_ts=event.source_ts,
            fusion_score=event.fusion_score,
            risk_level=event.risk_level,
        )
        self._events[task_id].append(record)
        if self._backend_client is not None:
            self._backend_client.report_event(
                task_id,
                {
                    "drone_sn": task.drone_sn,
                    "source_ts": event.source_ts,
                    "visible_score": event.visible_score,
                    "thermal_score": event.thermal_score,
                    "fusion_score": event.fusion_score,
                    "risk_level": event.risk_level,
                },
            )
        return record

    def list_events(self, task_id: str) -> List[EventRecord]:
        self.get(task_id)
        return list(self._events.get(task_id, []))


def build_registry() -> TaskRegistry:
    from app.fusion.service import DualStreamFusionService
    from app.inference.thermal.analyzer import StubThermalAnalyzer
    from app.inference.visible.detector import StubVisibleDetector
    from app.services.continuous_runner import ContinuousTaskRunner
    from app.services.continuous_supervisor import (
        ContinuousTaskSupervisor,
        opencv_source_factory_from_task,
    )
    from app.services.task_runner import (
        StreamScoreThermalAnalyzer,
        StreamScoreVisibleDetector,
        TaskRunner,
    )

    settings = Settings()
    backend_client = BackendClient(settings.backend_base_url) if settings.backend_base_url else None
    registry = TaskRegistry(backend_client=backend_client)
    fusion = DualStreamFusionService()
    registry.bind_runner(
        TaskRunner(
            registry=registry,
            visible_detector=StreamScoreVisibleDetector(),
            thermal_analyzer=StreamScoreThermalAnalyzer(),
            fusion_service=fusion,
        )
    )
    if settings.use_continuous_runner:
        registry.bind_continuous_supervisor(
            ContinuousTaskSupervisor(
                runner=ContinuousTaskRunner(
                    registry=registry,
                    visible_detector=StubVisibleDetector(),
                    thermal_analyzer=StubThermalAnalyzer(),
                    fusion_service=fusion,
                ),
            ),
            source_factory=opencv_source_factory_from_task,
        )
    return registry


registry = build_registry()

if TYPE_CHECKING:
    from app.services.continuous_supervisor import ContinuousTaskSupervisor
    from app.services.task_runner import TaskRunner
