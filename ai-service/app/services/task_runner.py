import time
from typing import Protocol

from app.fusion.service import DualStreamFusionService
from app.models.event import EventRecord
from app.models.task import TaskRecord


class SupportsVisibleDetection(Protocol):
    def detect(self, task: TaskRecord) -> float:
        ...


class SupportsThermalAnalysis(Protocol):
    def analyze(self, task: TaskRecord) -> float:
        ...


class StreamScoreVisibleDetector:
    def detect(self, task: TaskRecord) -> float:
        return 0.81 if task.visible_stream_url else 0.0


class StreamScoreThermalAnalyzer:
    def analyze(self, task: TaskRecord) -> float:
        return 0.74 if task.thermal_stream_url else 0.0


class TaskRunner:
    def __init__(
        self,
        registry: "TaskRegistry",
        visible_detector: SupportsVisibleDetection,
        thermal_analyzer: SupportsThermalAnalysis,
        fusion_service: DualStreamFusionService,
    ) -> None:
        self._registry = registry
        self._visible_detector = visible_detector
        self._thermal_analyzer = thermal_analyzer
        self._fusion_service = fusion_service

    def run_once(self, task_id: str) -> EventRecord:
        task = self._registry.get(task_id)
        visible_score = self._visible_detector.detect(task)
        thermal_score = self._thermal_analyzer.analyze(task)
        event = self._fusion_service.combine(
            visible_score=visible_score,
            thermal_score=thermal_score,
            source_ts=int(time.time()),
        )
        return self._registry.record_detection_event(task_id, event)


from app.services.task_registry import TaskRegistry  # noqa: E402
