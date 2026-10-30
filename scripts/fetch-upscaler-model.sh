#!/usr/bin/env bash
set -euo pipefail

# LiteRT-native Real-ESRGAN General x4v3 export. Commit and SHA-256 are pinned.
MODEL_URL="https://huggingface.co/litert-community/real-esrgan-x4v3-litert/resolve/1017caa/realesr_general_x4v3.tflite"
EXPECTED_SHA256="6d597b46d812ada42bdde641ec6e78c0ef0b78ff5a825fcfd74b9a1b3bcbe9a4"
DEST="app/src/main/assets/realesr_general_x4v3.tflite"

mkdir -p "$(dirname "$DEST")"
curl --fail --location --retry 4 --retry-delay 2 "$MODEL_URL" --output "$DEST"

SIZE="$(stat -c '%s' "$DEST")"
if [[ "$SIZE" -lt 3000000 ]]; then
  echo "Downloaded model is unexpectedly small ($SIZE bytes)." >&2
  rm -f "$DEST"
  exit 1
fi

echo "$EXPECTED_SHA256  $DEST" | sha256sum --check -
echo "Pinned Real-ESRGAN LiteRT model verified ($SIZE bytes)."