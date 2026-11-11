package com.nesgames.app

import com.nesgames.app.library.Ps2Bios
import com.nesgames.app.library.RomInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic ROMDIR tables only (no real BIOS bytes): a main BIOS has ROMVER, a ROM1/DVD image does not. */
class Ps2BiosRomverTest {
    private fun image(withRomver: Boolean): ByteArray {
        val b = ByteArray(4 shl 20)
        fun entry(at: Int, name: String, size: Int) {
            name.toByteArray().copyInto(b, at)
            for (k in 0..3) b[at + 12 + k] = (size ushr (8 * k)).toByte()
        }
        entry(0x2700, "RESET", 0x2800); entry(0x2710, "ROMDIR", 0x60); entry(0x2720, "EXTINFO", 0x10)
        if (withRomver) { entry(0x2730, "ROMVER", 0x10); "0200AC20040614".toByteArray().copyInto(b, 0x2800 + 0x60 + 0x10) }
        return b
    }

    @Test fun mainBiosHasVersion() {
        val img = image(true)
        assertTrue(RomInspector.isPs2Bios(img.size.toLong(), img.copyOf(256 * 1024)))
        assertEquals(Triple("2.00", "USA", "2004-06-14"), Ps2Bios.romver(img))
    }

    @Test fun romdirWithoutRomverIsNotABios() {
        val img = image(false)
        assertTrue(RomInspector.isPs2Bios(img.size.toLong(), img.copyOf(256 * 1024)))   // structure alone passes...
        assertNull(Ps2Bios.romver(img))                                                  // ...but it cannot be imported
    }
}
