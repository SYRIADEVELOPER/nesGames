# Third-party notices

nesGames bundles the following components. Each keeps its own license; full details, file hashes and
the release checklist are in [docs/LICENSE_AUDIT.md](docs/LICENSE_AUDIT.md).

| Component | Used for | License | Corresponding source |
|---|---|---|---|
| FCEUmm | NES core (`libfceumm_libretro.so`) | GPL-2.0 | https://github.com/libretro/libretro-fceumm, commit e0f3086a8104f8fbbe49a2615b10401504a9533f |
| Genesis Plus GX | Mega Drive core (`libgenesis_plus_gx_libretro.so`) | Genesis Plus GX license (non-commercial) | https://github.com/libretro/Genesis-Plus-GX, commit b9c2316441978d4015ba30e9a95d9b0e6f8ee981 |
| PCSX ReARMed | PlayStation core (`libpcsx_rearmed_libretro.so`) | GPL-2.0 | https://github.com/libretro/pcsx_rearmed, commit 7b0e8f8fadfbce3d170d195b9d9eecaabfa08c8e |
| ARMSX2 | PlayStation 2 core (`libarmsx2_libretro.so`) and `assets/pcsx2/resources` | GPL-3.0 | https://github.com/ARMSX2/ARMSX2, commit 15f7db0 (libretro buildbot nightly, 2026-11-11) |
| libretro.h | Core API header | MIT | https://github.com/libretro/libretro-common |
| AndroidX, Jetpack Compose, Room, DataStore | App framework | Apache-2.0 | https://developer.android.com/jetpack |

**Not included:** games, PlayStation / PlayStation 2 BIOS files, box art (downloaded on the device at runtime
from thumbnails.libretro.com).

Genesis Plus GX may not be used commercially: nesGames must be distributed free of charge, without ads
or in-app purchases, while this core is bundled. Get legal advice before distributing it publicly
together with GPL-3.0 code.
