import hashlib
import json
import subprocess
import sys
from pathlib import Path

import pytest

from app.mobile_model import (
    build_benchmark_manifest,
    build_candidate_manifest,
    build_benchmark_set,
    sha256_file,
    write_json_atomically,
)


SOURCE_MODEL = Path(__file__).parents[1] / "weights" / "thermal-fire-yolov8n-640-gt-20260709.pt"


def test_candidate_manifest_is_reproducible(tmp_path):
    exported_files = []
    for engine, filename in (("onnx", "model.onnx"), ("tflite", "model.tflite"), ("ncnn", "model.param")):
        path = tmp_path / filename
        path.write_bytes(engine.encode())
        exported_files.append((engine, path))

    manifest = build_candidate_manifest(SOURCE_MODEL, exported_files)

    assert manifest["modelVersion"] == "thermal-fire-yolov8n-640-gt-20260709"
    assert manifest["source"]["sha256"] == sha256_file(SOURCE_MODEL)
    assert manifest["inputSize"] == [640, 640]
    assert manifest["classNames"] == ["fire"]
    assert manifest["normalization"] == {"scale": 0.00392156862745098, "mean": [0, 0, 0], "std": [1, 1, 1]}
    assert manifest["confidenceThreshold"] == 0.25
    assert manifest["iouThreshold"] == 0.7
    assert manifest["outputLayout"] == "xywh, class scores; postprocess with NMS"
    assert {item["engine"] for item in manifest["candidates"]} == {"onnx", "tflite", "ncnn"}
    assert all(item["sha256"] for item in manifest["candidates"])


def test_candidate_checksum_is_independent_of_export_directory(tmp_path):
    first = tmp_path / "first"
    second = tmp_path / "second"
    first.mkdir()
    second.mkdir()
    first_exports = []
    second_exports = []
    for engine, filename in (("onnx", "model.onnx"), ("tflite", "model.tflite"), ("ncnn", "model.param")):
        (first / filename).write_bytes(engine.encode())
        (second / filename).write_bytes(engine.encode())
        first_exports.append((engine, first / filename))
        second_exports.append((engine, second / filename))

    first_manifest = build_candidate_manifest(SOURCE_MODEL, first_exports)
    second_manifest = build_candidate_manifest(SOURCE_MODEL, second_exports)

    assert [candidate["sha256"] for candidate in first_manifest["candidates"]] == [
        candidate["sha256"] for candidate in second_manifest["candidates"]
    ]


def test_write_json_atomically_replaces_complete_manifest(tmp_path):
    output = tmp_path / "model-candidates.json"
    output.write_text('{"old": true}\n', encoding="utf-8")

    write_json_atomically(output, {"new": True})

    assert json.loads(output.read_text(encoding="utf-8")) == {"new": True}
    assert not list(tmp_path.glob(".model-candidates.json.*.tmp"))


def test_benchmark_manifest_uses_stable_ids_without_source_paths(tmp_path):
    image = tmp_path / "thermal frame.jpg"
    image.write_bytes(b"image bytes")

    manifest = build_benchmark_manifest(
        source_dataset=tmp_path / "source-validation",
        samples=[
            (
                image,
                tmp_path / "thermal frame.txt",
                [{"class": 0, "x": 0.5, "y": 0.5, "width": 0.2, "height": 0.2}],
            )
        ],
        seed=20260727,
    )

    sample = manifest["samples"][0]
    assert manifest["seed"] == 20260727
    assert sample["id"] == "sample-0001"
    assert sample["imageSha256"] == hashlib.sha256(b"image bytes").hexdigest()
    assert sample["expectedBoxes"] == [{"class": 0, "x": 0.5, "y": 0.5, "width": 0.2, "height": 0.2}]
    assert sample["sourcePath"] == str(image)
    assert sample["labelPath"] == str(tmp_path / "thermal frame.txt")


def test_build_benchmark_set_rejects_missing_labeled_validation_data(tmp_path):
    with pytest.raises(ValueError, match="dataset YAML"):
        build_benchmark_set(tmp_path / "missing", tmp_path / "output")


def test_build_benchmark_set_does_not_allow_smaller_counts_or_a_different_seed(tmp_path):
    with pytest.raises(TypeError):
        build_benchmark_set(tmp_path / "missing", tmp_path / "output", 1, 1, 20260727)


def test_export_script_runs_directly_from_project_root():
    project_root = Path(__file__).parents[1]

    result = subprocess.run(
        [sys.executable, "scripts/export_mobile_thermal_model.py", "--help"],
        cwd=project_root,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0, result.stderr


def test_exporter_uses_the_current_virtualenv_yolo_executable():
    import importlib.util

    script = Path(__file__).parents[1] / "scripts" / "export_mobile_thermal_model.py"
    spec = importlib.util.spec_from_file_location("export_mobile_thermal_model", script)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)

    assert module.yolo_executable() == Path(sys.executable).with_name("yolo")


def test_exporter_stages_outputs_away_from_the_deployed_checkpoint(tmp_path, monkeypatch):
    import importlib.util

    script = Path(__file__).parents[1] / "scripts" / "export_mobile_thermal_model.py"
    spec = importlib.util.spec_from_file_location("export_mobile_thermal_model", script)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    source = tmp_path / "deployed.pt"
    source.write_bytes(b"checkpoint")
    output = tmp_path / "mobile-model"

    def create_staged_onnx(command, check):
        staged_model = Path(next(argument.split("=", 1)[1] for argument in command if argument.startswith("model=")))
        staged_model.with_suffix(".onnx").write_bytes(b"onnx")

    monkeypatch.setattr(module.subprocess, "run", create_staged_onnx)

    exported = module._export(source, output, "onnx", simplify=True)

    assert exported == output / "deployed.onnx"
    assert exported.read_bytes() == b"onnx"
    assert not source.with_suffix(".onnx").exists()
    assert not list(output.glob(".export-staging-*"))


def test_exporter_cleans_staging_when_ultralytics_fails(tmp_path, monkeypatch):
    import importlib.util

    script = Path(__file__).parents[1] / "scripts" / "export_mobile_thermal_model.py"
    spec = importlib.util.spec_from_file_location("export_mobile_thermal_model", script)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    source = tmp_path / "deployed.pt"
    source.write_bytes(b"checkpoint")
    output = tmp_path / "mobile-model"

    def fail_export(command, check):
        raise subprocess.CalledProcessError(1, command)

    monkeypatch.setattr(module.subprocess, "run", fail_export)

    with pytest.raises(subprocess.CalledProcessError):
        module._export(source, output, "onnx")

    assert not source.with_suffix(".onnx").exists()
    assert not list(output.glob(".export-staging-*"))
