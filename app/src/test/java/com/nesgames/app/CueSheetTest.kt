package com.nesgames.app

import com.nesgames.app.library.ConsoleFolders
import com.nesgames.app.library.CueSheet
import com.nesgames.app.library.GameSystem
import org.junit.Assert.*
import org.junit.Test

class CueSheetTest {
    @Test fun singleBinPs1() {
        val p = CueSheet.parse("FILE \"Metal Slug X (USA).bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n")!!
        assertEquals(listOf("Metal Slug X (USA).bin"), p.files); assertTrue(p.mode2)
    }
    @Test fun multiTrackKeepsAllFiles() {
        val p = CueSheet.parse("FILE \"a (Track 1).bin\" BINARY\nTRACK 01 MODE2/2352\nFILE \"a (Track 2).bin\" BINARY\nTRACK 02 AUDIO\n")!!
        assertEquals(2, p.files.size)
    }
    @Test fun audioOnlyIsNotPs1() = assertFalse(CueSheet.parse("FILE \"x.bin\" BINARY\nTRACK 01 AUDIO\n")!!.mode2)
    @Test fun garbageRejected() = assertNull(CueSheet.parse("hello world"))
    @Test fun folderAliases() {
        assertEquals(GameSystem.PS1, ConsoleFolders.systemFor("PS1")); assertEquals(GameSystem.PS1, ConsoleFolders.systemFor("PlayStation"))
        assertEquals(GameSystem.MD, ConsoleFolders.systemFor("Mega Drive")); assertEquals(GameSystem.MD, ConsoleFolders.systemFor("genesis"))
        assertEquals(GameSystem.NES, ConsoleFolders.systemFor("nes")); assertNull(ConsoleFolders.systemFor("Music"))
    }
}
