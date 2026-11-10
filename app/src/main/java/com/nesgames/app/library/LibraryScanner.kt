package com.nesgames.app.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.util.zip.ZipInputStream

data class Game(
    val id: String,          // document uri (+ "!" + zip entry)
    val title: String,
    val fileName: String,
    val system: GameSystem,
    val uri: Uri,
    val zipEntry: String?,
    val size: Long,
)

data class ScanResult(val games: List<Game>, val detectedUnsupported: Int, val rejected: Int)

/** Walks the user-granted SAF tree. Read-only, bounded depth and bounded reads. */
class LibraryScanner(private val ctx: Context) {
    private val maxDepth = 6
    private val maxZipEntries = 256
    private val maxRomBytes = 64L shl 20

    fun scan(tree: Uri): ScanResult {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return ScanResult(emptyList(), 0, 0)
        val games = ArrayList<Game>()
        var unsupported = 0
        var rejected = 0
        fun walk(dir: DocumentFile, depth: Int) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory) {
                    if (depth < maxDepth && !name.equals("BIOS", true) && !name.startsWith(".")) walk(f, depth + 1)
                    continue
                }
                if (!RomInspector.isCandidate(name) || f.length() > maxRomBytes) continue
                val found = runCatching { inspect(f, name) }.getOrNull()
                if (found.isNullOrEmpty()) { rejected++; continue }
                for (g in found) if (com.nesgames.app.emu.Cores.isAvailable(g.system)) games += g else unsupported++
            }
        }
        walk(root, 0)
        games.sortBy { it.title.lowercase() }
        return ScanResult(games, unsupported, rejected)
    }

    private fun inspect(f: DocumentFile, name: String): List<Game> {
        val ext = RomInspector.extensionOf(name)
        ctx.contentResolver.openInputStream(f.uri)?.use { input ->
            if (ext == "zip") return inspectZip(input, f, name)
            val sys = RomInspector.identify(name, readUpTo(input, RomInspector.HEADER_BYTES)) ?: return emptyList()
            return listOf(Game(f.uri.toString(), titleOf(name), name, sys, f.uri, null, f.length()))
        }
        return emptyList()
    }

    /** A cartridge zip holds one recognised ROM; zips with many members (arcade sets) are not listed here. */
    private fun inspectZip(input: InputStream, f: DocumentFile, name: String): List<Game> {
        val hits = ArrayList<Game>()
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (++entries > maxZipEntries) return emptyList()
                if (e.isDirectory || !RomInspector.isSafeEntryName(e.name)) continue
                val sys = RomInspector.identify(e.name, readUpTo(zip, RomInspector.HEADER_BYTES)) ?: continue
                hits += Game("${f.uri}!${e.name}", titleOf(name), name, sys, f.uri, e.name, f.length())
            }
        }
        return if (hits.size == 1) hits else emptyList()
    }

    companion object {
        fun readUpTo(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) { val r = input.read(buf, off, n - off); if (r < 0) break; off += r }
            return buf.copyOf(off)
        }

        fun titleOf(fileName: String) = fileName.substringBeforeLast('.')
            .replace('_', ' ').replace(Regex("\\s*[\\[(][^\\])]*[\\])]"), "").trim().ifEmpty { fileName }

        /** Reads a ROM fully into memory with a hard cap; zip entries are extracted in-memory, never to disk. */
        fun loadRom(ctx: Context, game: Game, cap: Int = 16 shl 20): ByteArray {
            val input = ctx.contentResolver.openInputStream(game.uri) ?: error("Cannot open ${game.fileName}")
            input.use {
                val stream: InputStream = if (game.zipEntry == null) it else {
                    val zip = ZipInputStream(it)
                    while (true) { val e = zip.nextEntry ?: error("Entry missing"); if (e.name == game.zipEntry) break }
                    zip
                }
                val data = readUpTo(stream, cap + 1)
                require(data.size <= cap) { "ROM is larger than ${cap shr 20} MiB" }
                return data
            }
        }
    }
}
