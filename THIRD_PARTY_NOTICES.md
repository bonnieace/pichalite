# Bundled queue animations

All queue animations work offline and are distributed under the [Lottie Simple License](https://lottiefiles.com/page/license), reproduced in [lottie_simple_license.txt](android/app/src/main/res/raw/lottie_simple_license.txt) and included in the APK. The adapted resources retain these terms.

| Resource | Original animation | Creator | Source and adaptation |
| --- | --- | --- | --- |
| `photo_upload.json` | Upload/Import | Hari | https://lottiefiles.com/free-animation/uploadimport-UWv32HdvbL — original JSON, vertically reflected in Compose to show a download into the phone. |
| `photo_compression.json` | 3D Isometric Factory Process | Irfan | https://lottiefiles.com/free-animation/3d-isometric-factory-process-TJAlBgYcXw — the approved illustration, adapted to 56 embedded 384×288 WebP frames, held for two frames each, preserving the original 2.24-second loop. No external images or fonts. Worst-case decoded frame memory: 23.625 MiB, instead of 189 MiB for the original 112 frames at 768×576. |
| `photo_processing.json` | Cog Animation | Jonathan | https://lottiefiles.com/free-animation/cog-animation-uhpAJfwtr4 — unsupported After Effects `loopOut` expressions replaced by linear full-turn rotation keyframes across the complete loop. Cogs keep turning beyond the original one-second keyframes and loop seamlessly. |

SHA-256:

- Upload/Import original JSON: `f3980cf1b256252a7fd7758dd25f3ed50160512b89ca6ef39362433bed9da526`
- Factory original JSON: `03f9340fdcad67fada9d367dd60ed75d9eca634d682afcea859ef43fd4589f8e`
- Factory adapted JSON: `5b07c2d33ef0db9d5781635b38b79f8bb3e4ee1121666267639bfbbb977ee5f0`
- Cog original JSON: `250c963e5e7b5d33cf442a8be45461f2ace47322b6dab93eb10b875f5940163f`
- Cog adapted JSON: `0b941399a1ce23f69b12da3a048a680a274dfcd674b613118eba3c800dbd3929`
