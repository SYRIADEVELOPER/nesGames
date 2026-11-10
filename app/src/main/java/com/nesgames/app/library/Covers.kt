package com.nesgames.app.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Automatic cover art:
 *  1. official box art from the libretro thumbnail database (looked up by file name only),
 *  2. otherwise a gameplay snapshot saved by GameActivity,
 *  3. otherwise the UI draws a generated tile.
 * Everything is cached in app storage; misses are remembered for a week.
 */
object Covers {
    private const val TAG = "nesGamesCovers"
    private const val MAX_BYTES = 3 shl 20
    private const val MISS_TTL_MS = 7L * 24 * 3600 * 1000

    private val dbSystem = mapOf(
        GameSystem.NES to "Nintendo - Nintendo Entertainment System",
        GameSystem.MD to "Sega - Mega Drive - Genesis",
        GameSystem.PS1 to "Sony - PlayStation",
        GameSystem.GB to "Nintendo - Game Boy",
        GameSystem.GBC to "Nintendo - Game Boy Color",
        GameSystem.GBA to "Nintendo - Game Boy Advance",
        GameSystem.SNES to "Nintendo - Super Nintendo Entertainment System",
    )

    fun key(gameId: String) = MessageDigest.getInstance("SHA-1").digest(gameId.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    private fun dir(ctx: Context) = File(ctx.filesDir, "covers").apply { mkdirs() }
    fun boxArtFile(ctx: Context, gameId: String) = File(dir(ctx), key(gameId) + ".png")
    fun snapshotFile(ctx: Context, gameId: String) = File(dir(ctx), key(gameId) + "_snap.png")
    private fun missFile(ctx: Context, gameId: String) = File(dir(ctx), key(gameId) + ".miss")

    /** Best available cover file, or null. */
    fun bestFile(ctx: Context, gameId: String): File? =
        boxArtFile(ctx, gameId).takeIf { it.length() > 0 } ?: snapshotFile(ctx, gameId).takeIf { it.length() > 0 }

    fun decode(f: File, maxPx: Int = 512): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= maxPx && o.outHeight / (s * 2) >= maxPx) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** libretro thumbnail naming: these characters are replaced by '_' in file names. */
    fun thumbnailName(fileName: String) = fileName.substringBeforeLast('.').replace(Regex("[&*/:`<>?\\\\|\"]"), "_")

    fun thumbnailUrl(system: GameSystem, fileName: String): String? {
        val sys = dbSystem[system] ?: return null
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "https://thumbnails.libretro.com/${enc(sys)}/Named_Boxarts/${enc(thumbnailName(fileName))}.png"
    }

    /** Downloads box art if not cached. Returns true if a new image was stored. Call off the main thread. */
    fun fetchBoxArt(ctx: Context, game: Game): Boolean {
        val out = boxArtFile(ctx, game.id)
        if (out.length() > 0) return false
        val miss = missFile(ctx, game.id)
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < MISS_TTL_MS) return false
        val url = thumbnailUrl(game.system, game.fileName) ?: return false
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 10000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "nesGames/0.1")
            }
            try {
                if (c.responseCode != 200) { Log.i(TAG, "no box art (${c.responseCode}) for ${game.fileName}"); miss.writeText(""); return false }
                val bytes = c.inputStream.use { LibraryScanner.readUpTo(it, MAX_BYTES + 1) }
                val png = bytes.size in 8..MAX_BYTES && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()
                if (!png) { miss.writeText(""); return false }
                val tmp = File(out.path + ".part"); tmp.writeBytes(bytes)
                if (decode(tmp, 64) == null) { tmp.delete(); miss.writeText(""); return false }   // must be a real image
                tmp.renameTo(out).also { Log.i(TAG, "box art stored for ${game.fileName}") }
            } finally { c.disconnect() }
        } catch (e: Exception) {
            Log.i(TAG, "box art fetch failed for ${game.fileName}: ${e.message}")   // offline: retry next scan
            false
        }
    }

    /** Saves a gameplay frame (ARGB pixels) as the fallback cover. */
    fun saveSnapshot(ctx: Context, gameId: String, argb: IntArray, w: Int, h: Int) {
        if (w <= 0 || h <= 0 || argb.size < w * h) return
        val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        val f = snapshotFile(ctx, gameId)
        val tmp = File(f.path + ".part")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(f)
    }
}
