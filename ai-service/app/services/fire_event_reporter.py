"""Risk-state-machine FireEvent reporter.

Translates per-frame DualStreamEvent into discrete FireEvent POSTs to backend:
- POST when risk level upgrades (LOW -> MEDIUM, LOW -> HIGH, MEDIUM -> HIGH).
- Same-or-downgrade levels do not POST.
- Falling back to LOW resets the per-task state so the next upgrade fires again.
- POST failures log ERROR but never retry; the next upgrade will try again.
- 升级触发时若注入了 snapshot_writer，则把当前 visible frame 写出 raw + annotated JPG，
  并把 annotated 的公网 URL 放入 payload.visible_image_url。
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any, Dict, Optional, Protocol

from app.models.event import DualStreamEvent
from app.models.task import TaskRecord


logger = logging.getLogger(__name__)


_RISK_RANK = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}


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
        self._last_posted_risk: Dict[str, str] = {}

    def maybe_report(
        self,
        task: TaskRecord,
        event: DualStreamEvent,
        visible_frame: Optional[Any] = None,
        visible_boxes: Optional[list] = None,
        thermal_frame: Optional[Any] = None,
    ) -> bool:
        risk = (event.risk_level or "").upper()
        last = self._last_posted_risk.get(task.task_id)
        if risk == "LOW":
            if last is not None:
                self._last_posted_risk.pop(task.task_id, None)
            return False
        if last is not None and _RISK_RANK.get(risk, 0) <= _RISK_RANK.get(last, 0):
            return False
        event_id = f"{task.task_id}-{event.source_ts}"
        # 按分析通道路由图片字段：visible -> visible_image_url, thermal -> thermal_image_url
        # 这样前端列表 / 驾驶舱 notification 能拿到正确语义的图。
        is_thermal = (event.analysis_channel or "").lower() == "thermal"
        snapshot_frame = thermal_frame if is_thermal else visible_frame
        # 热成像通道没有 YOLO 框（HotSpotAnalyzer 不暴露 box），boxes=None 时 annotated 图等同 raw。
        snapshot_boxes = None if is_thermal else visible_boxes
        snapshot_url: Optional[str] = None
        if self._snapshot_writer is not None and snapshot_frame is not None:
            try:
                _, snapshot_url = self._snapshot_writer.write_pair(
                    event_id, snapshot_frame, snapshot_boxes
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
                payload.get("event_id"),
                risk,
            )
            return False
        self._last_posted_risk[task.task_id] = risk
        logger.info(
            "fire-event POSTed task=%s eventId=%s risk=%s confidence=%.3f channel=%s image=%s",
            task.task_id,
            payload["event_id"],
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
    payload: Dict[str, Any] = {
        "event_id": f"{task.task_id}-{event.source_ts}",
        "source": "M4T",
        "device_sn": task.drone_sn,
        "confidence": round(confidence, 3),
        "fire_level": event.risk_level,
        "timestamp": _epoch_ms_to_iso8601(event.source_ts),
    }
    if visible_image_url:
        payload["visible_image_url"] = visible_image_url
    if thermal_image_url:
        payload["thermal_image_url"] = thermal_image_url
    return payload


def _epoch_ms_to_iso8601(ms: int) -> str:
    return (
        datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )
