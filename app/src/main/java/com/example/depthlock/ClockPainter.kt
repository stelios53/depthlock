package com.example.depthlock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.core.graphics.ColorUtils
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Polar "cookie / flower" blobs, the Material 3 Expressive shape language. */
object Blob {
    fun cookie(path: Path, cx: Float, cy: Float, r: Float, lobes: Int, amp: Float, rotDeg: Float) {
        path.reset()
        val n = 120
        val rot = Math.toRadians(rotDeg.toDouble()).toFloat()
        for (k in 0..n) {
            val th = (2.0 * PI * k / n).toFloat()
            val rr = r * (1f + amp * cos(lobes * th))
            val x = cx + rr * cos(th + rot)
            val y = cy + rr * sin(th + rot)
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }
}

/**
 * Draws the custom clocks with real fonts. p = 0 is the AOD look (small, dim), p = 1 the lock screen look.
 */
class ClockPainter(private val ctx: Context) {

    companion object {
        const val THIN = 0
        const val BOLD = 1
        const val COOKIE = 2
        const val DOTS = 3
        val NAMES = listOf("Ethereal", "Bubble", "Cookie", "Dots")

        private val DOT_FONT = arrayOf(
            "111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001",
            "111100111001111", "111100111101111", "111001010010010", "111101111101111", "111101111001111"
        )

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun smooth(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }
    }

    var style = THIN
    var colorMode = 0
    var sizeScale = 1f
    var lockY = 0.42f
    var aodY = 0.22f
    var aodAlpha = 0.8f
    var tick = 1f

    private var shiftX = 0f
    private var shiftY = 0f
    private val density = ctx.resources.displayMetrics.density

    private val digits = intArrayOf(0, 0, 0, 0)
    private val prev = intArrayOf(0, 0, 0, 0)

    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        strokeJoin = Paint.Join.ROUND
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val mat = Matrix()
    private val tilePath = Path()

    // ---- font ----
    private var typeface: Typeface = Typeface.DEFAULT
    private var scaleX = 1f
    private var metricsDirty = true
    private var h100 = 72f        // digit height at text size 100
    private var top100 = 72f      // distance from baseline up to the digit top at size 100
    private var dw100 = 56f       // widest digit advance at size 100 (unscaled)
    private var colon100 = 24f

    fun setFont(tf: Typeface, sx: Float) {
        if (tf !== typeface || sx != scaleX) {
            typeface = tf
            scaleX = sx
            metricsDirty = true
        }
    }

    private fun metrics() {
        if (!metricsDirty) return
        metricsDirty = false
        tp.typeface = typeface
        tp.textSize = 100f
        tp.textScaleX = 1f
        tp.style = Paint.Style.FILL
        val b = Rect()
        tp.getTextBounds("0123456789", 0, 10, b)
        h100 = max(1f, b.height().toFloat())
        top100 = max(1f, -b.top.toFloat())
        var w = 1f
        for (d in 0..9) w = max(w, tp.measureText(d.toString()))
        dw100 = w
        colon100 = max(1f, tp.measureText(":"))
    }

    // ---- time ----

    /** Returns true if any digit changed. */
    fun setTime(cal: Calendar, is24: Boolean): Boolean {
        var h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        if (!is24) { h %= 12; if (h == 0) h = 12 }
        val n = intArrayOf(if (!is24 && h < 10) -1 else h / 10, h % 10, m / 10, m % 10)
        shiftX = (((m / 3) % 5) - 2) * 4f * density
        shiftY = (((m / 15) % 3) - 1) * 4f * density
        val changed = !n.contentEquals(digits)
        if (changed) {
            digits.copyInto(prev)
            n.copyInto(digits)
        }
        return changed
    }

    fun needsContinuous(p: Float) = style == COOKIE && p > 0.2f

    // ---- colours ----

    private fun sys(id: Int, fallback: Int): Int = try { ctx.getColor(id) } catch (e: Exception) { fallback }
    private fun mainColor() =
        if (colorMode == 1) Color.WHITE else sys(android.R.color.system_accent1_100, Color.WHITE)
    private fun ink() =
        if (colorMode == 1) 0xFF1B1B1F.toInt() else sys(android.R.color.system_accent1_900, 0xFF1B1B1F.toInt())
    private fun tileColors(): IntArray =
        if (colorMode == 1) intArrayOf(Color.WHITE, 0xFFEDEDED.toInt(), 0xFFDCDCDC.toInt(), 0xFFF5F5F5.toInt())
        else intArrayOf(
            sys(android.R.color.system_accent1_200, Color.WHITE),
            sys(android.R.color.system_accent2_200, Color.WHITE),
            sys(android.R.color.system_accent3_200, Color.WHITE),
            sys(android.R.color.system_accent1_100, Color.WHITE)
        )

