package com.nesgames.app.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class Game(
    val id: String,
    val title: String,
    val fileName: String,
    val system: GameSystem,
    val uri: Uri,
    val zipEntry: String?,
    val size: Long,
    val discFiles: List<String> = emptyList(),
    val dirUri: Uri? = null,
    val saveKey: String = SaveSlots.keyFor(id),
    val favorite: Boolean = false,
    val lastPlayed: Long = 0,
)

fun GameRow.toGame(): Game? {
    val sys = runCatching { GameSystem.valueOf(system) }.getOrNull() ?: return null
    return Game(id, title, fileName, sys, Uri.parse(uri), zipEntry, size,
        if (discFiles.isEmpty()) emptyList() else discFiles.split('\n'), dirUri?.let(Uri::parse), saveKey, favorite, lastPlayed)
}

data class ScanStats(val files: Int, val reused: Int, val inspected: Int, val added: Int, val renamed: Int,
                     val missing: Int, val unsupported: Int, val rejected: Int, val millis: Long)

/** One sub-folder per console inside the user's chosen nesGames folder. */
object ConsoleFolders {
    val folders = linkedMapOf(
        "NES" to GameSystem.NES,
        "MegaDrive" to GameSystem.MD,
        "PS1" to GameSystem.PS1,
        "PS2" to GameSystem.PS2,
        "GameBoy" to GameSystem.GB,
        "GBA" to GameSystem.GBA,
        "SNES" to GameSystem.SNES,
    )
    private val aliases = mapOf(
        "nes" to GameSystem.NES, "famicom" to GameSystem.NES,
        "megadrive" to GameSystem.MD, "genesis" to GameSystem.MD, "md" to GameSystem.MD,
        "ps1" to GameSystem.PS1, "psx" to GameSystem.PS1, "playstation" to GameSystem.PS1,
        "ps2" to GameSystem.PS2, "playstation2" to GameSystem.PS2,
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

/**
 * Walks the user-granted SAF tree and keeps the Room cache in step with it.
 * A file whose document (uri, size, modified time) is unchanged is taken from the cache without being opened.
 * Read-only on the user's files, bounded depth and bounded reads.
 */
class LibraryScanner(private val ctx: Context) {
    private val maxDepth = 6
    private val maxZipEntries = 256
    private val maxRomBytes = 64L shl 20

    private data class Doc(val uri: Uri, val docId: String, val name: String, val isDir: Boolean, val size: Long, val mtime: Long)

    /** One provider query per directory (much cheaper than DocumentFile's query-per-file). */
    private fun children(tree: Uri, parentDocId: String): List<Doc> {
        val out = ArrayList<Doc>()
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        ctx.contentResolver.query(q, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                out += Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id), id, c.getString(1) ?: continue,
                    c.getString(2) == Document.MIME_TYPE_DIR, c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    suspend fun scan(tree: Uri): ScanStats {
        val t0 = System.currentTimeMillis()
        val dao = GameDb.get(ctx).games()
        val cached = dao.all()
        val byUri = cached.groupBy { it.uri }
        val seen = HashSet<String>()
        val upserts = ArrayList<GameRow>()
        val fresh = ArrayList<Pair<Game, Doc>>()
        var files = 0; var reused = 0; var inspected = 0; var unsupported = 0; var rejected = 0

        fun walk(dirId: String, dirUri: Uri, depth: Int, hint: GameSystem?) {
            val kids = runCatching { children(tree, dirId) }.getOrDefault(emptyList())
            val names = kids.filter { !it.isDir }.associateBy { it.name }
            for (d in kids) {
                if (d.isDir) {
                    if (depth < maxDepth && !d.name.equals("BIOS", true) && !d.name.startsWith("."))
                        walk(d.docId, d.uri, depth + 1, ConsoleFolders.systemFor(d.name) ?: hint)
                    continue
                }
                if (!RomInspector.isCandidate(d.name)) continue
                files++
                val rows = byUri[d.uri.toString()]
                val unchanged = !rows.isNullOrEmpty() && d.mtime != 0L && rows.all {
                    it.fileSize == d.size && it.mtime == d.mtime &&
                        (it.discFiles.isEmpty() || it.discFiles.split('\n').all { n -> names.containsKey(n) })   // disc tracks still present
                }
                if (unchanged) {
                    reused++
                    for (r in rows!!) { seen += r.id; if (r.missing) upserts += r.copy(missing = false) }
                    continue
                }
                inspected++
                val found = runCatching {
                    val ext = RomInspector.extensionOf(d.name)
                    if (ext == "iso" || ext == "cso" || ext == "chd") inspectDisc(d, hint)
                    else if (ext == "cue") inspectCue(d, dirUri, names, hint)
                    else if (d.size <= maxRomBytes) inspect(d) else emptyList()
                }.getOrNull()
                if (found.isNullOrEmpty()) { rejected++; continue }
                for (g in found) if (com.nesgames.app.emu.Cores.isAvailable(g.system)) fresh += g to d else unsupported++
            }
        }
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        walk(rootId, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), 0, null)
        runCatching { importBios(tree, rootId) }.onFailure { Log.w("nesGamesScan", "BIOS import: ${it.message}") }

        // New or changed files: keep user data when the id is known, or when a vanished entry has the same fingerprint (rename/move).
        val byId = cached.associateBy { it.id }
        val orphans = cached.filter { it.id !in seen }.toMutableList()
        var added = 0; var renamed = 0
        for ((g, d) in fresh) {
            val fp = runCatching { fingerprint(g, d) }.getOrDefault("")
            val old = byId[g.id]
            val moved = if (old == null && fp.isNotEmpty()) orphans.firstOrNull { it.fingerprint == fp && it.system == g.system.name && it.id !in seen } else null
            val keep = old ?: moved
            if (moved != null) { dao.delete(moved.id); orphans.remove(moved); renamed++ } else if (old == null) added++
            seen += g.id
            upserts += GameRow(g.id, g.title, g.fileName, g.system.name, g.uri.toString(), g.zipEntry, g.size, d.size, d.mtime,
                g.discFiles.joinToString("\n"), g.dirUri?.toString(), fp, keep?.saveKey ?: SaveSlots.keyFor(g.id),
                keep?.favorite ?: false, keep?.lastPlayed ?: 0, false)
        }
        if (upserts.isNotEmpty()) dao.upsert(upserts)
        val gone = cached.filter { it.id !in seen && !it.missing }.map { it.id }
        gone.chunked(500).forEach { dao.markMissing(it) }   // flagged, never deleted: saves and favourites stay
        val stats = ScanStats(files, reused, inspected, added, renamed, gone.size, unsupported, rejected, System.currentTimeMillis() - t0)
        Log.i("nesGamesScan", stats.toString())
        return stats
    }

    /** Copies validated PS2 BIOS images from <root>/BIOS into app storage (files/system/pcsx2/bios). Never downloads anything. */
    private fun importBios(tree: Uri, rootId: String) {
        val biosDir = children(tree, rootId).firstOrNull { it.isDir && it.name.equals("BIOS", true) } ?: return
        val dest = File(ctx.filesDir, "system/pcsx2/bios").apply { mkdirs() }
        val kids = children(tree, biosDir.docId).filter { !it.isDir && RomInspector.isSafeEntryName(it.name) && !it.name.contains('/') }
        for (d in kids) {
            if (d.size != 4L shl 20) continue
            val out = File(dest, d.name)
            if (out.length() != d.size) {
                val data = ctx.contentResolver.openInputStream(d.uri)?.use { readUpTo(it, (4 shl 20) + 1) } ?: continue
                if (!RomInspector.isPs2Bios(data.size.toLong(), data.copyOf(minOf(data.size, 256 * 1024)))) { Log.i("nesGamesScan", "BIOS skipped (not a PS2 BIOS): ${d.name}"); continue }
                if (Ps2Bios.romver(data) == null) { Log.i("nesGamesScan", "BIOS skipped (no ROMVER, not a main PS2 BIOS): ${d.name}"); continue }
                val tmp = File(dest, d.name + ".part"); tmp.writeBytes(data); tmp.renameTo(out)
                Log.i("nesGamesScan", "PS2 BIOS imported: ${d.name}")
            }
            // Companion .nvm/.mec (same base name) are copied once and never overwritten: the core keeps them updated.
            val base = d.name.substringBeforeLast('.')
            for (c in kids) {
                val ext = c.name.substringAfterLast('.', "").lowercase()
                if (ext !in Ps2Bios.COMPANIONS || !c.name.substringBeforeLast('.').equals(base, true) || c.size > 64 * 1024) continue
                val co = File(dest, "$base.$ext")
                if (co.exists()) continue
                ctx.contentResolver.openInputStream(c.uri)?.use { s -> val b = readUpTo(s, 64 * 1024); File(dest, co.name + ".part").apply { writeBytes(b); renameTo(co) } }
                Log.i("nesGamesScan", "PS2 BIOS companion copied: ${co.name}")
            }
        }
    }

    private fun head(uri: Uri, n: Int) = ctx.contentResolver.openInputStream(uri)?.use { readUpTo(it, n) } ?: ByteArray(0)

    private fun fingerprint(g: Game, d: Doc): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(head(d.uri, 64 * 1024))
        return md.digest().joinToString("") { "%02x".format(it) } + ":" + g.size
    }

    /** Single-file PS2 disc images (.iso/.cso/.chd) live in the PS2 folder; an .iso must carry an ISO 9660 header. */
    private fun inspectDisc(d: Doc, hint: GameSystem?): List<Game> {
        if (hint != GameSystem.PS2) return emptyList()
        when (RomInspector.extensionOf(d.name)) {
            "iso" -> {
                val head = ctx.contentResolver.openInputStream(d.uri)?.use { s -> s.skip(0x8000); readUpTo(s, 8) } ?: return emptyList()
                if (!RomInspector.isIso9660(head)) return emptyList()
            }
            "chd" -> if (String(head(d.uri, 8), Charsets.US_ASCII) != "MComprHD") return emptyList()   // MAME CHD v5 header
            "cso" -> if (String(head(d.uri, 4), Charsets.US_ASCII) != "CISO") return emptyList()       // compressed ISO
        }
        return listOf(Game(d.uri.toString(), titleOf(d.name), d.name, GameSystem.PS2, d.uri, null, d.size))
    }

    /** A CUE is accepted only if every referenced track file exists next to it. */
    private fun inspectCue(d: Doc, dirUri: Uri, siblings: Map<String, Doc>, hint: GameSystem?): List<Game> {
        val parsed = CueSheet.parse(String(head(d.uri, 64 * 1024))) ?: return emptyList()
        if (parsed.files.any { siblings[it] == null || !RomInspector.isSafeEntryName(it) || it.contains('/') }) return emptyList()
        val system = hint ?: if (parsed.mode2) GameSystem.PS1 else return emptyList()   // data tracks are MODE2 on PS1
        if (system != GameSystem.PS1) return emptyList()
        val size = parsed.files.sumOf { siblings[it]!!.size }
        return listOf(Game(d.uri.toString(), titleOf(d.name), d.name, system, d.uri, null, size, parsed.files, dirUri))
    }

    private fun inspect(d: Doc): List<Game> {
        ctx.contentResolver.openInputStream(d.uri)?.use { input ->
            if (RomInspector.extensionOf(d.name) == "zip") return inspectZip(input, d)
            val sys = RomInspector.identify(d.name, readUpTo(input, RomInspector.HEADER_BYTES)) ?: return emptyList()
            return listOf(Game(d.uri.toString(), titleOf(d.name), d.name, sys, d.uri, null, d.size))
        }
        return emptyList()
    }

    /** A cartridge zip holds one recognised ROM; zips with many members (arcade sets) are not listed here. */
    private fun inspectZip(input: InputStream, d: Doc): List<Game> {
        val hits = ArrayList<Game>()
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (++entries > maxZipEntries) return emptyList()
                if (e.isDirectory || !RomInspector.isSafeEntryName(e.name)) continue
                val sys = RomInspector.identify(e.name, readUpTo(zip, RomInspector.HEADER_BYTES)) ?: continue
                hits += Game("${d.uri}!${e.name}", titleOf(d.name), d.name, sys, d.uri, e.name, d.size)
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
        fun loadRom(ctx: Context, uri: Uri, zipEntry: String?, cap: Int = 16 shl 20): ByteArray {
            val input = ctx.contentResolver.openInputStream(uri) ?: error("Cannot open the game file")
            input.use {
                val stream: InputStream = if (zipEntry == null) it else {
                    val zip = ZipInputStream(it)
                    while (true) { val e = zip.nextEntry ?: error("Entry missing"); if (e.name == zipEntry) break }
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

        /**
         * Copies a single-file disc (PS2 .iso/.chd/.cso) into app-private storage once. Android does not let a
         * native core re-open a shared-storage document by path (/proc/self/fd re-open fails with EACCES), and
         * MANAGE_EXTERNAL_STORAGE is deliberately not requested. Re-uses the copy while the size still matches.
         */
        fun importSingleDisc(ctx: Context, uri: Uri, name: String, progress: (Float) -> Unit): File {
            require(RomInspector.isSafeEntryName(name) && !name.contains('/')) { "Unsafe file name" }
            val key = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
            val dest = File(ctx.filesDir, "discs/$key").apply { mkdirs() }
            val out = File(dest, name)
            val size = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            if (size > 0 && out.length() == size) { progress(1f); return out }
            dest.listFiles()?.forEach { it.delete() }   // an older or partial copy of this disc
            require(size <= 0 || dest.usableSpace > size + (256L shl 20)) { "Not enough free space to prepare this disc" }
            val tmp = File(dest, "$name.part")
            var done = 0L
            ctx.contentResolver.openInputStream(uri)!!.use { ins ->
                tmp.outputStream().use { os ->
                    val buf = ByteArray(4 shl 20)
                    while (true) { val r = ins.read(buf); if (r < 0) break; os.write(buf, 0, r); done += r; if (size > 0) progress(done.toFloat() / size) }
                    os.fd.sync()
                }
            }
            require(size <= 0 || tmp.length() == size) { "Disc copy incomplete" }
            require(tmp.renameTo(out)) { "Could not store $name" }
            return out
        }

        /** name -> (document uri, size) for the direct children of a SAF directory document. */
        fun listChildren(ctx: Context, dirUri: Uri): Map<String, Pair<Uri, Long>> {
            val parentId = DocumentsContract.getDocumentId(dirUri)
            val q = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, parentId)
            val out = HashMap<String, Pair<Uri, Long>>()
            ctx.contentResolver.query(q, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) out[c.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(dirUri, c.getString(0)) to c.getLong(2)
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
