#!/usr/bin/env bash
set -euo pipefail

MODEL_URL="https://storage.googleapis.com/mediapipe-models/image_segmenter/hair_segmenter/float32/1/hair_segmenter.tflite"
MODEL_SHA256="2628cf3ce5f695f604cbea2841e00befcaa3624bf80caf3664bef2656d59bf84"
TARGET="app/src/main/assets/models/hair_segmenter.tflite"
TEMP="${TARGET}.download"

mkdir -p "$(dirname "$TARGET")"
rm -f "$TEMP"
curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 20 "$MODEL_URL" -o "$TEMP"
printf '%s  %s\n' "$MODEL_SHA256" "$TEMP" | sha256sum --check --status || {
  echo "MediaPipe Hair Segmenter model SHA-256 mismatch; refusing to package it." >&2
  rm -f "$TEMP"
  exit 1
}
mv "$TEMP" "$TARGET"
echo "Verified MediaPipe Hair Segmenter model."
