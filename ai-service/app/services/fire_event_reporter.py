"""Score-and-time-window FireEvent reporter.

Translates per-frame DualStreamEvent into discrete FireEvent POSTs to backend:
- POST only after the event is a thermal-channel confirmation and score >= _REPORT_SCORE_FLOOR.
  risk_level 仅作为业务展示标签（HIGH/MEDIUM/LOW），不再决定是否上报。
- 同一 task 在 _REPORT_DEBOUNCE_S 秒内不重复 POST，避免高频帧刷屏。
- score 跌回 floor 以下视作噪声，并 reset 时间窗，让下次抬升能立刻 POST。
- POST failures log ERROR but never retry; the next window-eligible frame will try again.
- 升级触发时若注入了 snapshot_writer，则把当前 visible frame 写出 raw + annotated JPG，
  并把 annotated 的公网 URL 放入 payload.visible_image_url。
"""

from __future__ import annotations

import logging
import time
from datetime import datetime, timezone
from typing import Any, Dict, Optional, Protocol

from app.models.event import DualStreamEvent
from app.models.task import TaskRecord


logger = logging.getLogger(__name__)


# 低于此分数不上报（噪声）。risk_level 仍按 fusion 业务阈值 0.4/0.7 标签化，
# 这个 floor 只决定"是否值得让后端记录/可能建 mission"。
_REPORT_SCORE_FLOOR = 0.01

# 同 task 两次 POST 之间的最小间隔（秒），用于去重避免持续火源刷屏。
# 低/中/高风险仍都会提示，但同一监测任务最多每分钟生成一条火情事件。
_REPORT_DEBOUNCE_S = 60.0


class SupportsFireEventReporting(Protocol):
    def report_fire_event(self, payload: Dict[str, Any]) -> None: ...


class FireEventReporter:
    def __init__(
        self,
        backend_client: SupportsFireEventReporting,
        snapshot_writer: Optional[Any] = None,
    ) -> None:
        self._backend_client = backend_client
        self._snapshot_writer = snapshot_writer
        self._last_posted_ts: Dict[str, float] = {}

    def maybe_report(
        self,
        task: TaskRecord,
        event: DualStreamEvent,
        visible_frame: Optional[Any] = None,
        visible_boxes: Optional[list] = None,
        thermal_frame: Optional[Any] = None,
    ) -> bool:
        risk = (event.risk_level or "").upper()
        score = max(float(event.visible_score), float(event.thermal_score))
        is_thermal = (event.analysis_channel or "").lower() == "thermal"
        # 1) 可见光只触发后端复核/切红外，不直接创建火情事件。
        if not is_thermal:
            return False
        # 2) 低于上报 floor 视作噪声：reset 时间窗并丢弃
        if score < _REPORT_SCORE_FLOOR:
            self._last_posted_ts.pop(task.task_id, None)
            return False
        # 3) 时间窗去重：同 task 在 DEBOUNCE 秒内最多 POST 一次
        now_ts = time.monotonic()
        last_ts = self._last_posted_ts.get(task.task_id)
        if last_ts is not None and (now_ts - last_ts) < _REPORT_DEBOUNCE_S:
            return False
        event_id = f"{task.task_id}-{event.source_ts}"
        # 按分析通道路由图片字段：visible -> visible_image_url, thermal -> thermal_image_url
        # 这样前端列表 / 驾驶舱 notification 能拿到正确语义的图。
        snapshot_frame = thermal_frame if is_thermal else visible_frame
        # 热成像通道没有 YOLO 框（HotSpotAnalyzer 不暴露 box），boxes=None 时 annotated 图等同 raw。
        snapshot_boxes = None if is_thermal else visible_boxes
        snapshot_url: Optional[str] = None
        if self._snapshot_writer is not None and snapshot_frame is not None:
            should_write_snapshot = True
            if is_thermal:
                from app.services.task_registry import _looks_like_thermal_frame

                should_write_snapshot = _looks_like_thermal_frame(snapshot_frame)
                if not should_write_snapshot:
                    logger.info(
                        "skip thermal fire-event snapshot for visible-looking frame event=%s",
                        event_id,
                    )
            if should_write_snapshot:
                try:
                    _, snapshot_url = self._snapshot_writer.write_pair(
                        event_id,
                        snapshot_frame,
                        snapshot_boxes,
                        thermal_temperature=event.thermal_temperature if is_thermal else None,
                        thermal_measure_roi=event.thermal_measure_roi if is_thermal else None,
                    )
                except Exception:
                    logger.exception("snapshot write failed event=%s", event_id)
        payload = build_fire_event_payload(
            task,
            event,
            visible_image_url=None if is_thermal else snapshot_url,
            thermal_image_url=snapshot_url if is_thermal else None,
        )
        try:
            self._backend_client.report_fire_event(payload)
        except Exception:
            logger.exception(
                "fire-event POST failed task=%s eventId=%s risk=%s",
                task.task_id,
                payload.get("eventId"),
                risk,
            )
            return False
        self._last_posted_ts[task.task_id] = now_ts
        logger.info(
            "fire-event POSTed task=%s eventId=%s risk=%s confidence=%.3f channel=%s image=%s",
            task.task_id,
            payload["eventId"],
            risk,
            payload["confidence"],
            event.analysis_channel or "-",
            snapshot_url or "-",
        )
        return True


def build_fire_event_payload(
    task: TaskRecord,
    event: DualStreamEvent,
    visible_image_url: Optional[str] = None,
    thermal_image_url: Optional[str] = None,
) -> Dict[str, Any]:
    confidence = max(float(event.visible_score), float(event.thermal_score))
    event_id = f"{task.task_id}-{event.source_ts}"
    payload: Dict[str, Any] = {
        "event_id": event_id,
        "eventId": event_id,
        "source": "M4T",
        "device_sn": task.drone_sn,
        "deviceSn": task.drone_sn,
        "confidence": round(confidence, 3),
        "fire_level": event.risk_level,
        "fireLevel": event.risk_level,
        "timestamp": _epoch_ms_to_iso8601(event.source_ts),
    }
    if visible_image_url:
        payload["visible_image_url"] = visible_image_url
        payload["visibleImageUrl"] = visible_image_url
    if thermal_image_url:
        payload["thermal_image_url"] = thermal_image_url
        payload["thermalImageUrl"] = thermal_image_url
    if event.thermal_temperature is not None:
        payload["thermal_temperature"] = event.thermal_temperature
        payload["thermalTemperature"] = event.thermal_temperature
    if event.thermal_measure_roi is not None:
        thermal_measure_roi = event.thermal_measure_roi.model_dump()
        payload["thermal_measure_roi"] = thermal_measure_roi
        payload["thermalMeasureRoi"] = thermal_measure_roi
    if event.geo_snapshot is not None:
        payload["geo_snapshot"] = event.geo_snapshot
        payload["geoSnapshot"] = event.geo_snapshot
    return payload


def _epoch_ms_to_iso8601(ms: int) -> str:
    return (
        datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )
