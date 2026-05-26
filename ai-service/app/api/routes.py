import json
import logging
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, HTTPException, Request, status
from pydantic import BaseModel, ConfigDict, Field

from app.config.settings import Settings
from app.models.event import DualStreamEvent, EventRecord
from app.models.task import TaskCreateRequest, TaskRecord
from app.services.snapshot_writer import SnapshotWriter
from app.services.task_registry import registry


router = APIRouter()
logger = logging.getLogger(__name__)


class ThermalAnnotationRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    thermal_temperature: float = Field(alias="thermalTemperature")
    thermal_measure_roi: Optional[dict] = Field(default=None, alias="thermalMeasureRoi")


def parse_thermal_annotation_request(payload: Dict[str, Any]) -> ThermalAnnotationRequest:
    if not isinstance(payload, dict):
        logger.warning("thermal annotation payload is not object payload=%r", payload)
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="json body must be an object")
    temperature = payload.get("thermal_temperature", payload.get("thermalTemperature"))
    if temperature is None:
        logger.warning("thermal annotation payload missing temperature payload=%r", payload)
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="thermal_temperature is required")
    try:
        temperature_value = float(temperature)
    except (TypeError, ValueError) as exc:
        logger.warning("thermal annotation payload invalid temperature payload=%r", payload)
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="thermal_temperature is invalid") from exc

    roi = payload.get("thermal_measure_roi", payload.get("thermalMeasureRoi"))
    return ThermalAnnotationRequest(
        thermal_temperature=temperature_value,
        thermal_measure_roi=roi if isinstance(roi, dict) else None,
    )


@router.get("/healthz")
def healthz() -> Dict[str, str]:
    return {"status": "ok"}


@router.post("/api/v1/dual-stream/tasks", response_model=TaskRecord, status_code=status.HTTP_201_CREATED)
def create_task(payload: TaskCreateRequest) -> TaskRecord:
    return registry.create(payload)


@router.post("/api/v1/dual-stream/tasks/{task_id}/start", response_model=TaskRecord)
def start_task(task_id: str) -> TaskRecord:
    try:
        return registry.start(task_id)
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Task not found") from exc


@router.post("/api/v1/dual-stream/tasks/{task_id}/stop", response_model=TaskRecord)
def stop_task(task_id: str) -> TaskRecord:
    try:
        return registry.stop(task_id)
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Task not found") from exc


@router.get("/api/v1/dual-stream/tasks/{task_id}", response_model=TaskRecord)
def get_task(task_id: str) -> TaskRecord:
    try:
        return registry.get(task_id)
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Task not found") from exc


@router.get("/api/v1/dual-stream/tasks/{task_id}/events", response_model=List[EventRecord])
def list_task_events(task_id: str) -> List[EventRecord]:
    try:
        return registry.list_events(task_id)
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Task not found") from exc


@router.post("/api/v1/dual-stream/tasks/{task_id}/events", response_model=EventRecord, status_code=status.HTTP_201_CREATED)
def record_task_event(task_id: str, payload: DualStreamEvent) -> EventRecord:
    try:
        return registry.record_detection_event(task_id, payload)
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Task not found") from exc


@router.post("/api/v1/snapshots/{event_id}/thermal-annotation")
async def refresh_thermal_annotation(event_id: str, request: Request) -> Dict[str, str]:
    raw_body = await request.body()
    try:
        payload = json.loads(raw_body.decode("utf-8") or "{}")
    except json.JSONDecodeError as exc:
        logger.warning("invalid thermal annotation payload event_id=%s body=%r", event_id, raw_body[:500])
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="invalid json body") from exc
    annotation = parse_thermal_annotation_request(payload)
    settings = Settings()
    writer = SnapshotWriter(
        snapshot_dir=settings.snapshot_dir,
        public_base_url=settings.snapshot_public_base_url,
    )
    url = writer.refresh_thermal_annotation(
        event_id,
        thermal_temperature=annotation.thermal_temperature,
        thermal_measure_roi=annotation.thermal_measure_roi,
    )
    if not url:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Snapshot not found")
    return {"url": url}
