# Glide 1.12.1 release status

Verification date: 27 November 2026.

**Private installed candidate with partial app checks. No stable or public-binary acceptance is claimed.**

Public source baseline: 819fd37e2ff8caf9281d495ce63fe9b502b3ae1f on review PR #1. Private implementation: a9d10191f64b793ca35ec7ba10c40016bd387722. Companion version: 1.12.1-transcription-preview / 11009. Installed APK SHA-256: d5956de4c179a72b5cd3359d50d8d155dfe1be65d28cb300c7a9144f54dfa7b3.

| Gate | Result |
|---|---|
| Owner-host Gradle unit tests | 53 passed; zero failures/skips |
| Release assembly / private signing | Passed |
| Lint | Zero errors; 127 warnings; two hints; no threshold weakened |
| Android instrumentation | Seven methods compiled; execution unverified |
| Actual G6 native inference | One licensed four-word fixture passed three repeats in an isolated debug harness |
| Actual app transcription workflow | Six bounded checks passed; see device record |
| Photo comparison/export and repaired pixels | Unverified; accessibility state unavailable; export not attempted by final continuation |
| Real accessories, natural-photo quality, active-playback coexistence | Unverified |
| Controlled performance improvement | Unverified; no new speed/RAM gain claimed |
| Public source | Published review branch; not assumed merged |
| Public APK / store release | Not published |

Three zero-WER fixture results do not establish general speech accuracy. End-to-end timing includes model-load and other work and is not real-time for this fixture. Application memory snapshots are not a measured peak or an optimization result.

The private companion retains the owner's fonts and signing identity. This does not permit redistributing restricted fonts. Public source requires independently licensed local font inputs. Recovery uses preserved same-certificate 1.11 behavior with forward version code 11010, not uninstalling or forcing a downgrade. Future versions must account for 11010 if that recovery is installed.

Historical reports remain unchanged. Current evidence supersedes stale statements that transcription is unimplemented or native execution has never occurred. Remaining gates cannot become PASS by changing documentation or version labels.
