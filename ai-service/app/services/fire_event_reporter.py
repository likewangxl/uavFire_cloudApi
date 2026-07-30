"""Score-and-time-window FireEvent reporter.

Translates per-frame DualStreamEvent into discrete FireEvent POSTs to backend:
- POST whenever score >= _REPORT_SCORE_FLOOR（纯可见光模式：可见光通道事件直接建火情事件）。
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

# 可见光直报的噪声线：红外 0.01 是为"弱分也触发测温"特意放低的。
# 与 box_display_floor(0.25) 对齐——低于它的框不会画到标注图上，
# 若在此之下就上报，事件快照会是无框图；对齐后"有事件必有框"。
_VISIBLE_REPORT_SCORE_FLOOR = 0.25

# 红外路径：同 task 两次 POST 之间的最小间隔（秒），避免持续火源刷屏。
_REPORT_DEBOUNCE_S = 60.0

# 可见光路径改"空间去抖"：这里只留一个短窗防 POST 刷屏，
# "同一处火不重复建事件"交给后端空间合并裁决（同位置合并且同级不重复通知，
# 新位置的火 10s 内就能出独立事件——多火点巡飞不再被 60s 时间窗压住）。
_VISIBLE_REPORT_DEBOUNCE_S = 10.0

# 可见光连续帧确认：单帧噪声误报（黑暗树丛 0.78 实测踩过）不建事件，
# 需在窗口内连续两帧达线、且框中心位置相近（真火持续稳定，噪声框随机闪现）。
_VISIBLE_CONFIRM_WINDOW_S = 6.0
_VISIBLE_CONFIRM_MAX_CENTER_SHIFT = 0.3


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
        # task_id -> (monotonic_ts, 归一化框中心 or None)：可见光连续帧确认的上一帧记录
        self._pending_visible: Dict[str, tuple] = {}

    def maybe_report(
        self,
        task: TaskRecord,
        event: DualStreamEvent,
        visible_frame: Optional[Any] = None,
        visible_boxes: Optional[list] = None,
        thermal_frame: Optional[Any] = None,
        thermal_boxes: Optional[list] = None,
    ) -> bool:
        risk = (event.risk_level or "").upper()
        score = max(float(event.visible_score), float(event.thermal_score))
        is_thermal = (event.analysis_channel or "").lower() == "thermal"
        # 1) 低于上报 floor 的帧丢弃。红外路径跌破线视作火没了，reset 去抖窗让下次抬升立刻 POST；
        #    可见光 0.25 线火苗闪烁就会跌破，只清连续帧记录、不清去抖窗（否则闪烁把 60s 窗打穿变成刷屏）。
        score_floor = _REPORT_SCORE_FLOOR if is_thermal else _VISIBLE_REPORT_SCORE_FLOOR
        if score < score_floor:
            if is_thermal:
                self._last_posted_ts.pop(task.task_id, None)
            else:
                self._pending_visible.pop(task.task_id, None)
            return False
        # 2) 时间窗去重：同 task 在 DEBOUNCE 秒内最多 POST 一次
        now_ts = time.monotonic()
        last_ts = self._last_posted_ts.get(task.task_id)
        debounce_s = _REPORT_DEBOUNCE_S if is_thermal else _VISIBLE_REPORT_DEBOUNCE_S
        if last_ts is not None and (now_ts - last_ts) < debounce_s:
            return False
        # 3) 可见光连续帧确认：首帧只记录不上报，窗口内第二帧且框位置相近才放行
        if not is_thermal:
            center = _top_box_center(visible_boxes, visible_frame)
            pending = self._pending_visible.get(task.task_id)
            self._pending_visible[task.task_id] = (now_ts, center)
            if (
                pending is None
                or (now_ts - pending[0]) > _VISIBLE_CONFIRM_WINDOW_S
                or not _centers_close(pending[1], center)
            ):
                return False
        event_id = f"{task.task_id}-{event.source_ts}"
        # 按分析通道路由图片字段：visible -> visible_image_url, thermal -> thermal_image_url
        # 这样前端列表 / 驾驶舱 notification 能拿到正确语义的图。
        snapshot_frame = thermal_frame if is_thermal else visible_frame
        # 红外走 YoloThermalAnalyzer 时有像素框；HotSpot/Stub 场景 thermal_boxes 为 None，annotated 图等同 raw。
        snapshot_boxes = thermal_boxes if is_thermal else visible_boxes
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


def _top_box_center(boxes: Optional[list], frame: Optional[Any]) -> Optional[tuple]:
    """最高分框的归一化中心；无框或无帧尺寸时返回 None（位置校验自动放行）。"""
    if not boxes:
        return None
    shape = getattr(frame, "shape", None)
    if not shape or len(shape) < 2 or int(shape[0]) <= 0 or int(shape[1]) <= 0:
        return None
    try:
        top = max(boxes, key=lambda b: float(b.get("conf", 0.0)))
        height, width = int(shape[0]), int(shape[1])
        return (
            (float(top["x1"]) + float(top["x2"])) / 2.0 / width,
            (float(top["y1"]) + float(top["y2"])) / 2.0 / height,
        )
    except (KeyError, TypeError, ValueError, ZeroDivisionError):
        return None


def _centers_close(previous: Optional[tuple], current: Optional[tuple]) -> bool:
    # 任一帧没有框中心（ColorFire 探测器、无帧尺寸等）就只做"连续两帧达线"校验
    if previous is None or current is None:
        return True
    dx = float(previous[0]) - float(current[0])
    dy = float(previous[1]) - float(current[1])
    return (dx * dx + dy * dy) ** 0.5 <= _VISIBLE_CONFIRM_MAX_CENTER_SHIFT


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
    if (event.analysis_channel or "").lower() == "visible":
        payload["geo_method"] = "LASER_RANGEFINDER"
        payload["geoMethod"] = "LASER_RANGEFINDER"
        if event.visible_roi is not None:
            visible_roi = event.visible_roi.model_dump()
            payload["visible_roi"] = visible_roi
            payload["visibleRoi"] = visible_roi
            payload["geo_quality"] = "LASER_LOCATING"
            payload["geoQuality"] = "LASER_LOCATING"
        else:
            payload["geo_quality"] = "LASER_FAILED"
            payload["geoQuality"] = "LASER_FAILED"
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
