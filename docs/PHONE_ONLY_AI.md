# Phone-only AI: current source status

Target: LG G6 H870DS / Android 9. No laptop inference server, cloud fallback or paid API.

| Request | Actual disposition |
|---|---|
| Question answering | NOT IMPLEMENTED |
| Neural photo enhancement | NOT IMPLEMENTED; deterministic enhancement remains |
| Semantic object removal | NOT IMPLEMENTED; bounded non-neural small-mark repair remains |
| Audio transcription | IMPLEMENTED IN PRIVATE PREVIEW CODE using Vosk 0.3.75 and a separately imported pinned English model. WAV PCM16 only, up to 30 seconds. Android native execution, real-speech accuracy, latency/RAM and UI behavior remain UNVERIFIED |
| Image generation | NOT IMPLEMENTED |

The owner-host acquisition completed: 55,205,151 received bytes including runtime/model metadata. Weights are not bundled, tracked or automatically downloaded by the app. The prebuilt runtime avoids an NDK requirement for this integration. AAR ABI/minimum-SDK compatibility was inspected; that does not prove G6 CPU/runtime success. No model inference was executed in the managed environment, which cannot reach ADB and has no emulator image.

See [OFFLINE_TRANSCRIPTION.md](OFFLINE_TRANSCRIPTION.md) for hashes, licences, input limits, cancellation/resource handling and acceptance gaps. Source integration and successful JVM/model-integrity tests must not be described as production deployment or measured recognition quality.
