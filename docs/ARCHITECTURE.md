# Architecture (v0.2.0-phase2)

```
MainActivity (Compose, PS5-style home)
  ├─ GameDb (Room, library.db) ── observe() → shelf renders instantly from cache
  ├─ LibraryScanner (SAF, read-only) ── one provider query per folder; unchanged (uri,size,mtime) entries reused;
  │     new/changed files inspected; vanished files flagged `missing` (never deleted); renamed/moved files matched
  │     by fingerprint = sha1(first 64 KiB)+size, keeping saveKey/favourite/lastPlayed
  ├─ Covers (libretro thumbnails → gameplay snapshot → generated tile) + in-memory LruCache
  └─ intent: uri, zip entry, system, game id, saveKey, resume
GameActivity
  ├─ GLSurfaceView (vsync) · ControlsView (PadLayout + Geometry + TouchTracker) · edit bar
  ├─ LayoutStore: files/layouts/<SYSTEM>.txt (presets, export/import) · padmap prefs (gamepad remap)
  ├─ SaveSlots: files/saves/<saveKey>/{game.srm, slotN.state, slotN.png, slotN.meta}
  └─ debug-only QA broadcast channel (com.nesgames.app.QA) used by tools/qa.ps1
libnesgames.so (frontend.cpp, C++17)
  ├─ dlopen core from nativeLibraryDir only; symbol + API version checked; core switch = dlclose + reload
  ├─ emu thread: retro_run at the core's own fps (±0.5% nudge for audio), pause re-checked under the core lock
  ├─ nativeSetPaused(true) waits for the in-flight frame → state + screenshot are the same frame
  ├─ save: serialize → tmp file → fsync → rename;  load: size must equal serialize_size, rolls back on failure
  ├─ video → RGBA → GL texture; scale modes: original, integer, full screen (default), 4:3
  └─ audio: lock-free SPSC ring → AAudio low latency
```

## Touch input
- `TouchTracker` keys every pointer by (deviceId, pointerId); the button mask is recomputed from all live pointers on every event.
- `ACTION_CANCEL`, window-focus loss, `onPause`, visibility change and any dialog opening call `releaseAll()`: nothing can stay held.
- A press shorter than 50 ms is held until 50 ms have passed (≥ 3 frames at 60 fps), then released once. No repeats.
- Layout values are fractions of min(width, height) anchored to a screen edge, so presets work on any resolution.

## Save safety
| Step | Crash here leaves |
|---|---|
| delete old .meta | old state (loads as LEGACY, size-checked) |
| write state.tmp + fsync + rename | old state or new state, never a half file |
| write .meta (crc32) | new state, LEGACY until the record lands |
| screenshot tmp + rename | new state with old/no thumbnail |

Damaged slots (crc/size mismatch) are refused before reaching the core. Files from older builds (no .meta) still load.
A PlayStation resume is applied after the core's first 12 frames, because PCSX ReARMed's disc/GPU state is not ready straight after load.
