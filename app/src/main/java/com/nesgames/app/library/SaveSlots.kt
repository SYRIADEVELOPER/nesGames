package com.nesgames.app.library

import android.content.Context
import java.io.File

/**
 * Per-game save storage in app-private files/saves/<key>/:
 *   game.srm            in-game battery save / PS1 memory card (written by the core)
 *   slot0.state/.png    auto-save on exit (used by "Continue")
 *   slot1..4.state/.png manual save slots with a screenshot
 */
object SaveSlots {
    const val AUTO = 0
    val MANUAL = 1..4

    fun dir(ctx: Context, key: String) = File(ctx.filesDir, "saves/$key").apply { mkdirs() }
    fun state(ctx: Context, key: String, slot: Int) = File(dir(ctx, key), "slot$slot.state")
    fun thumb(ctx: Context, key: String, slot: Int) = File(dir(ctx, key), "slot$slot.png")
    fun sram(ctx: Context, key: String) = File(dir(ctx, key), "game.srm")
    fun exists(ctx: Context, key: String, slot: Int) = state(ctx, key, slot).length() > 0

    /** Save key for a library game (stable across rescans; same scheme as covers). */
    fun keyFor(gameId: String) = Covers.key(gameId)

    fun ago(ms: Long): String {
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} days ago"
        }
    }
}
