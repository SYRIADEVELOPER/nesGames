# License audit

| Component | Source | Version | License | Note |
|---|---|---|---|---|
| FCEUmm (NES) | github.com/libretro/libretro-fceumm | commit e0f3086a8104f8fbbe49a2615b10401504a9533f (2026-11-02) | GPL-2.0 | Built by us with NDK 28.2. arm64 SHA-256 43907B449C4B31B98BD92C68FF781C98981EDE9C1DA7D9026C8CEF643CF98C2C, x86_64 ED9C2E9CCB159D23C19AB27CE02F7977A45AE25454F70E56340B09F99D164FB6 |
| Genesis Plus GX (Mega Drive) | github.com/libretro/Genesis-Plus-GX | commit b9c2316441978d4015ba30e9a95d9b0e6f8ee981 (2026-11-09) | Custom non-commercial | arm64 SHA-256 0B88064E57F304D7F0F8090268A032659F20E4B003D8B335F5B7544A4FDE759A, x86_64 12E21401F1CC5A465ADF7AA796D149B88319611BF507C7CA5E35F1A42FAF553D. **The app may not be sold or used commercially while this core is bundled** |
| libretro.h | same repo | same | MIT | API header |
| AndroidX / Compose / DataStore | Google Maven | see gradle/libs.versions.toml | Apache-2.0 | |

**Consequence:** a GPL-2.0 core ships inside the APK, so the distributed app must be GPL-compatible, and the corresponding source must be offered for the cores and the app.
Before release (M7), choose GPL-2.0-or-later or GPL-3.0 for nesGames and add a LICENSE file.

Planned cores to review before adding: Gambatte (GPL-2.0), mGBA (MPL-2.0), Snes9x (**non-commercial license**, so it cannot be sold or monetised; consider bsnes/Snes9x-2010 alternatives), Genesis Plus GX (**non-commercial clause**), FinalBurn Neo (**non-commercial**, and Neo Geo needs a proprietary BIOS), PCSX ReARMed (GPL-2.0; PS1 needs a BIOS, or HLE with lower compatibility).
