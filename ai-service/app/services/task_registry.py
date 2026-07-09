import logging
from pathlib import Path
from threading import Lock
from typing import Any, Callable, Dict, List, Optional, Protocol, Tuple, TYPE_CHECKING

from app.clients.backend_client import BackendClient
from app.config.settings import Settings
from app.models.event import DualStreamEvent, EventRecord, ThermalMeasureRoi
from app.models.task import DualStreamTaskStatus, TaskCreateRequest, TaskRecord
from app.video.source import VideoSource


logger = logging.getLogger(__name__)
_visible_detector_cache: Dict[Tuple[Any, ...], Any] = {}
_visible_detector_cache_lock = Lock()


class SupportsBackendEventReporting(Protocol):
    def report_event(self, task_id: str, payload: Dict[str, object]) -> None:
        ...


SourceFactory = Callable[[TaskRecord], Tuple[Optional[VideoSource], Optional[VideoSource]]]


class TaskRegistry:
    def __init__(
        self,
        backend_client: Optional[SupportsBackendEventReporting] = None,
        fire_event_reporter: Optional["FireEventReporter"] = None,
        detection_snapshot_writer: Optional[Any] = None,
    ) -> None:
        self._tasks: Dict[str, TaskRecord] = {}
        self._events: Dict[str, List[EventRecord]] = {}
        self._backend_client = backend_client
        self._fire_event_reporter = fire_event_reporter
        self._detection_snapshot_writer = detection_snapshot_writer
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

    def mark_failed(self, task_id: str, reason: str) -> TaskRecord:
        task = self.get(task_id)
        task.status = DualStreamTaskStatus.FAILED
        self._events[task_id].append(
            EventRecord(
                task_id=task_id,
                event_type="failed",
                status=task.status,
            )
        )
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

    def record_detection_event(
        self,
        task_id: str,
        event: DualStreamEvent,
        *,
        visible_frame: Optional[Any] = None,
        visible_boxes: Optional[list] = None,
        thermal_frame: Optional[Any] = None,
    ) -> EventRecord:
        task = self.get(task_id)
        visible_image_url, thermal_image_url = self._write_detection_snapshot(
            task_id,
            event,
            visible_frame=visible_frame,
            visible_boxes=visible_boxes,
            thermal_frame=thermal_frame,
        )
        visible_image_url = visible_image_url or event.visible_image_url
        thermal_image_url = thermal_image_url or event.thermal_image_url
        record = EventRecord(
            task_id=task_id,
            event_type="detection",
            status=task.status,
            drone_sn=task.drone_sn,
            source_ts=event.source_ts,
            fusion_score=event.fusion_score,
            risk_level=event.risk_level,
            analysis_channel=event.analysis_channel,
            visible_image_url=visible_image_url,
            thermal_image_url=thermal_image_url,
            thermal_source_event_id=event.thermal_source_event_id,
            thermal_temperature=event.thermal_temperature,
            thermal_measure_roi=event.thermal_measure_roi,
        )
        self._events[task_id].append(record)
        logger.info(
            "detection task=%s ts=%s ch=%s visible=%.3f thermal=%.3f fusion=%.3f risk=%s",
            task_id,
            event.source_ts,
            event.analysis_channel,
            event.visible_score,
            event.thermal_score,
            event.fusion_score,
            event.risk_level,
        )
        if self._backend_client is not None:
            try:
                self._backend_client.report_event(
                    task_id,
                    {
                        "drone_sn": task.drone_sn,
                        "source_ts": event.source_ts,
                        "visible_score": event.visible_score,
                        "thermal_score": event.thermal_score,
                        "fusion_score": event.fusion_score,
                        "risk_level": event.risk_level,
                        "analysis_channel": event.analysis_channel,
                        "visible_image_url": visible_image_url,
                        "visibleImageUrl": visible_image_url,
                        "thermal_image_url": thermal_image_url,
                        "thermalImageUrl": thermal_image_url,
                        "thermal_source_event_id": event.thermal_source_event_id,
                        "thermalSourceEventId": event.thermal_source_event_id,
                        "thermal_temperature": event.thermal_temperature,
                        "thermalTemperature": event.thermal_temperature,
                        "thermal_measure_roi": _roi_payload(event.thermal_measure_roi),
                        "thermalMeasureRoi": _roi_payload(event.thermal_measure_roi),
                    },
                )
            except Exception:
                logger.exception(
                    "backend event report failed task=%s ts=%s; keeping local runner alive",
                    task_id,
                    event.source_ts,
                )
        if self._fire_event_reporter is not None:
            try:
                self._fire_event_reporter.maybe_report(
                    task,
                    event,
                    visible_frame=visible_frame,
                    visible_boxes=visible_boxes,
                    thermal_frame=thermal_frame,
                )
            except Exception:
                logger.exception(
                    "fire-event reporter raised unexpectedly task=%s", task_id
                )
        return record

    def _write_detection_snapshot(
        self,
        task_id: str,
        event: DualStreamEvent,
        *,
        visible_frame: Optional[Any],
        visible_boxes: Optional[list],
        thermal_frame: Optional[Any],
    ) -> Tuple[Optional[str], Optional[str]]:
        if self._detection_snapshot_writer is None:
            return None, None
        is_thermal = (event.analysis_channel or "").lower() == "thermal"
        frame = thermal_frame if is_thermal else visible_frame
        if frame is None:
            return None, None
        if not is_thermal and _looks_like_thermal_frame(frame):
            logger.info(
                "skip visible snapshot for thermal-looking frame task=%s ts=%s",
                task_id,
                event.source_ts,
            )
            return None, None
        if is_thermal and not _looks_like_thermal_frame(frame):
            logger.info(
                "skip thermal snapshot for visible-looking frame task=%s ts=%s",
                task_id,
                event.source_ts,
            )
            return None, None
        boxes = None if is_thermal else visible_boxes
        event_id = f"{task_id}-{event.source_ts}"
        try:
            _, snapshot_url = self._detection_snapshot_writer.write_pair(
                event_id,
                frame,
                boxes,
                thermal_temperature=event.thermal_temperature if is_thermal else None,
                thermal_measure_roi=event.thermal_measure_roi if is_thermal else None,
            )
        except Exception:
            logger.exception("detection snapshot write failed event=%s", event_id)
            return None, None
        if is_thermal:
            return None, snapshot_url
        return snapshot_url, None

    def list_events(self, task_id: str) -> List[EventRecord]:
        self.get(task_id)
        return list(self._events.get(task_id, []))


