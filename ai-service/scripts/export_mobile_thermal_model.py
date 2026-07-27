#!/usr/bin/env python3
"""Export and hash mobile candidates from the deployed thermal checkpoint."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.mobile_model import build_candidate_manifest, write_json_atomically


def yolo_executable() -> Path:
    return Path(sys.executable).with_name("yolo")


def _export(model: Path, output: Path, export_format: str, simplify: bool = False) -> Path:
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".export-staging-", dir=output) as staging_directory:
        staging_model = (Path(staging_directory) / model.name).resolve()
        shutil.copy2(model, staging_model)
        command = [str(yolo_executable()), "export", f"model={staging_model}", f"format={export_format}", "imgsz=640"]
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
            shutil.copytree(source, destination)
        else:
            shutil.copy2(source, destination)
        return destination


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not args.model.is_file():
        raise SystemExit(f"Model does not exist: {args.model}")
    args.output.mkdir(parents=True, exist_ok=True)
    exports = [
        ("onnx", _export(args.model, args.output, "onnx", simplify=True)),
        ("tflite", _export(args.model, args.output, "tflite")),
        ("ncnn", _export(args.model, args.output, "ncnn")),
    ]
    write_json_atomically(args.output / "model-candidates.json", build_candidate_manifest(args.model, exports))


if __name__ == "__main__":
    main()
