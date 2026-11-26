# Third-party notices

Original Glide code is MIT. This does not relicense the following materials.

| Material | Upstream / terms | Included notice |
|---|---|---|
| Selawik fonts | Microsoft, SIL Open Font License 1.1; reserved font name Selawik | `app/src/main/assets/licenses/Selawik-OFL.txt` |
| Keyboard word-frequency content | Hermit Dave / FrequencyWords contributors; OpenSubtitles 2018 / OPUS source; CC BY-SA 4.0 | `app/src/main/assets/glide_words_NOTICE.txt` states sources, filtering changes and licence URI. Adapted word list remains CC BY-SA 4.0 |
| FrequencyWords generator | MIT, copyright 2016 Hermit Dave | Generator code is not included as Glide app code. Content is not MIT |
| AndroidX, Media3, Compose, Kotlin, osmdroid and Gradle wrapper | Corresponding upstream licences, primarily Apache 2.0; preserve artifact-specific notices | `licenses/Apache-2.0.txt`; Gradle script headers and dependency artifacts retain original notices |

Primary sources:

- https://github.com/microsoft/Selawik/blob/master/LICENSE.txt
- https://github.com/hermitdave/FrequencyWords (README distinguishes code and content licences)
- https://opus.nlpl.eu/OpenSubtitles2018.php
- https://creativecommons.org/licenses/by-sa/4.0/legalcode.en
- https://github.com/gradle/gradle/blob/master/LICENSE

The word list was filtered to roughly 30,000 English entries for keyboard suggestions. Preserve attribution and the CC BY-SA notice with redistribution or modifications of that content; this file makes no assertion that all material is covered by the app's MIT licence. Third-party network responses/artwork/map tiles are not supplied as part of this source snapshot and have their own terms and attribution requirements.

No Zegoe font binaries, vendor SDKs, copied third-party skin assets or pretrained model weights are part of this export. Visual references informed an original implementation rather than redistribution of those skins.
