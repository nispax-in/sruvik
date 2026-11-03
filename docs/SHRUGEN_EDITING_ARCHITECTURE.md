# ShruGen editing architecture audit

Status: repository/source audit plus first incremental implementation. This document does not claim physical-device validation.

## Verified current stack

- UI: Jetpack Compose in `SruGenPage.kt`; encrypted chat/attachment persistence via `SruGenChatStore`; foreground generation ownership via `SruGenGenerationService`.
- Engine routing: `SruGenEngineFactory` selects MediaPipe SD 1.5 or Nezumi Kiln MNN SD 1.5 based on the active gallery model.
- Primary photo-edit checkpoint: `majicMIX_realistic_v6`, registry source `emilianJR/majicMIX_realistic_v6`; Android pack `Mouserat/majicMIX_realistic_v6-mnn`, pinned revision `265cbbf`, archive `majicMIX_realistic_v6-int8-block32.zip`, registry SHA-256 `caeceb902b6c01f09c8685d87f712b2107c4e5e6241011efe77c8e11e80990ff`, listed package size about 1.24 GB. Registry identifies it as SD 1.5 / MNN int8 block-32 and requires a VAE encoder for img2img.
- Native adapter: `NezumiMnnSruGenEngine.kt` calls `MnnSdNative.generate` with prompt, dimensions, steps, CFG, seed, scheduler, optional init RGB image and denoise strength. The JNI method has no mask tensor, masked-image latent, ControlNet input or image-reference embedding input. Capability JSON currently exposes img2img but not inpainting.
- Runtime: MNN JNI; current app path deliberately selects CPU, loads at 448 safe max side and generates at up to 512-side SD1.5 dimensions, with 20 steps and device resource checks. Photo edits use a 0.38 img2img denoise strength on a crop, then paste generated pixels through the broad original clothing mask.
- Segmentation: bundled MediaPipe Selfie Multiclass 256x256 TFLite category model; current label mapping is background 0, hair 1, body skin 2, face skin 3, clothes 4, other/accessories 5. It produces semantic categories, not independent garment instances.
- Existing deterministic clothing recolour already uses the clothes mask and preserves source pixels outside the feathered region. Background removal uses the person foreground labels.

## Confirmed capability boundaries

| User task | Current route | Honest status |
|---|---|---|
| Create image | SD 1.5 MediaPipe or MNN, according to installed model | Implemented |
| Clothing colour change | MediaPipe clothes mask + pixel colour transform + composite | Implemented; one broad clothes class, not garment-specific |
| Hair colour change | MediaPipe hair label + pixel colour transform + composite | Incremental implementation added; needs image-set and target-device validation |
| Structural garment replacement | MNN img2img crop + broad clothes-mask composite | Experimental/approximate; not true mask-conditioned inpainting |
| Two overlapping garment layers | No independent garment-instance masks or mask-conditioned generation | Not supported reliably |
| Haircut / hairstyle structure | No dedicated hair synthesis/shape-conditioning route | Not supported |
| Reference-garment virtual try-on | No CatVTON/IDM-VTON/OOTDiffusion runtime or model pack | Not supported locally |
| Object removal | No object-specific mask plus inpainting route | Not supported |

## First implementation slice

1. Route explicit single-region hair-colour prompts to the existing MediaPipe hair class. Apply a deterministic colour transform while retaining source luminance/texture and compositing the original outside the hair mask.
2. Keep clothing colour operations on the clothing class.
3. Do not route hairstyle shape changes through the clothing crop pipeline.
4. Replace the technical layered-outfit refusal with a short user-facing recovery message; preserve the source and prompt.
5. Apply translucent Obsidian material layers to the editor environment, message surface and composer. The Compose blur modifier is not used as a backdrop blur; surfaces use translucent tints, restrained gradients and edge reflections with readable fallbacks.

## Next technically sound work

- Evaluate SCHP or another human parser against the actual Android device. Semantic coat/upper-clothes labels still do not guarantee inner/outer garment instance separation. Add user-guided selection for ambiguous layers rather than guessing.
- For true inpainting, integrate a dedicated SD 1.5 inpainting UNet with verified 9-channel conditioning (latent + mask + masked-image latent) and a runtime that exposes those inputs. The current MNN JNI signature does not. A normal SD 1.5 text-to-image or img2img pack is not a drop-in substitute.
- Prototype the exact 9-channel graph and its conversion separately before changing the production runtime. Keep majicMIX generation intact.
- Treat CatVTON, IDM-VTON and OOTDiffusion as specialist research pipelines, not small Android segmentation helpers. Audit their model/data licenses, dependencies, full model footprint, GPU assumptions and runtime before considering an optional desktop/server route.
- Do not add ControlNet or IP-Adapter to majicMIX until exact base-family, encoder, attention-module and MNN operator compatibility is verified.
- Build a consented local evaluation set with source, prompt, target masks, output and failure labels. Measure mask Dice/boundary F-score, target edit fidelity, unedited-region pixel/LPIPS drift, face/pose/background preservation, cold/warm latency, peak process/native memory, package size and thermal behaviour.
- Test offline with network disabled on the target phone. No new helper model has been downloaded or claimed to work offline by this code change.

## Provenance and validation notes

The pinned archive SHA above is the registry's expected ZIP digest; it is not a hash of a user's installed unpacked checkpoint file. The source repository and conversion pack are separate provenance records. The app must validate the actual installed files at runtime. Build/CI success is not equivalent to physical-device image-quality or performance validation.
