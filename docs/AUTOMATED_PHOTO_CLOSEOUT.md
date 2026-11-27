# Automated photo verification / transcription boundary

26 November 2026. This continuation changes tests and documentation only. Production source, app version 11008, fonts, signing and the private release APK remain unchanged.

Ten new JVM tests cover clipped corner masks, one-pixel-wide/high images, alpha handling, cancellation during final smoothing, invalid buffers/dimensions/radius, deterministic results and independent returned arrays. These supplement the original 27 tests. They do not establish UI lifecycle correctness.

Four new Android instrumentation tests cover landscape/square preview sizing, a synthetic bitmap repair/JPEG-copy round trip, and unreadable byte input. Together with existing tests, seven instrumentation test methods compile. Compilation does not mean execution. No device test was executed in the managed environment because ADB was inaccessible and no emulator image was installed. Document-picker save failures, actual dialog cancellation/reopening, stale UI result publication, playback interaction, natural-photo quality and peak device memory remain UNVERIFIED.

Release APK assembly/signing and the earlier normal 27-test host pass are preserved. The new 37-test direct JVM result and lint comparison are recorded in the private closeout reports. The original direct launcher still expected 27 tests and initially returned a nonzero status despite all 37 passing; its count guard was corrected to require 37 without relaxing failure/skip checks.

## Transcription

No recognizer, model, transcription button or placeholder was added. Both native Maven/model-host probes failed DNS. This is a blocked implementation, not an AI deployment.

Vosk is a candidate that can avoid a local NDK build through its published Android AAR. The official demo currently names `com.alphacephei:vosk-android:0.3.75` and `net.java.dev.jna:jna:5.18.1@aar`; the upstream model table lists `vosk-model-small-en-us-0.15` under Apache 2.0. Upstream memory/accuracy figures are not G6 measurements. Current device ABI, exact artifact hashes and native CPU/runtime compatibility are not established here. No speech fixture or model was acquired and no WER, latency or RAM result exists.

Official references:
- https://alphacephei.com/vosk/install
- https://alphacephei.com/vosk/models
- https://github.com/alphacep/vosk-android-demo/blob/master/app/build.gradle
- https://central.sonatype.com/artifact/com.alphacephei/vosk-android/0.3.75
- https://github.com/alphacep/vosk-api/blob/master/COPYING

The private host runner performs automated checks and read-only identity inspection. Only after a matching ARM/Android 9 device is established can its fixed, bounded prerequisite downloader run. It verifies Maven-published SHA-256 checksums, hashes the initial official-HTTPS model download, inventories archives without extraction/loading, and retains a persistent acquisition ledger. It does not integrate or execute these dependencies. No model weights, font binaries, APKs, personal media or signing material belong in this repository.
