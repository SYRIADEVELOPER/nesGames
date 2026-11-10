# License audit

| Component | Source | Version | License | Note |
|---|---|---|---|---|
| FCEUmm (NES) | github.com/libretro/libretro-fceumm | commit e0f3086a8104f8fbbe49a2615b10401504a9533f (2026-11-02) | GPL-2.0 | Built by us with NDK 28.2. arm64 SHA-256 43907B449C4B31B98BD92C68FF781C98981EDE9C1DA7D9026C8CEF643CF98C2C, x86_64 ED9C2E9CCB159D23C19AB27CE02F7977A45AE25454F70E56340B09F99D164FB6 |
| libretro.h | same repo | same | MIT | API header |
| AndroidX / Compose / DataStore | Google Maven | see gradle/libs.versions.toml | Apache-2.0 | |

**Consequence:** a GPL-2.0 core ships inside the APK, so the distributed app must be GPL-compatible, and the corresponding source must be offered for the cores and the app.
Before release (M7), choose GPL-2.0-or-later or GPL-3.0 for nesGames and add a LICENSE file.

Planned cores to review before adding: Gambatte (GPL-2.0), mGBA (MPL-2.0), Snes9x (**non-commercial license**, so it cannot be sold or monetised; consider bsnes/Snes9x-2010 alternatives), Genesis Plus GX (**non-commercial clause**), FinalBurn Neo (**non-commercial**, and Neo Geo needs a proprietary BIOS), PCSX ReARMed (GPL-2.0; PS1 needs a BIOS, or HLE with lower compatibility).
