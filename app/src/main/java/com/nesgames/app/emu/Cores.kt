package com.nesgames.app.emu

import android.content.Context
import com.nesgames.app.library.GameSystem
import java.io.File

/** Cores bundled inside the signed APK. Only these files, from nativeLibraryDir, are ever dlopen'ed. */
object Cores {
    private val bundled = mapOf(GameSystem.NES to "libfceumm_libretro.so")

    fun isAvailable(system: GameSystem) = system in bundled

    fun corePath(ctx: Context, system: GameSystem): String? {
        val name = bundled[system] ?: return null
        val dir = File(ctx.applicationInfo.nativeLibraryDir).canonicalFile
        val f = File(dir, name).canonicalFile
        return if (f.parentFile == dir && f.isFile) f.path else null
    }
}
