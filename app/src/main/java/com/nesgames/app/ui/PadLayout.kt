package com.nesgames.app.ui

import com.nesgames.app.emu.RetroPad
import com.nesgames.app.library.GameSystem
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

enum class Shape { CIRCLE, PILL, DPAD, MENU, STICK }
enum class AH { LEFT, CENTER, RIGHT }
enum class AV { TOP, BOTTOM }

/** Static description of one control. Offsets and radius are in units of u = min(width, height). */
data class Spec(
    val id: String, val label: String, val retro: Int, val shape: Shape,
    val baseR: Float, val ah: AH, val av: AV, val dx: Float, val dy: Float,
)

class Elem(val spec: Spec, var dx: Float = spec.dx, var dy: Float = spec.dy, var scale: Float = 1f)

/**
 * Per-console touch layout. Pure Kotlin (no Android types) so layout maths, hit-testing and the
 * preset format are covered by JVM unit tests.
 */
class PadLayout(val system: GameSystem) {
    val elems: List<Elem> = specsFor(system).map { Elem(it) }
    var opacity = 0.55f
    var dpadSpread = 1f
    var haptic = false
    /** Movement style: false = D-pad squares, true = a joystick in the D-pad's place giving the same 8 directions. */
    var dpadStick = false

    fun reset() {
        elems.forEach { it.dx = it.spec.dx; it.dy = it.spec.dy; it.scale = 1f }
        opacity = 0.55f; dpadSpread = 1f
    }

    fun elem(id: String) = elems.firstOrNull { it.spec.id == id }

    /** Text preset: stable, human-readable, versioned. */
    fun serialize(): String = buildString {
        append("nesgames-layout 1\n")
        append("system ${system.name}\n")
        append("opacity $opacity\n")
        append("dpadSpread $dpadSpread\n")
        append("haptic ${if (haptic) 1 else 0}\n")
        append("dpadStick ${if (dpadStick) 1 else 0}\n")
        for (e in elems) append("elem ${e.spec.id} ${e.dx} ${e.dy} ${e.scale}\n")
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.2f

        /** @return null if the text is not a valid preset for [system]. Unknown controls are ignored, values clamped. */
        fun parse(text: String, system: GameSystem): PadLayout? {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.size > 200 || lines.firstOrNull() != "nesgames-layout 1") return null
            val out = PadLayout(system)
            var sawSystem = false
            for (l in lines.drop(1)) {
                val p = l.split(' ')
                when (p[0]) {
                    "system" -> { if (p.getOrNull(1) != system.name) return null; sawSystem = true }
                    "opacity" -> out.opacity = (p.getOrNull(1)?.toFloatOrNull() ?: return null).coerceIn(0.15f, 1f)
                    "dpadSpread" -> out.dpadSpread = (p.getOrNull(1)?.toFloatOrNull() ?: return null).coerceIn(0.7f, 1.6f)
                    "haptic" -> out.haptic = p.getOrNull(1) == "1"
                    "dpadStick" -> out.dpadStick = p.getOrNull(1) == "1"   // absent in older presets: D-pad
                    "elem" -> {
                        if (p.size != 5) return null
                        val e = out.elem(p[1]) ?: continue
                        val dx = p[2].toFloatOrNull(); val dy = p[3].toFloatOrNull(); val s = p[4].toFloatOrNull()
                        if (dx == null || dy == null || s == null || dx.isNaN() || dy.isNaN() || s.isNaN()) return null
                        e.dx = dx.coerceIn(-3f, 3f); e.dy = dy.coerceIn(0f, 1f); e.scale = s.coerceIn(MIN_SCALE, MAX_SCALE)
                    }
                    else -> return null
                }
            }
            return if (sawSystem) out else null
        }

