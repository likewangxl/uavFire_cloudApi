"""Risk-state-machine FireEvent reporter.

Translates per-frame DualStreamEvent into discrete FireEvent POSTs to backend:
- POST when risk level upgrades (LOW -> MEDIUM, LOW -> HIGH, MEDIUM -> HIGH).
- Same-or-downgrade levels do not POST.
- Falling back to LOW resets the per-task state so the next upgrade fires again.
- POST failures log ERROR but never retry; the next upgrade will try again.
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
    def __init__(self, backend_client: SupportsFireEventReporting) -> None:
        self._backend_client = backend_client
        self._last_posted_risk: Dict[str, str] = {}

    def maybe_report(self, task: TaskRecord, event: DualStreamEvent) -> bool:
        risk = (event.risk_level or "").upper()
        last = self._last_posted_risk.get(task.task_id)
        if risk == "LOW":
            if last is not None:
                self._last_posted_risk.pop(task.task_id, None)
            return False
        if last is not None and _RISK_RANK.get(risk, 0) <= _RISK_RANK.get(last, 0):
            return False
        payload = build_fire_event_payload(task, event)
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
        self._last_posted_risk[task.task_id] = risk
        logger.info(
            "fire-event POSTed task=%s eventId=%s risk=%s confidence=%.3f",
            task.task_id,
            payload["eventId"],
            risk,
            payload["confidence"],
        )
        return True


def build_fire_event_payload(task: TaskRecord, event: DualStreamEvent) -> Dict[str, Any]:
    confidence = max(float(event.visible_score), float(event.thermal_score))
    return {
        "eventId": f"{task.task_id}-{event.source_ts}",
        "source": "M4T",
        "deviceSn": task.drone_sn,
        "confidence": round(confidence, 3),
        "fireLevel": event.risk_level,
        "timestamp": _epoch_ms_to_iso8601(event.source_ts),
    }


def _epoch_ms_to_iso8601(ms: int) -> str:
    return (
        datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )
