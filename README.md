# nesGames

Lightweight, offline Android multi-system emulator (package `com.nesgames.app`) built on native libretro cores.
No ROMs are bundled. The app uses no network, telemetry or background services.

## Status
| Milestone | State |
|---|---|
| M1 NES runs real ROMs | **Done, verified on a Galaxy S24 Ultra (Android 14)**. See docs/QA_REPORT.md |
| M2 Library + folder scanning | Partial: SAF picker, scanner, search and console filter work. Room DB, favorites and recents are not built yet |
| M3 Touch controls + saving | Partial: multi-touch pad, SRAM and one save-state slot work. Layout editor, opacity setting and remapping are not built yet |
| M4–M7 | Not started |

## Use
1. Put your own ROM files in `Download/nesGames/` on the phone.
2. Open nesGames, tap **Select nesGames folder**, then choose the `nesGames` folder inside Download.
3. Tap a game. In-game, tap **MENU** or press Back to save/load state, restart or exit.

## Build
Needs JDK 17+ (Android Studio JBR), SDK 37 and NDK 28.2.13676358.
`gradlew assembleDebug testDebugUnitTest`

Cores are prebuilt into `app/src/main/jniLibs/` from pinned source. See `docs/LICENSE_AUDIT.md` and `tools/`.
NDK projects cannot build from a path containing spaces, so cores are built from `C:\nesbuild`.

## QA helpers (debug builds only)
`adb shell am start -n com.nesgames.app/.ui.GameActivity --es test_rom_file /storage/emulated/0/Android/data/com.nesgames.app/files/nesgames_test.nes`
logs per-second stats to logcat tag `nesGamesQA`. The test ROM is built from `tools/make_test_rom.py`.
