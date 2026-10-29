package com.example.depthlock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.text.format.DateFormat
import android.view.View
import android.view.animation.PathInterpolator
import java.util.Calendar

/** The depth cut-out window: draws exactly like the earlier, reliable version. */
class CutoutView(ctx: Context, private val r: OverlayRenderer) : View(ctx) {
    var fullW = 0
    var fullH = 0
    override fun onDraw(canvas: Canvas) {
        r.drawDepth(canvas, fullW, fullH)
    }
}

/** The custom clock window (sits below the cut-out window). */
class ClockView(ctx: Context, private val r: OverlayRenderer) : View(ctx) {
    var fullW = 0
    var fullH = 0

    /** 0 = AOD look, 1 = lock screen look. */
    var p = 1f
        set(v) { field = v; invalidate() }

    private var tickAnim: ValueAnimator? = null

    fun updateTime(animate: Boolean) {
        val changed = r.clock.setTime(Calendar.getInstance(), DateFormat.is24HourFormat(context))
        if (!changed) { invalidate(); return }
        tickAnim?.cancel()
        if (animate && p > 0.5f) {
            r.clock.tick = 0f
            tickAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 550
                interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
                addUpdateListener { r.clock.tick = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            r.clock.tick = 1f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        tickAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        r.drawClock(canvas, fullW, fullH, p)
        if (r.needsContinuous(p)) postInvalidateOnAnimation()
    }
}
