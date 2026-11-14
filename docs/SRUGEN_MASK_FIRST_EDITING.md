# SruGen mask-first local editing

## What is in this branch

- The existing SelfieMulticlass model identifies hair (class 1) and clothing (class 4).
- MediaPipe Hand Landmarker supplies 21 landmarks per detected hand. The new hand mask planner requires one explicitly named finger; when two hands are visible, it also requires left/right to be specified.
- A shared compositor blends the local generated crop only where the edit alpha mask is non-zero. A zero-alpha output pixel is copied exactly from the original pixel array.
- Unit tests exercise the zero-alpha and outside-crop preservation guarantees.

## Important limitation: generation is not yet true latent inpainting

The pinned Nezumi MNN JNI generate call accepts an init RGB image and a denoise strength. It does not accept a mask tensor. Its native C API currently validates and runs image-to-image, not the 9-channel Stable Diffusion inpainting UNet path. Therefore this branch constrains the final composite, but the model can still hallucinate content within the selected region, and it is not equivalent to masked latent diffusion.

The correct next native milestone is a separately catalogued SD 1.5 inpainting checkpoint, a mask-aware input contract, and a pipeline that encodes the masked image, supplies the mask and masked-image latent to the inpainting UNet, and composites/decodes at the target crop. Do not pass a conventional 4-channel img2img checkpoint through that API and call it inpainting.

## Target-region requirements

- Hair edits need an enlarged halo around the current hair silhouette so new hair can grow into nearby background. Face, skin and non-hair pixels remain protected by the final compositor.
- Finger edits require a single finger target and unambiguous hand selection. The landmark path is a first-pass region locator; hand segmentation plus an editable silhouette halo and anatomy checks are still needed for production-quality finger length/shape edits.
- Ambiguous or multiple-finger requests must fail closed rather than guess.

## Offline/privacy

Image pixels, landmarks and masks are processed locally. The hand landmark task is downloaded during the build and packaged as an app asset. No photo upload or remote inference is introduced.
