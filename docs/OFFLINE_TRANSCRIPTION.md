# Offline transcription private preview

Scope: one file-based English transcription feature. No microphone recording, background listening, cloud calls, audio-focus request, playback control, transcript persistence or telemetry is added.

The existing Zune/Metro settings screen gains an Offline transcription action. The dialog imports only the exact verified `vosk-model-small-en-us-0.15.zip`, then accepts local uncompressed PCM16 WAV clips: mono/stereo, 8–48 kHz, at most 30 seconds and 8 MiB. AAC/MP3/float WAV/RF64 are explicitly rejected. Stereo channels are averaged without integer overflow; input is converted to 16 kHz with bounded linear interpolation. This is a simple resampler, not a high-fidelity bandlimited converter; non-16-kHz recognition quality needs measurement. Prefer 16-kHz mono input for evaluation.

The model ZIP is size/hash checked before extraction. Extraction stays within a unique app-private staging directory, has entry/expanded-size bounds, and finalizes only after the expected model files exist. A prior model is preserved until replacement succeeds. Recovery material stays outside disposable staging if restoration fails. Per-file receipts detect subsequent corruption before native model loading.

Only one import/transcription runs at a time across dialog lifetimes. File work is off the main thread; decoding and recognition run off the main thread. Cancellation is checked during reads, hashes and between recognition chunks. Native model load/recognizer calls are not forcibly interrupted: cancellation completes when the current native call returns. Closing the dialog cancels its scope, and `use` closes the recognizer/model. The runtime shared library can remain loaded by Android; that is separate from model resource ownership.

The displayed model-load timing covers the Model constructor only. Recognition timing covers recognizer feed/finalization; neither is end-to-end latency or a RAM measurement. Initial archive verification/import, WAV decode and model-file verification add time. An isolated debug G6 harness has now passed a single four-word fixture three times: median end-to-end 3,559 ms; maximum sampled PSS 194,420 KiB. These are not release-app performance or general accuracy results.

## Exact dependencies

| Input | Bytes | SHA-256 |
|---|---:|---|
| Vosk Android 0.3.75 AAR | 13,472,638 | ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad |
| JNA 5.18.1 AAR | 522,677 | 7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0 |
| small-en-us-0.15 ZIP | 41,205,931 | 30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498 |

The model expands to 70,898,967 bytes in its upstream archive. This is disk content, not process RAM. The acquisition ledger records 55,205,151 received bytes including metadata from the prior owner-host run. No new download is required for this integration. The private preview packages ARM64 only for the verified G6, excluding unused native architectures. This reduces APK storage, not demonstrated process RAM. Vosk supplies ARM64 and ARMv7 libraries, and declares minimum Android API 21; the host previously verified the LG's Android API 28 and matching ABIs. ABI/manifest compatibility is not proof that actual native inference works or performs acceptably on the G6.

Vosk/model: Apache-2.0 according to upstream. JNA is dual licensed; this integration selects Apache-2.0. Preserve embedded declarations and upstream notices. The private APK includes the JNA declaration and Apache-2.0 license. Public source excludes runtime AARs, model weights, restricted fonts and signing material. Do not distribute a public APK from this preview.

Official sources:
- https://alphacephei.com/vosk/install
- https://alphacephei.com/vosk/models
- https://github.com/alphacep/vosk-api/blob/master/COPYING
- https://github.com/java-native-access/jna/blob/master/LICENSE
- https://github.com/alphacep/vosk-android-demo/blob/master/app/proguard-rules.pro
- JNA integrity corroboration: https://monitor.f-droid.org/builds/log/ch.threema.app.libre/1142

## Automated checks and limits

The existing 37 tests are retained. New tests cover PCM decode, clipping limits, resampling/duration, stereo overflow, silence at the preprocessing stage, unsupported/truncated input, cancellation, input preservation, importing and validating the actual pinned model archive, corrupted model-file rejection, missing model, failed import preservation and staging cleanup. These do not execute Android native inference.

The existing seven Android instrumentation methods concern photo/bitmap behavior. They do not establish speech recognition correctness. Normal host Gradle results, direct JVM results, assembly, signature verification and lint are recorded separately in the manifest/reports.

Managed ADB access is unavailable and no emulator image is installed. No app update, synthetic fixture push, UI action, recording, or permission grant is performed. **Historical managed-session statement: the later owner-host run installed the exact 11009 candidate and passed six app transcription checks.**

Outstanding gates: actual model import and JNI execution on Android, a fixed openly licensed speech recording with verified reference transcript, WER with documented normalization, repeated model-load/end-to-end timing, sampled process memory, silence/noise behavior in the recognizer, UI picker/cancellation/reopening, and playback coexistence. No real-speech fixture or transcription-quality result is fabricated. This is implementation plus automated preprocessing/model-integrity verification, not stable device acceptance.

The private host runner performs normal Gradle tests and lint and can publish the reviewed code branch if explicitly invoked with `-PublishCode`. It does not install, navigate the app, transcribe personal files or claim to close device gates. It stops if the current PR no longer matches the expected parent.

## Recovery

Existing 11007/11008 APKs, reports and source worktrees are retained. Candidate 11009 uses the same package and certificate. It is not installed automatically. Android downgrade rules still apply; do not uninstall, clear data or force a downgrade. A later recovery build must use the same signing identity and a compatible greater version code. Private source bundles contain personal font history and must never be uploaded publicly. The public bundle contains sanitized source history only.

## Current owner-host acceptance update - 27 November 2026

Normal Gradle tests passed: 53, zero failures/skips. The owner installed the exact candidate, then passed model import, short-fixture transcription, invalid-input preservation, picker cancellation, processing cancellation and dialog close/reopen. The isolated harness separately recognized licensed speech and silence. Photo comparison/export, repaired pixels, broad accuracy, active-playback coexistence and controlled performance gains remain unverified. Earlier pending statements above describe historical checkpoints, not current results. See DEVICE_VERIFICATION_11009.json and ../RELEASE_STATUS.md.
