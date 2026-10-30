# Image upscaler model and runtime

Sruvik uses the BSD-3-Clause Real-ESRGAN General x4v3 weights re-authored as a LiteRT-native FP16 model by LiteRT Community.

## Model delivery
The 3.55 MB model file is downloaded from pinned Hugging Face revision `1017caa` during CI and SHA-256 verified before packaging. The binary is intentionally not committed to the source repository. For a local build, run `bash scripts/fetch-upscaler-model.sh`.

## Runtime and privacy
Inference uses LiteRT CompiledModel through Google Play services, CPU-only in this release. The model is bundled in the APK; Google Play services may prepare its LiteRT runtime component on first use. The app has no Internet permission and does not upload photos.

## Model contract
Input: `[1,128,128,3]` NHWC, RGB float, range [0,1].
Output: `[1,3,512,512]` NCHW, RGB float, range [0,1].
The model reconstructs at 4x. The 2x option downsamples its learned 4x output.

## Safety
A preflight memory check rejects work when Android reports low memory or less than 400 MB free. Output dimensions and pixel count are bounded using current available memory. The app processes one overlapping tile at a time, checks cancellation and Android thermal state between tiles, and yields briefly at moderate thermal status. Native runtime handles are confined to one worker thread and closed after the task.

CPU inference is intentionally selected for the first release to avoid OEM GPU-driver incompatibilities. Performance is hardware-dependent; published measurements show substantial differences between GPU and CPU runtimes. If the AI model/runtime cannot run, a clearly labelled standard resize fallback is offered; it is not presented as AI.
