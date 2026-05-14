from typing import Optional

from pydantic import BaseModel

from app.models.task import DualStreamTaskStatus


class EventRecord(BaseModel):
    task_id: str
    event_type: str
    status: DualStreamTaskStatus
    drone_sn: Optional[str] = None
    source_ts: Optional[int] = None
    fusion_score: Optional[float] = None
    risk_level: Optional[str] = None
    analysis_channel: Optional[str] = None


class DualStreamEvent(BaseModel):
    source_ts: int
    visible_score: float
    thermal_score: float
    fusion_score: float
    risk_level: str
    analysis_channel: Optional[str] = None
