from typing import Optional

from pydantic import BaseModel

from app.models.task import DualStreamTaskStatus


class ThermalMeasureRoi(BaseModel):
    x: float
    y: float
    width: float
    height: float


class EventRecord(BaseModel):
    task_id: str
    event_type: str
    status: DualStreamTaskStatus
    drone_sn: Optional[str] = None
    source_ts: Optional[int] = None
    fusion_score: Optional[float] = None
    risk_level: Optional[str] = None
    analysis_channel: Optional[str] = None
    visible_image_url: Optional[str] = None
    thermal_image_url: Optional[str] = None
    thermal_source_event_id: Optional[str] = None
    thermal_temperature: Optional[float] = None
    thermal_measure_roi: Optional[ThermalMeasureRoi] = None
    geo_snapshot: Optional[dict] = None


class DualStreamEvent(BaseModel):
    source_ts: int
    visible_score: float
    thermal_score: float
    fusion_score: float
    risk_level: str
    analysis_channel: Optional[str] = None
    visible_image_url: Optional[str] = None
    thermal_image_url: Optional[str] = None
    thermal_source_event_id: Optional[str] = None
    thermal_temperature: Optional[float] = None
    thermal_measure_roi: Optional[ThermalMeasureRoi] = None
    geo_snapshot: Optional[dict] = None