        private fun meta(sel: String) = listOf(
            Spec("SELECT", sel, RetroPad.SELECT, Shape.PILL, 0.05f, AH.CENTER, AV.BOTTOM, -0.08f, 0.06f),
            Spec("START", "START", RetroPad.START, Shape.PILL, 0.05f, AH.CENTER, AV.BOTTOM, 0.08f, 0.06f),
            Spec("MENU", "MENU", -1, Shape.MENU, 0.05f, AH.CENTER, AV.TOP, 0f, 0.047f),
        )
        private val dpad = Spec("DPAD", "", -1, Shape.DPAD, 0.17f, AH.LEFT, AV.BOTTOM, 0.247f, 0.2125f)

        fun specsFor(system: GameSystem): List<Spec> = when (system) {
            // Mega Drive mapping follows Genesis Plus GX: Y=A, B=B, A=C
            GameSystem.MD -> listOf(dpad,
                Spec("C", "C", RetroPad.A, Shape.CIRCLE, 0.068f, AH.RIGHT, AV.BOTTOM, 0.102f, 0.255f),
                Spec("B", "B", RetroPad.B, Shape.CIRCLE, 0.068f, AH.RIGHT, AV.BOTTOM, 0.2652f, 0.1938f),
                Spec("A", "A", RetroPad.Y, Shape.CIRCLE, 0.068f, AH.RIGHT, AV.BOTTOM, 0.4284f, 0.255f)) + meta("MODE")
            // PlayStation: A=Circle, B=Cross, Y=Square, X=Triangle
            GameSystem.PS1 -> listOf(dpad,
                Spec("CIRCLE", "○", RetroPad.A, Shape.CIRCLE, 0.068f, AH.RIGHT, AV.BOTTOM, 0.1054f, 0.2125f),
                Spec("CROSS", "✕", RetroPad.B, Shape.CIRCLE, 0.068f, AH.RIGHT, AV.BOTTOM, 0.2448f, 0.0731f),
                Spec("SQUARE", "□", RetroPad.Y, Shape.CIRCLE, 0.068f, AH.RIGHT, AV.BOTTOM, 0.3842f, 0.2125f),
                Spec("TRIANGLE", "△", RetroPad.X, Shape.CIRCLE, 0.068f, AH.RIGHT, AV.BOTTOM, 0.2448f, 0.3519f),
                Spec("L1", "L1", RetroPad.L, Shape.PILL, 0.06f, AH.LEFT, AV.TOP, 0.132f, 0.2f),
                Spec("R1", "R1", RetroPad.R, Shape.PILL, 0.06f, AH.RIGHT, AV.TOP, 0.132f, 0.2f)) + meta("SELECT")
            // PlayStation 2 (DualShock 2): two analog sticks, D-pad above the left stick, face buttons, L1/R1/L2/R2.
            GameSystem.PS2 -> listOf(
                Spec("LSTICK", "", 0, Shape.STICK, 0.13f, AH.LEFT, AV.BOTTOM, 0.20f, 0.20f),
                Spec("DPAD", "", -1, Shape.DPAD, 0.11f, AH.LEFT, AV.BOTTOM, 0.20f, 0.56f),
                Spec("RSTICK", "", 1, Shape.STICK, 0.10f, AH.RIGHT, AV.BOTTOM, 0.64f, 0.16f),
                Spec("CIRCLE", "○", RetroPad.A, Shape.CIRCLE, 0.068f, AH.RIGHT, AV.BOTTOM, 0.1054f, 0.2125f),
                Spec("CROSS", "✕", RetroPad.B, Shape.CIRCLE, 0.068f, AH.RIGHT, AV.BOTTOM, 0.2448f, 0.0731f),
                Spec("SQUARE", "□", RetroPad.Y, Shape.CIRCLE, 0.068f, AH.RIGHT, AV.BOTTOM, 0.3842f, 0.2125f),
                Spec("TRIANGLE", "△", RetroPad.X, Shape.CIRCLE, 0.068f, AH.RIGHT, AV.BOTTOM, 0.2448f, 0.3519f),
                Spec("L1", "L1", RetroPad.L, Shape.PILL, 0.06f, AH.LEFT, AV.TOP, 0.132f, 0.2f),
                Spec("R1", "R1", RetroPad.R, Shape.PILL, 0.06f, AH.RIGHT, AV.TOP, 0.132f, 0.2f),
                Spec("L2", "L2", RetroPad.L2, Shape.PILL, 0.06f, AH.LEFT, AV.TOP, 0.132f, 0.075f),
                Spec("R2", "R2", RetroPad.R2, Shape.PILL, 0.06f, AH.RIGHT, AV.TOP, 0.132f, 0.075f)) + meta("SELECT")
            else -> listOf(dpad,
                Spec("A", "A", RetroPad.A, Shape.CIRCLE, 0.075f, AH.RIGHT, AV.BOTTOM, 0.1125f, 0.255f),
                Spec("B", "B", RetroPad.B, Shape.CIRCLE, 0.075f, AH.RIGHT, AV.BOTTOM, 0.2925f, 0.1875f)) + meta("SELECT")
        }
    }
}

