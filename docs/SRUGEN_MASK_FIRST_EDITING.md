# SruGen mask-first local editing

## Local vision and region selection

- Clothing and person regions use MediaPipe Selfie Multiclass segmentation (the app's existing local model; hair class 1, clothing class 4).
- Hair edits use Google's dedicated 512 × 512 Hair Segmenter model (binary background/hair categories), downloaded at a fixed version and SHA-256 verified during the Android build.
- Finger edits use MediaPipe Hand Landmarker, which yields 21 landmarks and handedness per detected hand. Only one named finger is accepted. If two hands are visible, an explicit left/right hand selection is required and checked against the model's handedness confidence.
- Hair masks receive an editable halo around the detected silhouette so a new hairstyle can extend beyond the current hairline. The segmentation model is designed primarily for front-facing selfie-like imagery; occlusion, headwear, very fine braids and unusual viewpoints can reduce mask quality.
- The finger mask is a first-pass landmark-guided region, not a full hand/finger segmentation model. It should be used with a clear, close photo and a single visible hand. Anatomical validation and user-adjustable mask painting remain future improvements.

## Exact pixel-preservation boundary

The shared compositor applies a generated crop only through the local edit alpha mask. Pixels with zero mask alpha are copied directly from the original pixel array, without colour conversion or blending. The JVM tests verify that zero-alpha pixels and all pixels outside a crop remain unchanged. Pixels within the feathered mask are intentionally allowed to blend.

## Important limitation: generation is not yet true latent inpainting

The pinned Nezumi MNN JNI generate call accepts an init RGB image and a denoise strength. It does not accept a mask tensor. Its native C API currently runs image-to-image, not the 9-channel Stable Diffusion inpainting UNet path. This branch therefore constrains the final composite, but the model can still hallucinate content inside the selected region; it is not equivalent to masked latent diffusion.

For true inpainting, the next native milestone is a separately catalogued SD 1.5 inpainting checkpoint and a mask-aware native pipeline that encodes the masked source, supplies the mask and masked-image latent to the 9-channel inpainting UNet, and decodes/composites the result at the target crop. A standard 4-channel img2img checkpoint must not be described as an inpainting model.

## Offline/privacy

Image pixels, landmarks and masks are processed locally. Hair and hand model assets are downloaded and checksum-verified during the build, then packaged in the APK. No photo upload or remote inference is introduced.
