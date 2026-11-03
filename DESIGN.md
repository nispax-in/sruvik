# Sruvik visual direction

## Point of view

Sruvik is a **field studio for ideas**: part photographic darkroom, part instrument bench, part private notebook. Its interface should feel authored, tactile and useful—not like a template for a generic AI product.

The visual language is called **Fieldwork**. It uses the contrast of a darkroom (deep ink surfaces), a marked-up proof sheet (warm bone text), and the labels on an instrument (small technical annotations). The lime and oxide accents are functional inks, not decoration.

## Palette

| Token | Hex | Use |
|---|---|---|
| Ink | #121710 | Main canvas |
| Bench | #1B2219 | Working surfaces and composer |
| Rule | #30392C | Hairlines and section divisions |
| Bone | #E8E5D8 | Primary reading text |
| Field grey | #AEB49F | Secondary reading text |
| Calibration lime | #D5E58B | Selected tools, active state, primary action |
| Oxide | #C77B5D | Index numbers, warnings, secondary emphasis |

## Type

- Use Sruvik's existing editorial serif (SruvikType.display / MaterialTheme.typography.displayLarge) for page titles and short statements.
- Use the existing sans family for reading copy and controls.
- Small uppercase tracking is reserved for instrument labels, metadata, status and section indices. Never use it for paragraphs.
- Keep a strong size contrast between title, reading copy and labels. Avoid gradient text.

## Composition

- Put the work first. In creative tools, the image or conversation is the canvas; controls should orbit it.
- Prefer one strong editorial composition over rows of interchangeable cards.
- Use numbered sections, hairline rules, offset alignments, open space and real app artwork to establish rhythm.
- Keep the primary action close to the work. Keep secondary actions in a restrained toolbar.
- Use a flat surface when it is enough. Rounded cards, translucent glass and gradients are not defaults.
- Use image overlays only when they preserve text contrast over actual imagery. No ambient gradient blobs.
- Tool selectors should read like a workbench index, not a row of generic pills.

## SruGen information architecture and interaction model

SruGen is a focused creative workspace, not a dashboard. Its primary journey is:

1. **Choose context** — inspect or change the active local model from the compact header control.
2. **Work in a conversation** — prompts, reference images, assistant responses and generated image attachments share one chronological stream.
3. **Refine** — choose Create, Edit clothes or Remove background before composing; attachment and prompt stay together.
4. **Review** — open generated images in the full-screen viewer; inspect the model attribution and encrypted-chat status.
5. **Keep or protect** — save a generated attachment to the device or explicitly import it into Srukrypt.
6. **Continue** — return to encrypted conversation history, rename/delete a chat, or start a fresh draft.

### SruGen navigation

- Conversation history is a contextual destination opened from the workspace header.
- Model gallery/detail is a separate destination reached from the compact active-model control.
- The active conversation remains the primary surface; global app navigation and global legal footer are suppressed while SruGen is open.
- Android system Back returns from SruGen to its Home launch point. Dialogs and the image viewer retain their own higher-priority dismissal behaviour.
- Model settings and device performance are secondary, contextual actions—not persistent content panels.

### Content and action hierarchy

- **Primary:** conversation stream and anchored prompt composer.
- **Primary contextual action:** send/generate, enabled only when a prompt or image is present and no generation is running.
- **Secondary:** attach photo / browse files, switch generation mode, inspect active model, start a new chat.
- **Tertiary:** performance sampling, rename/delete history, save/export and Srukrypt import.
- User messages are right-aligned and visually grouped; SruGen responses remain left-aligned and open on the canvas rather than inside nested cards.
- Generated images are treated as message content, not decorative thumbnails. They open the existing zoom viewer and retain the existing save/import actions.

### Responsive and accessibility rules

- Conversation content uses the available vertical space; the composer resizes above the Android IME rather than competing with a global bottom dock.
- Content is width-constrained on large windows and full-width on compact phones.
- Mode choices expose selected state to accessibility services and retain at least 48 dp touch height.
- Model, history, performance, new-chat, attachment, send, stop, save and import actions have descriptive accessible labels.
- No fixed-height conversation content; text and messages may wrap and scroll under large font scales.

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
