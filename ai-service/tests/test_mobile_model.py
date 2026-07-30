import hashlib
import json
import subprocess
import sys
from pathlib import Path

import pytest

import app.mobile_model as mobile_model
from app.mobile_model import (
    _validation_images,
    build_benchmark_manifest,
    build_candidate_manifest,
    build_benchmark_set,
    sha256_file,
    write_json_atomically,
)


SOURCE_MODEL = Path(__file__).parents[1] / "weights" / "visible-fire-wechat-best2-20260728.pt"


def test_visible_manifest_matches_production_contract(tmp_path):
    """Catches an accidental thermal/640 export or a manifest without reproducibility inputs."""
    exported_files = []
    for engine, filename in (("onnx", "model.onnx"), ("tflite", "model.tflite"), ("ncnn", "model.param")):
        path = tmp_path / filename
        path.write_bytes(engine.encode())
        exported_files.append((engine, path))

    manifest = build_candidate_manifest(SOURCE_MODEL, exported_files, input_size=960)

    assert manifest["modelVersion"] == "visible-fire-wechat-best2-20260728"
    assert manifest["source"]["sha256"] == sha256_file(SOURCE_MODEL)
    assert manifest["source"] == {
        "name": "visible-fire-wechat-best2-20260728.pt",
        "sha256": "957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650",
    }
    assert manifest["classes"] == ["fire", "smoke"]
    assert manifest["input"] == {
        "width": 960,
        "height": 960,
        "channels": 3,
        "colorSpace": "RGB",
        "normalization": {"scale": 0.00392156862745098, "mean": [0, 0, 0], "std": [1, 1, 1]},
    }
    assert manifest["postprocess"] == {
        "confidenceThreshold": 0.25,
        "iouThreshold": 0.7,
        "outputLayout": "xywh, class scores; postprocess with NMS",
    }
    assert set(manifest["exporter"]) == {
        "python", "torch", "ultralytics", "onnx", "onnxslim", "tensorflow", "onnx2tf", "ncnn", "pnnx",
    }
    assert {item["engine"] for item in manifest["candidates"]} == {"onnx", "tflite", "ncnn"}
    assert all(item["sha256"] for item in manifest["candidates"])
    assert all(artifact["sha256"] for item in manifest["candidates"] for artifact in item["artifacts"])


def test_visible_manifest_rejects_an_unapproved_checkpoint(tmp_path):
    """Catches an export of a similarly named but non-production visible checkpoint."""
    unapproved_model = tmp_path / "visible-fire-wechat-best2-20260728.pt"
    unapproved_model.write_bytes(b"not the deployed checkpoint")
    exported_files = []
    for engine in ("onnx", "tflite", "ncnn"):
        artifact = tmp_path / f"model.{engine}"
        artifact.write_bytes(engine.encode())
        exported_files.append((engine, artifact))

    with pytest.raises(ValueError, match="SHA-256"):
        build_candidate_manifest(unapproved_model, exported_files, input_size=960)


def test_visible_model_validation_rejects_classes_outside_production_contract():
    """Catches a checkpoint that has the approved bytes but incompatible class labels at export time."""
    with pytest.raises(ValueError, match="classes"):
        mobile_model.validate_production_visible_model(SOURCE_MODEL, ["fire"])


def test_visible_manifest_rejects_an_empty_class_list(tmp_path):
    """Catches an exporter bypassing class validation with a false-y empty list."""
    exports = []
    for engine in ("onnx", "tflite", "ncnn"):
        artifact = tmp_path / f"model.{engine}"
        artifact.write_bytes(engine.encode())
        exports.append((engine, artifact))

    with pytest.raises(ValueError, match="classes"):
        build_candidate_manifest(SOURCE_MODEL, exports, input_size=960, class_names=[])


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

    first_manifest = build_candidate_manifest(SOURCE_MODEL, first_exports, input_size=960)
    second_manifest = build_candidate_manifest(SOURCE_MODEL, second_exports, input_size=960)

    assert [candidate["sha256"] for candidate in first_manifest["candidates"]] == [
        candidate["sha256"] for candidate in second_manifest["candidates"]
    ]


