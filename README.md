# Glide for Android — public source preview

A personal Android launcher and media interface with a typography-led Metro-inspired design, local media controls, an accessory connection card and deterministic photo enhancement/comparison.

This is a source preview, not a stable public APK. No neural image restoration, audio denoiser or local language model is included. The installed personal-device build remains separate and unchanged.

## Existing 1.11 work

- Generic Bluetooth A2DP and wired-output recognition, reconnect/duplicate handling, motion-aware connection illustrations and explicitly simulated preview. Connected output availability is not proof of the active music route.
- Single-photo Original/Enhanced comparison with a bounded 768-pixel original preview and save-copy output up to 2048 pixels. Three-frame deterministic alignment/median stacking remains capped at 1600 pixels. This is not neural restoration.
- Existing launcher, library, Now Playing, playlists, themes and optional network features remain in the source. No 20% responsiveness, battery-life or universal frame-rate improvement is claimed.

## Build

Minimum Android API 28 (Android 9). The recorded environment uses Android Gradle Plugin 9.5.1, Gradle 9.6.0, compile/target SDK 37 and Android Studio JBR 25. See `gradle/libs.versions.toml` and the wrapper for exact pins. Configure your installed SDK through Android Studio or a local, untracked `local.properties` file.

```powershell
./gradlew.bat --no-configuration-cache '-Dorg.gradle.jvmargs=-Xmx2048m --add-opens=java.base/java.util=ALL-UNNAMED' '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Use `--offline` only when required dependencies are cached. The release configuration is deliberately **unsigned**. Set up your own release signing locally; never commit keys or passwords. Do not install an unsigned artifact or assume a differently signed public build can update the existing personal-device installation. This repository does not generate or rotate signing keys.

## Licensing and privacy

Public typography uses the already-bundled OFL-licensed Selawik fonts. Restricted Zegoe binaries and personal Git history are excluded from this clean snapshot. Original code is MIT; dictionary content is separately CC BY-SA 4.0. See `THIRD_PARTY_NOTICES.md`, `PRIVACY.md` and `SOURCE_PROVENANCE.json`.

No owner photos, screenshots, APK backups, device dumps, credentials or application-only contact details are included. Optional network features have external providers and must not be described as offline. Review provider terms and supply your own keys where required.

## Verification limits

The private predecessor passed 22 host unit tests and an owner-controlled install. Those observations do not verify public-font layout or a separately signed public binary. See `BUILD_VERIFICATION.json` for this source preview's actual build checks when available. Known-model Bluetooth and the owner photo-comparison response remained untested in the predecessor's final record. Device instrumentation, cold-launch/library-ready timing and reliable controlled performance gains are unverified.
