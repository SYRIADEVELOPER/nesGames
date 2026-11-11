package com.nesgames.app

import com.nesgames.app.library.ConsoleFolders
import com.nesgames.app.library.GameSystem
import com.nesgames.app.library.RomInspector
import org.junit.Assert.*
import org.junit.Test

class Ps2Test {
    private fun fakeBios(): ByteArray {
        val b = ByteArray(256 * 1024)
        val entry = "RESET".toByteArray() + ByteArray(5) + ByteArray(6) + "ROMDIR".toByteArray()
        entry.copyInto(b, 0x2700)
        return b
    }
    @Test fun biosNeedsExactSizeAndRomdir() {
        assertTrue(RomInspector.isPs2Bios(4L shl 20, fakeBios()))
        assertFalse(RomInspector.isPs2Bios(4L shl 20, ByteArray(256 * 1024)))          // a 4 MB file that is not a BIOS
        assertFalse(RomInspector.isPs2Bios(4L shl 20 - 1, fakeBios()))                  // wrong size
        assertFalse(RomInspector.isPs2Bios(8L shl 20, fakeBios()))
    }
    @Test fun iso9660Header() {
        assertTrue(RomInspector.isIso9660(byteArrayOf(1, 'C'.code.toByte(), 'D'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), '1'.code.toByte(), 1, 0)))
        assertFalse(RomInspector.isIso9660(ByteArray(8)))
    }
    @Test fun ps2FolderAndExtensions() {
        assertEquals(GameSystem.PS2, ConsoleFolders.systemFor("PS2"))
        assertEquals(GameSystem.PS2, ConsoleFolders.systemFor("PlayStation 2"))
        assertTrue(RomInspector.isCandidate("Black (USA).iso"))
        assertTrue(ConsoleFolders.folders.containsKey("PS2"))
    }
}
