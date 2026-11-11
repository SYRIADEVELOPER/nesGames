# ROM support (v0.2.0-phase2)

Folders under Download/nesGames are created automatically: NES · MegaDrive · PS1 · GameBoy · GBA · SNES · BIOS.

| System | Formats | Detection | Core | Status |
|---|---|---|---|---|
| NES/Famicom | .nes, .unif/.unf, .zip (single ROM) | iNES/NES 2.0 magic + PRG>0; "UNIF" | FCEUmm | Playable, saves verified |
| Mega Drive | .md/.gen, .zip | "SEGA" at 0x100 | Genesis Plus GX | Playable, saves verified |
| PlayStation | .cue + .bin (single or multi-track) | CUE parsed, every track must exist, MODE2 data track | PCSX ReARMed (HLE BIOS) | Playable from the library (disc copied once to app storage), saves verified |
| PlayStation 2 | .iso (ISO 9660 checked), .cso, .chd in the PS2 folder | folder + ISO header | ARMSX2 (OpenGL ES 3.2, GPU-rendered) | **Needs the user's own PS2 BIOS** in Download/nesGames/BIOS (4 MiB, ROMDIR + ROMVER validated, .nvm/.mec copied). Disc is copied once into app storage on first launch. Black (USA) boots to its title on the S24 with BIOS 2.00 USA; gameplay not yet tested. Analog sticks + L2/R2/L3/R3 mapped |
| GB/GBC/GBA | .gb/.gbc/.gba | header logo / fixed bytes | — | Detected, core not bundled (M4) |
| SNES | .sfc/.smc | needs internal-header check | — | Not yet (M5) |
| PS1 .chd / .m3u | — | — | — | Not yet |

The extension alone never counts as proof. Zips with more than one recognised ROM (arcade sets) are not listed.
Renamed or moved files keep their saves and favourite status. Missing files are hidden, never deleted from the database, and their saves stay on disk.
