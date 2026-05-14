#!/usr/bin/env bash
set -euo pipefail

MODEL_DIR="${MODEL_DIR:-/tmp/uavfire-models}"
MODEL_URL="${MODEL_URL:-https://huggingface.co/SalahALHaismawi/yolov26-fire-detection/resolve/main/best.pt}"
MODEL_FILE="${MODEL_FILE:-yolov26-fire-detection-best.pt}"
OUTPUT_PATH="${MODEL_DIR}/${MODEL_FILE}"

mkdir -p "${MODEL_DIR}"

if [[ ! -s "${OUTPUT_PATH}" ]]; then
  curl -L "${MODEL_URL}" -o "${OUTPUT_PATH}"
fi

echo "${OUTPUT_PATH}"