/** Pixel geometry of a layout for a given view size. */
class Geometry(val layout: PadLayout, val w: Float, val h: Float) {
    val u = min(w, h)
    fun cx(e: Elem) = when (e.spec.ah) { AH.LEFT -> e.dx * u; AH.RIGHT -> w - e.dx * u; AH.CENTER -> w / 2 + e.dx * u }
    fun cy(e: Elem) = when (e.spec.av) { AV.TOP -> e.dy * u; AV.BOTTOM -> h - e.dy * u }
    fun r(e: Elem) = e.spec.baseR * e.scale * u

    /** Moves an element so its centre is at (x, y), kept on screen. */
    fun moveTo(e: Elem, x: Float, y: Float) {
        val px = x.coerceIn(0f, w); val py = y.coerceIn(0f, h)
        e.dx = when (e.spec.ah) { AH.LEFT -> px / u; AH.RIGHT -> (w - px) / u; AH.CENTER -> (px - w / 2) / u }
        e.dy = when (e.spec.av) { AV.TOP -> py / u; AV.BOTTOM -> (h - py) / u }
    }

    private fun dist(e: Elem, x: Float, y: Float): Float {
        val k = if (e.spec.shape == Shape.PILL || e.spec.shape == Shape.MENU) 1.6f else 1f   // pills are wider than tall
        return hypot((x - cx(e)) / k, y - cy(e))
    }

    fun dpadReach(e: Elem) = r(e) * (0.73f + 0.62f * layout.dpadSpread)

    /** Radius of the D-pad when it is drawn and used as a joystick. */
    fun dpadStickR(e: Elem) = r(e) * 1.15f

    /** The stick whose area contains (x, y), if any: analog sticks (grab radius 1.4 r) and, in joystick mode, the D-pad. */
    fun stickAt(x: Float, y: Float): Elem? = layout.elems.firstOrNull {
        when (it.spec.shape) {
            Shape.STICK -> hypot(x - cx(it), y - cy(it)) <= r(it) * 1.4f
            Shape.DPAD -> layout.dpadStick && hypot(x - cx(it), y - cy(it)) <= dpadStickR(it) * 1.3f
            else -> false
        }
    }

    /** D-pad joystick: 8-way directions from the finger's offset; dead zone 30 % of the radius. */
    fun dpadStickMask(e: Elem, x: Float, y: Float): Int {
        val dx = x - cx(e); val dy = y - cy(e)
        if (hypot(dx, dy) < dpadStickR(e) * 0.30f) return 0
        return dirMask(dx, dy)
    }

    /** Knob offset for drawing the D-pad joystick, clamped to its radius. */
    fun dpadStickKnob(e: Elem, x: Float, y: Float): Pair<Float, Float> {
        val R = dpadStickR(e); var dx = x - cx(e); var dy = y - cy(e)
        val len = hypot(dx, dy); if (len > R) { dx = dx / len * R; dy = dy / len * R }
        return dx to dy
    }

