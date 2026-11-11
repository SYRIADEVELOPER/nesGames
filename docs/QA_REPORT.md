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

---

# Phase 2 regression report: v0.2.0-phase2 (2026-11-11)

Devices: **S24** = Samsung Galaxy S24 Ultra SM-S928B, Android 14, arm64 (real phone). **EMU** = Android 15 x86_64 emulator (sga_test), used for long scripted runs while the phone was in use.
Method: `tools/qa.ps1` drives a debug-only QA broadcast channel. Save evidence = FNV hash of the emulated system RAM at save (H1), after 1.2 s more play (H2), and after load (H3). **PASS requires H3 = H1 and H2 ≠ H1**, i.e. the real game position came back, not just "load returned true".
Test content: `tools/testroms/nesgames_test.nes` (own NROM program; it now keeps a 16-bit frame counter in RAM, SHA-256 a92cfe43…b5b4), plus the user's own Tiny Toon Adventures (MD) and Metal Slug X (PS1).
Checkpoint before changes: git tag `phase1-checkpoint`; the user's saves were backed up on the phone to `files/saves_backup_phase2`.

## Priority 1: save system
| Test | NES | Mega Drive | PS1 |
|---|---|---|---|
| Slots 1–4 save → play on → load, RAM restored | PASS (EMU, S24) | PASS (EMU 1–4, S24 1–2) | PASS (EMU 1–4) |
| Auto-save on exit → app killed → Continue, RAM hash equal | PASS (EMU) | PASS (EMU) | PASS (EMU) |
| Continue on the real phone | PASS (S24: slot0 written with integrity record at exit, RESUME OK) | NOT TESTED on S24 | PASS (S24, boss fight restored: p2_s24_ps1_continued.png) |
| Restore of a cold-booted game from a manual slot | — | — | PASS (S24, user's slot 2: score 193,536, p2_s24_ps1_slot2.png) |
| Thumbnails written with the state, same frame | PASS 256×240 | PASS 320×224 / 256×224 | PASS 320×240 |
| Timestamps ("x minutes ago") in slot list and Continue | PASS (visual, S24) | | |
| Truncated state with record → refused, game untouched | PASS (EMU, NES) | | |
| Truncated state, no record → core refuses, current game kept | PASS (EMU, NES) | | |
| Old-build save (no record) loads | PASS (EMU NES; S24 PS1 user saves show Continue and load) | | |
| User's saves from the previous build kept | PASS (S24: all 3 games show Continue) | | |

**Found and fixed during testing:**
- PlayStation Continue restored before the core had run a frame, and the game rebooted. The restore now waits for the first 12 frames: PASS afterwards.
- A pause could land between two `retro_run` calls, so a state could be one frame newer than its screenshot. Pausing now waits for the frame in progress.
- **Known, PARTIAL:** the user's existing PS1 auto-save was taken on Metal Slug X's "NOW LOADING!" screen, during a disc read. That one state boots to a black screen. Manual saves and mid-gameplay auto-saves restore correctly. Mitigation idea: keep the previous auto-save as a fallback.

## Priority 2: touch controller
| Test | Result | Evidence |
|---|---|---|
| Two-finger chord, D-pad + face button | PASS (S24, EMU NES+MD) | TOUCH2 step 2, mask 384 |
| Lift in either order, other finger stays held | PASS (S24, EMU) | steps 3–4 |
| ACTION_CANCEL releases everything immediately | PASS (S24, EMU) | step 5 |
| 0 ms tap held ≥ 50 ms then released once | PASS (S24, EMU) + unit test | steps 6–7 |
| Focus loss releases everything | PASS (S24, EMU) | step 8 |
| Stuck/highlighted D-pad (phase-1 bug) | FIXED; covered by the above + unit tests | cause: pointer-index tracking and no cancel handling |
| Editor: drag, resize, opacity, D-pad gap, reset | PASS (EMU; drag shown in p2_editor.png; geometry logged) | |
| Preset export / import / invalid preset rejected | PASS (EMU) | IMPORT ok / IMPORT rejected |
| Per-console layouts | PASS (unit tests: every button hit on NES/MD/PS1 at 4 screen sizes) | |
| Haptic feedback | PARTIAL: setting saved and wired; vibration not felt by a person | |
| Bluetooth/USB gamepad remap | NOT TESTED: no controller connected (logic unit-tested) | |
| Real two-finger play on glass | NOT TESTED: adb cannot inject two fingers; synthetic events went through the real view | |

## Priority 3: UI
| Test | Result |
|---|---|
| App icon (user artwork) on Samsung | PASS: App info + splash (p2_s24_icon_appinfo.png, p2_s24_splash.png) |
| Recent / Favorites tabs; gamepad Y toggles favourite | PASS (S24, p2_s24_favorites.png) |
| D-pad navigation on home | PASS (S24, keyevent moved selection) |
| Continue + Start new + favourite buttons | PASS (S24) |
| Arabic UI with right-to-left layout | PASS (S24, p2_s24_arabic.png); game pad stays left-to-right on purpose |
| Aspect modes: original / integer / full / 4:3 | PARTIAL: implemented; 4:3 not screenshotted |
| Cover memory cache, animated tab and tile transitions | Implemented; checked visually only |

## Priority 4: library
| Test | Result | Evidence |
|---|---|---|
| Room cache, first scan | PASS | inspected 3, 124 ms |
| Unchanged files not reopened | PASS | reused 3, inspected 0, 29 ms |
| Rename keeps saves (Continue still shown) | PASS (S24) | renamed=1, p2_s24_renamed.png |
| Missing file → hidden, save data kept | PASS (S24) | missing=1 during rename; save folder untouched |
| SAF permission kept across updates | PASS (S24) | library loaded after reinstall without asking again |

## Stability
| Test | Result |
|---|---|
| Unit tests | PASS 31/31 (5 CUE, 14 controller/save, 11 ROM, 1 template) |
| Invalid ROM | PASS: message shown, process alive |
| Background → frames stop → foreground resumes | PASS (S24) |
| Memory, Mega Drive running | 95 MB PSS (S24) |
| Crash buffer during the whole S24 session | 0 nesGames crashes |
| Audio by ear, long-session soak, low-end phone | NOT TESTED |