    // ---- drawing ----

    fun draw(c: Canvas, W: Float, H: Float, p: Float) {
        val cx = W / 2f + shiftX * (1f - p)
        val cy = lerp(aodY, lockY, p) * H + shiftY * (1f - p)
        val color = mainColor()
        val a = lerp(aodAlpha, 1f, smooth(p))
        val now = SystemClock.uptimeMillis()
        when (style) {
            BOLD -> drawBold(c, H, p, cx, cy, color, a)
            COOKIE -> drawCookie(c, H, p, cx, cy, color, a, now)
            DOTS -> drawDots(c, H, p, cx, cy, color, a)
            else -> drawThin(c, H, p, cx, cy, color, a)
        }
    }

    private inline fun anim(i: Int, h: Float, f: (Int, Float, Float) -> Unit) {
        val cur = digits[i]
        val old = prev[i]
        if (tick >= 1f || cur == old) {
            if (cur >= 0) f(cur, 1f, 0f)
        } else {
            val t = tick.coerceIn(0f, 1f)
            if (old >= 0) f(old, 1f - t, -t * 0.35f * h)
            if (cur >= 0) f(cur, t, (1f - t) * 0.35f * h)
        }
    }

    /** Draws [s] centred on [cx]; digit tops sit at [top], digit height is [h]. */
    private fun drawStr(
        c: Canvas, s: String, cx: Float, top: Float, h: Float,
        fillA: Float, outA: Float, ow: Float, color: Int, a: Float
    ) {
        metrics()
        val size = 100f * h / h100
        tp.typeface = typeface
        tp.textSize = size
        tp.textScaleX = scaleX
        val base = top + top100 * size / 100f
        if (fillA > 0.01f) {
            tp.style = Paint.Style.FILL
            tp.color = color
            tp.alpha = (255f * (fillA * a).coerceIn(0f, 1f)).toInt()
            c.drawText(s, cx, base, tp)
        }
        if (outA > 0.01f) {
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = ow
            tp.color = color
            tp.alpha = (255f * (outA * a).coerceIn(0f, 1f)).toInt()
            c.drawText(s, cx, base, tp)
        }
    }

    private fun advance(h: Float): Float { metrics(); return dw100 * (h / h100) * scaleX }

    // ---- style 0: Ethereal (single line, thin font) ----
    private fun drawThin(c: Canvas, H: Float, p: Float, cx: Float, cy: Float, color: Int, a: Float) {
        metrics()
        val h = lerp(H * 0.095f, H * 0.20f, p) * sizeScale
        val w = advance(h)
        val gap = h * 0.03f
        val colonW = colon100 * (h / h100) * scaleX
        val showFirst = digits[0] >= 0
        val total = (if (showFirst) w + gap else 0f) + w + gap + colonW + gap + w + gap + w
        val start = cx - total / 2f
        val x1 = start + if (showFirst) w + gap else 0f
        val x0 = x1 - (w + gap)
        val xc = x1 + w + gap
        val x2 = xc + colonW + gap
        val x3 = x2 + w + gap
        val top = cy - h / 2f
        val xs = floatArrayOf(x0 + w / 2f, x1 + w / 2f, x2 + w / 2f, x3 + w / 2f)
        for (i in 0..3) {
            anim(i, h) { d, al, yo -> drawStr(c, d.toString(), xs[i], top + yo, h, al, 0f, 0f, color, a) }
        }
        drawStr(c, ":", xc + colonW / 2f, top, h, 1f, 0f, 0f, color, a)
    }

    // ---- style 1: Bubble (stacked, heavy font, outline on AOD) ----
    private fun drawBold(c: Canvas, H: Float, p: Float, cx: Float, cy: Float, color: Int, a: Float) {
        metrics()
        val h = lerp(H * 0.085f, H * 0.165f, p) * sizeScale
        val w = advance(h)
        val gap = h * 0.05f
        val rowGap = h * 0.16f
        val e = smooth(p)
        val ow = max(1.6f * density, 1f)
        val top0 = cy - (2f * h + rowGap) / 2f
        val top1 = top0 + h + rowGap
        val half = (w + gap) / 2f
        val showFirst = digits[0] >= 0
        val cxs = floatArrayOf(
            if (showFirst) cx - half else cx - 2f * half,
            if (showFirst) cx + half else cx,
            cx - half,
            cx + half
        )
        val tops = floatArrayOf(top0, top0, top1, top1)
        for (i in 0..3) {
            anim(i, h) { d, al, yo ->
                drawStr(c, d.toString(), cxs[i], tops[i] + yo, h, e * al, (1f - e) * al, ow, color, a)
            }
        }
    }

