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


class TaskRecord(TaskCreateRequest):
    status: DualStreamTaskStatus = DualStreamTaskStatus.CREATED
