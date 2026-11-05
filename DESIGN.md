# Sruvik — Obsidian Liquid Glass design system

## Product thesis

Sruvik is a local-first creative AI workspace: an on-device image studio, a model library, a private encrypted vault and a set of device-aware utilities. It is not currently a general-purpose language-model assistant. The UI must not imply that SruGen can browse the web, run arbitrary agents, transcribe live voice or answer open-domain questions; its confirmed engine is image generation/editing with explicit unsupported-mode messaging.

## Visual direction

**Obsidian Liquid Glass** combines deep black/charcoal foundations with carefully layered translucent surfaces, restrained violet/cyan reflections, crisp hairline edges and believable depth. Glass is a material role, not the default treatment for every container.

- Canvas: near-black #080A10, with very low-intensity violet/cyan light at the perimeter. No visible grid or large gradient blobs.
- Base surface: #111520; raised surface #171D2A; secondary surface #202838.
- Text: near-white #F0F2F8, secondary #B0B8C9, muted #7E899E.
- Cyan: #75E3F3 for active/focus/action and live instrumentation.
- Violet: #A99BFF for secondary emphasis, selected model context and restrained reflection.
- Semantic states: rose #FF829B for errors/critical conditions; amber #F1B86D for caution; neutral silver for ordinary information.
- Material construction: dark translucent base, subtle top-edge reflection, fine cool border and restrained elevation. Avoid stacked translucent cards, thick glow, fake refraction and blur that reduces legibility.
- Typography: sharp Android sans-serif for all functional content. Larger display scale is reserved for page titles; numbers use stable tabular formatting where useful. Uppercase tracking is restricted to short instrument labels and metadata.

## Surface hierarchy

1. **Canvas / direct content:** conversation text, image output, headings, lists and settings rows sit directly on the background when no enclosure is needed.
2. **Quiet glass:** compact selectors, attachment trays, contextual tool groups and secondary status areas use dark glass with a fine border.
3. **Instrument glass:** only focal controls or precision instruments receive stronger edge reflections and modest depth.
4. **Flat semantic surfaces:** errors, warnings, progress and destructive confirmation use clear state colours and readable text rather than decorative glass.

## Motion

- Use short state transitions for navigation, selection and appearance changes.
- Animate a real value only between valid samples; keep the displayed measurement raw and authoritative.
- No perpetual decorative meter sweeps, pulsing alerts or forced scroll jumps.
- Respect system animation scale and reduced-motion preferences.

## App information architecture (confirmed code)

- **Home:** entry point to SruGen, upscaler, model gallery, Srukrypt and settings.
- **Models / Model detail:** model discovery, device compatibility, install, active-model selection and model provenance/licensing.
- **SruGen:** local image-generation workspace with encrypted conversation history, image attachment, Create/Edit clothes/Remove background modes, model readiness/download progress, cancellation, image viewer, save-to-device and import-to-Srukrypt.
- **Upscaler:** image selection, local Real-ESRGAN processing, background foreground-service lifecycle, result restoration and export.
- **Srukrypt:** encrypted private vault with device credential/biometric access and explicit recovery/reset paths.
- **Device:** platform telemetry and this-app process diagnostics.
- **Settings:** profile, local preferences, memory toggle for SruGen context, vault timeout and privacy controls.
- **Activity:** currently an empty placeholder, not a persisted task-history feature.

## Assistant-product research and transfer decisions (3 Nov 2026)

This is a documented pattern study, not a claim that Sruvik currently implements all of these capabilities. Current SruGen is a local image-generation conversation, not a general language assistant.

| Reference product | Pattern studied | Transfer to Sruvik | Explicit non-transfer |
|---|---|---|---|
| ChatGPT | Unified thread for text, files, images, voice and generated content; simplified Android composer and contextual quick tools | Keep creation context in one chronological workspace; group secondary controls; keep dictation available while composing | Do not add a voice-mode or file-analysis entry until a compatible engine exists |
| Gemini | Android-aware multimodal context, structured rich responses, supervised multi-step task progress with a visible stop/hand-off | Keep real generation progress visible, preserve image context, expose cancel/stop and return paths | No fake agents, background automation or Android-wide integrations |
| Claude | Reading-first long-form content, distinct dictation vs two-way voice, interruptible voice with transcript continuity, artefact workspace for substantial outputs | Keep SruGen status messages open and readable; image outputs have a focused viewer; preserve conversation when opening image/model surfaces | No general markdown/code renderer or artefact editor until a text assistant exists |
| Meta AI | Voice-forward assistant positioning and optional discovery/social layer | Avoid engagement feed; keep private creation and history central | No social discovery feed or public sharing surface |
| Grok | Broad file, voice, image/video and connected-tool capability taxonomy | Organise any future capabilities by user intent and actual backend support | Do not expose model-incompatible uploads or connectors as universal actions |

