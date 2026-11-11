package com.nesgames.app.ui

import android.content.Context
import com.nesgames.app.library.GameSystem
import java.io.File

/** Saved touch layouts (files/layouts/<SYSTEM>.txt) and gamepad maps (SharedPreferences), one per console. */
object LayoutStore {
    private fun file(ctx: Context, s: GameSystem) = File(File(ctx.filesDir, "layouts").apply { mkdirs() }, "${s.name}.txt")

    fun load(ctx: Context, s: GameSystem): PadLayout =
        runCatching { PadLayout.parse(file(ctx, s).readText(), s) }.getOrNull() ?: PadLayout(s)

    fun save(ctx: Context, l: PadLayout) {
        val f = file(ctx, l.system); val tmp = File(f.path + ".tmp")
        tmp.writeText(l.serialize()); if (!tmp.renameTo(f)) tmp.delete()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("padmap", Context.MODE_PRIVATE)
    fun loadMap(ctx: Context, s: GameSystem) = PadMap.parse(prefs(ctx).getString(s.name, null))
    fun saveMap(ctx: Context, s: GameSystem, m: PadMap) = prefs(ctx).edit().putString(s.name, m.serialize()).apply()
    fun resetMap(ctx: Context, s: GameSystem) = prefs(ctx).edit().remove(s.name).apply()
}