    // ---- style 2: Cookie tiles ----
    private fun drawCookie(c: Canvas, H: Float, p: Float, cx: Float, cy: Float, color: Int, a: Float, now: Long) {
        val s = lerp(H * 0.085f, H * 0.165f, p) * sizeScale
        val g = s * 0.08f
        val e = smooth(p)
        val tiles = tileColors()
        val inkC = ink()
        val lobes = intArrayOf(8, 0, 6, 10)
        val amps = floatArrayOf(0.05f, 0f, 0.085f, 0.04f)
        for (i in 0..3) {
            val col = i % 2
            val row = i / 2
            val tcx = cx + (col - 0.5f) * (s + g)
            val tcy = cy + (row - 0.5f) * (s + g)
            val sec = (now % 3600000L) / 1000f
            val spin = if (p > 0.2f) sec * (if (i % 2 == 0) 9f else -7f) * e else 0f
            if (lobes[i] == 0) {
                val rr = s * 0.34f
                tilePath.reset()
                tilePath.addRoundRect(RectF(tcx - s / 2f, tcy - s / 2f, tcx + s / 2f, tcy + s / 2f), rr, rr, Path.Direction.CW)
                mat.setRotate(sin(now / 1500f) * 5f * e, tcx, tcy)
                tilePath.transform(mat)
            } else {
                Blob.cookie(tilePath, tcx, tcy, s / 2f / (1f + amps[i]), lobes[i], amps[i], spin)
            }
            fill.color = tiles[i]
            fill.alpha = (255f * (e * a).coerceIn(0f, 1f)).toInt()
            c.drawPath(tilePath, fill)
            outline.color = color
            outline.strokeWidth = max(1.6f * density, s * 0.02f)
            outline.alpha = (255f * ((1f - e) * a).coerceIn(0f, 1f)).toInt()
            c.drawPath(tilePath, outline)

            val dh = s * 0.46f
            val dcol = ColorUtils.blendARGB(color, inkC, e)
            anim(i, dh) { d, al, yo ->
                drawStr(c, d.toString(), tcx, tcy - dh / 2f + yo, dh, al, 0f, 0f, dcol, a)
            }
        }
    }

    // ---- style 3: Dots ----
    private fun drawDots(c: Canvas, H: Float, p: Float, cx: Float, cy: Float, color: Int, a: Float) {
        val pitch = lerp(H * 0.0165f, H * 0.036f, p) * sizeScale
        val digitW = 3f * pitch
        val digitH = 5f * pitch
        val gapX = pitch
        val rowGap = 1.7f * pitch
        val top0 = cy - (2f * digitH + rowGap) / 2f
        val top1 = top0 + digitH + rowGap
        val showFirst = digits[0] >= 0
        val pairW = 2f * digitW + gapX
        val left0 = if (showFirst) cx - pairW / 2f else cx - digitW / 2f - digitW - gapX
        val left1 = if (showFirst) left0 + digitW + gapX else cx - digitW / 2f
        val lefts = floatArrayOf(left0, left1, left0, left1)
        val tops = floatArrayOf(top0, top0, top1, top1)
        for (i in 0..3) {
            anim(i, digitH) { d, al, yo -> drawDotDigit(c, d, lefts[i], tops[i] + yo, pitch, p, al, color, a) }
        }
    }

    private fun drawDotDigit(c: Canvas, d: Int, left: Float, top: Float, pitch: Float, p: Float, al: Float, color: Int, a: Float) {
        if (d < 0 || d > 9) return
        val bits = DOT_FONT[d]
        fill.color = color
        for (r in 0 until 5) {
            for (cl in 0 until 3) {
                val on = bits[r * 3 + cl] == '1'
                val delay = (cl + r) * 0.03f
                val lp = smooth(((p - delay) / (1f - 0.18f)).coerceIn(0f, 1f))
                val x = left + (cl + 0.5f) * pitch
                val y = top + (r + 0.5f) * pitch
                if (on) {
                    fill.alpha = (255f * (al * a).coerceIn(0f, 1f)).toInt()
                    c.drawCircle(x, y, lerp(pitch * 0.2f, pitch * 0.43f, lp), fill)
                } else if (p > 0.05f) {
                    fill.alpha = (255f * (0.2f * lp * al * a).coerceIn(0f, 1f)).toInt()
                    c.drawCircle(x, y, pitch * 0.08f, fill)
                }
            }
        }
    }
}