def _looks_like_thermal_frame(frame: Any) -> bool:
    return bool(_thermal_frame_stats(frame)["looks_thermal"])


def _thermal_frame_stats(frame: Any) -> Dict[str, Any]:
    try:
        import numpy as np

        arr = np.asarray(frame)
    except Exception:
        return {
            "looks_thermal": False,
            "shape": "-",
            "grayscale_ratio": 0.0,
            "intensity_range": 0.0,
            "intensity_std": 0.0,
            "obvious_visible": True,
        }
    if arr.ndim != 3 or arr.shape[2] < 3 or arr.size == 0:
        return {
            "looks_thermal": False,
            "shape": str(getattr(arr, "shape", "-")),
            "grayscale_ratio": 0.0,
            "intensity_range": 0.0,
            "intensity_std": 0.0,
            "obvious_visible": True,
        }

    sample = arr[..., :3].astype("float32", copy=False)
    channel_delta = sample.max(axis=2) - sample.min(axis=2)
    grayscale_ratio = float((channel_delta < 4).mean())
    intensity = sample.mean(axis=2)
    intensity_range = float(np.percentile(intensity, 95) - np.percentile(intensity, 5))
    intensity_std = float(intensity.std())
    looks_thermal = (
        grayscale_ratio >= 0.95
        and intensity_range >= 35
        and intensity_std >= 12
    )
    obvious_visible = grayscale_ratio < 0.50
    return {
        "looks_thermal": looks_thermal,
        "shape": str(arr.shape),
        "grayscale_ratio": grayscale_ratio,
        "intensity_range": intensity_range,
        "intensity_std": intensity_std,
        "obvious_visible": obvious_visible,
    }


def _roi_payload(roi: Optional[ThermalMeasureRoi]) -> Optional[dict]:
    return roi.model_dump() if roi is not None else None


def _build_visible_detector(settings: Settings):
    if settings.visible_yolo_model_path:
        from app.inference.visible.detector import YoloVisibleDetector

        det = YoloVisibleDetector(
            model_path=settings.visible_yolo_model_path,
            target_class_names=[
                name.strip() for name in settings.visible_target_classes.split(",") if name.strip()
            ],
            confidence_floor=settings.visible_confidence_floor,
            imgsz=settings.visible_yolo_imgsz,
        )
        logger.info(
            "visible_detector=YoloVisibleDetector model=%s floor=%s imgsz=%s classes=%s",
            settings.visible_yolo_model_path,
            settings.visible_confidence_floor,
            settings.visible_yolo_imgsz,
            settings.visible_target_classes,
        )
        return det
    if settings.visible_detector_mode.lower() == "stub":
        from app.inference.visible.detector import StubVisibleDetector

        logger.info("visible_detector=StubVisibleDetector")
        return StubVisibleDetector()
    from app.inference.visible.detector import ColorFireVisibleDetector

    logger.info("visible_detector=ColorFireVisibleDetector saturation_ratio=%s", settings.visible_fire_saturation_ratio)
    return ColorFireVisibleDetector(
        saturation_ratio=settings.visible_fire_saturation_ratio,
    )


