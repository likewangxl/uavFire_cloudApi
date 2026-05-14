from typing import Dict, List

from fastapi import APIRouter, HTTPException, status

from app.models.event import DualStreamEvent, EventRecord
from app.models.task import TaskCreateRequest, TaskRecord
from app.services.task_registry import registry


router = APIRouter()


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
