package com.nesgames.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.nesgames.app.emu.RetroPad
import com.nesgames.app.library.GameSystem
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Multi-touch NES pad. Every MotionEvent recomputes the full button mask from all live
 * pointers, so D-pad + A/B chords and finger slides between buttons behave reliably.
 */
class ControlsView(ctx: Context, system: GameSystem, private val onMask: (Int) -> Unit, private val onMenu: () -> Unit) : View(ctx) {
    private class Btn(val id: Int, val label: String, var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f, val pill: Boolean = false)

    // Face buttons listed right-to-left. Mega Drive mapping follows Genesis Plus GX: Y=A, B=B, A=C.
    private val face = when (system) {
        GameSystem.MD -> listOf(Btn(RetroPad.A, "C"), Btn(RetroPad.B, "B"), Btn(RetroPad.Y, "A"))
        else -> listOf(Btn(RetroPad.A, "A"), Btn(RetroPad.B, "B"))
    }
    private val meta = when (system) {
        GameSystem.MD -> listOf(Btn(RetroPad.SELECT, "MODE", pill = true), Btn(RetroPad.START, "START", pill = true))
        else -> listOf(Btn(RetroPad.SELECT, "SELECT", pill = true), Btn(RetroPad.START, "START", pill = true))
    }
    private val buttons = face + meta
    private var dx = 0f; private var dy = 0f; private var dr = 0f
    private var menuRect = RectF()
    private var mask = 0
    var opacity = 0.55f; set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val u = min(w, h).toFloat()
        dr = u * 0.17f; dx = u * 0.06f + dr * 1.1f; dy = h - dr * 1.25f
        val r = u * (if (face.size > 2) 0.068f else 0.075f)
        face.forEachIndexed { i, b ->
            b.r = r
            b.cx = w - r * 1.5f - i * r * 2.4f
            b.cy = h - dr * 1.5f + (if (i % 2 == 1) r * 0.9f else 0f)
        }
        val sel = meta[0]; val st = meta[1]
        sel.r = u * 0.05f; st.r = sel.r
        sel.cx = w / 2f - sel.r * 1.6f; st.cx = w / 2f + st.r * 1.6f
        sel.cy = h - sel.r * 1.2f; st.cy = sel.cy
        val m = u * 0.09f
        menuRect = RectF(w / 2f - m, u * 0.02f, w / 2f + m, u * 0.02f + m * 0.6f)
        text.textSize = u * 0.035f
    }

    override fun onDraw(c: Canvas) {
        val alpha = (opacity * 255).toInt()
        fun col(pressed: Boolean) = if (pressed) Color.argb(alpha, 229, 72, 77) else Color.argb(alpha * 3 / 4, 24, 24, 28)  // dark fill stays visible on bright scenes
        stroke.color = Color.argb(alpha, 255, 255, 255); text.color = Color.argb(alpha, 255, 255, 255)
        // D-pad
        val dirs = listOf(RetroPad.UP to (0f to -1f), RetroPad.DOWN to (0f to 1f), RetroPad.LEFT to (-1f to 0f), RetroPad.RIGHT to (1f to 0f))
        val arm = dr * 0.36f
        for ((id, v) in dirs) {
            fill.color = col(mask and RetroPad.bit(id) != 0)
            val cx = dx + v.first * dr * 0.62f; val cy = dy + v.second * dr * 0.62f
            c.drawRoundRect(cx - arm, cy - arm, cx + arm, cy + arm, arm * 0.3f, arm * 0.3f, fill)
            c.drawRoundRect(cx - arm, cy - arm, cx + arm, cy + arm, arm * 0.3f, arm * 0.3f, stroke)
        }
        c.drawCircle(dx, dy, dr, stroke)
        for (b in buttons) {
            fill.color = col(mask and RetroPad.bit(b.id) != 0)
            if (b.pill) { c.drawRoundRect(b.cx - b.r * 1.4f, b.cy - b.r * 0.5f, b.cx + b.r * 1.4f, b.cy + b.r * 0.5f, b.r, b.r, fill); c.drawRoundRect(b.cx - b.r * 1.4f, b.cy - b.r * 0.5f, b.cx + b.r * 1.4f, b.cy + b.r * 0.5f, b.r, b.r, stroke) }
            else { c.drawCircle(b.cx, b.cy, b.r, fill); c.drawCircle(b.cx, b.cy, b.r, stroke) }
            c.drawText(b.label, b.cx, b.cy + text.textSize / 3, text)
        }
        fill.color = Color.argb(alpha * 3 / 4, 24, 24, 28)
        c.drawRoundRect(menuRect, 12f, 12f, fill)
        c.drawText("MENU", menuRect.centerX(), menuRect.centerY() + text.textSize / 3, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) &&
            menuRect.contains(e.getX(e.actionIndex), e.getY(e.actionIndex))) { onMenu(); return true }
        var m = 0
        if (action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until e.pointerCount) {
                if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && i == e.actionIndex) continue
                m = m or hit(e.getX(i), e.getY(i))
            }
        }
        if (m != mask) { mask = m; onMask(m); invalidate() }
        return true
    }

    private fun hit(x: Float, y: Float): Int {
        val d = hypot(x - dx, y - dy)
        if (d <= dr * 1.35f) {
            if (d < dr * 0.22f) return 0
            // 8-way: 45° sectors around each axis give diagonals
            val ang = Math.toDegrees(atan2((y - dy).toDouble(), (x - dx).toDouble())).let { if (it < 0) it + 360 else it }
            var m = 0
            if (ang < 67.5 || ang > 292.5) m = m or RetroPad.bit(RetroPad.RIGHT)
            if (ang in 22.5..157.5) m = m or RetroPad.bit(RetroPad.DOWN)
            if (ang in 112.5..247.5) m = m or RetroPad.bit(RetroPad.LEFT)
            if (ang in 202.5..337.5) m = m or RetroPad.bit(RetroPad.UP)
            return m
        }
        var best: Btn? = null; var bestD = Float.MAX_VALUE
        for (b in buttons) { val bd = hypot(x - b.cx, y - b.cy); if (bd < b.r * 1.5f && bd < bestD) { best = b; bestD = bd } }
        return best?.let { RetroPad.bit(it.id) } ?: 0
    }
}
