# ROM support (M1)

| System | Formats | Detection | Core | Status |
|---|---|---|---|---|
| NES/Famicom | .nes, .unif/.unf, .zip (single ROM) | iNES/NES2.0 magic + PRG>0; "UNIF" magic | FCEUmm | Playable |
| GB/GBC | .gb/.gbc | Nintendo logo @0x104, CGB flag @0x143 | — | Detected only (M4) |
| GBA | .gba | 0x96 @0xB2 + ARM branch | — | Detected only (M4) |
| Mega Drive | .md/.gen | "SEGA" @0x100 | — | Detected only (M5) |
| SNES | .sfc/.smc | needs internal-header check | — | M5 |
| PS1 | .cue/.chd | — | — | M6 |

The extension alone never counts as proof. Zips with more than one recognised ROM (for example arcade sets) are not listed.
FDS disk images need `disksys.rom` in `Download/nesGames/BIOS/` (BIOS manager comes in M5).
