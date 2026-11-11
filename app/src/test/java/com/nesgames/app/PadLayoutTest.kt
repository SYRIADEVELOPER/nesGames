package com.nesgames.app

import com.nesgames.app.emu.RetroPad
import com.nesgames.app.library.GameSystem
import com.nesgames.app.library.SaveSlots
import com.nesgames.app.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PadLayoutTest {
    private val W = 3120f; private val H = 1440f
    private fun geo(s: GameSystem) = PadLayout(s).let { it to Geometry(it, W, H) }
    private val RIGHT = RetroPad.bit(RetroPad.RIGHT); private val UP = RetroPad.bit(RetroPad.UP)

    @Test fun everyButtonIsHitAtItsCentreOnEveryConsole() {
        for (s in listOf(GameSystem.NES, GameSystem.MD, GameSystem.PS1)) {
            val (l, g) = geo(s)
            for (e in l.elems.filter { it.spec.retro >= 0 })
                assertEquals("${s.name}/${e.spec.id}", RetroPad.bit(e.spec.retro), g.maskAt(g.cx(e), g.cy(e)))
            val menu = l.elem("MENU")!!
            assertTrue(g.isMenu(g.cx(menu), g.cy(menu))); assertEquals(0, g.maskAt(g.cx(menu), g.cy(menu)))
        }
    }

    @Test fun dpadGivesFourWaysDiagonalsAndDeadZone() {
        val (l, g) = geo(GameSystem.NES); val d = l.elem("DPAD")!!; val r = g.r(d)
        assertEquals(RIGHT, g.maskAt(g.cx(d) + r * 0.6f, g.cy(d)))
        assertEquals(UP, g.maskAt(g.cx(d), g.cy(d) - r * 0.6f))
        assertEquals(UP or RIGHT, g.maskAt(g.cx(d) + r * 0.5f, g.cy(d) - r * 0.5f))
        assertEquals(0, g.maskAt(g.cx(d), g.cy(d)))
        assertEquals(0, g.maskAt(W / 2, H / 2))
    }

    @Test fun defaultsDoNotOverlapAndStayOnScreen() {
        for (s in listOf(GameSystem.NES, GameSystem.MD, GameSystem.PS1)) for ((w, h) in listOf(3120f to 1440f, 1920f to 1080f, 1280f to 720f, 2400f to 1080f)) {
            val l = PadLayout(s); val g = Geometry(l, w, h)
            for (e in l.elems) { assertTrue(g.cx(e) in 0f..w); assertTrue(g.cy(e) in 0f..h) }
            val btns = l.elems.filter { it.spec.shape == Shape.CIRCLE }
            for (a in btns) for (b in btns) if (a !== b)
                assertTrue("${s.name} ${a.spec.id}/${b.spec.id}", Math.hypot((g.cx(a) - g.cx(b)).toDouble(), (g.cy(a) - g.cy(b)).toDouble()) >= g.r(a) + g.r(b))
        }
    }

    @Test fun moveAndResizeSurviveARoundTrip() {
        val (l, g) = geo(GameSystem.PS1)
        g.moveTo(l.elem("CROSS")!!, 2000f, 900f); l.elem("CROSS")!!.scale = 1.4f
        l.opacity = 0.8f; l.dpadSpread = 1.3f; l.haptic = true
        val back = PadLayout.parse(l.serialize(), GameSystem.PS1)!!
        val g2 = Geometry(back, W, H); val c = back.elem("CROSS")!!
        assertEquals(2000f, g2.cx(c), 0.5f); assertEquals(900f, g2.cy(c), 0.5f); assertEquals(1.4f, c.scale, 0.001f)
        assertEquals(0.8f, back.opacity, 0.001f); assertEquals(1.3f, back.dpadSpread, 0.001f); assertTrue(back.haptic)
        assertEquals(RetroPad.bit(RetroPad.B), g2.maskAt(2000f, 900f))
    }

    @Test fun movingKeepsControlsOnScreen() {
        val (l, g) = geo(GameSystem.NES); val a = l.elem("A")!!
        g.moveTo(a, -500f, 99999f); assertEquals(0f, g.cx(a), 0.01f); assertEquals(H, g.cy(a), 0.01f)
    }

    @Test fun badPresetsAreRejectedAndValuesClamped() {
        assertNull(PadLayout.parse("", GameSystem.NES))
        assertNull(PadLayout.parse("hello", GameSystem.NES))
        assertNull(PadLayout.parse(PadLayout(GameSystem.PS1).serialize(), GameSystem.NES))             // wrong console
        assertNull(PadLayout.parse("nesgames-layout 1\nsystem NES\nelem A NaN 0.2 1\n", GameSystem.NES))
        assertNull(PadLayout.parse("nesgames-layout 1\nsystem NES\nrm -rf /\n", GameSystem.NES))
        assertNull(PadLayout.parse("nesgames-layout 2\nsystem NES\n", GameSystem.NES))
        val l = PadLayout.parse("nesgames-layout 1\nsystem NES\nopacity 9\nelem A 0.1 0.2 99\nelem NOPE 1 1 1\n", GameSystem.NES)!!
        assertEquals(1f, l.opacity, 0f); assertEquals(PadLayout.MAX_SCALE, l.elem("A")!!.scale, 0f)
    }

    // ---- pointer tracking ----
    @Test fun chordAndLiftInAnyOrder() {
        val t = TouchTracker(50)
        t.set(1, RIGHT, 0); t.set(2, 256, 10)
        assertEquals(RIGHT or 256, t.mask(100))
        t.remove(1, 200); assertEquals(256, t.mask(200))      // first finger lifts, second stays held
        t.remove(2, 300); assertEquals(0, t.mask(300))
    }

    @Test fun slideBetweenButtonsMovesThePress() {
        val t = TouchTracker(50)
        t.set(1, 1, 0); t.set(1, 256, 200)
        assertEquals(256, t.mask(200))
    }

    @Test fun veryShortTapIsHeldThenReleasedExactlyOnce() {
        val t = TouchTracker(50)
        t.set(1, 256, 1000); t.remove(1, 1003)                 // 3 ms tap
        assertEquals(256, t.mask(1003)); assertEquals(256, t.mask(1049))
        assertEquals(47L, t.nextExpiry(1003))
        assertEquals(0, t.mask(1050)); assertNull(t.nextExpiry(1050))
        assertEquals(0, t.mask(5000))                           // never comes back: no repeat
    }

    @Test fun longPressIsNotExtended() {
        val t = TouchTracker(50)
        t.set(1, 256, 0); t.remove(1, 400)
        assertEquals(0, t.mask(400))
    }

    @Test fun clearDropsLivePointersAndLatches() {
        val t = TouchTracker(50)
        t.set(1, RIGHT, 0); t.set(2, 256, 0); t.remove(2, 5)   // 256 is latched, RIGHT is live
        t.clear()
        assertEquals(0, t.mask(6)); assertEquals(0, t.pointerCount); assertNull(t.nextExpiry(6))
    }

    @Test fun twoDevicesDoNotClobberEachOther() {
        val t = TouchTracker(50)
        val finger = (3L shl 32) or 0; val injected = (-1L shl 32) or 0
        t.set(finger, RIGHT, 0); t.set(injected, 2, 100); t.remove(injected, 300)
        assertEquals(RIGHT, t.mask(300))                        // the real finger is still down
    }

    // ---- gamepad map ----
    @Test fun remapReplacesOldBindingAndSurvivesRoundTrip() {
        val m = PadMap()
        assertEquals(RetroPad.B, m.lookup(PadMap.BUTTON_A))
        m.bind(PadMap.BUTTON_Y, RetroPad.B)                     // move B/Cross to the top face button
        assertEquals(RetroPad.B, m.lookup(PadMap.BUTTON_Y)); assertEquals(-1, m.lookup(PadMap.BUTTON_A))
        val back = PadMap.parse(m.serialize())
        assertEquals(RetroPad.B, back.lookup(PadMap.BUTTON_Y)); assertEquals(PadMap.BUTTON_Y, back.keyFor(RetroPad.B))
        assertEquals(RetroPad.B, PadMap.parse("garbage").lookup(PadMap.BUTTON_A))   // bad data falls back to defaults
    }

    // ---- save integrity ----
    @Test fun slotHealthDetectsDamage() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ngsave${System.nanoTime()}").apply { mkdirs() }
        val st = File(dir, "slot1.state"); val meta = File(dir, "slot1.meta")
        assertEquals(SaveSlots.Health.EMPTY, SaveSlots.health(st, meta))
        st.writeBytes(ByteArray(4096) { it.toByte() })
        assertEquals(SaveSlots.Health.LEGACY, SaveSlots.health(st, meta))       // old-build save without a record still loads
        SaveSlots.writeMeta(st, meta)
        assertEquals(SaveSlots.Health.OK, SaveSlots.health(st, meta))
        st.writeBytes(ByteArray(4096) { if (it == 100) 7 else it.toByte() })     // one flipped byte
        assertEquals(SaveSlots.Health.CORRUPT, SaveSlots.health(st, meta))
        st.writeBytes(ByteArray(1000))                                           // truncated
        assertEquals(SaveSlots.Health.CORRUPT, SaveSlots.health(st, meta))
        dir.deleteRecursively()
    }

    @Test fun dpadJoystickGivesEightWaysAndIgnoresTaps() {
        for (s in listOf(GameSystem.NES, GameSystem.MD, GameSystem.PS1, GameSystem.PS2)) {
            val (l, g) = geo(s); val d = l.elem("DPAD")!!; val R = g.dpadStickR(d)
            assertNull("${s.name}: D-pad is not a stick by default", g.stickAt(g.cx(d), g.cy(d)))
            l.dpadStick = true
            assertSame(d, g.stickAt(g.cx(d) + R * 0.5f, g.cy(d)))
            assertEquals(0, g.maskAt(g.cx(d) + R * 0.6f, g.cy(d)))            // taps no longer hit the squares
            assertEquals(0, g.dpadStickMask(d, g.cx(d) + R * 0.2f, g.cy(d)))  // dead zone
            assertEquals(RIGHT, g.dpadStickMask(d, g.cx(d) + R * 3f, g.cy(d))) // beyond the edge still counts
            assertEquals(UP or RetroPad.bit(RetroPad.LEFT), g.dpadStickMask(d, g.cx(d) - R * 0.6f, g.cy(d) - R * 0.6f))
            val k = g.dpadStickKnob(d, g.cx(d) + R * 3f, g.cy(d)); assertEquals(R, k.first, 0.5f)
        }
    }

    @Test fun movementStyleIsSavedAndOldPresetsStayDpad() {
        val l = PadLayout(GameSystem.MD); l.dpadStick = true
        assertTrue(PadLayout.parse(l.serialize(), GameSystem.MD)!!.dpadStick)
        val old = l.serialize().lines().filterNot { it.startsWith("dpadStick") }.joinToString("\n")
        assertFalse(PadLayout.parse(old, GameSystem.MD)!!.dpadStick)
    }
}
