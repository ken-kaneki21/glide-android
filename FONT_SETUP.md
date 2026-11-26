# Original personal fonts

The owner requested the original font references and typography remain unchanged. This branch retains `R.font.zegoe_light`, `zegoe_regular`, `zegoe_semibold` and `zegoe_bold`; it does not replace them with Selawik.

The four corresponding TTF binaries are restricted third-party assets and are excluded from Git. To build your own personal copy, supply your own lawfully obtained/licensed files under `app/src/main/res/font/` using those names. Git ignores these files. The build fails with an explicit message if they are missing; it does not silently substitute another font. This repository grants no rights to acquire or redistribute those font binaries.

The owner's existing private build already has its original fonts. No installed font, signing identity, launcher default or app data was changed by this source update. The already-present Selawik assets retain their OFL notice but are not used as an automatic replacement for the original personal typography.
