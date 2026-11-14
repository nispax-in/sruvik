#!/usr/bin/env bash
set -euo pipefail

MODEL_URL="https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
MODEL_SHA256="fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"
TARGET="app/src/main/assets/models/hand_landmarker.task"
TEMP="${TARGET}.download"

mkdir -p "$(dirname "$TARGET")"
rm -f "$TEMP"
curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 20 "$MODEL_URL" -o "$TEMP"
printf '%s  %s\n' "$MODEL_SHA256" "$TEMP" | sha256sum --check --status || {
  echo "MediaPipe Hand Landmarker model SHA-256 mismatch; refusing to package it." >&2
  rm -f "$TEMP"
  exit 1
}
mv "$TEMP" "$TARGET"
echo "Verified MediaPipe Hand Landmarker model."
