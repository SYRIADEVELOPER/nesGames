# QA report: M1 (2026-11-10)

Device: Samsung Galaxy S24 Ultra SM-S928B, Android 14, arm64-v8a, 1440x3120. Build: app-debug.apk (0.1.0-m1).
Test content: `tools/testroms/nesgames_test.nes`, an original NROM program written for this project
(SHA-256 c7046bbeed03895b30446e1dd4bb4bea63a04da105ffb020764ba87864595065). It plays a 440 Hz square wave
and sets the backdrop colour from controller 1 (none=blue, A=red, B=green, other=yellow).

| Test | Result | Evidence |
|---|---|---|
| Gradle build (arm64 + x86_64 native) | PASS | BUILD SUCCESSFUL |
| Unit tests (ROM detection, zip-slip, titles, test ROM) | PASS 12/12 | test-results XML, 0 failures |
| Native core loading (dlopen + symbol and API check) | PASS | `loaded ... FCEUmm`, BOOT_OK |
| Game boots and renders | PASS | 256x240 frames, video frame count == emulated frame count |
| Frame pacing | PASS | +60 frames per 1.0 s logcat interval (core: NTSC 60.1 fps) |
| Audio output | PASS (data path) | ~47,900 audio frames/s delivered to AAudio (48 kHz). Underruns stay flat after startup (9–19 at launch) |
| Touch input A / B / D-pad Right | PASS | centre pixel red D52020 (mask 256), green 4ADE4A (mask 1), yellow DEAA00 (mask 128); release returns to blue |
| Background / foreground | PASS | frame counter frozen at 1234 while in background, resumes in the same process |
| Invalid ROM | PASS | random 40 KB `.nes` → "The core rejected this ROM" dialog, no crash |
| Save state | PASS | 13,758-byte state1 file written atomically |
| Load state | PARTIAL | load ran with no crash and emulation continued. The test ROM has no persistent state, so restored game state was not proven |
| Simultaneous multi-touch | NOT TESTED | adb cannot inject two pointers; code computes the mask from all pointers |
| External gamepad | NOT TESTED | no controller attached |
| Audible audio quality / latency | NOT TESTED | needs a human listener |
| SAF folder picker and library scan on device | NOT TESTED | requires interactive picker; scanner logic covered by unit tests |
| Commercial ROMs | NOT TESTED | none provided as NES |

## Mega Drive: Tiny Toon Adventures – Buster's Hidden Treasure (Europe), user-supplied ROM
Same device. Screenshots are in docs/qa/.

| Test | Result | Evidence |
|---|---|---|
| Boot (Genesis Plus GX) | PASS | Konami logo renders (md_1_title.png); resolution switches 320x224 ↔ 256x224 correctly |
| Timing | PASS | ~50 frames/s, correct for a PAL cartridge |
| Audio data path | PASS | ~44.3k audio frames/s delivered; no underruns after startup |
| START gets into gameplay | PASS | md_2_after_start.png |
| D-pad Right walks and scrolls | PASS | md_3_walk_right.png, mask=128 |
| C button jumps | PASS | md_5_fullscreen_jump.png |
| Full-screen mode (default) | PASS | the picture fills the whole 3120x1440 display |
| Switching core NES → MD in one process | PASS | NES test ROM then Tiny Toon launched in the same process with no restart |

## PlayStation: Metal Slug X (USA), user-supplied CUE/BIN
| Test | Result | Evidence |
|---|---|---|
| Disc boot with HLE BIOS (no BIOS file) | PASS | main menu renders (qa/ps1_1_boot.png) |
| Timing / audio | PASS | 60 fps NTSC; ~44.1k audio frames/s; underruns flat after startup |
| Menu → Arcade Mission → gameplay | PASS | qa/ps1_4.png |
| □ fires | PASS | qa/ps1_5_shoot.png |
| Reading discs from Android/data via a native path | FAIL → worked around | the core got "Permission denied"; the disc now loads from app-internal storage |
| Open issue | — | D-pad Right drew highlighted in ps1_5 while the logged mask was 0; needs investigation |

## Library v2: per-console folders, PS5-style home, automatic covers (2026-11-10)
| Test | Result | Evidence |
|---|---|---|
| Folder picker → console folders auto-created | PASS | Download/nesGames now holds BIOS, GBA, GameBoy, MegaDrive, NES, PS1, SNES |
| Games found from their folders (NES / MegaDrive / PS1 CUE+BIN) | PASS | qa/ui_2_home.png: 3 games, correct systems |
| Matching controls shown per game | PASS | Details panel and in-game layout per system |
| Box art downloaded automatically | PASS | logcat nesGamesCovers: stored for Metal Slug X and Tiny Toon; 404 for the test cart (expected) → generated tile; qa/ui_4_covers.png |
| PS1 launched from the library (one-time disc copy) | PASS | loaded files/discs/<id>/Metal Slug X (USA).cue, running at 60 fps |
| Unit tests | PASS 17/17 | |
| Gamepad/D-pad navigation on the home screen | NOT TESTED | no controller attached |
| Gameplay-snapshot fallback cover | NOT TESTED | needs 20+ s of play of a game without box art |

## Save system (2026-11-10)
Every game, whatever the console, gets its own folder `files/saves/<game key>/` containing:
in-game save (battery RAM / PS1 memory card), auto-save slot 0 (written on exit or backgrounding, used by "Continue"), and manual slots 1–4, each with a screenshot.

| Test | Result | Evidence |
|---|---|---|
| Save to slot 1 from the pause menu (Tiny Toon) | PASS | "Saved to slot 1" toast (qa/save_2_ingame.png); slot1.state 1,036,288 B + slot1.png on disk |
| Slot chooser shows thumbnail and time | PASS (visual) | qa/save_1_slots.png |
| Auto-save on exit → "Continue" on home → resume | NOT YET VERIFIED | the test was interrupted while the user was playing on the device |
| Load from a slot | NOT YET VERIFIED | |
