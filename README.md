# Glide — personal Android source

A typography-led launcher/media interface for the owner's LG G6. This branch is code only. No public APK or store release is being made, and the installed 1.11 app remains unchanged.

## Original fonts preserved

Original Zegoe resource references are retained. Restricted font binaries stay out of public Git. Supply your own licensed local files to compile, as described in [FONT_SETUP.md](FONT_SETUP.md). The build checks for them explicitly and never silently replaces them. Bundled Selawik/OFL assets remain available without changing the personal font choices.

## Implemented

- Existing local library/Now Playing, playback controls, themes, Bluetooth/wired handling and explicitly simulated accessory preview.
- Existing deterministic photo enhancement, Original/Enhanced comparison and save-copy workflow; single-photo limit 2048 px, burst limit 1600 px.
- New **small-mark pixel repair**: select a mark in the preview and repair a copy up to 1024 px. It uses neighbouring pixels and works best on plain backgrounds. Originals remain untouched. Cancellation and previous-preview preservation use the existing workflow. It is not neural inpainting or semantic reconstruction.

## Phone-only AI request

No model is integrated. Question answering, neural enhancement, transcription and image generation remain unimplemented. See [docs/PHONE_ONLY_AI.md](docs/PHONE_ONLY_AI.md). There is no laptop/cloud backend and no fake generation or chatbot button.

## Verification

Companion private preview: 1.12.0-private-preview / 11008. Release assembly, signing verification and 27 direct JUnit tests passed with zero failures/skips; lint has zero errors. All nine original font binaries are unchanged in that private build. [BUILD_VERIFICATION.json](BUILD_VERIFICATION.json) records pending checks. The new repair has not passed device interaction/quality/performance acceptance. No speedup or AI-deployment claim is made.

## Build and licence

Minimum Android 9 / API 28; compile/target SDK 37, pinned Gradle 9.6.0 and Android Gradle Plugin 9.5.1. Configure your own SDK and licensed fonts locally. Public source is unsigned; never commit keys or assume a differently signed build updates the owner's installed app.

```powershell
./gradlew.bat --no-configuration-cache '-Dorg.gradle.jvmargs=-Xmx2048m --add-opens=java.base/java.util=ALL-UNNAMED' '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Original code is MIT. Dictionary content is separately CC BY-SA 4.0; fonts/dependencies retain their notices. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), [PRIVACY.md](PRIVACY.md), and [SOURCE_PROVENANCE.json](SOURCE_PROVENANCE.json). No owner media, device reports, keys or restricted font binaries are published.

## Offline transcription source preview

The continuation adds a bounded English PCM16 WAV transcription dialog using verified, locally supplied Vosk/JNA runtimes. Models are imported separately and never bundled. This is a private candidate, not a device-validated release; see [limits and verification](docs/OFFLINE_TRANSCRIPTION.md). No public APK is supplied.

Build/test with the pinned local dependency directory: `gradle -PglideSpeechInputs=PATH_TO_LOCAL_INPUTS :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`. The directory must contain the two exact AARs and the exact model ZIP listed in the document; normal unit tests validate the real archive. Also supply your own licensed local fonts per FONT_SETUP.md. No dependency/model is downloaded automatically.