    private fun dirMask(dx: Float, dy: Float): Int {
        // 8-way: 45° sectors around each axis give diagonals
        val ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
        var m = 0
        if (ang < 67.5 || ang > 292.5) m = m or RetroPad.bit(RetroPad.RIGHT)
        if (ang in 22.5..157.5) m = m or RetroPad.bit(RetroPad.DOWN)
        if (ang in 112.5..247.5) m = m or RetroPad.bit(RetroPad.LEFT)
        if (ang in 202.5..337.5) m = m or RetroPad.bit(RetroPad.UP)
        return m
    }

    /** Stick deflection for a finger at (x, y): -32767..32767 per axis, clamped to the stick radius, +y = down. */
    fun stickValue(e: Elem, x: Float, y: Float): Pair<Int, Int> {
        val r = r(e); var dx = (x - cx(e)) / r; var dy = (y - cy(e)) / r
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        if (len < 0.12f) return 0 to 0   // dead zone
        return (dx * 32767).toInt() to (dy * 32767).toInt()
    }

    fun isMenu(x: Float, y: Float) = layout.elems.any { it.spec.shape == Shape.MENU && dist(it, x, y) <= r(it) * 1.2f }

    /** Buttons under one touch point, as a RetroPad bit mask. */
    fun maskAt(x: Float, y: Float): Int {
        var best: Elem? = null; var bestD = Float.MAX_VALUE
        for (e in layout.elems) {
            when (e.spec.shape) {
                Shape.DPAD -> if (!layout.dpadStick) {   // in joystick mode the D-pad is steered via stickAt
                    val d = hypot(x - cx(e), y - cy(e))
                    if (d <= dpadReach(e)) {
                        if (d < r(e) * 0.22f) return 0
                        return dirMask(x - cx(e), y - cy(e))
                    }
                }
                Shape.MENU, Shape.STICK -> {}
                else -> { val d = dist(e, x, y); if (d < r(e) * 1.5f && d < bestD) { best = e; bestD = d } }
            }
        }
        return best?.let { RetroPad.bit(it.spec.retro) } ?: 0
    }

    /** Element to grab in the editor (generous radius). */
    fun elemAt(x: Float, y: Float): Elem? = layout.elems
        .map { it to (if (it.spec.shape == Shape.DPAD) hypot(x - cx(it), y - cy(it)) / (dpadReach(it) / r(it)) else dist(it, x, y)) }
        .filter { (e, d) -> d <= r(e) * 1.6f }
        .minByOrNull { it.second }?.first
}

/**
 * Tracks every live pointer by id, so chords, slides between buttons and lifts in any order are exact.
 * A press shorter than [minHoldMs] is held until that time has passed, so the emulated game (which polls
 * once per frame) always sees it; nothing is ever repeated, and [clear] drops everything at once.
 */
class TouchTracker(private val minHoldMs: Long = 50) {
    private val pointers = HashMap<Long, Int>()
    private val pressedAt = HashMap<Int, Long>()      // bit index -> time it went down
    private val latchedUntil = HashMap<Int, Long>()   // bit index -> keep-until time
    private var live = 0

    private fun refresh(now: Long) {
        val m = pointers.values.fold(0) { a, b -> a or b }
        val went = m and live.inv(); val gone = live and m.inv()
        for (b in 0 until 16) {
            val bit = 1 shl b
            if (went and bit != 0) { pressedAt[b] = now; latchedUntil.remove(b) }
            if (gone and bit != 0) {
                val t0 = pressedAt.remove(b) ?: now
                if (now - t0 < minHoldMs) latchedUntil[b] = t0 + minHoldMs
            }
        }
        live = m
    }

