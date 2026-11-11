package com.nesgames.app.library

import android.content.Context
import java.io.File
import java.util.zip.CRC32

/**
 * Per-game save storage in app-private files/saves/<key>/:
 *   game.srm            in-game battery save / PS1 memory card (written by the core)
 *   slot0.state/.png    auto-save on exit (used by "Continue")
 *   slot1..4.state/.png manual save slots with a screenshot
 *   slotN.meta          "size crc32" integrity record (absent for saves made by older builds)
 * The .state file is the raw core state, unchanged since the first build, so old saves keep loading.
 */
object SaveSlots {
    const val AUTO = 0
    val MANUAL = 1..4

    enum class Health { OK, LEGACY, EMPTY, CORRUPT }

    fun dir(ctx: Context, key: String) = File(ctx.filesDir, "saves/$key").apply { mkdirs() }
    fun state(ctx: Context, key: String, slot: Int) = File(dir(ctx, key), "slot$slot.state")
    fun thumb(ctx: Context, key: String, slot: Int) = File(dir(ctx, key), "slot$slot.png")
    fun meta(ctx: Context, key: String, slot: Int) = File(dir(ctx, key), "slot$slot.meta")
    fun sram(ctx: Context, key: String) = File(dir(ctx, key), "game.srm")
    fun exists(ctx: Context, key: String, slot: Int) = state(ctx, key, slot).length() > 0

    /** Save key for a library game the first time it is seen (kept in the database afterwards, so it survives renames). */
    fun keyFor(gameId: String) = Covers.key(gameId)

    fun crcOf(f: File): Long {
        val crc = CRC32(); val buf = ByteArray(1 shl 16)
        f.inputStream().use { while (true) { val n = it.read(buf); if (n < 0) break; crc.update(buf, 0, n) } }
        return crc.value
    }

    /** Written after the state file is safely on disk. A crash in between leaves a LEGACY (size-checked only) slot, never a false CORRUPT. */
    fun writeMeta(state: File, meta: File) {
        val tmp = File(meta.path + ".tmp")
        tmp.writeText("${state.length()} ${crcOf(state)}")
        if (!tmp.renameTo(meta)) tmp.delete()
    }

    fun health(state: File, meta: File): Health {
        if (state.length() <= 0L) return Health.EMPTY
        if (!meta.exists()) return Health.LEGACY
        val p = runCatching { meta.readText().trim().split(' ') }.getOrNull() ?: return Health.CORRUPT
        val size = p.getOrNull(0)?.toLongOrNull(); val crc = p.getOrNull(1)?.toLongOrNull()
        if (size == null || crc == null) return Health.CORRUPT
        return if (size == state.length() && crc == crcOf(state)) Health.OK else Health.CORRUPT
    }

    fun health(ctx: Context, key: String, slot: Int) = health(state(ctx, key, slot), meta(ctx, key, slot))
}
