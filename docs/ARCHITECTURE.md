# Sruvik foundation

## Modules and boundaries

The initial app is intentionally a single installable Android module with package-level feature boundaries. Split into Gradle modules only when feature implementation justifies the build complexity.

- `ui/theme`: central palette, typography, shapes and theme tokens
- `ui`: app shell, navigation and screen composition
- Future `core/device`: capability, thermal and memory safeguards
- Future `core/updates`: GitHub Releases metadata and APK update handoff
- Future `feature/narration`, `feature/upscaler`: isolated creative workflows

## Safety defaults

- No broad storage permission; use Android system Photo Picker / document picker when media workflows arrive.
- No network permission until a feature genuinely needs it.
- AI inference must be cancellable and must not run unbounded in the background.
- Model downloads require explicit user action, size disclosure and checksum verification.
- Never silently upload user media.

## Distribution

Use signed APKs attached to GitHub Releases for direct distribution. Keep update checks opt-in and verify release metadata and APK signing identity before offering an update. Google Play can be a separate distribution channel.

## Typography

Edit `ui/theme/Typography.kt` to change the typeface, weights, sizes, tracking and line heights app-wide.
