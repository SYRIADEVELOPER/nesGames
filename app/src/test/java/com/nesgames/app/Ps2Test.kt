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
    /** Synthetic ROMDIR (not a real BIOS): RESET 0x2800 bytes, ROMDIR 0x60, EXTINFO 0x10, ROMVER 0x10. */
    private fun syntheticRom(romver: String): ByteArray {
        val rom = ByteArray(4 shl 20)
        fun entry(at: Int, name: String, size: Int) {
            name.toByteArray().copyInto(rom, at)
            rom[at + 12] = size.toByte(); rom[at + 13] = (size shr 8).toByte(); rom[at + 14] = (size shr 16).toByte(); rom[at + 15] = (size shr 24).toByte()
        }
        entry(0x2700, "RESET", 0x2800); entry(0x2710, "ROMDIR", 0x60); entry(0x2720, "EXTINFO", 0x10); entry(0x2730, "ROMVER", 0x10)
        romver.toByteArray().copyInto(rom, 0x2800 + 0x60 + 0x10)
        return rom
    }
    @Test fun romverIsParsed() {
        val v = com.nesgames.app.library.Ps2Bios.romver(syntheticRom("0230EC20040614"))!!
        assertEquals("2.30", v.first); assertEquals("Europe", v.second); assertEquals("2004-06-14", v.third)
        assertEquals("USA", com.nesgames.app.library.Ps2Bios.romver(syntheticRom("0160AC20011004"))!!.second)
        assertNull(com.nesgames.app.library.Ps2Bios.romver(syntheticRom("garbage-garbage")))
        assertNull(com.nesgames.app.library.Ps2Bios.romver(ByteArray(4 shl 20)))
    }
    @Test fun ps2PadHasTwoSticksAndAllShoulders() {
        val l = com.nesgames.app.ui.PadLayout(GameSystem.PS2)
        val g = com.nesgames.app.ui.Geometry(l, 3120f, 1440f)
        val ls = l.elem("LSTICK")!!; val rs = l.elem("RSTICK")!!
        assertEquals(ls, g.stickAt(g.cx(ls), g.cy(ls))); assertEquals(rs, g.stickAt(g.cx(rs), g.cy(rs)))
        assertEquals(0, g.maskAt(g.cx(ls), g.cy(ls)))                                  // a stick never presses a button
        assertEquals(0 to 0, g.stickValue(ls, g.cx(ls), g.cy(ls)))                     // dead zone
        assertEquals(32767 to 0, g.stickValue(ls, g.cx(ls) + 10_000f, g.cy(ls)))       // clamped
        for (id in listOf("L1", "R1", "L2", "R2", "CROSS", "CIRCLE", "SQUARE", "TRIANGLE", "START", "SELECT"))
            assertEquals(id, com.nesgames.app.emu.RetroPad.bit(l.elem(id)!!.spec.retro), g.maskAt(g.cx(l.elem(id)!!), g.cy(l.elem(id)!!)))
        // nothing a finger aims at overlaps a stick's grab area
        for (e in l.elems.filter { it.spec.shape != com.nesgames.app.ui.Shape.STICK && it.spec.shape != com.nesgames.app.ui.Shape.MENU })
            assertNull(e.spec.id, g.stickAt(g.cx(e), g.cy(e)))
    }
    @Test fun gamepadTriggersAndThumbsMapForPs2() {
        val m = com.nesgames.app.ui.PadMap()
        assertEquals(12, m.lookup(com.nesgames.app.ui.PadMap.BUTTON_L2)); assertEquals(13, m.lookup(com.nesgames.app.ui.PadMap.BUTTON_R2))
        assertEquals(14, m.lookup(com.nesgames.app.ui.PadMap.BUTTON_THUMBL)); assertEquals(15, m.lookup(com.nesgames.app.ui.PadMap.BUTTON_THUMBR))
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
