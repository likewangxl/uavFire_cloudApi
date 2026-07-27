# Mobile Model Artifacts

This directory is intentionally ignored except for this document. Generate candidates from
the deployed thermal checkpoint with `scripts/export_mobile_thermal_model.py`. Stage the
labeled validation split at `source-validation/`, then build the deterministic benchmark set
with `scripts/build_mobile_benchmark_set.py` using seed `20260727`.

Generated exports, validation data, benchmark images, baseline detections, and JSON manifests
are local evaluation artifacts and must not be committed.
