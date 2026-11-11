package com.nesgames.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.nesgames.app.emu.RetroPad
import kotlin.math.hypot

/**
 * Touch pad drawn from a [PadLayout]. Play mode: every pointer is tracked by (device, pointer id).
 * Edit mode: drag a control to move it, pinch to resize the selected one.
 */
class ControlsView(
    ctx: Context, layout: PadLayout, private val onMask: (Int) -> Unit, private val onMenu: () -> Unit,
    private val onStick: (stick: Int, x: Int, y: Int) -> Unit = { _, _, _ -> },
) : View(ctx) {
    var layout: PadLayout = layout
        set(v) { field = v; geom = Geometry(v, width.toFloat().coerceAtLeast(1f), height.toFloat().coerceAtLeast(1f)); selected = null; invalidate() }
    var geom = Geometry(layout, 1f, 1f); private set
    var editMode = false
        set(v) { field = v; releaseAll(); selected = if (v) this.layout.elems.first() else null; invalidate() }
    var selected: Elem? = null; private set
    var onEdited: (() -> Unit)? = null

    private val tracker = TouchTracker()
    private val ignored = HashSet<Long>()     // pointers that opened the menu
    private val stickOwner = HashMap<Long, Elem>()   // pointer -> analog stick it is steering
    private val stickPos = HashMap<Elem, Pair<Int, Int>>()
    private var mask = 0
    private val expire = Runnable { publish() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val under = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f }   // dark halo: outline stays visible on bright scenes
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.rgb(255, 214, 10); pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    val currentMask get() = mask

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { geom = Geometry(layout, w.toFloat(), h.toFloat()) }

    /** Drops every pointer and latched press and reports mask 0. Safe to call at any time. */
    fun releaseAll() {
        tracker.clear(); ignored.clear(); removeCallbacks(expire)
        for (e in stickOwner.values.toSet() + stickPos.keys) { onStick(e.spec.retro, 0, 0) }
        stickOwner.clear(); stickPos.clear()
        if (mask != 0) { mask = 0; onMask(0) }
        invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) releaseAll() }
    override fun onDetachedFromWindow() { releaseAll(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); if (visibility != VISIBLE) releaseAll() }

    private fun publish() {
        val now = SystemClock.uptimeMillis()
        val m = tracker.mask(now)
        if (m != mask) {
            if (layout.haptic && (m and mask.inv()) != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            mask = m; onMask(m); invalidate()
        }
        removeCallbacks(expire)
        tracker.nextExpiry(now)?.let { postDelayed(expire, it) }
    }

    private fun key(e: MotionEvent, i: Int) = (e.deviceId.toLong() shl 32) or (e.getPointerId(i).toLong() and 0xffffffffL)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editMode) return onEditTouch(e)
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val x = e.getX(i); val y = e.getY(i)
                val stick = geom.stickAt(x, y)
                if (geom.isMenu(x, y)) { ignored += key(e, i); onMenu() }
                else if (stick != null && stick !in stickOwner.values) { stickOwner[key(e, i)] = stick; moveStick(stick, x, y) }
                else tracker.set(key(e, i), geom.maskAt(x, y), now)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val k = key(e, i)
                val s = stickOwner[k]
                if (s != null) moveStick(s, e.getX(i), e.getY(i))
                else if (k !in ignored) tracker.set(k, geom.maskAt(e.getX(i), e.getY(i)), now)
            }
            MotionEvent.ACTION_POINTER_UP -> { val k = key(e, e.actionIndex); ignored -= k; liftStick(k); tracker.remove(k, now) }
            MotionEvent.ACTION_UP -> for (i in 0 until e.pointerCount) { val k = key(e, i); ignored -= k; liftStick(k); tracker.remove(k, now) }
            MotionEvent.ACTION_CANCEL -> { releaseAll(); return true }   // gesture stolen by the system: release everything now
        }
        publish()
        return true
    }

    private fun moveStick(s: Elem, x: Float, y: Float) {
        val v = geom.stickValue(s, x, y)
        if (stickPos[s] != v) { stickPos[s] = v; onStick(s.spec.retro, v.first, v.second); invalidate() }
    }

    private fun liftStick(k: Long) {
        val s = stickOwner.remove(k) ?: return
        stickPos.remove(s); onStick(s.spec.retro, 0, 0); invalidate()   // springs back to centre
    }

    fun stickValue(stick: Int) = stickPos.entries.firstOrNull { it.key.spec.retro == stick }?.value ?: (0 to 0)

    // ---- editor ----
    private var grabDx = 0f; private var grabDy = 0f
    private var pinchStart = 0f; private var pinchScale = 1f

    private fun onEditTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                geom.elemAt(e.x, e.y)?.let { selected = it; grabDx = e.x - geom.cx(it); grabDy = e.y - geom.cy(it) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                pinchStart = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); pinchScale = selected?.scale ?: 1f
            }
            MotionEvent.ACTION_MOVE -> selected?.let { s ->
                if (e.pointerCount >= 2 && pinchStart > 10f) {
                    val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    s.scale = (pinchScale * d / pinchStart).coerceIn(PadLayout.MIN_SCALE, PadLayout.MAX_SCALE)
                } else if (e.pointerCount == 1) geom.moveTo(s, e.x - grabDx, e.y - grabDy)
            }
            MotionEvent.ACTION_POINTER_UP -> { pinchStart = 0f
                // keep dragging with the remaining finger without a jump
                val rest = if (e.actionIndex == 0) 1 else 0
                selected?.let { grabDx = e.getX(rest) - geom.cx(it); grabDy = e.getY(rest) - geom.cy(it) } }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pinchStart = 0f; onEdited?.invoke() }
        }
        invalidate()
        return true
    }

    fun resizeSelected(factor: Float) { selected?.let { it.scale = (it.scale * factor).coerceIn(PadLayout.MIN_SCALE, PadLayout.MAX_SCALE); invalidate(); onEdited?.invoke() } }

    override fun onDraw(c: Canvas) {
        val g = geom
        val alpha = ((if (editMode) maxOf(layout.opacity, 0.6f) else layout.opacity) * 255).toInt()
        fun col(pressed: Boolean) = if (pressed) Color.argb(alpha, 229, 72, 77) else Color.argb(alpha * 3 / 4, 24, 24, 28)
        stroke.color = Color.argb(alpha, 255, 255, 255); under.color = Color.argb(alpha / 2, 0, 0, 0)
        text.color = Color.argb(alpha, 255, 255, 255); text.setShadowLayer(4f, 0f, 0f, Color.argb(alpha, 0, 0, 0))
        for (e in layout.elems) {
            val cx = g.cx(e); val cy = g.cy(e); val r = g.r(e)
            when (e.spec.shape) {
                Shape.DPAD -> {
                    val dirs = listOf(RetroPad.UP to (0f to -1f), RetroPad.DOWN to (0f to 1f), RetroPad.LEFT to (-1f to 0f), RetroPad.RIGHT to (1f to 0f))
                    val arm = r * 0.36f; val off = r * 0.62f * layout.dpadSpread
                    for ((id, v) in dirs) {
                        fill.color = col(mask and RetroPad.bit(id) != 0)
                        val ax = cx + v.first * off; val ay = cy + v.second * off
                        c.drawRoundRect(ax - arm, ay - arm, ax + arm, ay + arm, arm * 0.3f, arm * 0.3f, fill)
                        c.drawRoundRect(ax - arm, ay - arm, ax + arm, ay + arm, arm * 0.3f, arm * 0.3f, under)
                        c.drawRoundRect(ax - arm, ay - arm, ax + arm, ay + arm, arm * 0.3f, arm * 0.3f, stroke)
                    }
                }
                Shape.STICK -> {
                    fill.color = col(false)
                    c.drawCircle(cx, cy, r, fill); c.drawCircle(cx, cy, r, under); c.drawCircle(cx, cy, r, stroke)
                    val (vx, vy) = stickPos[e] ?: (0 to 0)
                    val kx = cx + vx / 32767f * r * 0.6f; val ky = cy + vy / 32767f * r * 0.6f
                    fill.color = col(vx != 0 || vy != 0)
                    c.drawCircle(kx, ky, r * 0.45f, fill); c.drawCircle(kx, ky, r * 0.45f, stroke)
                }
                Shape.CIRCLE -> {
                    fill.color = col(mask and RetroPad.bit(e.spec.retro) != 0)
                    c.drawCircle(cx, cy, r, fill); c.drawCircle(cx, cy, r, under); c.drawCircle(cx, cy, r, stroke)
                }
                Shape.PILL, Shape.MENU -> {
                    fill.color = col(e.spec.retro >= 0 && mask and RetroPad.bit(e.spec.retro) != 0)
                    c.drawRoundRect(cx - r * 1.4f, cy - r * 0.55f, cx + r * 1.4f, cy + r * 0.55f, r, r, fill)
                    c.drawRoundRect(cx - r * 1.4f, cy - r * 0.55f, cx + r * 1.4f, cy + r * 0.55f, r, r, under)
                    c.drawRoundRect(cx - r * 1.4f, cy - r * 0.55f, cx + r * 1.4f, cy + r * 0.55f, r, r, stroke)
                }
            }
            if (e.spec.label.isNotEmpty()) {
                text.textSize = r * (if (e.spec.shape == Shape.CIRCLE) 0.62f else 0.5f)
                c.drawText(e.spec.label, cx, cy + text.textSize / 3, text)
            }
            if (editMode && e === selected) c.drawCircle(cx, cy, r * (if (e.spec.shape == Shape.DPAD) 1.45f else 1.7f), sel)
        }
    }
}
