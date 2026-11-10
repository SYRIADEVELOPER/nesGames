# Architecture

```
Compose UI (MainActivity) ── LibraryScanner (SAF, read-only) ── RomInspector (header-based identification)
        │ intent (uri, zip entry, system)
GameActivity ── GLSurfaceView (vsync) ── ControlsView (multi-touch) ── KeyEvent/MotionEvent (gamepad)
        │ JNI: NativeBridge
libnesgames.so (frontend.cpp, C++17)
  ├─ dlopen core (only from APK nativeLibraryDir; symbols and API version checked)
  ├─ emu thread: retro_run paced at the core's own fps (±0.5% nudge to keep audio buffered, no fast-forward)
  ├─ video cb → RGBA buffer → GL thread uploads texture, nearest filter, aspect-correct or integer scale
  ├─ audio cb → lock-free SPSC ring → AAudio low-latency callback
  ├─ input: atomic bitmask per port (supports RETRO_DEVICE_ID_JOYPAD_MASK)
  └─ SRAM/state: core mutex, atomic file writes (tmp + rename), keyed by SHA-1 of the ROM
```
Threading: every call into the core is serialised by `coreMutex`; video uses a separate mutex; audio and input are lock-free.
Storage: `files/saves/<sha1>.srm`, `files/states/<sha1>.state1`, `files/system/` (BIOS, M5).
Zip ROMs are inspected and extracted in memory, with an entry limit, a 16 MiB cap and zip-slip name rejection.
