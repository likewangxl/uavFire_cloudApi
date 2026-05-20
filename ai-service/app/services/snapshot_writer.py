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
            annotated = self._annotate(frame_bgr, boxes)
            cv2.imwrite(str(self._dir / annotated_name), annotated)
        except Exception:
            logger.exception("snapshot annotated write failed event=%s", event_id)
            annotated_name = raw_name
        return self._url(raw_name), self._url(annotated_name)

    def _annotate(self, frame: np.ndarray, boxes: Optional[Iterable[dict]]) -> np.ndarray:
        out = frame.copy()
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
    return out


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
