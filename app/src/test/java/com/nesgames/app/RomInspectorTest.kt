package com.nesgames.app

import com.nesgames.app.library.GameSystem
import com.nesgames.app.library.LibraryScanner
import com.nesgames.app.library.RomInspector
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RomInspectorTest {
    private fun ines(prg: Int = 1) = ByteArray(16).also { it[0] = 'N'.code.toByte(); it[1] = 'E'.code.toByte(); it[2] = 'S'.code.toByte(); it[3] = 0x1A; it[4] = prg.toByte() }

    @Test fun inesHeaderIsNes() = assertEquals(GameSystem.NES, RomInspector.identify("x.nes", ines()))
    @Test fun inesDetectedEvenWithWrongExtension() = assertEquals(GameSystem.NES, RomInspector.identify("x.gba", ines()))
    @Test fun zeroPrgIsRejected() = assertNull(RomInspector.identify("x.nes", ines(prg = 0)))
    @Test fun unifIsNes() = assertEquals(GameSystem.NES, RomInspector.identify("x.unf", "UNIF".toByteArray() + ByteArray(28)))
    @Test fun extensionAloneIsNotProof() = assertNull(RomInspector.identify("fake.nes", ByteArray(512) { 0x41 }))
    @Test fun emptyFileIsRejected() = assertNull(RomInspector.identify("empty.nes", ByteArray(0)))

    @Test fun gameBoyLogo() {
        val logo = intArrayOf(0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D,
            0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99,
            0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E)
        val h = ByteArray(0x200); logo.forEachIndexed { i, b -> h[0x104 + i] = b.toByte() }
        assertEquals(GameSystem.GB, RomInspector.identify("a.gb", h))
        h[0x143] = 0x80.toByte()
        assertEquals(GameSystem.GBC, RomInspector.identify("a.gbc", h))
    }

    @Test fun candidates() {
        assertTrue(RomInspector.isCandidate("Game.NES")); assertTrue(RomInspector.isCandidate("pack.zip"))
        assertFalse(RomInspector.isCandidate("game.srm")); assertFalse(RomInspector.isCandidate("scph1001.bin")); assertFalse(RomInspector.isCandidate("readme.txt"))
    }

    @Test fun zipSlipNamesRejected() {
        assertFalse(RomInspector.isSafeEntryName("../evil.nes")); assertFalse(RomInspector.isSafeEntryName("a/../../b.nes"))
        assertFalse(RomInspector.isSafeEntryName("/abs.nes")); assertFalse(RomInspector.isSafeEntryName("C:\\x.nes"))
        assertTrue(RomInspector.isSafeEntryName("dir/game.nes"))
    }

    @Test fun titles() {
        assertEquals("My Game", LibraryScanner.titleOf("My_Game (USA) [!].nes"))
    }

    @Test fun bundledTestRomIsValidNes() {
        val f = File("../tools/testroms/nesgames_test.nes").takeIf { it.exists() } ?: File("tools/testroms/nesgames_test.nes")
        if (!f.exists()) return
        assertEquals(GameSystem.NES, RomInspector.identify(f.name, f.readBytes().copyOf(512)))
    }
}
