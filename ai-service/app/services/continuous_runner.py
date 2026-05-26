import logging
import time
from typing import Callable, Optional, TYPE_CHECKING

from app.fusion.service import DualStreamFusionService
from app.inference.thermal.analyzer import ThermalAnalyzer
from app.inference.visible.detector import VisibleDetector
from app.models.event import EventRecord, ThermalMeasureRoi
from app.models.frame import FramePacket
from app.video.source import VideoSource

if TYPE_CHECKING:
    from app.services.task_registry import TaskRegistry


logger = logging.getLogger(__name__)


class ContinuousTaskRunner:
    """Frame-driven runner.

    Consumes visible + thermal VideoSources and emits fused detection events
    through the registry. The loop is cooperative: `tick()` does one frame
    iteration, `run()` drives the loop until `stop_predicate()` returns True
    or `max_consecutive_read_failures` is reached. Both source slots are
    optional so single-channel degraded operation works (e.g. thermal
    `degraded` from `RealMsdkStreamProvider`).
    """

    def __init__(
        self,
        registry: "TaskRegistry",
        visible_detector: VisibleDetector,
        thermal_analyzer: ThermalAnalyzer,
        fusion_service: DualStreamFusionService,
        sleep: Callable[[float], None] = time.sleep,
        poll_interval_s: float = 0.5,
        max_consecutive_read_failures: int = 5,
        max_reconnect_attempts: int = 12,
        reconnect_backoff_s: Optional[float] = None,
    ) -> None:
        self._registry = registry
        self._visible_detector = visible_detector
        self._thermal_analyzer = thermal_analyzer
        self._fusion_service = fusion_service
        self._sleep = sleep
        self._poll_interval_s = float(poll_interval_s)
        self._max_consecutive_read_failures = int(max_consecutive_read_failures)
        self._max_reconnect_attempts = int(max_reconnect_attempts)
        self._reconnect_backoff_s = (
            float(reconnect_backoff_s)
            if reconnect_backoff_s is not None
            else self._poll_interval_s
        )

    def tick(
        self,
        task_id: str,
        visible_source: Optional[VideoSource],
        thermal_source: Optional[VideoSource],
    ) -> Optional[EventRecord]:
        visible_packet = self._read_safely(visible_source)
        thermal_packet = self._read_safely(thermal_source)
        visible_packet, thermal_packet = _route_mislabeled_thermal_packet(
            visible_packet,
            thermal_packet,
        )
        if visible_packet is None and thermal_packet is None:
            return None
        visible_score = (
            self._visible_detector.detect(visible_packet) if visible_packet is not None else 0.0
        )
        # 仅在 detector 暴露了 last_boxes 时（如 YoloVisibleDetector）才有框信息，
        # ColorFire / Stub detector 没有这个属性 — 直接 fallback 到 None。
        visible_boxes = getattr(self._visible_detector, "last_boxes", None)
        thermal_score = (
            self._thermal_analyzer.analyze(thermal_packet) if thermal_packet is not None else 0.0
        )
        source_ts = (
            visible_packet.source_ts
            if visible_packet is not None
            else thermal_packet.source_ts
        )
        analysis_channel = _resolve_analysis_channel(visible_packet, thermal_packet)
        event = self._fusion_service.combine(
            visible_score=visible_score,
            thermal_score=thermal_score,
            source_ts=source_ts,
            analysis_channel=analysis_channel,
        )
        thermal_measure_roi = _validated_measure_roi(
            getattr(self._thermal_analyzer, "last_measure_roi", None)
        )
        if thermal_packet is not None and thermal_measure_roi is not None:
            event = event.model_copy(update={"thermal_measure_roi": thermal_measure_roi})
        return self._registry.record_detection_event(
            task_id,
            event,
            visible_frame=visible_packet.frame if visible_packet is not None else None,
            visible_boxes=visible_boxes,
            thermal_frame=thermal_packet.frame if thermal_packet is not None else None,
        )

    def run(
        self,
        task_id: str,
        visible_source: Optional[VideoSource],
        thermal_source: Optional[VideoSource],
        stop_predicate: Callable[[], bool],
    ) -> None:
        opened_sources = []
        try:
            for source in (visible_source, thermal_source):
                if source is None:
                    continue
                try:
                    source.open()
                except Exception:
                    logger.exception(
                        "task=%s failed to open %s source url=%s",
                        task_id,
                        getattr(source, "channel", "?"),
                        getattr(source, "url", "?"),
                    )
                    self._mark_task_failed(task_id, "video-source-open-failed")
                    return
                opened_sources.append(source)
            consecutive_read_failures = 0
            reconnect_attempts = 0
            while not stop_predicate():
                try:
                    produced = self.tick(task_id, visible_source, thermal_source)
                except Exception:
                    logger.exception("task=%s tick failed unexpectedly", task_id)
                    self._mark_task_failed(task_id, "continuous-runner-tick-failed")
                    return
                if produced is None:
                    consecutive_read_failures += 1
                    if consecutive_read_failures >= self._max_consecutive_read_failures:
                        if reconnect_attempts < self._max_reconnect_attempts:
                            reconnect_attempts += 1
                            logger.warning(
                                "task=%s reopening stream sources after %d consecutive read failures attempt=%d/%d",
                                task_id,
                                consecutive_read_failures,
                                reconnect_attempts,
                                self._max_reconnect_attempts,
                            )
                            self._reopen_sources(task_id, opened_sources)
                            consecutive_read_failures = 0
                            if stop_predicate():
                                break
                            self._sleep(self._reconnect_backoff_s)
                            continue
                        logger.warning(
                            "task=%s exiting after %d consecutive read failures and %d reconnect attempts (stream stale?)",
                            task_id,
                            consecutive_read_failures,
                            reconnect_attempts,
                        )
                        self._mark_task_failed(task_id, "stream-read-failed")
                        break
                else:
                    consecutive_read_failures = 0
                    reconnect_attempts = 0
                if stop_predicate():
                    break
                self._sleep(self._poll_interval_s)
        finally:
            for source in opened_sources:
                try:
                    source.close()
                except Exception:
                    pass

    def _read_safely(self, source: Optional[VideoSource]) -> Optional[FramePacket]:
        if source is None:
            return None
        try:
            return source.read()
        except Exception:
            return None

    def _reopen_sources(self, task_id: str, sources: list[VideoSource]) -> None:
        for source in sources:
            try:
                source.close()
            except Exception:
                pass
            try:
                source.open()
            except Exception:
                logger.warning(
                    "task=%s source reopen failed channel=%s url=%s",
                    task_id,
                    getattr(source, "channel", "?"),
                    getattr(source, "url", "?"),
                    exc_info=True,
                )

    def _mark_task_failed(self, task_id: str, reason: str) -> None:
        marker = getattr(self._registry, "mark_failed", None)
        if marker is None:
            return
        try:
            marker(task_id, reason)
        except Exception:
            logger.exception("task=%s failed to mark task failed reason=%s", task_id, reason)


def _resolve_analysis_channel(
    visible_packet: Optional[FramePacket],
    thermal_packet: Optional[FramePacket],
) -> str:
    if visible_packet is not None and thermal_packet is not None:
        return "dual"
    if thermal_packet is not None:
        return "thermal"
    return "visible"


def _route_mislabeled_thermal_packet(
    visible_packet: Optional[FramePacket],
    thermal_packet: Optional[FramePacket],
) -> tuple[Optional[FramePacket], Optional[FramePacket]]:
    if visible_packet is None or thermal_packet is not None:
        return visible_packet, thermal_packet
    try:
        from app.services.task_registry import _looks_like_thermal_frame

        looks_thermal = _looks_like_thermal_frame(visible_packet.frame)
    except Exception:
        looks_thermal = False
    if not looks_thermal:
        return visible_packet, thermal_packet
    logger.info(
        "visible source yielded thermal-looking frame ts=%s; routing to thermal analyzer",
        visible_packet.source_ts,
    )
    return None, visible_packet.model_copy(update={"channel": "thermal"})


def _validated_measure_roi(value: object) -> Optional[ThermalMeasureRoi]:
    if value is None:
        return None
    try:
        return ThermalMeasureRoi.model_validate(value)
    except Exception:
        return None
