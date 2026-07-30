#!/usr/bin/env python3
"""Build a deterministic, labeled mobile benchmark set and PyTorch baseline."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.mobile_model import (
    BENCHMARK_SEED,
    CONFIDENCE_THRESHOLD,
    IOU_THRESHOLD,
    PRODUCTION_VISIBLE_MODEL_SHA256,
    VISIBLE_INPUT_SIZE,
    build_pytorch_baseline_manifest,
    build_benchmark_set,
    sha256_file,
    validate_production_visible_model,
    write_json_atomically,
)


def run_pytorch_baseline(model: Path, benchmark: Path) -> None:
    from ultralytics import YOLO

    manifest_path = benchmark / "manifest.json"
    import json

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    detector = YOLO(model)
    detections = []
    for sample in manifest["samples"]:
        result = detector(
            benchmark / sample["image"],
            imgsz=VISIBLE_INPUT_SIZE,
            conf=CONFIDENCE_THRESHOLD,
            iou=IOU_THRESHOLD,
            verbose=False,
        )[0]
        boxes = []
        for box in result.boxes:
            xyxy = [float(value) for value in box.xyxy[0].tolist()]
            boxes.append({"class": int(box.cls[0]), "confidence": float(box.conf[0]), "xyxy": xyxy})
        detections.append({"id": sample["id"], "detections": boxes})
    write_json_atomically(
        benchmark / "pytorch-baseline.json",
        build_pytorch_baseline_manifest(
            manifest,
            benchmark_manifest_sha256=sha256_file(manifest_path),
            detections=detections,
        ),
    )


def _required_value(expected: int):
    def parse(value: str) -> int:
        parsed = int(value)
        if parsed != expected:
            raise argparse.ArgumentTypeError(f"must be {expected}")
        return parsed

    return parse


def parse_arguments(arguments: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--seed", required=True, type=_required_value(BENCHMARK_SEED))
    parser.add_argument("--model", type=Path, default=Path("weights/visible-fire-wechat-best2-20260728.pt"))
    return parser.parse_args(arguments)


def main() -> None:
    args = parse_arguments()
    if not args.model.is_file():
        raise SystemExit(f"Baseline model does not exist: {args.model}")
    from ultralytics import YOLO

    validate_production_visible_model(args.model, YOLO(args.model).names)
    build_benchmark_set(args.dataset, args.output, seed=args.seed)
    run_pytorch_baseline(args.model, args.output)


if __name__ == "__main__":
    main()