def test_candidate_manifest_excludes_interpreter_bytecode_from_ncnn_hashes(tmp_path):
    """Catches non-reproducible machine-local Python bytecode entering an NCNN candidate hash."""
    exports = []
    for engine in ("onnx", "tflite"):
        artifact = tmp_path / f"model.{engine}"
        artifact.write_bytes(engine.encode())
        exports.append((engine, artifact))
    ncnn = tmp_path / "model_ncnn"
    (ncnn / "__pycache__").mkdir(parents=True)
    (ncnn / "model.ncnn.param").write_bytes(b"ncnn")
    (ncnn / "__pycache__" / "model.cpython-311.pyc").write_bytes(b"machine-local bytecode")
    exports.append(("ncnn", ncnn))

    manifest = build_candidate_manifest(SOURCE_MODEL, exports, input_size=960)

    ncnn_paths = {artifact["path"] for candidate in manifest["candidates"] if candidate["engine"] == "ncnn" for artifact in candidate["artifacts"]}
    assert ncnn_paths == {"model_ncnn/model.ncnn.param"}


def test_candidate_manifest_rejects_duplicate_engine_entries(tmp_path):
    """Catches a manifest with multiple files posing as the same export engine."""
    exports = []
    for engine in ("onnx", "onnx", "tflite", "ncnn"):
        artifact = tmp_path / f"model-{len(exports)}.{engine}"
        artifact.write_bytes(engine.encode())
        exports.append((engine, artifact))

    with pytest.raises(ValueError, match="exactly one"):
        build_candidate_manifest(SOURCE_MODEL, exports, input_size=960)


def test_write_json_atomically_replaces_complete_manifest(tmp_path):
    output = tmp_path / "model-candidates.json"
    output.write_text('{"old": true}\n', encoding="utf-8")

    write_json_atomically(output, {"new": True})

    assert json.loads(output.read_text(encoding="utf-8")) == {"new": True}
    assert not list(tmp_path.glob(".model-candidates.json.*.tmp"))


def test_benchmark_manifest_uses_stable_ids_and_hashes_without_private_source_paths(tmp_path):
    image = tmp_path / "thermal frame.jpg"
    image.write_bytes(b"image bytes")
    label = tmp_path / "thermal frame.txt"
    label.write_text("", encoding="utf-8")

    manifest = build_benchmark_manifest(
        samples=[
            (
                "fire-small-001",
                ["fire", "small-target"],
                image,
                label,
                [{"class": 0, "x": 0.5, "y": 0.5, "width": 0.2, "height": 0.2}],
            )
        ],
        seed=20260730,
    )

    sample = manifest["samples"][0]
    assert manifest["seed"] == 20260730
    assert sample["id"] == "fire-small-001"
    assert sample["tags"] == ["fire", "small-target"]
    assert sample["imageSha256"] == hashlib.sha256(b"image bytes").hexdigest()
    assert sample["labelSha256"] == hashlib.sha256(b"").hexdigest()
    assert sample["expectedBoxes"] == [{"class": 0, "x": 0.5, "y": 0.5, "width": 0.2, "height": 0.2}]
    assert "sourcePath" not in sample
    assert "labelPath" not in sample
    assert str(tmp_path) not in json.dumps(manifest)


def test_benchmark_set_requires_each_visible_gate_category(tmp_path):
    """Catches benchmark datasets that omit a required real-world visible-light gate category."""
    dataset = tmp_path / "source-visible-validation"
    image = dataset / "images" / "val" / "fire.jpg"
    label = dataset / "labels" / "val" / "fire.txt"
    image.parent.mkdir(parents=True)
    label.parent.mkdir(parents=True)
    image.write_bytes(b"visible fire")
    label.write_text("0 0.5 0.5 0.2 0.2\n", encoding="utf-8")
    (dataset / "data.yaml").write_text("val: images/val\n", encoding="utf-8")
    (dataset / "benchmark-tags.json").write_text(
        json.dumps({"samples": [{"id": "fire-001", "image": "images/val/fire.jpg", "tags": ["fire"]}]}),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="required categories"):
        build_benchmark_set(dataset, tmp_path / "output", seed=20260730)

    assert set(mobile_model.REQUIRED_BENCHMARK_TAGS) == {
        "fire", "smoke", "hard-negative-orange-red", "night-dark", "small-target", "zoomed-roi",
    }


def test_benchmark_set_rejects_a_path_traversal_sample_id(tmp_path):
    """Catches a tag manifest writing benchmark images outside its output directory."""
    dataset = tmp_path / "source-visible-validation"
    (dataset / "benchmark-tags.json").parent.mkdir(parents=True)
    (dataset / "benchmark-tags.json").write_text(
        json.dumps({"samples": [{"id": "../../escaped", "image": "images/val/fire.jpg", "tags": ["fire"]}]}),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="safe filename"):
        mobile_model._load_benchmark_tags(dataset)


