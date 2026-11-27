# Glide - personal Android source

A typography-led launcher and media interface for the owner's LG G6. Public source is available; no public APK is supplied. The installed private companion is **1.12.1-transcription-preview / 11009**, with partial app verification. It is not a fully accepted stable release.

## Implemented

- Local music library, Now Playing, queue, playback controls, themes, wired/Bluetooth connection handling and an explicitly simulated connection-animation preview.
- Deterministic single-photo enhancement and Original/Enhanced comparison, with save-copy output. Single-photo processing is limited to 2048 px; burst processing to 1600 px.
- Bounded small-mark pixel repair on a copy up to 1024 px, using surrounding pixels. It is not neural inpainting or semantic object reconstruction. Device export/pixel acceptance remains unverified.
- Offline English transcription through Vosk 0.3.75 and a separately imported, hash-pinned model. Inputs are PCM16 WAV files, mono/stereo, 8-48 kHz, up to 30 seconds and 8 MiB. No microphone recording, cloud inference, background listening or transcript telemetry is added.

Question answering, neural enhancement, semantic object removal and image generation are not implemented. See [phone-only AI status](docs/PHONE_ONLY_AI.md).

## Verification and limits

The owner-host private companion passed 53 normal Gradle unit tests, zero failures/skips, release assembly and signing verification. Lint recorded zero errors, 127 warnings and two hints. Seven Android instrumentation methods compiled; they were not executed.

An isolated debug harness on the LG G6 recognized one licensed four-word fixture exactly in three repeated loads. Median end-to-end time was 3,559 ms for 2.78625 seconds of audio; maximum sampled PSS was 194,420 KiB. This does not establish broad accuracy, real-time performance or release-app latency. The actual installed app passed model import, short-fixture transcription, invalid-input preservation, picker cancellation, processing cancellation and dialog close/reopen.

Photo toggle/export and repaired-pixel acceptance remain **UNVERIFIED** because bounded accessibility automation could not observe the required state. Real accessory coverage, natural-photo quality, active-playback coexistence and controlled performance gains also remain unverified. No further speed/RAM improvement is claimed. See [device verification](docs/DEVICE_VERIFICATION_11009.json) and [release status](RELEASE_STATUS.md).

## Build and personal fonts

Android 9/API 28 minimum; compile/target SDK 37; Gradle 9.6.0 and Android Gradle Plugin 9.5.1. Use the pinned local Vosk/JNA AARs and model ZIP described in [offline transcription](docs/OFFLINE_TRANSCRIPTION.md). The model is required by integrity tests but is never bundled or downloaded automatically.

The owner's Zegoe resource references are preserved. Restricted font binaries stay out of Git. Supply your own licensed local files per [FONT_SETUP.md](FONT_SETUP.md); the build checks for them and never silently substitutes another font. Public source is unsigned. Configure signing locally; a differently signed build cannot update the owner's installed app.

Use the existing documented Gradle tasks with --no-configuration-cache, --add-opens=java.base/java.util=ALL-UNNAMED on the Gradle JVM, -Pkotlin.compiler.execution.strategy=in-process and -PglideSpeechInputs pointing to the verified local input directory: :app:testDebugUnitTest :app:lintDebug :app:assembleRelease.

Original code is MIT. Dictionary content is separately CC BY-SA 4.0; dependencies and fonts retain their own notices. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [PRIVACY.md](PRIVACY.md). Keys, weights, runtime AARs, private media, restricted fonts and private Git history are excluded.
