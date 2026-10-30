# Image upscaler model and runtime

Sruvik's image restoration feature uses Qualcomm's Real-ESRGAN-General-x4v3 model through LiteRT on Android.

## Model delivery
The model is fetched from Qualcomm AI Hub's versioned v0.65.0 public TFLite float export during CI and placed in `app/src/main/assets/realesr-general-x4v3.tflite`. The model binary is intentionally not committed to the source repository. For a local build, run:

```bash
bash scripts/fetch-upscaler-model.sh
```

## Runtime
Inference uses LiteRT CompiledModel through Google Play services, CPU-only in this release. The model is bundled in the APK; Google Play services may prepare its LiteRT runtime component on first use. The app does not send photos to a Sruvik server and adds no app-side internet permission.

Model calls and native tensor handles are confined to one worker thread. Tiles are processed sequentially with a context border; cancellation and Android thermal state are checked between tiles. A memory budget based on the device's memory class and current free memory limits input scaling and final image dimensions. Low-memory devices use a stricter cap.

## Model contract
Qualcomm documents a 128x128 input and a 4x output scale. The model recipe uses RGB float input normalized to [0,1]. The 2x mode uses the learned 4x prediction and downsamples the tile output. This is still AI inference, but native 4x output is the highest-fidelity mode.

## Notes
Performance varies by chipset. CPU-only is chosen as the conservative initial backend to avoid OEM GPU driver differences. If the runtime or model is unavailable, the app offers a clearly-labelled quick resize fallback; that fallback does not reconstruct image details.
