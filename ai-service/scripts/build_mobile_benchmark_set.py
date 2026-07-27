#!/usr/bin/env python3
"""Build a deterministic, labeled mobile benchmark set and PyTorch baseline."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.mobile_model import build_benchmark_set, write_json_atomically


def run_pytorch_baseline(model: Path, benchmark: Path) -> None:
    from ultralytics import YOLO

    manifest_path = benchmark / "manifest.json"
    import json

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    detector = YOLO(model)
    detections = []
    for sample in manifest["samples"]:
        result = detector(benchmark / sample["image"], verbose=False)[0]
        boxes = []
        for box in result.boxes:
            xyxy = [float(value) for value in box.xyxy[0].tolist()]
            boxes.append({"class": int(box.cls[0]), "confidence": float(box.conf[0]), "xyxy": xyxy})
        detections.append({"id": sample["id"], "detections": boxes})
    write_json_atomically(benchmark / "pytorch-baseline.json", {"model": model.name, "samples": detections})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--model", type=Path, default=Path("weights/thermal-fire-yolov8n-640-gt-20260709.pt"))
    args = parser.parse_args()
    build_benchmark_set(args.dataset, args.output)
    if not args.model.is_file():
        raise SystemExit(f"Baseline model does not exist: {args.model}")
    run_pytorch_baseline(args.model, args.output)


if __name__ == "__main__":
    main()
