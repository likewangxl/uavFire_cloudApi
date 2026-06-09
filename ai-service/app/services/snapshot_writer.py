"""Save annotated + raw JPGs for fire-event snapshots.

Each upgrade-triggered FireEvent gets two files in `snapshot_dir`:
- `<event_id>-raw.jpg`        : 原始 BGR 帧
- `<event_id>-annotated.jpg`  : 在原帧上画 YOLO 框 + label/置信度

Public URLs are built from `public_base_url + "/<filename>"`, served by the
FastAPI StaticFiles mount under `/api/v1/snapshots` in app/main.py.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any, Iterable, List, Optional, Tuple

import cv2
import numpy as np

from app.models.event import ThermalMeasureRoi


logger = logging.getLogger(__name__)


class SnapshotWriter:
    def __init__(self, snapshot_dir: str, public_base_url: str) -> None:
        self._dir = Path(snapshot_dir)
        self._dir.mkdir(parents=True, exist_ok=True)
        self._public_base = (public_base_url or "").rstrip("/")

    def write_pair(
        self,
        event_id: str,
        frame_bgr: np.ndarray,
        boxes: Optional[Iterable[dict]] = None,
        thermal_temperature: Optional[float] = None,
        thermal_measure_roi: Optional[Any] = None,
        thermal_detect_roi: Optional[Any] = None,
        thermal_measurements: Optional[Iterable[Any]] = None,
    ) -> Tuple[Optional[str], Optional[str]]:
        """Write raw + annotated JPG. Returns (raw_url, annotated_url) or (None,None) on failure."""
        if frame_bgr is None or getattr(frame_bgr, "size", 0) == 0:
            return None, None
        raw_name = f"{event_id}-raw.jpg"
        annotated_name = f"{event_id}-annotated.jpg"
        try:
            cv2.imwrite(str(self._dir / raw_name), frame_bgr)
        except Exception:
            logger.exception("snapshot raw write failed event=%s", event_id)
            return None, None
        try:
            annotated = self._annotate(
                frame_bgr,
                boxes,
                thermal_temperature=thermal_temperature,
                thermal_measure_roi=thermal_measure_roi,
                thermal_detect_roi=thermal_detect_roi,
                thermal_measurements=thermal_measurements,
            )
            cv2.imwrite(str(self._dir / annotated_name), annotated)
        except Exception:
            logger.exception("snapshot annotated write failed event=%s", event_id)
            annotated_name = raw_name
        return self._url(raw_name), self._url(annotated_name)

    def refresh_thermal_annotation(
        self,
        event_id: str,
        thermal_temperature: float,
        thermal_measure_roi: Optional[Any] = None,
        thermal_detect_roi: Optional[Any] = None,
        thermal_measurements: Optional[Iterable[Any]] = None,
    ) -> Optional[str]:
        raw_name = f"{event_id}-raw.jpg"
        annotated_name = f"{event_id}-annotated.jpg"
        raw_path = self._dir / raw_name
        if not raw_path.exists():
            return None
        frame = cv2.imread(str(raw_path))
        if frame is None or getattr(frame, "size", 0) == 0:
            return None
        annotated = self._annotate(
            frame,
            boxes=None,
            thermal_temperature=thermal_temperature,
            thermal_measure_roi=thermal_measure_roi,
            thermal_detect_roi=thermal_detect_roi,
            thermal_measurements=thermal_measurements,
        )
        try:
            cv2.imwrite(str(self._dir / annotated_name), annotated)
        except Exception:
            logger.exception("snapshot refresh failed event=%s", event_id)
            return None
        return self._url(annotated_name)

    def write_uploaded_thermal_jpeg(self, event_id: str, content: bytes) -> Optional[str]:
        if not content:
            return None
        raw_name = f"{event_id}-raw.jpg"
        annotated_name = f"{event_id}-annotated.jpg"
        try:
            raw_path = self._dir / raw_name
            annotated_path = self._dir / annotated_name
            raw_path.write_bytes(content)
            annotated_path.write_bytes(content)
        except Exception:
            logger.exception("uploaded thermal snapshot write failed event=%s", event_id)
            return None
        return self._url(annotated_name)

    def _annotate(
        self,
        frame: np.ndarray,
        boxes: Optional[Iterable[dict]],
        thermal_temperature: Optional[float] = None,
        thermal_measure_roi: Optional[Any] = None,
        thermal_detect_roi: Optional[Any] = None,
        thermal_measurements: Optional[Iterable[Any]] = None,
    ) -> np.ndarray:
        out = frame.copy()
        self._annotate_temperature(
            out,
            thermal_temperature,
            thermal_measure_roi,
            thermal_detect_roi,
            thermal_measurements=thermal_measurements,
        )
        if not boxes:
            return out
        for b in boxes:
            try:
                x1, y1, x2, y2 = int(b["x1"]), int(b["y1"]), int(b["x2"]), int(b["y2"])
            except (KeyError, TypeError, ValueError):
                continue
            conf = float(b.get("conf", 0.0))
            label = str(b.get("label", "") or "")
            cv2.rectangle(out, (x1, y1), (x2, y2), (0, 0, 255), 2)
            caption = f"{label} {conf:.2f}".strip()
            if caption:
                cv2.putText(
                    out,
                    caption,
                    (x1, max(y1 - 6, 12)),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.6,
                    (0, 0, 255),
                    2,
                )
        return out

    def _annotate_temperature(
        self,
        out: np.ndarray,
        thermal_temperature: Optional[float],
        thermal_measure_roi: Optional[Any],
        thermal_detect_roi: Optional[Any] = None,
        thermal_measurements: Optional[Iterable[Any]] = None,
    ) -> None:
        rendered_measurements = _thermal_measurements(thermal_measurements)
        if thermal_detect_roi is not None:
            detect_x1, detect_y1, detect_x2, detect_y2 = _thermal_measure_region(out, thermal_detect_roi)
            cv2.rectangle(out, (detect_x1, detect_y1), (detect_x2, detect_y2), (255, 0, 255), 1)
        if rendered_measurements:
            for index, (temperature, roi) in enumerate(rendered_measurements):
                is_primary = index == 0 and thermal_measure_roi is not None
                self._draw_temperature_label(
                    out,
                    temperature,
                    roi,
                    color=(0, 255, 255) if is_primary else (0, 200, 255),
                    thickness=2 if is_primary else 1,
                )
            return
        if thermal_temperature is None:
            return
        try:
            value = float(thermal_temperature)
        except (TypeError, ValueError):
            return
        if not np.isfinite(value):
            return
        self._draw_temperature_label(out, value, thermal_measure_roi, color=(0, 255, 255), thickness=2)

    def _draw_temperature_label(
        self,
        out: np.ndarray,
        temperature: float,
        roi: Optional[Any],
        color: Tuple[int, int, int],
        thickness: int,
    ) -> None:
        caption = f"{temperature:.1f}C"
        x1, y1, x2, y2 = _thermal_measure_region(out, roi)
        cv2.rectangle(out, (x1, y1), (x2, y2), color, thickness)
        text_x = min(max(x2 + 6, 6), max(out.shape[1] - 116, 6))
        text_y = min(max((y1 + y2) // 2, 22), max(out.shape[0] - 8, 22))
        cv2.rectangle(out, (text_x - 4, text_y - 20), (text_x + 104, text_y + 6), (0, 0, 0), -1)
        cv2.putText(
            out,
            caption,
            (text_x, text_y),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.75,
            (255, 255, 255),
            2,
        )

    def _url(self, filename: str) -> str:
        return f"{self._public_base}/{filename}" if self._public_base else filename


def boxes_from_yolo_results(
    results: Any,
    target_class_names: Optional[set] = None,
    confidence_floor: float = 0.0,
) -> List[dict]:
    """Flatten ultralytics result objects into [{x1,y1,x2,y2,conf,label}]."""
    out: List[dict] = []
    for result in results or []:
        boxes = getattr(result, "boxes", None)
        if boxes is None:
            continue
        names = getattr(result, "names", {}) or {}
        xyxy = _to_list(getattr(boxes, "xyxy", []))
        cls_seq = _to_list(getattr(boxes, "cls", []))
        conf_seq = _to_list(getattr(boxes, "conf", []))
        for coords, cls_value, conf_value in zip(xyxy, cls_seq, conf_seq):
            conf = float(_unwrap(conf_value))
            if conf < confidence_floor:
                continue
            cls_idx = int(_unwrap(cls_value))
            label = str(names.get(cls_idx, "")).lower()
            if target_class_names is not None and label not in target_class_names:
                continue
            try:
                x1, y1, x2, y2 = (float(c) for c in _to_list(coords)[:4])
            except (TypeError, ValueError):
                continue
            out.append({"x1": x1, "y1": y1, "x2": x2, "y2": y2, "conf": conf, "label": label})
    return _dedupe_overlapping_boxes(out)


def _dedupe_overlapping_boxes(boxes: List[dict]) -> List[dict]:
    kept: List[dict] = []
    for box in sorted(boxes, key=lambda item: float(item.get("conf", 0.0)), reverse=True):
        label = str(box.get("label", ""))
        if any(label == str(existing.get("label", "")) and _box_iou(box, existing) >= DUPLICATE_BOX_IOU for existing in kept):
            continue
        kept.append(box)
    return kept


def _box_iou(a: dict, b: dict) -> float:
    ax1, ay1, ax2, ay2 = float(a["x1"]), float(a["y1"]), float(a["x2"]), float(a["y2"])
    bx1, by1, bx2, by2 = float(b["x1"]), float(b["y1"]), float(b["x2"]), float(b["y2"])
    ix1 = max(ax1, bx1)
    iy1 = max(ay1, by1)
    ix2 = min(ax2, bx2)
    iy2 = min(ay2, by2)
    intersection = max(ix2 - ix1, 0.0) * max(iy2 - iy1, 0.0)
    if intersection <= 0:
        return 0.0
    area_a = max(ax2 - ax1, 0.0) * max(ay2 - ay1, 0.0)
    area_b = max(bx2 - bx1, 0.0) * max(by2 - by1, 0.0)
    union = area_a + area_b - intersection
    return intersection / union if union > 0 else 0.0


def _to_list(value: Any) -> list:
    if value is None:
        return []
    tolist = getattr(value, "tolist", None)
    if callable(tolist):
        try:
            return tolist()
        except Exception:
            pass
    return list(value)


def _unwrap(value: Any) -> Any:
    item = getattr(value, "item", None)
    if callable(item):
        try:
            return item()
        except Exception:
            pass
    return value


def _thermal_center_measure_region(frame: np.ndarray) -> Tuple[int, int, int, int]:
    arr = np.asarray(frame)
    height = int(arr.shape[0]) if arr.ndim >= 2 else 0
    width = int(arr.shape[1]) if arr.ndim >= 2 else 0
    if width <= 0 or height <= 0:
        return 0, 0, 0, 0
    x1 = int(round(width * 0.35))
    y1 = int(round(height * 0.35))
    x2 = int(round(width * 0.65))
    y2 = int(round(height * 0.65))
    return x1, y1, x2, y2


def _thermal_measure_region(frame: np.ndarray, roi: Optional[Any]) -> Tuple[int, int, int, int]:
    if roi is None:
        return _thermal_center_measure_region(frame)
    try:
        normalized = ThermalMeasureRoi.model_validate(roi)
    except Exception:
        return _thermal_center_measure_region(frame)
    arr = np.asarray(frame)
    height = int(arr.shape[0]) if arr.ndim >= 2 else 0
    width = int(arr.shape[1]) if arr.ndim >= 2 else 0
    if width <= 0 or height <= 0:
        return 0, 0, 0, 0
    active_x1, active_y1, active_x2, active_y2 = _active_thermal_content_bounds(arr)
    active_width = max(active_x2 - active_x1, 1)
    active_height = max(active_y2 - active_y1, 1)
    x1 = int(round(active_x1 + active_width * normalized.x))
    y1 = int(round(active_y1 + active_height * normalized.y))
    x2 = int(round(active_x1 + active_width * (normalized.x + normalized.width)))
    y2 = int(round(active_y1 + active_height * (normalized.y + normalized.height)))
    x1 = min(max(x1, 0), width)
    y1 = min(max(y1, 0), height)
    x2 = min(max(x2, x1), width)
    y2 = min(max(y2, y1), height)
    return x1, y1, x2, y2


def _thermal_measurements(items: Optional[Iterable[Any]]) -> List[Tuple[float, Any]]:
    measurements: List[Tuple[float, Any]] = []
    if not items:
        return measurements
    for item in items:
        if not isinstance(item, dict):
            continue
        raw_temperature = item.get("temperature_c", item.get("temperatureC", item.get("temperature")))
        roi = item.get("roi", item.get("thermal_measure_roi", item.get("thermalMeasureRoi")))
        if roi is None:
            continue
        try:
            temperature = float(raw_temperature)
        except (TypeError, ValueError):
            continue
        if not np.isfinite(temperature):
            continue
        measurements.append((temperature, roi))
    return measurements


def _active_thermal_content_bounds(frame: np.ndarray) -> Tuple[int, int, int, int]:
    arr = np.asarray(frame)
    if arr.ndim == 3:
        intensity = arr.max(axis=-1)
    else:
        intensity = arr
    height, width = intensity.shape
    active_columns = np.where(intensity.max(axis=0) > BLACK_BAR_INTENSITY_FLOOR)[0]
    active_rows = np.where(intensity.max(axis=1) > BLACK_BAR_INTENSITY_FLOOR)[0]
    if active_columns.size:
        x1 = int(active_columns.min())
        x2 = int(active_columns.max()) + 1
        if (x2 - x1) / width < MIN_ACTIVE_AXIS_RATIO:
            x1, x2 = 0, width
    else:
        x1, x2 = 0, width
    if active_rows.size:
        y1 = int(active_rows.min())
        y2 = int(active_rows.max()) + 1
        if (y2 - y1) / height < MIN_ACTIVE_AXIS_RATIO:
            y1, y2 = 0, height
    else:
        y1, y2 = 0, height
    return x1, y1, x2, y2


BLACK_BAR_INTENSITY_FLOOR = 8
MIN_ACTIVE_AXIS_RATIO = 0.45
DUPLICATE_BOX_IOU = 0.6
