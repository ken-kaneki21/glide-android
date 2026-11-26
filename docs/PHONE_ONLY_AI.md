# Phone-only local AI: actual status

Target: the owner's LG G6 / Android 9, with no laptop inference service, cloud inference or paid API. This is a source branch, not an all-in-one AI release.

| Requested function | Current implementation / disposition |
|---|---|
| Answer questions | NOT IMPLEMENTED. A very small SmolLM2-class model is a candidate for basic English prompts, with substantial factual/reasoning limits. No language-model runtime or weights are integrated |
| Enhance/edit photos | Existing deterministic shadow enhancement, Original/Enhanced preview and save-copy workflow retained. No neural enhancement model is integrated |
| Remove things from photos | IMPLEMENTED only as bounded small-mark pixel repair on a copy up to 1024 px. Best on simple backgrounds. It is non-neural and does not reconstruct missing semantic content |
| Transcribe audio | NOT IMPLEMENTED. Whisper Tiny is a candidate; upstream lists about 75 MiB on disk and approximately 273 MB runtime memory, not measured on this G6. No speech runtime or weights are integrated |
| Generate images | NOT IMPLEMENTED. No useful low-memory prompt-based image generator has passed a G6 feasibility/memory/latency gate. No placeholder, procedural drawing or remote generation is presented as neural generation |

The managed implementation environment lacks Android NDK/CMake and an inference runtime. Model acquisition failed DNS resolution for huggingface.co. Those are execution blockers; a phone can be an inference target while the laptop is still required to compile its app. No model downloads, SDK upgrade, GPU rental, network backend or paid service was used.

Future model work must pin code and weight licences/hashes, remain within the existing acquisition budget, load one model on demand, preserve originals, allow cancellation and benchmark actual G6 peak PSS, cold/warm latency, thermal behavior and playback continuity. Tiny weight files do not prove tiny runtime memory. No claim of AI deployment or full feature completion is supported by this branch.

Sources: https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct ; https://github.com/ggml-org/whisper.cpp ; https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md ; https://github.com/leejet/stable-diffusion.cpp

## Automated closeout, 26 November 2026

The earlier code-only update was pushed by the owner to draft PR #1. Follow-up tests and accurate dependency/device dispositions are in [AUTOMATED_PHOTO_CLOSEOUT.md](AUTOMATED_PHOTO_CLOSEOUT.md). No transcription UI, runtime or model was integrated. Original personal fonts and the exact 11008 private APK remain unchanged.
