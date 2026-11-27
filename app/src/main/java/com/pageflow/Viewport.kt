package com.pageflow

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Pure zoom/pan maths. Screen = content * scale + (tx, ty).
 * [zoomBy] keeps the content point under the anchor fixed, which is what makes pinch-zoom feel natural.
 */
data class VpState(
    val scale: Float = 1f, val tx: Float = 0f, val ty: Float = 0f,
    val vw: Float = 0f, val vh: Float = 0f, val cw: Float = 0f, val ch: Float = 0f,
    val minScale: Float = 1f, val maxScale: Float = 6f, val centerY: Boolean = false
) {
    val zoomed: Boolean get() = scale > 1.02f
    fun canScrollY(): Boolean = ch * scale > vh + 1f

    fun clamped(): VpState {
        val w = cw * scale; val h = ch * scale
        val nx = if (w <= vw) (vw - w) / 2f else tx.coerceIn(vw - w, 0f)
        val ny = if (h <= vh) (if (centerY) (vh - h) / 2f else 0f) else ty.coerceIn(vh - h, 0f)
        return copy(tx = nx, ty = ny)
    }

    fun zoomBy(factor: Float, ax: Float, ay: Float): VpState {
        val ns = (scale * factor).coerceIn(minScale, maxScale)
        val r = ns / scale
        return copy(scale = ns, tx = ax - (ax - tx) * r, ty = ay - (ay - ty) * r).clamped()
    }

    fun zoomTo(target: Float, ax: Float, ay: Float): VpState = zoomBy(target / scale, ax, ay)
    fun panBy(dx: Float, dy: Float): VpState = copy(tx = tx + dx, ty = ty + dy).clamped()
    fun withViewport(w: Float, h: Float): VpState = copy(vw = w, vh = h).clamped()
    fun withContent(w: Float, h: Float): VpState = copy(cw = w, ch = h).clamped()

    fun toContentX(sx: Float) = (sx - tx) / scale
    fun toContentY(sy: Float) = (sy - ty) / scale
}

class Viewport(centerY: Boolean) {
    var st by mutableStateOf(VpState(centerY = centerY))
}

private suspend fun flingViewport(vp: Viewport, vx: Float, vy: Float) = coroutineScope {
    val decay = exponentialDecay<Float>(frictionMultiplier = 1.4f)
    launch {
        var last = 0f
        AnimationState(0f, vx).animateDecay(decay) { val d = value - last; last = value; vp.st = vp.st.panBy(d, 0f) }
    }
    launch {
        var last = 0f
        AnimationState(0f, vy).animateDecay(decay) { val d = value - last; last = value; vp.st = vp.st.panBy(0f, d) }
    }
}

/**
 * Tap / double-tap / long-press plus pinch-zoom (anchored at the finger midpoint), one-finger pan and fling.
 * In [pagerMode] a horizontal swipe at 1x is left to the surrounding pager.
 */
fun Modifier.viewportGestures(
    vp: Viewport, pagerMode: Boolean,
    onTap: (Offset) -> Unit, onDoubleTap: (Offset) -> Unit, onLongPress: (Offset) -> Unit
): Modifier = this
    .pointerInput(vp) {
        detectTapGestures(onTap = onTap, onDoubleTap = onDoubleTap, onLongPress = onLongPress)
    }
    .pointerInput(vp, pagerMode) {
        coroutineScope {
            var fling: Job? = null
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fling?.cancel()
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var total = Offset.Zero
                var slop = false
                var multi = false
                var skip = false
                do {
                    val ev = awaitPointerEvent()
                    if (ev.changes.any { it.isConsumed }) skip = true
                    val pressed = ev.changes.count { it.pressed }
                    if (pressed >= 2 && !skip) {
                        multi = true
                        val z = ev.calculateZoom()
                        val p = ev.calculatePan()
                        val c = ev.calculateCentroid(useCurrent = true)
                        if (z != 1f || p != Offset.Zero) {
                            vp.st = vp.st.zoomBy(z, c.x, c.y).panBy(p.x, p.y)
                            ev.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                        }
                    } else if (pressed == 1 && !multi && !skip) {
                        val ch = ev.changes.first { it.pressed }
                        val d = ch.positionChange()
                        total += d
                        var move = Offset.Zero
                        if (!slop && total.getDistance() > viewConfiguration.touchSlop) {
                            slop = true
                            if (pagerMode && !vp.st.zoomed && (abs(total.x) > abs(total.y) || !vp.st.canScrollY())) skip = true
                            else move = total          // apply the distance travelled while below the slop
                        } else if (slop) move = d
                        if (slop && !skip) {
                            vp.st = vp.st.panBy(move.x, move.y)
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            ch.consume()
                        }
                    }
                } while (ev.changes.any { it.pressed })
                if (slop && !multi && !skip) {
                    val v = tracker.calculateVelocity()
                    fling = launch { flingViewport(vp, v.x, v.y) }
                }
            }
        }
    }
