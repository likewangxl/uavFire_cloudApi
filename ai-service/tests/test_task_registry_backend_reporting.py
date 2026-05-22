from app.models.event import DualStreamEvent
from app.models.task import TaskCreateRequest
from app.services.task_registry import TaskRegistry
from typing import Optional


def test_record_detection_event_appends_local_event_and_reports_to_backend():
    backend = RecordingBackendClient()
    registry = TaskRegistry(backend_client=backend)
    registry.create(
        TaskCreateRequest(
            task_id="task-100",
            drone_sn="DRONE-100",
            visible_stream_url="rtsp://visible",
            thermal_stream_url="rtsp://thermal",
        )
    )

    registry.record_detection_event(
        "task-100",
        DualStreamEvent(
            source_ts=1710000000,
            visible_score=0.81,
            thermal_score=0.74,
            fusion_score=0.782,
            risk_level="HIGH",
            analysis_channel="visible",
        ),
    )

    events = registry.list_events("task-100")
    assert events[-1].event_type == "detection"
    assert events[-1].risk_level == "HIGH"
    assert events[-1].fusion_score == 0.782
    assert events[-1].analysis_channel == "visible"
    assert backend.last_task_id == "task-100"
    assert backend.last_payload["drone_sn"] == "DRONE-100"
    assert backend.last_payload["risk_level"] == "HIGH"
    assert backend.last_payload["analysis_channel"] == "visible"
    assert backend.fire_event_payloads == []


class RecordingBackendClient:
    def __init__(self) -> None:
        self.last_task_id: Optional[str] = None
        self.last_payload: Optional[dict] = None
        self.fire_event_payloads = []

    def report_event(self, task_id: str, payload: dict) -> None:
        self.last_task_id = task_id
        self.last_payload = payload

    def report_fire_event(self, payload: dict) -> None:
        self.fire_event_payloads.append(payload)
