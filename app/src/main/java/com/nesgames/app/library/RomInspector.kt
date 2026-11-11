package com.nesgames.app.library

enum class GameSystem(val label: String, val short: String) {
    NES("Nintendo Entertainment System", "NES"),
    GB("Game Boy", "GB"),
    GBC("Game Boy Color", "GBC"),
    GBA("Game Boy Advance", "GBA"),
    SNES("Super Nintendo", "SNES"),
    MD("Mega Drive / Genesis", "MD"),
    PS1("PlayStation", "PS1"),
    PS2("PlayStation 2", "PS2"),
}

/**
 * Identifies ROM content from its bytes, never from the extension alone.
 * Pure Kotlin so it is covered by JVM unit tests.
 */
object RomInspector {
    const val HEADER_BYTES = 0x200

    val SUPPORTED_EXTENSIONS = setOf("nes", "unif", "unf", "gb", "gbc", "gba", "sfc", "smc", "md", "gen", "zip", "cue", "chd", "iso", "cso")
    private val IGNORED_EXTENSIONS = setOf("srm", "sav", "state", "rtc", "bin", "bios", "rom")

    fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()

    fun isCandidate(name: String): Boolean {
        val ext = extensionOf(name)
        return ext in SUPPORTED_EXTENSIONS && ext !in IGNORED_EXTENSIONS
    }

    // First 48 bytes of the Nintendo logo, required by the Game Boy boot ROM at 0x104.
    private val GB_LOGO = intArrayOf(
        0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D,
        0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99,
        0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E,
    )

    /** @param header the first [HEADER_BYTES] bytes (or fewer if the file is shorter). */
    fun identify(fileName: String, header: ByteArray): GameSystem? {
        fun u(i: Int) = if (i < header.size) header[i].toInt() and 0xFF else -1
        val ext = extensionOf(fileName)

        // iNES / NES 2.0: "NES" 0x1A, PRG size in 16 KiB units must be non-zero
        if (u(0) == 'N'.code && u(1) == 'E'.code && u(2) == 'S'.code && u(3) == 0x1A && (u(4) > 0 || (u(7) and 0x0C) == 0x08)) return GameSystem.NES
        if (u(0) == 'U'.code && u(1) == 'N'.code && u(2) == 'I'.code && u(3) == 'F'.code) return GameSystem.NES

        if (header.size >= 0x150 && GB_LOGO.indices.all { u(0x104 + it) == GB_LOGO[it] }) {
            val cgb = u(0x143)
            return if (cgb == 0x80 || cgb == 0xC0) GameSystem.GBC else GameSystem.GB
        }
        // GBA: fixed value 0x96 at 0xB2 and ARM branch opcode at 0x03
        if (header.size >= 0xC0 && u(0xB2) == 0x96 && u(0x03) == 0xEA) return GameSystem.GBA
        // Mega Drive: "SEGA" at 0x100
        if (header.size >= 0x104 && u(0x100) == 'S'.code && u(0x101) == 'E'.code && u(0x102) == 'G'.code && u(0x103) == 'A'.code) return GameSystem.MD
        // SNES has no magic number at file start; accept by extension only for later header checks (M5).
        if (ext == "sfc" || ext == "smc") return null
        return null
    }

    /** ISO 9660 volume descriptor ("CD001" at 0x8001): a disc image. Which console is decided by the folder. */
    fun isIso9660(sector16: ByteArray) = sector16.size >= 6 && String(sector16, 1, 5, Charsets.US_ASCII) == "CD001"

    /**
     * A PS2 BIOS image: 4 MiB with a ROMDIR whose first entry is "RESET". Checked before a file is copied
     * into the emulator, so a random .bin in the BIOS folder is never used as firmware.
     */
    fun isPs2Bios(size: Long, head: ByteArray): Boolean {
        if (size != 4L shl 20) return false
        val needle = byteArrayOf('R'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), 'T'.code.toByte(), 0, 0, 0, 0, 0)
        outer@ for (i in 0..head.size - needle.size) {
            for (j in needle.indices) if (head[i + j] != needle[j]) continue@outer
            val rest = String(head, i, minOf(64, head.size - i), Charsets.ISO_8859_1)
            if (rest.contains("ROMDIR")) return true
        }
        return false
    }

    /** Zip entry names that could escape a target directory or are otherwise unsafe. */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.length > 255) return false
        if (name.startsWith("/") || name.startsWith("\\") || name.contains(':')) return false
        return name.replace('\\', '/').split('/').none { it == ".." }
    }
}
