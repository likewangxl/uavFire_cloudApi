"""Reproducible artifact and benchmark-set helpers for the Android model gate."""

from __future__ import annotations

import hashlib
import json
import os
import random
import shutil
import tempfile
from pathlib import Path, PureWindowsPath
from typing import Any, Iterable

import yaml


IMAGE_SUFFIXES = {".bmp", ".jpeg", ".jpg", ".png", ".tif", ".tiff", ".webp"}
MODEL_INPUT_SIZE = [640, 640]
CLASS_NAMES = ["fire"]
CONFIDENCE_THRESHOLD = 0.25
IOU_THRESHOLD = 0.7
OUTPUT_LAYOUT = "xywh, class scores; postprocess with NMS"
BENCHMARK_POSITIVE_COUNT = 200
BENCHMARK_NEGATIVE_COUNT = 200
BENCHMARK_SEED = 20260727


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _model_version(source_model: Path) -> str:
    return source_model.stem


def _candidate_artifacts(path: Path) -> list[dict[str, str]]:
    files = [path] if path.is_file() else sorted(candidate for candidate in path.rglob("*") if candidate.is_file())
    if not files:
        raise ValueError(f"Exported artifact is empty: {path}")
    return [{"path": str(file.relative_to(path.parent)), "sha256": sha256_file(file)} for file in files]


def build_candidate_manifest(source_model: Path, exported_files: Iterable[tuple[str, Path]]) -> dict[str, Any]:
    source_model = Path(source_model)
    if not source_model.is_file():
        raise ValueError(f"Source model does not exist: {source_model}")

    candidates = []
    for engine, artifact in exported_files:
        artifact = Path(artifact)
        if not artifact.exists():
            raise ValueError(f"Missing {engine} export: {artifact}")
        artifacts = _candidate_artifacts(artifact)
        aggregate = hashlib.sha256(json.dumps(artifacts, sort_keys=True).encode()).hexdigest()
        candidates.append({"engine": engine, "sha256": aggregate, "artifacts": artifacts})

    engines = {candidate["engine"] for candidate in candidates}
    if engines != {"onnx", "tflite", "ncnn"}:
        raise ValueError(f"Expected ONNX, TFLite, and NCNN exports; found {sorted(engines)}")

    return {
        "modelVersion": _model_version(source_model),
        "source": {"path": str(source_model), "sha256": sha256_file(source_model)},
        "inputSize": MODEL_INPUT_SIZE,
        "classNames": CLASS_NAMES,
        "normalization": {"scale": 1 / 255, "mean": [0, 0, 0], "std": [1, 1, 1]},
        "confidenceThreshold": CONFIDENCE_THRESHOLD,
        "iouThreshold": IOU_THRESHOLD,
        "outputLayout": OUTPUT_LAYOUT,
        "candidates": sorted(candidates, key=lambda candidate: candidate["engine"]),
    }


def write_json_atomically(path: Path, payload: dict[str, Any]) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as temporary:
            json.dump(payload, temporary, indent=2, sort_keys=True)
            temporary.write("\n")
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, path)
    except BaseException:
        Path(temporary_name).unlink(missing_ok=True)
        raise


def _validation_images(dataset: Path) -> list[tuple[Path, Path, list[dict[str, float | int]]]]:
    yaml_files = sorted(dataset.glob("*.yaml")) + sorted(dataset.glob("*.yml"))
    if len(yaml_files) != 1:
        raise ValueError(f"Expected exactly one dataset YAML in {dataset}")
    config = yaml.safe_load(yaml_files[0].read_text(encoding="utf-8")) or {}
    if not isinstance(config, dict) or "val" not in config:
        raise ValueError(f"Dataset YAML must define a validation split: {yaml_files[0]}")

    root = yaml_files[0].parent.resolve()
    if config.get("path"):
        configured_root = str(config["path"])
        if not PureWindowsPath(configured_root).drive:
            root = (root / configured_root).resolve()
    validation = config["val"]
    validation_paths = validation if isinstance(validation, list) else [validation]
    images: list[tuple[Path, Path, list[dict[str, float | int]]]] = []
    for validation_path in validation_paths:
        image_root = (root / str(validation_path)).resolve()
        if not image_root.is_dir():
            raise ValueError(f"Validation image directory does not exist: {image_root}")
        try:
            relative_to_images = image_root.relative_to(root / "images")
        except ValueError as error:
            raise ValueError(f"Validation images must be under {root / 'images'}: {image_root}") from error
        label_root = root / "labels" / relative_to_images
        if not label_root.is_dir():
            raise ValueError(f"Validation label directory does not exist: {label_root}")
        for image in sorted(path for path in image_root.rglob("*") if path.suffix.lower() in IMAGE_SUFFIXES):
            label = label_root / image.relative_to(image_root).with_suffix(".txt")
            if not label.is_file():
                raise ValueError(f"Missing label for validation image: {image}")
            boxes = _read_yolo_boxes(label)
            images.append((image, label, boxes))
    return sorted(images, key=lambda item: str(item[0]))


def _read_yolo_boxes(label: Path) -> list[dict[str, float | int]]:
    boxes = []
    for line_number, line in enumerate(label.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        values = line.split()
        if len(values) != 5:
            raise ValueError(f"Invalid YOLO label at {label}:{line_number}")
        class_id, x, y, width, height = values
        boxes.append({"class": int(class_id), "x": float(x), "y": float(y), "width": float(width), "height": float(height)})
    return boxes


def build_benchmark_manifest(source_dataset: Path, samples: Iterable[tuple[Path, Path, list[dict[str, float | int]]]], seed: int) -> dict[str, Any]:
    entries = []
    for index, (image, label, boxes) in enumerate(samples, start=1):
        entries.append({
            "id": f"sample-{index:04d}",
            "sourcePath": str(image),
            "labelPath": str(label),
            "imageSha256": sha256_file(image),
            "expectedBoxes": boxes,
        })
    return {"sourceDataset": str(source_dataset), "seed": seed, "samples": entries}


def build_benchmark_set(dataset: Path, output: Path) -> dict[str, Any]:
    images = _validation_images(Path(dataset))
    positives = [item for item in images if item[2]]
    negatives = [item for item in images if not item[2]]
    if len(positives) < BENCHMARK_POSITIVE_COUNT or len(negatives) < BENCHMARK_NEGATIVE_COUNT:
        raise ValueError(
            f"Validation split needs {BENCHMARK_POSITIVE_COUNT} positive and {BENCHMARK_NEGATIVE_COUNT} negative images; "
            f"found {len(positives)} positive and {len(negatives)} negative"
        )
    selector = random.Random(BENCHMARK_SEED)
    selected = selector.sample(positives, BENCHMARK_POSITIVE_COUNT) + selector.sample(negatives, BENCHMARK_NEGATIVE_COUNT)
    selected.sort(key=lambda item: str(item[0]))
    manifest = build_benchmark_manifest(Path(dataset), selected, BENCHMARK_SEED)

    output = Path(output)
    images_output = output / "images"
    labels_output = output / "labels"
    for sample, (image, label, _) in zip(manifest["samples"], selected, strict=True):
        image_destination = images_output / f"{sample['id']}{image.suffix.lower()}"
        label_destination = labels_output / f"{sample['id']}.txt"
        image_destination.parent.mkdir(parents=True, exist_ok=True)
        label_destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(image, image_destination)
        shutil.copy2(label, label_destination)
        sample["image"] = str(image_destination.relative_to(output))
        sample["label"] = str(label_destination.relative_to(output))
    write_json_atomically(output / "manifest.json", manifest)
    return manifest