def test_benchmark_set_rejects_tags_that_misrepresent_visible_categories(tmp_path):
    """Catches a positive fire frame falsely claiming smoke and hard-negative coverage."""
    dataset = tmp_path / "source-visible-validation"
    image = dataset / "images" / "val" / "fire.jpg"
    label = dataset / "labels" / "val" / "fire.txt"
    image.parent.mkdir(parents=True)
    label.parent.mkdir(parents=True)
    image.write_bytes(b"visible fire")
    label.write_text("0 0.5 0.5 0.2 0.2\n", encoding="utf-8")
    (dataset / "data.yaml").write_text("val: images/val\n", encoding="utf-8")
    (dataset / "benchmark-tags.json").write_text(
        json.dumps({"samples": [{
            "id": "fire-001",
            "image": "images/val/fire.jpg",
            "tags": ["fire", "smoke", "hard-negative-orange-red", "night-dark", "small-target", "zoomed-roi"],
        }]}),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="do not match labels"):
        build_benchmark_set(dataset, tmp_path / "output", seed=20260730)


def test_build_benchmark_set_rejects_missing_labeled_validation_data(tmp_path):
    with pytest.raises(ValueError, match="dataset YAML"):
        build_benchmark_set(tmp_path / "missing", tmp_path / "output")


def test_build_benchmark_set_does_not_allow_smaller_counts_or_a_different_seed(tmp_path):
    with pytest.raises(TypeError):
        build_benchmark_set(tmp_path / "missing", tmp_path / "output", 1, 1, 20260727)


@pytest.mark.parametrize("label_contents", [
    "2 0.5 0.5 0.2 0.2\n",
    "-1 0.5 0.5 0.2 0.2\n",
    "0 nan 0.5 0.2 0.2\n",
    "0 0.5 0.5 0 0.2\n",
    "0 0.5 0.5 0.2 1.1\n",
    "0 0.1 0.5 0.3 0.2\n",
])
def test_read_yolo_boxes_rejects_invalid_visible_contract_labels(tmp_path, label_contents):
    """Catches labels that could falsify fire/smoke benchmark category semantics."""
    label = tmp_path / "invalid.txt"
    label.write_text(label_contents, encoding="utf-8")

    with pytest.raises(ValueError, match="Invalid YOLO label"):
        mobile_model._read_yolo_boxes(label)


