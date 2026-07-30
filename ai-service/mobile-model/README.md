# Visible Mobile Model Artifacts

This directory is intentionally ignored except for this document. The only accepted source for
the RC Plus baseline is `weights/visible-fire-wechat-best2-20260728.pt` with SHA-256
`957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650`. The exporter rejects a
different source hash or class contract; the approved classes are `fire` and `smoke`.

Run the reproducible 960px export from `ai-service`:

```bash
./.venv/bin/python scripts/export_mobile_visible_model.py \
  --model weights/visible-fire-wechat-best2-20260728.pt \
  --input-size 960 \
  --output mobile-model/visible-960
```

The output manifest records RGB normalization (`1/255`, zero mean, unit standard deviation),
confidence/IoU thresholds, raw output layout, exporter versions, and hashes for every ONNX,
TFLite, and NCNN artifact. Recalculate the manifest hash after copying an output; a mismatch is
an invalid candidate.

Stage a labeled visible validation split at `source-visible-validation/`. It must contain one
dataset YAML, matching YOLO labels, and a `benchmark-tags.json` that maps every validation image
to a stable ID and one or more tags. Across the set the required tags are `fire`, `smoke`,
`hard-negative-orange-red`, `night-dark`, `small-target`, and `zoomed-roi`; the build fails if
any is absent. IDs are filename-safe and the build verifies that `fire`/`smoke` tags have class
0/1 labels, hard negatives have no labels, and small targets have a labeled box no larger than
2% of the image. The tag file uses dataset-relative paths only, for example:

```json
{
  "samples": [
    {"id": "fire-small-001", "image": "images/val/fire-001.jpg", "tags": ["fire", "small-target"]}
  ]
}
```

Then create the deterministic benchmark set and PyTorch per-sample baseline:

```bash
./.venv/bin/python scripts/build_mobile_benchmark_set.py \
  --dataset mobile-model/source-visible-validation \
  --output mobile-model/visible-960/benchmark-set \
  --seed 20260730
```

Generated exports, validation data, benchmark images, baseline detections, and JSON manifests
are local evaluation artifacts and must not be committed.
