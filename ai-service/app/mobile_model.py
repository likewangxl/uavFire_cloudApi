"""Reproducible artifact and benchmark-set helpers for the Android model gate."""

from __future__ import annotations

import hashlib
import importlib.metadata
import json
import math
import os
import platform
import random
import re
import shutil
import tempfile
from pathlib import Path, PureWindowsPath
from typing import Any, Iterable

import yaml


IMAGE_SUFFIXES = {".bmp", ".jpeg", ".jpg", ".png", ".tif", ".tiff", ".webp"}
PRODUCTION_VISIBLE_MODEL_NAME = "visible-fire-wechat-best2-20260728.pt"
PRODUCTION_VISIBLE_MODEL_SHA256 = "957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650"
VISIBLE_CLASS_NAMES = ["fire", "smoke"]
VISIBLE_INPUT_SIZE = 960
CONFIDENCE_THRESHOLD = 0.25
IOU_THRESHOLD = 0.7
OUTPUT_LAYOUT = "xywh, class scores; postprocess with NMS"
BENCHMARK_SEED = 20260730
VISIBLE_MODALITY = "visible"
VISIBLE_BENCHMARK_DATASET_ID = "visible-validation-20260730"
REQUIRED_BENCHMARK_TAGS = frozenset({
    "fire",
    "smoke",
    "hard-negative-orange-red",
    "night-dark",
    "small-target",
    "zoomed-roi",
})
SAFE_SAMPLE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]*\Z")
SMALL_TARGET_MAX_RELATIVE_AREA = 0.02


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _model_version(source_model: Path) -> str:
    return source_model.stem


def _candidate_artifacts(path: Path) -> list[dict[str, str]]:
    files = [path] if path.is_file() else sorted(
        candidate
        for candidate in path.rglob("*")
        if candidate.is_file() and "__pycache__" not in candidate.parts and candidate.suffix != ".pyc"
    )
    if not files:
        raise ValueError(f"Exported artifact is empty: {path}")
    return [{"path": str(file.relative_to(path.parent)), "sha256": sha256_file(file)} for file in files]


def _normalized_class_names(class_names: list[str] | dict[int, str]) -> list[str]:
    if isinstance(class_names, dict):
        return [class_names[index] for index in sorted(class_names)]
    return list(class_names)


def validate_production_visible_model(source_model: Path, class_names: list[str] | dict[int, str]) -> None:
    """Reject inputs that cannot be the approved visible-light production release."""
    source_model = Path(source_model)
    if not source_model.is_file():
        raise ValueError(f"Source model does not exist: {source_model}")
    if source_model.name != PRODUCTION_VISIBLE_MODEL_NAME:
        raise ValueError(f"Expected production visible model {PRODUCTION_VISIBLE_MODEL_NAME}; found {source_model.name}")
    if sha256_file(source_model) != PRODUCTION_VISIBLE_MODEL_SHA256:
        raise ValueError("Source model SHA-256 does not match the approved production visible model")
    if _normalized_class_names(class_names) != VISIBLE_CLASS_NAMES:
        raise ValueError(f"Source model classes must be {VISIBLE_CLASS_NAMES}")


def _exporter_versions() -> dict[str, str]:
    def version(distribution: str) -> str:
        try:
            return importlib.metadata.version(distribution)
        except importlib.metadata.PackageNotFoundError:
            return "not-installed"

    return {
        "python": platform.python_version(),
        "torch": version("torch"),
        "ultralytics": version("ultralytics"),
        "onnx": version("onnx"),
        "onnxslim": version("onnxslim"),
        "tensorflow": version("tensorflow"),
        "onnx2tf": version("onnx2tf"),
        "ncnn": version("ncnn"),
        "pnnx": version("pnnx"),
    }


