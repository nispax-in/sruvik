#!/usr/bin/env bash
set -euo pipefail

MODEL_URL="https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite"
MODEL_SHA256="c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0"
TARGET="app/src/main/assets/models/selfie_multiclass_256x256.tflite"
TEMP="${TARGET}.download"

mkdir -p "$(dirname "$TARGET")"
rm -f "$TEMP"
curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 20 "$MODEL_URL" -o "$TEMP"
printf '%s  %s\n' "$MODEL_SHA256" "$TEMP" | sha256sum --check --status || {
  echo "MediaPipe SelfieMulticlass model SHA-256 mismatch; refusing to package it." >&2
  rm -f "$TEMP"
  exit 1
}
mv "$TEMP" "$TARGET"
echo "Verified MediaPipe SelfieMulticlass segmentation model."
