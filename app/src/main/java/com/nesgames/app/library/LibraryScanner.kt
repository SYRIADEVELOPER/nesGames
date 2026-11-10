package com.nesgames.app.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class Game(
    val id: String,          // document uri (+ "!" + zip entry)
    val title: String,
    val fileName: String,
    val system: GameSystem,
    val uri: Uri,
    val zipEntry: String?,
    val size: Long,
    val discFiles: List<String> = emptyList(),   // for CUE discs: track files next to the cue
    val dirUri: Uri? = null,
)

data class ScanResult(val games: List<Game>, val detectedUnsupported: Int, val rejected: Int)

/** One sub-folder per console inside the user's chosen nesGames folder. */
object ConsoleFolders {
    val folders = linkedMapOf(
        "NES" to GameSystem.NES,
        "MegaDrive" to GameSystem.MD,
        "PS1" to GameSystem.PS1,
        "GameBoy" to GameSystem.GB,
        "GBA" to GameSystem.GBA,
        "SNES" to GameSystem.SNES,
    )
    private val aliases = mapOf(
        "nes" to GameSystem.NES, "famicom" to GameSystem.NES,
        "megadrive" to GameSystem.MD, "genesis" to GameSystem.MD, "md" to GameSystem.MD,
        "ps1" to GameSystem.PS1, "psx" to GameSystem.PS1, "playstation" to GameSystem.PS1,
        "gameboy" to GameSystem.GB, "gb" to GameSystem.GB, "gbc" to GameSystem.GBC,
        "gba" to GameSystem.GBA, "snes" to GameSystem.SNES,
    )
    fun systemFor(dirName: String) = aliases[dirName.lowercase().replace(" ", "").replace("-", "")]

    /** Creates any missing console folders (+ BIOS). Needs write access to the tree. */
    fun ensure(ctx: Context, tree: Uri): Int {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return 0
        val existing = root.listFiles().filter { it.isDirectory }.mapNotNull { it.name?.lowercase() }.toSet()
        var made = 0
        for (name in folders.keys + "BIOS") if (name.lowercase() !in existing && root.createDirectory(name) != null) made++
        return made
    }
}

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
        fun walk(dir: DocumentFile, depth: Int, hint: GameSystem?) {
            val children = dir.listFiles()
            for (f in children) {
                val name = f.name ?: continue
                if (f.isDirectory) {
                    if (depth < maxDepth && !name.equals("BIOS", true) && !name.startsWith("."))
                        walk(f, depth + 1, ConsoleFolders.systemFor(name) ?: hint)
                    continue
                }
                if (!RomInspector.isCandidate(name)) continue
                val found = runCatching {
                    if (RomInspector.extensionOf(name) == "cue") inspectCue(f, name, dir, children, hint)
                    else if (f.length() <= maxRomBytes) inspect(f, name) else emptyList()
                }.getOrNull()
                if (found.isNullOrEmpty()) { rejected++; continue }
                for (g in found) if (com.nesgames.app.emu.Cores.isAvailable(g.system)) games += g else unsupported++
            }
        }
        walk(root, 0, null)
        games.sortBy { it.title.lowercase() }
        return ScanResult(games, unsupported, rejected)
    }

    /** A CUE is accepted only if every referenced track file exists next to it. */
    private fun inspectCue(f: DocumentFile, name: String, dir: DocumentFile, siblings: Array<DocumentFile>, hint: GameSystem?): List<Game> {
        val text = ctx.contentResolver.openInputStream(f.uri)?.use { String(readUpTo(it, 64 * 1024)) } ?: return emptyList()
        val parsed = CueSheet.parse(text) ?: return emptyList()
        val byName = siblings.associateBy { it.name }
        if (parsed.files.any { byName[it] == null || !RomInspector.isSafeEntryName(it) || it.contains('/') }) return emptyList()
        // Only PlayStation discs are supported; data tracks are MODE2 on PS1
        val system = hint ?: if (parsed.mode2) GameSystem.PS1 else return emptyList()
        if (system != GameSystem.PS1) return emptyList()
        val size = parsed.files.sumOf { byName[it]!!.length() }
        return listOf(Game(f.uri.toString(), titleOf(name), name, system, f.uri, null, size, parsed.files, dir.uri))
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

        /**
         * Copies a CUE disc (cue + track files) into app-private storage once, because native cores
         * open the cue's track files by path. Re-uses the copy when sizes still match.
         */
        fun importDisc(ctx: Context, cueUri: Uri, dirUri: Uri, cueName: String, files: List<String>, progress: (Float) -> Unit): File {
            val key = MessageDigest.getInstance("SHA-1").digest(cueUri.toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
            val dest = File(ctx.filesDir, "discs/$key").apply { mkdirs() }
            val docs = listChildren(ctx, dirUri)
            val all = files + cueName
            val total = all.sumOf { docs[it]?.second ?: 0L }.coerceAtLeast(1)
            var done = 0L
            for (n in all) {
                require(RomInspector.isSafeEntryName(n) && !n.contains('/')) { "Unsafe file name in cue" }
                val (srcUri, srcLen) = docs[n] ?: error("Missing $n")
                val out = File(dest, n)
                if (out.length() == srcLen) { done += srcLen; progress(done.toFloat() / total); continue }
                val tmp = File(dest, "$n.part")
                ctx.contentResolver.openInputStream(srcUri)!!.use { ins ->
                    tmp.outputStream().use { os ->
                        val buf = ByteArray(1 shl 20)
                        while (true) { val r = ins.read(buf); if (r < 0) break; os.write(buf, 0, r); done += r; progress(done.toFloat() / total) }
                    }
                }
                require(tmp.renameTo(out)) { "Could not store $n" }
            }
            return File(dest, cueName)
        }

        /** name -> (document uri, size) for the direct children of a SAF directory document. */
        fun listChildren(ctx: Context, dirUri: Uri): Map<String, Pair<Uri, Long>> {
            val parentId = android.provider.DocumentsContract.getDocumentId(dirUri)
            val q = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, parentId)
            val out = HashMap<String, Pair<Uri, Long>>()
            ctx.contentResolver.query(q, arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) out[c.getString(1)] = android.provider.DocumentsContract.buildDocumentUriUsingTree(dirUri, c.getString(0)) to c.getLong(2)
            }
            return out
        }
    }
}

/** Minimal CUE parser: referenced FILE names and whether any track is MODE2 (PlayStation data). */
object CueSheet {
    data class Parsed(val files: List<String>, val mode2: Boolean)
    fun parse(text: String): Parsed? {
        val files = ArrayList<String>()
        var mode2 = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("FILE", true)) {
                val q = Regex("^FILE\\s+\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)
                    ?: line.split(Regex("\\s+")).getOrNull(1) ?: return null
                files += q
            } else if (line.startsWith("TRACK", true) && line.contains("MODE2", true)) mode2 = true
        }
        return if (files.isEmpty()) null else Parsed(files.distinct(), mode2)
    }
}
