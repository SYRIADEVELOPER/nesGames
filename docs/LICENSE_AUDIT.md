# License audit

| Component | Source | Version | License | Note |
|---|---|---|---|---|
| FCEUmm (NES) | github.com/libretro/libretro-fceumm | commit e0f3086a8104f8fbbe49a2615b10401504a9533f (2026-11-02) | GPL-2.0 | Built by us with NDK 28.2. arm64 SHA-256 43907B449C4B31B98BD92C68FF781C98981EDE9C1DA7D9026C8CEF643CF98C2C, x86_64 ED9C2E9CCB159D23C19AB27CE02F7977A45AE25454F70E56340B09F99D164FB6 |
| Genesis Plus GX (Mega Drive) | github.com/libretro/Genesis-Plus-GX | commit b9c2316441978d4015ba30e9a95d9b0e6f8ee981 (2026-11-09) | Custom non-commercial | arm64 SHA-256 0B88064E57F304D7F0F8090268A032659F20E4B003D8B335F5B7544A4FDE759A, x86_64 12E21401F1CC5A465ADF7AA796D149B88319611BF507C7CA5E35F1A42FAF553D. **The app may not be sold or used commercially while this core is bundled** |
| PCSX ReARMed (PS1) | github.com/libretro/pcsx_rearmed | commit 7b0e8f8fadfbce3d170d195b9d9eecaabfa08c8e (2026-10-29) | GPL-2.0 | include/revision.h created by hand. arm64 SHA-256 1E20A2D5394AEA0426644C4883126C41AA17BE83A37CECEDF7838741DB038981. No BIOS bundled or downloaded; uses the built-in HLE BIOS |
| ARMSX2 (PS2), libarmsx2_libretro.so | prebuilt from buildbot.libretro.com/nightly/android/latest/arm64-v8a, downloaded 2026-11-11; source github.com/ARMSX2/ARMSX2 (master 15f7db0 the same day) | **GPL-3.0** | SHA-256 D666FAD8C9469DC6007CCE478B25C719C24C30611931F6A9099D2CE41340250B (33,109,080 B), 4 KiB page build. Prebuilt nightly, not reproducibly built here. Its resources/ (shaders, fonts, GameIndex) ship in assets/pcsx2/resources, also GPL-3.0. No BIOS is bundled or downloaded |
| libretro.h | same repo | same | MIT | API header |
| AndroidX / Compose / DataStore / Room 2.8.5 / KSP 2.3.13 | Google Maven | see gradle/libs.versions.toml | Apache-2.0 | |
| App icon "NG" (ng_logo, ic_launcher*) | supplied by the project owner (2026-11-11) | — | owner's artwork | Confirm the owner holds the rights before publishing |
| Box art | thumbnails.libretro.com, downloaded on the user's device at runtime | — | third-party trademarks/artwork | Not bundled in the APK |

**Consequence:** a GPL-2.0 core ships inside the APK, so the distributed app must be GPL-compatible, and the corresponding source must be offered for the cores and the app.
Before release (M7), choose GPL-2.0-or-later or GPL-3.0 for nesGames and add a LICENSE file.

Planned cores to review before adding: Gambatte (GPL-2.0), mGBA (MPL-2.0), Snes9x (**non-commercial license**, so it cannot be sold or monetised; consider bsnes/Snes9x-2010 alternatives), Genesis Plus GX (**non-commercial clause**), FinalBurn Neo (**non-commercial**, and Neo Geo needs a proprietary BIOS), PCSX ReARMed (GPL-2.0; PS1 needs a BIOS, or HLE with lower compatibility).

## Release checklist (not yet done)
- **GPL-3.0 core bundled (ARMSX2):** the app as a whole must be distributable under GPL-3.0, so pick GPL-3.0 for nesGames. FCEUmm and PCSX ReARMed must be confirmed "GPL-2.0-or-later" (needed for GPL-3 compatibility). The Genesis Plus GX non-commercial licence is not GPL-compatible, so get legal advice before distributing it together with GPL-3 code.
- Pick the app licence: GPL-2.0-or-later or GPL-3.0 (FCEUmm and PCSX ReARMed are GPL-2.0).
- **Genesis Plus GX forbids commercial use and sale.** As long as it is bundled, the app must be free, without ads or in-app purchases.
- Publish the corresponding source for the app and the three cores, at the commits listed above.
- Use a real release signing key (the current builds use the debug key).