def test_validation_images_rebases_windows_dataset_path_to_staged_root(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    dataset = Path("source-validation")
    image = dataset / "images" / "val" / "frame.jpg"
    label = dataset / "labels" / "val" / "frame.txt"
    image.parent.mkdir(parents=True)
    label.parent.mkdir(parents=True)
    image.write_bytes(b"thermal frame")
    label.write_text("0 0.5 0.5 0.2 0.2\n", encoding="utf-8")
    (dataset / "data.yaml").write_text(
        "path: E:/uavfire-training/thermal/datasets/thermal-fireman-multiclass-gt-20260709\n"
        "val: images/val\n",
        encoding="utf-8",
    )

    images = _validation_images(dataset)

    assert images == [(
        image.resolve(),
        label.resolve(),
        [{"class": 0, "x": 0.5, "y": 0.5, "width": 0.2, "height": 0.2}],
    )]


def test_visible_export_script_runs_directly_from_project_root():
    project_root = Path(__file__).parents[1]

    result = subprocess.run(
        [sys.executable, "scripts/export_mobile_visible_model.py", "--help"],
        cwd=project_root,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0, result.stderr


def test_exporter_uses_the_current_virtualenv_yolo_executable():
    import importlib.util

    script = Path(__file__).parents[1] / "scripts" / "export_mobile_visible_model.py"
    spec = importlib.util.spec_from_file_location("export_mobile_visible_model", script)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)

    assert module.yolo_executable() == Path(sys.executable).with_name("yolo")


def test_exporter_stages_outputs_away_from_the_deployed_checkpoint(tmp_path, monkeypatch):
    import importlib.util

    script = Path(__file__).parents[1] / "scripts" / "export_mobile_visible_model.py"
    spec = importlib.util.spec_from_file_location("export_mobile_visible_model", script)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    source = tmp_path / "deployed.pt"
    source.write_bytes(b"checkpoint")
    output = tmp_path / "mobile-model"

    def create_staged_onnx(command, check, *, cwd):
        staged_model = Path(next(argument.split("=", 1)[1] for argument in command if argument.startswith("model=")))
        staged_model.with_suffix(".onnx").write_bytes(b"onnx")

    monkeypatch.setattr(module.subprocess, "run", create_staged_onnx)

    exported = module._export(source, output, "onnx", simplify=True)

    assert exported == output / "deployed.onnx"
    assert exported.read_bytes() == b"onnx"
    assert not source.with_suffix(".onnx").exists()
    assert not list(output.glob(".export-staging-*"))


def test_exporter_runs_ultralytics_inside_the_staging_directory(tmp_path, monkeypatch):
    import importlib.util

    script = Path(__file__).parents[1] / "scripts" / "export_mobile_visible_model.py"
    spec = importlib.util.spec_from_file_location("export_mobile_visible_model", script)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    source = tmp_path / "deployed.pt"
    source.write_bytes(b"checkpoint")
    output = tmp_path / "mobile-model"
    captured = {}

    def create_staged_onnx(command, check, *, cwd):
        captured["cwd"] = Path(cwd)
        staged_model = Path(next(argument.split("=", 1)[1] for argument in command if argument.startswith("model=")))
        staged_model.with_suffix(".onnx").write_bytes(b"onnx")

    monkeypatch.setattr(module.subprocess, "run", create_staged_onnx)

    module._export(source, output, "onnx")

    assert captured["cwd"].parent == output
    assert captured["cwd"].name.startswith(".export-staging-")


def test_exporter_does_not_copy_python_bytecode_from_ncnn_staging(tmp_path, monkeypatch):
    """Catches PNNX cache files being preserved as a non-reproducible NCNN artifact."""
    import importlib.util

    script = Path(__file__).parents[1] / "scripts" / "export_mobile_visible_model.py"
    spec = importlib.util.spec_from_file_location("export_mobile_visible_model", script)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    source = tmp_path / "deployed.pt"
    source.write_bytes(b"checkpoint")
    output = tmp_path / "mobile-model"

    def create_staged_ncnn(command, check, *, cwd):
        staged_model = Path(next(argument.split("=", 1)[1] for argument in command if argument.startswith("model=")))
        ncnn = staged_model.parent / f"{staged_model.stem}_ncnn_model"
        (ncnn / "__pycache__").mkdir(parents=True)
        (ncnn / "model.ncnn.param").write_bytes(b"ncnn")
        (ncnn / "__pycache__" / "model.cpython-311.pyc").write_bytes(b"bytecode")

    monkeypatch.setattr(module.subprocess, "run", create_staged_ncnn)

    exported = module._export(source, output, "ncnn")

    assert (exported / "model.ncnn.param").is_file()
    assert not (exported / "__pycache__").exists()


def test_exporter_cleans_staging_when_ultralytics_fails(tmp_path, monkeypatch):
    import importlib.util

    script = Path(__file__).parents[1] / "scripts" / "export_mobile_visible_model.py"
    spec = importlib.util.spec_from_file_location("export_mobile_visible_model", script)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    source = tmp_path / "deployed.pt"
    source.write_bytes(b"checkpoint")
    output = tmp_path / "mobile-model"

    def fail_export(command, check, *, cwd):
        raise subprocess.CalledProcessError(1, command)

    monkeypatch.setattr(module.subprocess, "run", fail_export)

    with pytest.raises(subprocess.CalledProcessError):
        module._export(source, output, "onnx")

    assert not source.with_suffix(".onnx").exists()
    assert not list(output.glob(".export-staging-*"))


def test_visible_benchmark_cli_accepts_the_mandated_gate_values():
    import importlib.util

    script = Path(__file__).parents[1] / "scripts" / "build_mobile_benchmark_set.py"
    spec = importlib.util.spec_from_file_location("build_mobile_benchmark_set", script)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)

    args = module.parse_arguments([
        "--dataset", "mobile-model/source-validation",
        "--output", "mobile-model/visible-960/benchmark-set",
        "--seed", "20260730",
    ])

    assert args.model == Path("weights/visible-fire-wechat-best2-20260728.pt")
    assert args.seed == 20260730


def test_visible_benchmark_cli_rejects_a_non_mandated_seed():
    import importlib.util

    script = Path(__file__).parents[1] / "scripts" / "build_mobile_benchmark_set.py"
    spec = importlib.util.spec_from_file_location("build_mobile_benchmark_set", script)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)

    with pytest.raises(SystemExit):
        module.parse_arguments([
            "--dataset", "mobile-model/source-validation",
            "--output", "mobile-model/visible-960/benchmark-set",
            "--seed", "20260729",
        ])
