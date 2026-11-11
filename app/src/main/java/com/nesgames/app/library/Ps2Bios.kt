package com.nesgames.app.library

import android.content.Context
import java.io.File

/**
 * PS2 BIOS manager. Images are user-supplied only (copied from Download/nesGames/BIOS by the scanner);
 * nothing is ever downloaded. Each image is identified from its own ROMDIR/ROMVER table.
 */
object Ps2Bios {
    data class Info(val file: File, val valid: Boolean, val version: String?, val region: String?, val date: String?)

    fun dir(ctx: Context) = File(ctx.filesDir, "system/pcsx2/bios")

    /** NVRAM (console settings, clock) and MECHACON files that sit next to a BIOS image with the same name. */
    val COMPANIONS = setOf("nvm", "mec")

    fun installed(ctx: Context): List<Info> =
        dir(ctx).listFiles { f -> f.isFile && f.extension.lowercase() !in COMPANIONS && !f.name.endsWith(".part") }.orEmpty().sortedBy { it.name }.map { inspect(it) }

    fun inspect(f: File): Info {
        if (f.length() != 4L shl 20) return Info(f, false, null, null, null)
        val bytes = f.readBytes()
        val head = bytes.copyOf(256 * 1024)
        if (!RomInspector.isPs2Bios(bytes.size.toLong(), head)) return Info(f, false, null, null, null)
        // A main BIOS always carries ROMVER; ROM1/DVD-player images have a ROMDIR too but cannot boot a console.
        val v = romver(bytes) ?: return Info(f, false, null, null, null)
        return Info(f, true, v.first, v.second, v.third)
    }

    private val REGIONS = mapOf('J' to "Japan", 'A' to "USA", 'E' to "Europe", 'H' to "Asia", 'C' to "China", 'T' to "TEST", 'X' to "QA")

    /**
     * ROMDIR = 16-byte entries {name[10], extInfoSize u16, size u32} starting at the "RESET" entry;
     * files follow from offset 0, each padded to 16 bytes. ROMVER text looks like "0230EC20040614".
     * @return (version "2.30", region, date "2004-06-14") or null.
     */
    fun romver(rom: ByteArray): Triple<String, String, String>? {
        val start = indexOf(rom, "RESET".toByteArray() + ByteArray(5)) ?: return null
        var p = start; var offset = 0L
        while (p + 16 <= rom.size) {
            val name = String(rom, p, 10, Charsets.ISO_8859_1).trimEnd('\u0000')
            if (name.isEmpty()) break
            val size = (rom[p + 12].toLong() and 0xFF) or ((rom[p + 13].toLong() and 0xFF) shl 8) or
                ((rom[p + 14].toLong() and 0xFF) shl 16) or ((rom[p + 15].toLong() and 0xFF) shl 24)
            if (name == "ROMVER") {
                if (offset < 0 || offset + 14 > rom.size) return null
                val t = String(rom, offset.toInt(), 14, Charsets.ISO_8859_1)
                if (!t.substring(0, 4).all { it.isDigit() } || !t.substring(6, 14).all { it.isDigit() }) return null
                val ver = "${t.substring(0, 2).toInt()}.${t.substring(2, 4)}"
                val region = REGIONS[t[4]] ?: t[4].toString()
                val date = "${t.substring(6, 10)}-${t.substring(10, 12)}-${t.substring(12, 14)}"
                return Triple(ver, region, date)
            }
            offset += (size + 15) / 16 * 16
            p += 16
        }
        return null
    }

    private fun indexOf(h: ByteArray, n: ByteArray): Int? {
        outer@ for (i in 0..h.size - n.size) { for (j in n.indices) if (h[i + j] != n[j]) continue@outer; return i }
        return null
    }
}
