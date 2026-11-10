package com.nesgames.app.emu

/** JNI surface of the native libretro frontend (app/src/main/cpp/frontend.cpp). */
object NativeBridge {
    init { System.loadLibrary("nesgames") }

    /** @return null on success, otherwise a human-readable error. */
    external fun nativeLoad(
        corePath: String, romName: String, rom: ByteArray,
        systemDir: String, saveDir: String, sramPath: String, tempDir: String,
    ): String?
    external fun nativeUnload()
    external fun nativeSetPaused(paused: Boolean)
    external fun nativeSetInput(port: Int, mask: Int)
    external fun nativeReset()
    external fun nativeSaveSram(): Boolean
    external fun nativeSaveState(path: String): Boolean
    external fun nativeLoadState(path: String): Boolean
    external fun nativeSetIntegerScale(on: Boolean)
    /** [frames, videoFrames, audioFrames, underruns, frameHash, centerPixelRGBA, width, height] */
    external fun nativeGetStats(): LongArray
    external fun nativeGetFps(): Double
    external fun nativeSurfaceCreated()
    external fun nativeSurfaceChanged(w: Int, h: Int)
    external fun nativeDrawFrame()
}

/** libretro RETRO_DEVICE_ID_JOYPAD_* bit indices. */
object RetroPad {
    const val B = 0; const val Y = 1; const val SELECT = 2; const val START = 3
    const val UP = 4; const val DOWN = 5; const val LEFT = 6; const val RIGHT = 7
    const val A = 8; const val X = 9; const val L = 10; const val R = 11
    fun bit(id: Int) = 1 shl id
}
