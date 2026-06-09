import json
import logging
import time
from typing import Any, Dict, List, Optional

import cv2
import numpy as np
from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile, status
from pydantic import BaseModel, ConfigDict, Field

from app.config.settings import Settings
from app.models.event import DualStreamEvent, EventRecord
from app.models.frame import FramePacket
from app.models.task import TaskCreateRequest, TaskRecord
from app.services.snapshot_writer import SnapshotWriter
from app.services.task_registry import (
    _build_backend_client,
    _get_cached_visible_detector,
    _looks_like_thermal_frame,
    _thermal_frame_stats,
    registry,
)


router = APIRouter()
logger = logging.getLogger(__name__)


class ThermalAnnotationRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    thermal_temperature: float = Field(alias="thermalTemperature")
    thermal_measure_roi: Optional[dict] = Field(default=None, alias="thermalMeasureRoi")
    thermal_detect_roi: Optional[dict] = Field(default=None, alias="thermalDetectRoi")
    thermal_measurements: List[dict] = Field(default_factory=list, alias="thermalMeasurements")


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
    detect_roi = payload.get("thermal_detect_roi", payload.get("thermalDetectRoi"))
    measurements = payload.get("thermal_measurements", payload.get("thermalMeasurements"))
    return ThermalAnnotationRequest(
        thermal_temperature=temperature_value,
        thermal_measure_roi=roi if isinstance(roi, dict) else None,
        thermal_detect_roi=detect_roi if isinstance(detect_roi, dict) else None,
        thermal_measurements=measurements if isinstance(measurements, list) else [],
    )


def _decode_uploaded_jpeg(content: bytes) -> Any:
    if not content:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Snapshot file is empty")
    arr = np.frombuffer(content, dtype=np.uint8)
    frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if frame is None or getattr(frame, "size", 0) == 0:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Snapshot file is not a valid JPEG image")
    return frame


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


@router.post("/api/v1/snapshots/msdk-thermal/{event_id}", status_code=status.HTTP_201_CREATED)
async def upload_msdk_thermal_snapshot(
    request: Request,
    event_id: str,
    allow_unverified: bool = Form(default=False),
    file: UploadFile = File(...),
) -> Dict[str, Any]:
    content_type = (file.content_type or "").lower()
    if content_type and content_type not in {"image/jpeg", "image/jpg"}:
        raise HTTPException(status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, detail="Only JPEG snapshots are supported")
    content = await file.read()
    frame = _decode_uploaded_jpeg(content)
    thermal_stats = _thermal_frame_stats(frame)
    reject_reason = "obvious_visible_frame" if thermal_stats.get("obvious_visible") else "not_thermal_frame"
    if not thermal_stats["looks_thermal"]:
        logger.warning(
            "msdk thermal snapshot rejected event_id=%s reason=%s allow_unverified=%s "
            "client=%s filename=%s content_type=%s bytes=%s shape=%s grayscale_ratio=%.4f "
            "intensity_range=%.2f intensity_std=%.2f",
            event_id,
            reject_reason,
            allow_unverified,
            request.client.host if request.client else "-",
            file.filename,
            content_type or "-",
            len(content),
            thermal_stats["shape"],
            thermal_stats["grayscale_ratio"],
            thermal_stats["intensity_range"],
            thermal_stats["intensity_std"],
        )
    if not thermal_stats["looks_thermal"] and (not allow_unverified or thermal_stats.get("obvious_visible")):
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Snapshot does not look like a thermal frame")
    settings = Settings()
    writer = SnapshotWriter(
        snapshot_dir=settings.snapshot_dir,
        public_base_url=settings.snapshot_public_base_url,
    )
    url = writer.write_uploaded_thermal_jpeg(event_id, content)
    if not url:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="Snapshot write failed")
    thermal_verified = bool(thermal_stats["looks_thermal"])
    if not thermal_verified:
        logger.warning(
            "msdk thermal snapshot accepted as unverified fallback event_id=%s url=%s "
            "grayscale_ratio=%.4f intensity_range=%.2f intensity_std=%.2f",
            event_id,
            url,
            thermal_stats["grayscale_ratio"],
            thermal_stats["intensity_range"],
            thermal_stats["intensity_std"],
        )
    return {
        "url": url,
        "thermal_verified": thermal_verified,
        "thermal_reject_reason": None if thermal_verified else "not_thermal_frame",
    }


