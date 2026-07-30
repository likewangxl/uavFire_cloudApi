#!/usr/bin/env python3
"""Export reproducible 960px mobile candidates from the approved visible checkpoint."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.mobile_model import (
    VISIBLE_INPUT_SIZE,
    build_candidate_manifest,
    validate_production_visible_model,
    write_json_atomically,
)


def yolo_executable() -> Path:
    return Path(sys.executable).with_name("yolo")


def _export(model: Path, output: Path, export_format: str, *, input_size: int = VISIBLE_INPUT_SIZE, simplify: bool = False) -> Path:
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".export-staging-", dir=output) as staging_directory:
        staging_model = (Path(staging_directory) / model.name).resolve()
        shutil.copy2(model, staging_model)
        command = [
            str(yolo_executable()), "export", f"model={staging_model}", f"format={export_format}", f"imgsz={input_size}",
        ]
        if simplify:
            command.append("simplify=True")
        subprocess.run(command, check=True, cwd=staging_model.parent)
        stem = staging_model.with_suffix("")
        source = {
            "onnx": stem.with_suffix(".onnx"),
            "tflite": stem.parent / f"{stem.name}_saved_model" / f"{stem.name}_float32.tflite",
            "ncnn": stem.parent / f"{stem.name}_ncnn_model",
        }[export_format]
        if not source.exists():
            raise FileNotFoundError(f"Ultralytics did not produce {export_format} output: {source}")
        destination = output / source.name
        if source.is_dir():
            shutil.rmtree(destination, ignore_errors=True)
            shutil.copytree(source, destination, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
        else:
            shutil.copy2(source, destination)
        return destination


def _model_classes(model: Path) -> list[str] | dict[int, str]:
    from ultralytics import YOLO

    return YOLO(model).names


def _required_input_size(value: str) -> int:
    input_size = int(value)
    if input_size != VISIBLE_INPUT_SIZE:
        raise argparse.ArgumentTypeError(f"must be {VISIBLE_INPUT_SIZE}")
    return input_size


def parse_arguments(arguments: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--input-size", required=True, type=_required_input_size)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args(arguments)


def main() -> None:
    args = parse_arguments()
    validate_production_visible_model(args.model, _model_classes(args.model))
    args.output.mkdir(parents=True, exist_ok=True)
    exports = [
        ("onnx", _export(args.model, args.output, "onnx", input_size=args.input_size, simplify=True)),
        ("tflite", _export(args.model, args.output, "tflite", input_size=args.input_size)),
        ("ncnn", _export(args.model, args.output, "ncnn", input_size=args.input_size)),
    ]
    manifest = build_candidate_manifest(
        args.model,
        exports,
        input_size=args.input_size,
        class_names=_model_classes(args.model),
    )
    write_json_atomically(args.output / "model-candidates.json", manifest)


if __name__ == "__main__":
    main()
