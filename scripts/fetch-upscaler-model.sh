#!/usr/bin/env bash
set -euo pipefail

# Qualcomm AI Hub public, versioned TFLite export of Real-ESRGAN-General-x4v3.
MODEL_URL="https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_general_x4v3/releases/v0.65.0/real_esrgan_general_x4v3-tflite-float.zip"
DEST="app/src/main/assets/realesr-general-x4v3.tflite"
ARCHIVE="$(mktemp)"
trap 'rm -f "$ARCHIVE"' EXIT

mkdir -p "$(dirname "$DEST")"
curl --fail --location --retry 4 --retry-delay 2 "$MODEL_URL" --output "$ARCHIVE"
ENTRY="$(unzip -Z1 "$ARCHIVE" | grep -E '\.tflite$' | head -n 1 || true)"
if [[ -z "$ENTRY" ]]; then
  echo "No TFLite model found in the pinned model archive." >&2
  exit 1
fi
unzip -p "$ARCHIVE" "$ENTRY" > "$DEST"
SIZE="$(stat -c '%s' "$DEST")"
if [[ "$SIZE" -lt 1000000 ]]; then
  echo "Downloaded model is unexpectedly small ($SIZE bytes)." >&2
  rm -f "$DEST"
  exit 1
fi
echo "Model entry: $ENTRY"
echo "Model bytes: $SIZE"
sha256sum "$DEST"