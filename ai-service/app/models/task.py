from enum import Enum

from pydantic import BaseModel


class DualStreamTaskStatus(str, Enum):
    CREATED = "created"
    RUNNING = "running"
    STOPPED = "stopped"
    FAILED = "failed"


class TaskCreateRequest(BaseModel):
    task_id: str
    drone_sn: str
    visible_stream_url: str
    thermal_stream_url: str
    payload_model_key: str | None = None
    visible_model_path: str | None = None
    visible_confidence_floor: float | None = None


class TaskRecord(TaskCreateRequest):
    status: DualStreamTaskStatus = DualStreamTaskStatus.CREATED
