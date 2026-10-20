# Third-party code, fonts and data

Oriel is licensed under the GNU GPL v3.0 (see `LICENSE`). It includes or adapts the following,
each under its own licence; full texts are in `LICENSES/` or linked.

## Code copied or adapted into this repository

| What | Where in Oriel | Source | Licence |
|---|---|---|---|
| Interface design and components | `ui/components`, `ui/theme`, credited in file headers | [BitChord](https://github.com/kushagrasinghx/BitChord) | GPL-3.0 |
| Backdrop (liquid glass rendering) | `ui/glass/backdrop/` | [Kyant0/backdrop](https://github.com/Kyant0/backdrop), Copyright 2025 Kyant0 | Apache-2.0 (`LICENSES/Apache-2.0.txt`) |
| Liquid glass modifier | `ui/glass/LiquidGlass.kt` | BitChord, from [Echo-Music](https://github.com/EchoMusicApp/Echo-Music)'s GlassEffectConfig | GPL-3.0 |
| iOS-style overscroll | `ui/glass/IosOverscroll.kt` | [Convx](https://github.com/cosmictaserdev-creator/Convx), by way of BitChord | GPL-3.0 |

## Libraries

| Library | Licence |
|---|---|
| [InnerTubeX](https://github.com/MetrolistGroup/innertubex) | GPL-3.0 |
| AndroidX, Jetpack Compose, Media3, Material icons | Apache-2.0 |
| Coil | Apache-2.0 |
| Ktor, kotlinx.serialization, kotlinx.coroutines | Apache-2.0 |
| OkHttp | Apache-2.0 |
| quickjs-kt (unit tests only, not shipped in the app) | Apache-2.0 |

## Fonts

| Font | Where | Licence |
|---|---|---|
| [Inter](https://github.com/rsms/inter), Copyright 2016 The Inter Project Authors | `app/src/main/res/font/inter.ttf` (unmodified) | SIL Open Font License 1.1 (`LICENSES/OFL-1.1-Inter.txt`) |

## Data and services

- **SponsorBlock** segment data is © its contributors under
  [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/); see [sponsor.ajay.app](https://sponsor.ajay.app).
- **Return YouTube Dislike** counts come from [returnyoutubedislike.com](https://returnyoutubedislike.com).

YouTube is a trademark of Google LLC. Oriel is an independent project and is not affiliated with,
endorsed by or connected to YouTube or Google.