def build_candidate_manifest(
    source_model: Path,
    exported_files: Iterable[tuple[str, Path]],
    *,
    input_size: int,
    class_names: list[str] | dict[int, str] | None = None,
    exporter_versions: dict[str, str] | None = None,
) -> dict[str, Any]:
    source_model = Path(source_model)
    validate_production_visible_model(
        source_model,
        VISIBLE_CLASS_NAMES if class_names is None else class_names,
    )
    if input_size != VISIBLE_INPUT_SIZE:
        raise ValueError(f"Visible mobile export input size must be {VISIBLE_INPUT_SIZE}")

    candidates = []
    for engine, artifact in exported_files:
        artifact = Path(artifact)
        if not artifact.exists():
            raise ValueError(f"Missing {engine} export: {artifact}")
        artifacts = _candidate_artifacts(artifact)
        aggregate = hashlib.sha256(json.dumps(artifacts, sort_keys=True).encode()).hexdigest()
        candidates.append({"engine": engine, "sha256": aggregate, "artifacts": artifacts})

    engines = {candidate["engine"] for candidate in candidates}
    if len(candidates) != 3 or engines != {"onnx", "tflite", "ncnn"}:
        raise ValueError(f"Expected exactly one ONNX, TFLite, and NCNN export; found {sorted(engines)}")

    return {
        "schemaVersion": 2,
        "modality": VISIBLE_MODALITY,
        "modelVersion": _model_version(source_model),
        "source": {"name": source_model.name, "sha256": PRODUCTION_VISIBLE_MODEL_SHA256},
        "classes": VISIBLE_CLASS_NAMES,
        "input": {
            "width": input_size,
            "height": input_size,
            "channels": 3,
            "colorSpace": "RGB",
            "normalization": {"scale": 1 / 255, "mean": [0, 0, 0], "std": [1, 1, 1]},
        },
        "postprocess": {
            "confidenceThreshold": CONFIDENCE_THRESHOLD,
            "iouThreshold": IOU_THRESHOLD,
            "outputLayout": OUTPUT_LAYOUT,
        },
        "exporter": exporter_versions or _exporter_versions(),
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
        raw_class_id, raw_x, raw_y, raw_width, raw_height = values
        try:
            class_id = int(raw_class_id)
            x, y, width, height = (float(value) for value in (raw_x, raw_y, raw_width, raw_height))
        except ValueError as error:
            raise ValueError(f"Invalid YOLO label at {label}:{line_number}") from error
        if (
            class_id not in {0, 1}
            or not all(math.isfinite(value) for value in (x, y, width, height))
            or not (0 <= x <= 1 and 0 <= y <= 1 and 0 < width <= 1 and 0 < height <= 1)
            or x - width / 2 < 0
            or x + width / 2 > 1
            or y - height / 2 < 0
            or y + height / 2 > 1
        ):
            raise ValueError(f"Invalid YOLO label at {label}:{line_number}")
        boxes.append({"class": class_id, "x": x, "y": y, "width": width, "height": height})
    return boxes


def build_benchmark_manifest(
    samples: Iterable[tuple[str, list[str], Path, Path, list[dict[str, float | int]]]], seed: int
) -> dict[str, Any]:
    entries = []
    for sample_id, tags, image, label, boxes in samples:
        entries.append({
            "id": sample_id,
            "tags": tags,
            "imageSha256": sha256_file(image),
            "labelSha256": sha256_file(label),
            "expectedBoxes": boxes,
        })
    return {
        "schemaVersion": 2,
        "modality": VISIBLE_MODALITY,
        "datasetId": VISIBLE_BENCHMARK_DATASET_ID,
        "seed": seed,
        "samples": entries,
    }


def build_pytorch_baseline_manifest(
    benchmark_manifest: dict[str, Any],
    *,
    benchmark_manifest_sha256: str,
    detections: Iterable[dict[str, Any]],
) -> dict[str, Any]:
    detections_by_id = {item["id"]: item["detections"] for item in detections}
    benchmark_ids = [sample["id"] for sample in benchmark_manifest["samples"]]
    if len(detections_by_id) != len(benchmark_ids) or set(detections_by_id) != set(benchmark_ids):
        raise ValueError("PyTorch detections must cover exactly the benchmark manifest samples")
    samples = []
    for sample in benchmark_manifest["samples"]:
        samples.append({
            "id": sample["id"],
            "tags": list(sample["tags"]),
            "imageSha256": sample["imageSha256"],
            "labelSha256": sample["labelSha256"],
            "expectedBoxes": [dict(box) for box in sample["expectedBoxes"]],
            "detections": detections_by_id[sample["id"]],
        })
    return {
        "model": {"name": PRODUCTION_VISIBLE_MODEL_NAME, "sha256": PRODUCTION_VISIBLE_MODEL_SHA256},
        "modality": VISIBLE_MODALITY,
        "datasetId": VISIBLE_BENCHMARK_DATASET_ID,
        "seed": BENCHMARK_SEED,
        "benchmarkManifestSha256": benchmark_manifest_sha256,
        "inputSize": VISIBLE_INPUT_SIZE,
        "confidenceThreshold": CONFIDENCE_THRESHOLD,
        "iouThreshold": IOU_THRESHOLD,
        "samples": samples,
    }


def _load_benchmark_tags(dataset: Path) -> dict[str, tuple[str, list[str]]]:
    tags_path = dataset / "benchmark-tags.json"
    if not tags_path.is_file():
        raise ValueError(f"Visible benchmark dataset must provide {tags_path.name}")
    payload = json.loads(tags_path.read_text(encoding="utf-8"))
    records = payload.get("samples") if isinstance(payload, dict) else None
    if not isinstance(records, list):
        raise ValueError(f"{tags_path.name} must contain a samples list")
    tagged: dict[str, tuple[str, list[str]]] = {}
    ids: set[str] = set()
    for record in records:
        if not isinstance(record, dict):
            raise ValueError(f"Invalid sample in {tags_path.name}")
        sample_id, image, tags = record.get("id"), record.get("image"), record.get("tags")
        if not isinstance(sample_id, str) or not sample_id or not isinstance(image, str) or not isinstance(tags, list):
            raise ValueError(f"Each {tags_path.name} sample needs id, image, and tags")
        if not SAFE_SAMPLE_ID.fullmatch(sample_id):
            raise ValueError(f"Sample id must be a safe filename: {sample_id!r}")
        if sample_id in ids or image in tagged:
            raise ValueError(f"Duplicate sample id or image in {tags_path.name}")
        if not all(isinstance(tag, str) for tag in tags):
            raise ValueError(f"Sample {sample_id} has invalid tags")
        ids.add(sample_id)
        tagged[Path(image).as_posix()] = (sample_id, sorted(set(tags)))
    return tagged


def _validate_tag_semantics(sample_id: str, tags: list[str], boxes: list[dict[str, float | int]]) -> None:
    classes = {int(box["class"]) for box in boxes}
    has_small_target = any(float(box["width"]) * float(box["height"]) <= SMALL_TARGET_MAX_RELATIVE_AREA for box in boxes)
    invalid = (
        ("fire" in tags and 0 not in classes)
        or ("smoke" in tags and 1 not in classes)
        or ("hard-negative-orange-red" in tags and bool(boxes))
        or ("small-target" in tags and not has_small_target)
    )
    if invalid:
        raise ValueError(f"Benchmark tags for {sample_id} do not match labels")


def _tagged_validation_samples(
    dataset: Path, images: list[tuple[Path, Path, list[dict[str, float | int]]]], seed: int
) -> list[tuple[str, list[str], Path, Path, list[dict[str, float | int]]]]:
    tagged = _load_benchmark_tags(dataset)
    available = {image.relative_to(dataset).as_posix(): (image, label, boxes) for image, label, boxes in images}
    if set(tagged) != set(available):
        missing_tags = sorted(set(available) - set(tagged))
        missing_images = sorted(set(tagged) - set(available))
        raise ValueError(f"benchmark-tags.json must match validation images; missing tags={missing_tags}, missing images={missing_images}")
    tagged_samples = []
    for relative_image in sorted(tagged):
        sample_id, tags = tagged[relative_image]
        image, label, boxes = available[relative_image]
        _validate_tag_semantics(sample_id, tags, boxes)
        tagged_samples.append((sample_id, tags, image, label, boxes))
    found_tags = {tag for _, tags, _, _, _ in tagged_samples for tag in tags}
    missing_required_tags = sorted(REQUIRED_BENCHMARK_TAGS - found_tags)
    if missing_required_tags:
        raise ValueError(f"Visible benchmark set is missing required categories: {missing_required_tags}")
    selector = random.Random(seed)
    selector.shuffle(tagged_samples)
    return tagged_samples


def build_benchmark_set(dataset: Path, output: Path, *, seed: int = BENCHMARK_SEED) -> dict[str, Any]:
    dataset = Path(dataset).resolve()
    selected = _tagged_validation_samples(dataset, _validation_images(dataset), seed)
    manifest = build_benchmark_manifest(selected, seed)

    output = Path(output)
    images_output = output / "images"
    labels_output = output / "labels"
    for sample, (_, _, image, label, _) in zip(manifest["samples"], selected, strict=True):
        image_destination = images_output / f"{sample['id']}{image.suffix.lower()}"
        label_destination = labels_output / f"{sample['id']}.txt"
        try:
            image_destination.resolve().relative_to(images_output.resolve())
            label_destination.resolve().relative_to(labels_output.resolve())
        except ValueError as error:
            raise ValueError(f"Sample id escapes benchmark output: {sample['id']!r}") from error
        image_destination.parent.mkdir(parents=True, exist_ok=True)
        label_destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(image, image_destination)
        shutil.copy2(label, label_destination)
        sample["image"] = str(image_destination.relative_to(output))
        sample["label"] = str(label_destination.relative_to(output))
    write_json_atomically(output / "manifest.json", manifest)
    return manifest