def _visible_detector_cache_key(settings: Settings) -> Tuple[Any, ...]:
    target_classes = tuple(
        name.strip().lower()
        for name in settings.visible_target_classes.split(",")
        if name.strip()
    )
    return (
        settings.visible_detector_mode.lower(),
        settings.visible_yolo_model_path,
        int(settings.visible_yolo_imgsz),
        float(settings.visible_confidence_floor),
        target_classes,
        float(settings.visible_fire_saturation_ratio),
    )


def _get_cached_visible_detector(settings: Settings):
    key = _visible_detector_cache_key(settings)
    with _visible_detector_cache_lock:
        detector = _visible_detector_cache.get(key)
        if detector is None:
            detector = _build_visible_detector(settings)
            _visible_detector_cache.clear()
            _visible_detector_cache[key] = detector
        return detector


def _build_thermal_analyzer(settings: Settings):
    if settings.use_continuous_runner:
        from app.inference.thermal.analyzer import (
            HotSpotThermalAnalyzer,
            MaxThermalAnalyzer,
            YoloThermalAnalyzer,
        )

        brightness = HotSpotThermalAnalyzer(
            intensity_threshold=settings.thermal_intensity_threshold,
            saturation_ratio=settings.thermal_saturation_ratio,
        )
        mode = settings.thermal_detector_mode.lower()
        if mode == "brightness":
            return brightness
        if mode not in {"yolo", "max"}:
            logger.warning("unknown thermal_detector_mode=%s; falling back to brightness", settings.thermal_detector_mode)
            return brightness
        model_path = settings.thermal_yolo_model_path
        if not model_path:
            logger.warning(
                "thermal_detector_mode=%s requested but model path is empty; falling back to brightness",
                mode,
            )
            return brightness
        if not Path(model_path).is_file():
            logger.warning(
                "thermal_detector_mode=%s requested but model path does not exist: %s; falling back to brightness",
                mode,
                model_path,
            )
            return brightness
        yolo = YoloThermalAnalyzer(
            model_path=model_path,
            imgsz=settings.thermal_yolo_imgsz,
            conf_threshold=settings.thermal_yolo_conf_threshold,
        )
        if mode == "yolo":
            logger.info(
                "thermal_analyzer=YoloThermalAnalyzer model=%s conf=%s imgsz=%s",
                model_path,
                settings.thermal_yolo_conf_threshold,
                settings.thermal_yolo_imgsz,
            )
            return yolo
        logger.info(
            "thermal_analyzer=MaxThermalAnalyzer model=%s conf=%s imgsz=%s",
            model_path,
            settings.thermal_yolo_conf_threshold,
            settings.thermal_yolo_imgsz,
        )
        return MaxThermalAnalyzer([brightness, yolo])
    from app.inference.thermal.analyzer import StubThermalAnalyzer

    return StubThermalAnalyzer()


def _build_backend_client(settings: Settings) -> Optional[BackendClient]:
    if not settings.backend_base_url:
        return None
    return BackendClient(
        base_url=settings.backend_base_url,
        access_token=settings.backend_access_token,
        username=settings.backend_username,
        password=settings.backend_password,
        login_flag=settings.backend_login_flag,
    )


def build_registry() -> TaskRegistry:
    from app.fusion.service import DualStreamFusionService
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
    backend_client = _build_backend_client(settings)
    snapshot_writer = None
    if backend_client is not None:
        from app.services.snapshot_writer import SnapshotWriter

        snapshot_writer = SnapshotWriter(
            snapshot_dir=settings.snapshot_dir,
            public_base_url=settings.snapshot_public_base_url,
        )
    registry = TaskRegistry(
        backend_client=backend_client,
        fire_event_reporter=None,
        detection_snapshot_writer=snapshot_writer,
    )
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
                    visible_detector=_get_cached_visible_detector(settings),
                    thermal_analyzer=_build_thermal_analyzer(settings),
                    fusion_service=fusion,
                ),
            ),
            source_factory=opencv_source_factory_from_task,
        )
    return registry


registry = build_registry()

if TYPE_CHECKING:
    from app.services.continuous_supervisor import ContinuousTaskSupervisor
    from app.services.fire_event_reporter import FireEventReporter
    from app.services.task_runner import TaskRunner
