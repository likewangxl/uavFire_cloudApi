#!/usr/bin/env python3
"""Evaluate labelled M300 Agent fire-inference JSONL and enforce field thresholds."""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
from typing import Iterable

POSITIVE_LABELS = {"fire", "smoke"}


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(quantile * len(ordered)) - 1))
    return float(ordered[index])


def evaluate(records: Iterable[dict], flight_hours: float | None = None) -> dict:
    labelled = [record for record in records if str(record.get("ground_truth", "")).lower()]
    if not labelled:
        raise ValueError("no labelled records; provide ground_truth or --labels")
    tp = fp = fn = tn = 0
    latencies: list[float] = []
    timestamps: list[int] = []
    for record in labelled:
        truth = str(record["ground_truth"]).lower() in POSITIVE_LABELS
        predicted = bool(record.get("confirmed", False))
        tp += int(truth and predicted)
        fp += int(not truth and predicted)
        fn += int(truth and not predicted)
        tn += int(not truth and not predicted)
        latencies.append(float(record.get("inference_ms", 0)))
        timestamps.append(int(record["source_ts"]))
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    measured_hours = flight_hours
    if measured_hours is None:
        measured_hours = max((max(timestamps) - min(timestamps)) / 3_600_000, 1 / 60)
    return {
        "samples": len(labelled),
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "tn": tn,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "false_alarms_per_hour": fp / measured_hours,
        "measured_hours": measured_hours,
        "inference_ms_p50": percentile(latencies, 0.50),
        "inference_ms_p95": percentile(latencies, 0.95),
        "inference_ms_max": max(latencies),
    }


def read_records(path: Path) -> list[dict]:
    with path.open("r", encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def apply_labels(records: list[dict], labels_path: Path | None) -> None:
    if labels_path is None:
        return
    with labels_path.open("r", encoding="utf-8-sig", newline="") as source:
        labels = {int(row["source_ts"]): row["ground_truth"].strip().lower() for row in csv.DictReader(source)}
    for record in records:
        timestamp = int(record["source_ts"])
        if timestamp in labels:
            record["ground_truth"] = labels[timestamp]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("jsonl", type=Path)
    parser.add_argument("--labels", type=Path, help="CSV columns: source_ts,ground_truth")
    parser.add_argument("--flight-hours", type=float)
    parser.add_argument("--min-precision", type=float, default=0.90)
    parser.add_argument("--min-recall", type=float, default=0.90)
    parser.add_argument("--max-false-alarms-per-hour", type=float, default=1.0)
    parser.add_argument("--max-p95-inference-ms", type=float, default=400.0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    records = read_records(args.jsonl)
    apply_labels(records, args.labels)
    metrics = evaluate(records, args.flight_hours)
    checks = {
        "precision": metrics["precision"] >= args.min_precision,
        "recall": metrics["recall"] >= args.min_recall,
        "false_alarms_per_hour": metrics["false_alarms_per_hour"] <= args.max_false_alarms_per_hour,
        "p95_inference_ms": metrics["inference_ms_p95"] <= args.max_p95_inference_ms,
    }
    report = {"passed": all(checks.values()), "checks": checks, "metrics": metrics}
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    return 0 if report["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