    fun set(key: Long, mask: Int, now: Long) { pointers[key] = mask; refresh(now) }
    fun remove(key: Long, now: Long) { pointers.remove(key); refresh(now) }
    fun clear() { pointers.clear(); pressedAt.clear(); latchedUntil.clear(); live = 0 }
    val pointerCount get() = pointers.size

    fun mask(now: Long): Int {
        var m = live
        val it = latchedUntil.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.value > now) m = m or (1 shl e.key) else it.remove() }
        return m
    }

    /** Milliseconds until the next latched press expires, or null. */
    fun nextExpiry(now: Long): Long? = latchedUntil.values.minOrNull()?.let { (it - now).coerceAtLeast(1) }
}

/** Gamepad key code -> RetroPad id, per console. Pure so it is unit-testable; key codes are Android KeyEvent codes. */
class PadMap(private val map: MutableMap<Int, Int> = defaults().toMutableMap()) {
    fun lookup(keyCode: Int) = map[keyCode] ?: -1
    fun keyFor(retro: Int) = map.entries.firstOrNull { it.value == retro && it.key !in KEYBOARD }?.key
        ?: map.entries.firstOrNull { it.value == retro }?.key
    /** Binds [keyCode] to [retro], replacing whatever either was bound to before (no duplicates). */
    fun bind(keyCode: Int, retro: Int) {
        map.entries.removeAll { it.value == retro && it.key !in KEYBOARD }
        map[keyCode] = retro
    }
    fun serialize() = map.entries.joinToString(";") { "${it.key}=${it.value}" }

    companion object {
        // Android KeyEvent codes (kept numeric so this file has no Android dependency)
        const val DPAD_UP = 19; const val DPAD_DOWN = 20; const val DPAD_LEFT = 21; const val DPAD_RIGHT = 22
        const val BUTTON_A = 96; const val BUTTON_B = 97; const val BUTTON_X = 99; const val BUTTON_Y = 100
        const val BUTTON_L1 = 102; const val BUTTON_R1 = 103; const val BUTTON_START = 108; const val BUTTON_SELECT = 109
        const val BUTTON_L2 = 104; const val BUTTON_R2 = 105; const val BUTTON_THUMBL = 106; const val BUTTON_THUMBR = 107
        const val ENTER = 66; const val SPACE = 62; const val KEY_Z = 54; const val KEY_X = 52
        private val KEYBOARD = setOf(ENTER, SPACE, KEY_Z, KEY_X)

        fun defaults(): Map<Int, Int> = mapOf(
            DPAD_UP to RetroPad.UP, DPAD_DOWN to RetroPad.DOWN, DPAD_LEFT to RetroPad.LEFT, DPAD_RIGHT to RetroPad.RIGHT,
            BUTTON_A to RetroPad.B,      // bottom face button -> B / Cross
            BUTTON_B to RetroPad.A,      // right face button  -> A / Circle
            BUTTON_X to RetroPad.Y, BUTTON_Y to RetroPad.X,
            BUTTON_L1 to RetroPad.L, BUTTON_R1 to RetroPad.R,
            BUTTON_L2 to RetroPad.L2, BUTTON_R2 to RetroPad.R2, BUTTON_THUMBL to RetroPad.L3, BUTTON_THUMBR to RetroPad.R3,
            BUTTON_START to RetroPad.START, BUTTON_SELECT to RetroPad.SELECT,
            ENTER to RetroPad.START, SPACE to RetroPad.SELECT, KEY_Z to RetroPad.B, KEY_X to RetroPad.A,
        )

        fun parse(text: String?): PadMap {
            if (text.isNullOrBlank()) return PadMap()
            val m = HashMap<Int, Int>()
            for (p in text.split(';')) {
                val kv = p.split('='); val k = kv.getOrNull(0)?.toIntOrNull(); val v = kv.getOrNull(1)?.toIntOrNull()
                if (kv.size != 2 || k == null || v == null || v !in 0..15 || k !in 1..400) return PadMap()
                m[k] = v
            }
            return PadMap(m)
        }
    }
}