@router.post("/api/v1/snapshots/msdk-visible/{event_id}", status_code=status.HTTP_201_CREATED)
async def upload_msdk_visible_snapshot(
    event_id: str,
    task_id: str = Form(...),
    drone_sn: str = Form(...),
    source_ts: int = Form(...),
    thermal_source_event_id: Optional[str] = Form(default=None),
    thermal_image_url: Optional[str] = Form(default=None),
    file: UploadFile = File(...),
) -> Dict[str, Any]:
    total_start = time.perf_counter()
    decode_ms = 0.0
    detect_ms = 0.0
    write_ms = 0.0
    report_ms = 0.0
    content_type = (file.content_type or "").lower()
    if content_type and content_type not in {"image/jpeg", "image/jpg"}:
        raise HTTPException(status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, detail="Only JPEG snapshots are supported")
    decode_start = time.perf_counter()
    content = await file.read()
    frame = _decode_uploaded_jpeg(content)
    decode_ms = (time.perf_counter() - decode_start) * 1000
    if _looks_like_thermal_frame(frame):
        logger.info(
            "msdk visible snapshot timing event_id=%s task_id=%s status=422 decode_ms=%.1f detect_ms=%.1f "
            "write_ms=%.1f report_ms=%.1f total_ms=%.1f",
            event_id,
            task_id,
            decode_ms,
            detect_ms,
            write_ms,
            report_ms,
            (time.perf_counter() - total_start) * 1000,
        )
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Snapshot looks like a thermal frame")

    settings = Settings()
    detector = _get_cached_visible_detector(settings)
    packet = FramePacket(
        source_ts=source_ts,
        channel="visible",
        frame=frame,
        width=int(frame.shape[1]),
        height=int(frame.shape[0]),
    )
    detect_start = time.perf_counter()
    visible_score = float(detector.detect(packet))
    detect_ms = (time.perf_counter() - detect_start) * 1000
    visible_boxes = getattr(detector, "last_boxes", None)
    writer = SnapshotWriter(
        snapshot_dir=settings.snapshot_dir,
        public_base_url=settings.snapshot_public_base_url,
    )
    visible_snapshot_id = f"{event_id}-visible"
    write_start = time.perf_counter()
    _, visible_image_url = writer.write_pair(visible_snapshot_id, frame, visible_boxes)
    write_ms = (time.perf_counter() - write_start) * 1000
    if not visible_image_url:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="Snapshot write failed")

    event = DualStreamEvent(
        source_ts=source_ts,
        visible_score=visible_score,
        thermal_score=0.0,
        fusion_score=visible_score,
        risk_level="HIGH" if visible_score >= 0.7 else "MEDIUM" if visible_score >= 0.4 else "LOW",
        analysis_channel="visible",
        visible_image_url=visible_image_url,
        thermal_image_url=thermal_image_url,
        thermal_source_event_id=thermal_source_event_id,
    )
    report_start = time.perf_counter()
    try:
        registry.record_detection_event(
            task_id,
            event,
        )
    except KeyError as exc:
        backend_client = _build_backend_client(settings)
        if backend_client is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Task not found") from exc
        backend_client.report_event(
            task_id,
            {
                "drone_sn": drone_sn,
                "droneSn": drone_sn,
                "source_ts": source_ts,
                "sourceTs": source_ts,
                "visible_score": visible_score,
                "visibleScore": visible_score,
                "thermal_score": 0.0,
                "thermalScore": 0.0,
                "fusion_score": visible_score,
                "fusionScore": visible_score,
                "risk_level": event.risk_level,
                "riskLevel": event.risk_level,
                "analysis_channel": "visible",
                "analysisChannel": "visible",
                "visible_image_url": visible_image_url,
                "visibleImageUrl": visible_image_url,
                "thermal_image_url": thermal_image_url,
                "thermalImageUrl": thermal_image_url,
                "thermal_source_event_id": thermal_source_event_id,
                "thermalSourceEventId": thermal_source_event_id,
            },
        )
    finally:
        report_ms = (time.perf_counter() - report_start) * 1000
    logger.info(
        "msdk visible snapshot timing event_id=%s task_id=%s status=201 decode_ms=%.1f detect_ms=%.1f "
        "write_ms=%.1f report_ms=%.1f total_ms=%.1f",
        event_id,
        task_id,
        decode_ms,
        detect_ms,
        write_ms,
        report_ms,
        (time.perf_counter() - total_start) * 1000,
    )
    return {
        "event_id": event_id,
        "task_id": task_id,
        "drone_sn": drone_sn,
        "source_ts": source_ts,
        "visible_score": visible_score,
        "visible_image_url": visible_image_url,
    }


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
        thermal_detect_roi=annotation.thermal_detect_roi,
        thermal_measurements=annotation.thermal_measurements,
    )
    if not url:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Snapshot not found")
    return {"url": url}