First-party sources:
- ChatGPT Android/mobile updates and composer simplification: https://help.openai.com/en/articles/6825453-chatgpt-release-notes
- Gemini Live multimodal conversation and Android integrations: https://blog.google/products-and-platforms/products/gemini/new-gemini-app-updates-android/
- Gemini task progress and user stop/hand-off controls: https://blog.google/innovation-and-ai/products/gemini-app/android-multi-step-tasks/
- Claude voice continuity and interruption: https://support.claude.com/en/articles/11101966-use-voice-mode
- Meta AI standalone app and voice-first positioning: https://about.fb.com/news/2025/04/introducing-meta-ai-app-new-way-access-ai-assistant/
- Grok capability overview: https://docs.x.ai/grok/overview

## Obsidian Liquid Glass implementation rules

- Glass is restricted to surfaces where depth clarifies hierarchy: focused tool panels, composer, selected navigation, and instrument housings. The reading canvas and most list content remain flat.
- The current implementation uses dark layered gradients, hairline borders and edge reflections rather than platform blur. This is deliberate: Android cross-window blur is device-dependent and can flash, drop out or cost performance. An opaque charcoal fallback remains legible.
- Violet and cyan are reflections/semantic accents, not large gradient fills. Generated artwork remains distinct from controls.
- The design should remain recognisable with all blur/illumination removed; text contrast and touch states cannot depend on effects.

## Confirmed architecture

- Native Android app built with Kotlin and Jetpack Compose, Material 3, compile SDK 36, target SDK 36 and min SDK 26.
- UI composition is routed from SruvikApp.kt; page implementations live in ui/.
- SruGen uses local model engine adapters, a generation session/service, model catalogue/installer/compatibility guard, encrypted chat store and encrypted attachment persistence.
- Image upscaling uses a dedicated service and pinned LiteRT Real-ESRGAN model.
- Srukrypt storage and vault logic live in security/ and use Android Keystore-backed cryptography and device-auth integration.
- Existing features are preserved; this redesign is an incremental UI/material and telemetry refactor, not an engine or persistence rewrite.

## Instrumentation measurement contract

Every monitor view states scope, unit, source and limitation:

| Display label | Source | Scope / caveat |
|---|---|---|
| Device available RAM | ActivityManager.MemoryInfo.availMem | Android system-wide availability; includes reclaimable memory |
| Estimated in-use RAM | totalMem - availMem | Derived estimate, not a sum of process allocations |
| App PSS | Debug.getMemoryInfo().totalPss | Current Sruvik process only, KiB converted to MiB |
| App CPU share | Delta of Process.getElapsedCpuTime() over monotonic wall time, normalised by core count | Current app process, not device-wide CPU utilisation |
| Data-volume capacity | StatFs(Environment.getDataDirectory()) | /data filesystem capacity, not a precise view of user media storage |
| Battery | ACTION_BATTERY_CHANGED | Battery percentage/state; optional temperature is the battery sensor only |
| Thermal status | PowerManager.getCurrentThermalStatus() on API 29+ | Platform thermal severity, not CPU temperature |
| Network rate | TrafficStats UID byte counters | Sruvik UID only; not total device network throughput |

No third-party process list, total device CPU utilisation, CPU/GPU temperature or hidden OEM counters are claimed. Monitoring is sampled while the Device page is resumed and pauses when it is not visible.

## SruGen information architecture and interaction model

SruGen is a focused creative workspace, not a dashboard. Its primary journey is:

1. **Choose context** — inspect or change the active local model from the compact header control.
2. **Work in a conversation** — prompts, reference images, assistant responses and generated image attachments share one chronological stream.
3. **Refine** — choose Create, Edit clothes or Remove background before composing; attachment and prompt stay together.
4. **Review** — open generated images in the full-screen viewer; inspect model attribution and encrypted-chat status.
5. **Keep or protect** — save a generated attachment to the device or explicitly import it into Srukrypt.
6. **Continue** — return to encrypted conversation history, rename/delete a chat, or start a fresh draft.

### SruGen navigation

- Conversation history is a contextual destination opened from the workspace header.
- Model gallery/detail is a separate destination reached from the compact active-model control.
- The active conversation remains the primary surface; global app navigation and global legal footer are suppressed while SruGen is open.
- Android predictive Back returns from SruGen to its Home launch point. The model library and Srukrypt preserve their SruGen return route when opened contextually.
- Model settings and device performance are secondary, contextual actions—not persistent content panels.

