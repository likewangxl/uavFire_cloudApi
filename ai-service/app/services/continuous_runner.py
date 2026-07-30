import logging
import time
from typing import Callable, Optional, TYPE_CHECKING

from app.fusion.service import DualStreamFusionService
from app.inference.thermal.analyzer import ThermalAnalyzer
from app.inference.visible.detector import VisibleDetector
from app.models.event import EventRecord, NormalizedRoi, ThermalMeasureRoi
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
        stale_retry_interval_s: float = 30.0,
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
        self._stale_retry_interval_s = float(stale_retry_interval_s)

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
            allow_thermal_reroute=thermal_source is not None,
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
        # YoloThermalAnalyzer 暴露归一化检测框，换算成像素框供快照标注；
        # HotSpot / Stub analyzer 没有 last_detections — fallback 到 None。
        thermal_boxes = (
            _thermal_boxes_from_detections(
                getattr(self._thermal_analyzer, "last_detections", None),
                thermal_packet.frame,
            )
            if thermal_packet is not None
            else None
        )
        if thermal_packet is not None and thermal_boxes is None and thermal_score > 0.0:
            thermal_boxes = _hotspot_fallback_boxes(thermal_packet.frame, thermal_score)
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
        visible_roi = _visible_roi_from_boxes(
            visible_boxes,
            visible_packet.frame if visible_packet is not None else None,
        )
        if visible_roi is not None:
            event = event.model_copy(update={"visible_roi": visible_roi})
        thermal_measure_roi = _validated_measure_roi(
            getattr(self._thermal_analyzer, "last_measure_roi", None)
        )
        if thermal_packet is not None and thermal_measure_roi is None and thermal_score > 0.0:
            # YOLO 模式没有 last_measure_roi：用饱和热点当测温区域，供 agent MSDK 真实测温仲裁。
            thermal_measure_roi = _measure_roi_from_hotspot(thermal_packet.frame)
        if thermal_packet is not None and thermal_measure_roi is not None:
            event = event.model_copy(update={"thermal_measure_roi": thermal_measure_roi})
        return self._registry.record_detection_event(
            task_id,
            event,
            visible_frame=visible_packet.frame if visible_packet is not None else None,
            visible_boxes=visible_boxes,
            thermal_frame=thermal_packet.frame if thermal_packet is not None else None,
            thermal_boxes=thermal_boxes,
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
                        # 断流不自杀：快速重连预算用完后转慢速重试，流恢复（agent 重推）即自愈。
                        # 之前"12 次后退出"导致每次断流都要人工重建检测任务。
                        reconnect_attempts += 1
                        fast_retry = reconnect_attempts <= self._max_reconnect_attempts
                        logger.warning(
                            "task=%s reopening stream sources after %d consecutive read failures attempt=%d/%d%s",
                            task_id,
                            consecutive_read_failures,
                            reconnect_attempts,
                            self._max_reconnect_attempts,
                            "" if fast_retry else " (stream stale, slow retry)",
                        )
                        self._reopen_sources(task_id, opened_sources)
                        consecutive_read_failures = 0
                        if stop_predicate():
                            break
                        self._sleep(
                            self._reconnect_backoff_s
                            if fast_retry
                            else self._stale_retry_interval_s
                        )
                        continue
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


def _visible_roi_from_boxes(boxes: object, frame: object) -> Optional[NormalizedRoi]:
    if not boxes or frame is None:
        return None
    shape = getattr(frame, "shape", None)
    if not shape or len(shape) < 2:
        return None
    height, width = int(shape[0]), int(shape[1])
    if height <= 0 or width <= 0:
        return None
    try:
        top = max(boxes, key=lambda item: float(item.get("conf", 0.0)))
        x1 = max(0.0, min(float(width), float(top["x1"])))
        y1 = max(0.0, min(float(height), float(top["y1"])))
        x2 = max(x1, min(float(width), float(top["x2"])))
        y2 = max(y1, min(float(height), float(top["y2"])))
        if x2 <= x1 or y2 <= y1:
            return None
        return NormalizedRoi(
            x=x1 / width,
            y=y1 / height,
            width=(x2 - x1) / width,
            height=(y2 - y1) / height,
        )
    except (KeyError, TypeError, ValueError):
        return None


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
    allow_thermal_reroute: bool = True,
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
    if not allow_thermal_reroute:
        logger.warning(
            "visible-only source yielded thermal-looking frame ts=%s; dropping frame",
            visible_packet.source_ts,
        )
        return None, None
    logger.info(
        "visible source yielded thermal-looking frame ts=%s; routing to thermal analyzer",
        visible_packet.source_ts,
    )
    return None, visible_packet.model_copy(update={"channel": "thermal"})


# 红外 YOLO 在增强白热画面上会退化出近全幅大框（定位无效，只有分数可信），
# 超过该占比的框不画，改用亮度热点兜底定位。
_DEGENERATE_BOX_AREA_RATIO = 0.5
# 白热增强画面里日照地面也偏亮，只认近饱和像素；火点是实心饱和块，
# 取最大连通块即可避开时间戳白字（单字组件很小）与地面碎反光。
_HOTSPOT_SATURATION_THRESHOLD = 240
_HOTSPOT_MIN_AREA_PX = 200
_HOTSPOT_BOX_PAD_PX = 10


def _thermal_boxes_from_detections(detections: object, frame: object) -> Optional[list]:
    if not detections or frame is None:
        return None
    shape = getattr(frame, "shape", None)
    if not shape or len(shape) < 2:
        return None
    height, width = int(shape[0]), int(shape[1])
    boxes = []
    for det in detections:
        try:
            if float(det.w) * float(det.h) > _DEGENERATE_BOX_AREA_RATIO:
                continue
            x1 = max(0.0, (det.cx - det.w / 2.0)) * width
            y1 = max(0.0, (det.cy - det.h / 2.0)) * height
            x2 = min(1.0, (det.cx + det.w / 2.0)) * width
            y2 = min(1.0, (det.cy + det.h / 2.0)) * height
            boxes.append(
                {
                    "x1": int(x1),
                    "y1": int(y1),
                    "x2": int(x2),
                    "y2": int(y2),
                    "conf": float(det.conf),
                    "label": "fire",
                }
            )
        except (AttributeError, TypeError, ValueError):
            continue
    return boxes or None


def _hotspot_fallback_boxes(frame: object, conf: float) -> Optional[list]:
    """YOLO 框全部退化时，用白热画面近饱和的最大连通块（火点）画兜底框。"""
    try:
        import cv2
        import numpy as np

        arr = np.asarray(frame)
        intensity = arr.max(axis=-1) if arr.ndim >= 3 else arr
        mask = (intensity >= _HOTSPOT_SATURATION_THRESHOLD).astype(np.uint8)
        count, _, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
        if count <= 1:
            return None
        best = max(range(1, count), key=lambda i: stats[i, cv2.CC_STAT_AREA])
        x, y, w, h, area = (int(v) for v in stats[best])
        if area < _HOTSPOT_MIN_AREA_PX:
            return None
        frame_h, frame_w = intensity.shape[:2]
        return [
            {
                "x1": max(0, x - _HOTSPOT_BOX_PAD_PX),
                "y1": max(0, y - _HOTSPOT_BOX_PAD_PX),
                "x2": min(frame_w, x + w + _HOTSPOT_BOX_PAD_PX),
                "y2": min(frame_h, y + h + _HOTSPOT_BOX_PAD_PX),
                "conf": float(conf),
                "label": "fire",
            }
        ]
    except Exception:
        return None


def _measure_roi_from_hotspot(frame: object) -> Optional[ThermalMeasureRoi]:
    boxes = _hotspot_fallback_boxes(frame, 1.0)
    if not boxes:
        return None
    box = boxes[0]
    shape = getattr(frame, "shape", None)
    if not shape or len(shape) < 2:
        return None
    height, width = int(shape[0]), int(shape[1])
    if height <= 0 or width <= 0:
        return None
    try:
        return ThermalMeasureRoi(
            x=round(box["x1"] / width, 4),
            y=round(box["y1"] / height, 4),
            width=round((box["x2"] - box["x1"]) / width, 4),
            height=round((box["y2"] - box["y1"]) / height, 4),
        )
    except Exception:
        return None


def _validated_measure_roi(value: object) -> Optional[ThermalMeasureRoi]:
    if value is None:
        return None
    try:
        return ThermalMeasureRoi.model_validate(value)
    except Exception:
        return None
