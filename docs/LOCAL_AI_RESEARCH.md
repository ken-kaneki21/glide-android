# Local AI for Glide on the LG G6

Research date: 26 November 2026. No model weights downloaded, no neural feature implemented and no phone benchmark performed in this research. Published model sizes/parameters are not measured RAM, speed or battery use on this device.

## Recommendation

Prototype **speech recording cleanup with GTCRN first**, then evaluate **CPGA-Net for low-light photo previews**. Both are small task-specific networks. Do not add a local LLM, diffusion editor, generative fill, music stem separation or neural live-playback chain to this release. Existing enhancement remains a useful deterministic fallback.

| Candidate | Actual job | Published footprint and caveat | Decision |
|---|---|---|---|
| GTCRN | Remove background noise from speech recordings | Official implementation reports 48.2K parameters including the ERB module, 33 MMAC/s. The documented sherpa ONNX file is 523K. Reference inference requires 16 kHz audio. Upstream repo is MIT | Best first prototype. Stream/chunk a chosen voice clip and save a new file; verify the exact checkpoint licence/provenance before bundling |
| CPGA-Net | Improve low-light exposure/colour | Original repo reports about 25.4K parameters (about 100KB of raw FP32 parameter values), but also 6.03 GFLOPs at 600x400. Released weights are explicitly described as testing weights, not the paper's optimal model. Repo is MIT | Best image candidate to investigate. Start with 256-384 px previews. Conversion/operator support and exact checkpoint notices remain to verify; small weights do not prove low compute or quality |
| Silero VAD | Mark speech versus silence for optional trim suggestions | VAD identifies speech; it does not denoise or edit audio. Upstream provides ONNX support and MIT licensing | Optional later utility only if voice editing exists and users need trim suggestions. Do not add a separate runtime solely for this |
| RNNoise | Speech noise suppression | C library is BSD-3-Clause. Current models are downloaded separately; upstream issues 284/286 ask for explicit weight-redistribution terms. Old model footprint claims must not be applied automatically to the current model | Technically relevant alternative, but not the first public-bundling choice until exact weights and terms are resolved |
| Original Zero-DCE | Low-light image enhancement | Upstream explicitly restricts its implementation to non-commercial academic research (CC BY-NC 4.0) | Exclude from an unrestricted public app. An MIT-labelled reimplementation is not proof that borrowed checkpoints have changed licence |

GTCRN's 16 kHz speech path must not process the normal music library: it would constrain bandwidth and can suppress musical content. Use owner-selected voice recordings only. The current source has no established general-purpose audio editor, so model integration would require a bounded import/process/save-copy surface, not a claim that this feature already exists. Ordinary crop, rotate, exposure and audio trimming do not require a neural model.

## Runtime approach

Use CPU ARM64 as the initial compatibility baseline on Android 9, with one or two threads, one job at a time and one active inference session. ONNX Runtime Android is the likely shared runtime for a verified GTCRN graph and an image export; LiteRT is an alternative if the image conversion is simpler. Pin a runtime whose Android API/ABI and operators actually run on this phone. Do not assume the latest accelerator API, NNAPI driver, GPU delegate or NPU path works on the G6. Operator coverage and driver execution require a physical-device smoke test; CPU fallback must remain available.

Keep image decoding, FFT/resampling and inference off the UI thread, cancel stale work, release sessions on idle, preserve original files and expose processing as an explicit action. Do not load both networks continuously into the launcher. Do not put a model on the audio callback thread or silently modify playback/DSP.

Raw weights are only part of memory: image activations, input/output bitmaps, FFT buffers, recurrent caches and runtime libraries often dominate. The current 2048-pixel image output alone can require about 16 MiB of ARGB pixels; the existing original comparison preview adds at most 2.25 MiB. Avoid a full-resolution feature tensor pipeline on this device.

## Proposed acceptance gates — targets, not results

- Initial incremental-PSS targets: at most 64 MiB for a streaming speech job and 128 MiB for a bounded image-preview job. Reject or reduce scope if the actual process/heap budget cannot support them. These values have not been measured.
- Record model SHA, provenance, code/weight notices, runtime version/ABI, sample format and preprocessing. Confirm offline operation with networking denied.
- For speech, use consented clean/noisy paired fixtures plus real voice samples. Compare against the original and a deterministic baseline, check intelligibility/artifacts, latency/real-time factor and sample-rate/duration preservation. Silence must remain stable. Save a copy.
- For images, compare against today's deterministic enhancer on dark, bright, skin-tone/colour/neutral, textured and rotated fixtures. Check highlight clipping, hue drift, noise/texture and before/after preference. Use paired PSNR/SSIM only where matching ground truth exists. Reject the model if it loses to the simpler baseline.
- Measure cold load, warm p50/p95 job latency, incremental/peak PSS, cancellation/reopen behavior and thermal behavior with music playing. Keep screen mirroring off. Published desktop/Jetson/Pixel results are not G6 results.
- Promote only a measured quality improvement without a material playback/navigation/memory regression. Do not add INT8/FP16 merely for a smaller file; verify supported operations and output-quality parity.

## Primary sources

- https://github.com/Xiaobin-Rong/gtcrn (README, MIT LICENSE and infer.py)
- https://csukuangfj.github.io/sherpa/onnx/speech-enhancement/models.html
- https://github.com/Shyandram/CPGA-Net-Pytorch (README and MIT LICENSE)
- https://github.com/snakers4/silero-vad
- https://github.com/xiph/rnnoise and https://github.com/xiph/rnnoise/issues/284
- https://github.com/Li-Chongyi/Zero-DCE (upstream non-commercial restriction)
- https://onnxruntime.ai/docs/get-started/with-mobile.html
- https://developers.google.com/edge/litert/performance/gpu

This is a ranked research result, not an implementation promise or a deployment claim. No new model framework, paid API, weight download or cloud service was added.