### Content and action hierarchy

- **Primary:** conversation stream and anchored prompt composer.
- **Primary contextual action:** send/generate, enabled only when a prompt or image is present and no generation is running.
- **Secondary:** attach photo / browse files, switch generation mode, inspect active model, start a new chat.
- **Tertiary:** performance sampling, rename/delete history, save/export and Srukrypt import.
- User messages are right-aligned and visually grouped; SruGen responses remain left-aligned and open on the canvas rather than inside nested cards.
- Generated images are treated as message content, not decorative thumbnails. They open the existing zoom viewer and retain the existing save/import actions.

### Responsive and accessibility rules

- Conversation content uses the available vertical space; the composer resizes above the Android IME rather than competing with a global bottom navigation bar.
- Content is width-constrained on large windows and full-width on compact phones.
- Mode choices expose selected state to accessibility services and retain at least 48 dp touch height.
- Model, history, performance, new-chat, attachment, send, stop, save and import actions have descriptive accessible labels.
- No fixed-height conversation content; text and messages may wrap and scroll under large font scales.

### SruGen release QA matrix

The following are required device checks for this workspace; CI compilation alone does not establish visual or interaction quality:

- Compact portrait (320–360dp): verify title/model label remain legible and secondary actions remain available in overflow.
- Standard portrait (360–430dp): verify direct performance and new-chat actions do not crowd the header.
- Landscape and medium/expanded windows: verify the 920dp content measure, message wrapping and composer width.
- Keyboard closed/open: verify the composer remains visible above the IME, the latest message remains reachable and no system navigation inset is doubled.
- Long conversation and multiple image attachments: verify lazy scrolling, zoom viewer entry, save-to-device and Srukrypt import.
- Large font scale and TalkBack: verify no clipping, logical focus order, announced mode selection and labelled controls.
- Generation idle/running/completed/failed/cancelled: verify send disabled state, progress, stop action and recovery messaging.
- Model absent/downloading/installed: verify the model notice, progress/cancel, model gallery return route and active-model state.

Physical-device and screenshot checks must be recorded separately from automated build/test results.

## Interaction and states

- Every visible control must do something real. Do not display unavailable editing modes as though they work.
- Design empty, loading, failure, installed and offline states as part of the screen—not as afterthoughts.
- Drafts are ephemeral. A SruGen conversation enters encrypted history only after the user sends at least one text or image message.
- Keep the composer anchored while the conversation scrolls. Preserve image-picker access and encrypted attachment previews.
- Respect device size, font scaling, keyboard insets and touch target sizes.

## Anti-template review

Before shipping a screen, ask:
1. Which decision is specific to Sruvik rather than any AI app?
2. What is the user's main task on this screen, and what can be removed?
3. Does every graphic or effect communicate hierarchy, state or content?
4. Have the empty, loading, error and populated states all been opened and checked?
5. Does the screen still have character if its logo is hidden?

Avoid defaulting to purple/blue glow gradients, three equal feature cards, decorative icon-in-rounded-square patterns, nested glass cards, generic hype copy, and controls that only look interactive.

## References consulted

These are references for interaction and implementation principles, not templates to copy:

- [LightCraft for Android — EditorScreen.kt](https://github.com/artcraft-android/lightcraft-android/blob/main/apps/lightcraft-android/android/app/src/main/java/ai/storyteller/lightcraft/ui/EditorScreen.kt): work surface first, slim top controls, task tools anchored at the edge, and layouts that adapt to portrait/landscape.
- [Jetpack Compose Samples — Jetsnack Theme.kt](https://github.com/android/compose-samples/blob/main/Jetsnack/app/src/main/java/com/example/jetsnack/ui/theme/Theme.kt): an explicit, named palette instead of scattered one-off colors.
- [Jetpack Compose Samples — Jetchat Conversation.kt](https://github.com/android/compose-samples/blob/main/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/Conversation.kt): conversation state, anchored input and deliberate keyboard/navigation insets.
- [02UI — How to reduce AI slop in web design](https://02ui.com/blog/how-to-reduce-ai-slop-in-web-design/): use multiple references by extracting the principle from each; avoid blindly copying a single example because of design fixation.
- [miqdadbadjuber/anti-slop](https://github.com/miqdadbadjuber/anti-slop/blob/main/antislop.md): purpose-test every technique, and judge functional completeness, content-driven composition and resilience alongside appearance.
