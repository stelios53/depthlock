package com.example.depthlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader

/**
 * Shared drawing code for the overlay windows and the in-app previews.
 * Two separate windows use it: the depth cut-out (top) and the custom clock + stock-clock cover (below it).
 */
class OverlayRenderer(private val ctx: Context) {

    var cutout: Bitmap? = null
    var wallpaper: Bitmap? = null
    var clockEnabled = false
    val clock = ClockPainter(ctx)

    /** Wallpaper paint with the system dim applied; also used by the editor preview. */
    val dimPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var dx = 0f
    private var dy = 0f
    private var scale = 1f
    private var fade = 0f
    private var regionFrac = 0.62f
    private var coverTop = 0.20f
    private var coverBottom = 0.64f
    private var aodTop = 0.08f
    private var aodBottom = 0.40f
    private var debug = false

    private var lockPatch: Bitmap? = null
    private var patchTop = 0f
    private val patchPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val blackPaint = Paint()
    private val guidePaint = Paint().apply { style = Paint.Style.FILL }

    fun loadPrefs() {
        val c = ctx
        dx = Prefs.dx(c).toFloat()
        dy = Prefs.dy(c).toFloat()
        scale = Prefs.scale(c) / 1000f
        val f = (1f - Prefs.dim(c) / 100f).coerceIn(0f, 1f)
        dimPaint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            f, 0f, 0f, 0f, 0f,
            0f, f, 0f, 0f, 0f,
            0f, 0f, f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
        fade = (Prefs.fade(c) / 100f).coerceIn(0f, 0.95f)
        regionFrac = Prefs.region(c) / 100f
        clockEnabled = Prefs.clockEnabled(c)
        coverTop = Prefs.coverTop(c) / 100f
        coverBottom = Prefs.coverBottom(c) / 100f
        aodTop = Prefs.aodCoverTop(c) / 100f
        aodBottom = Prefs.aodCoverBottom(c) / 100f
        debug = Prefs.debugCover(c)
        val st = Prefs.clockStyle(c).coerceIn(0, 3)
        clock.style = st
        clock.colorMode = Prefs.clockColor(c)
        clock.sizeScale = Prefs.clockSize(c) / 100f
        clock.lockY = Prefs.clockY(c) / 100f
        clock.aodY = Prefs.aodY(c) / 100f
        clock.aodAlpha = Prefs.aodAlpha(c) / 100f
        clock.setFont(
            FontStore.typeface(c, Prefs.fontId(c, st), Prefs.fontWeight(c, st)),
            Prefs.fontWidth(c, st) / 100f
        )
    }

    fun regionFrac() = regionFrac
    fun clockWindowFrac() = (maxOf(coverBottom, aodBottom) + 0.05f).coerceIn(0.1f, 1f)
    fun needsContinuous(p: Float) = clockEnabled && clock.needsContinuous(p)

    /** Maps a screen-size bitmap onto the screen with the user's size / nudge alignment. */
    fun alignMatrix(W: Float, H: Float): Matrix {
        val m = Matrix()
        m.setScale(scale, scale, W / 2f, H / 2f)
        m.postTranslate(dx, dy)
        return m
    }

    // ---------- depth cut-out ----------

    fun drawDepth(c: Canvas, W: Int, H: Int) {
        val b = cutout ?: return
        drawCutout(c, W.toFloat(), H.toFloat(), b, alignMatrix(W.toFloat(), H.toFloat()), 1f)
    }

    fun drawCutout(c: Canvas, W: Float, H: Float, bmp: Bitmap, m: Matrix, alpha: Float) {
        val cutH = regionFrac * H
        val layer = c.saveLayerAlpha(0f, 0f, W, cutH, (alpha * 255f).toInt().coerceIn(0, 255))
        c.drawBitmap(bmp, m, dimPaint)
        if (fade > 0f) {
            maskPaint.shader = LinearGradient(
                0f, 0f, 0f, cutH,
                intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
                floatArrayOf(0f, 1f - fade, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, W, cutH, maskPaint)
        }
        c.restoreToCount(layer)
    }

    // ---------- clock window: stock-clock cover + custom clock ----------

    /** Pre-renders the wallpaper patch that hides the stock lock screen clock. */
    fun rebuildPatch(W: Int, H: Int) {
        val wp = wallpaper
        if (wp == null || !clockEnabled) { lockPatch = null; return }
        val f = 0.04f * H
        val top = (coverTop * H - f).coerceAtLeast(0f)
        val bottom = (coverBottom * H + f).coerceAtMost(H.toFloat())
        val ph = (bottom - top).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(W, ph, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.save()
        cv.translate(0f, -top)
        cv.drawBitmap(wp, alignMatrix(W.toFloat(), H.toFloat()), dimPaint)
        cv.restore()
        val span = ph.toFloat()
        maskPaint.shader = LinearGradient(
            0f, 0f, 0f, span,
            intArrayOf(0x00000000, 0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
            floatArrayOf(0f, (f / span).coerceAtMost(0.45f), 1f - (f / span).coerceAtMost(0.45f), 1f),
            Shader.TileMode.CLAMP
        )
        cv.drawRect(0f, 0f, W.toFloat(), span, maskPaint)
        lockPatch = bmp
        patchTop = top
    }

    fun hasPatch() = lockPatch != null

    /** p = 0 → AOD, p = 1 → lock screen. */
    fun drawClock(c: Canvas, W: Int, H: Int, p: Float) {
        val wf = W.toFloat()
        val hf = H.toFloat()
        val patch = lockPatch
        if (patch != null && p > 0.001f) {
            patchPaint.alpha = (p * 255f).toInt().coerceIn(0, 255)
            c.drawBitmap(patch, 0f, patchTop, patchPaint)
        }
        if (p < 0.999f) drawBlackPatch(c, wf, hf, 1f - p)
        if (debug) drawGuides(c, wf, hf, p)
        clock.draw(c, wf, hf, p)
    }

    private fun drawBlackPatch(c: Canvas, W: Float, H: Float, alpha: Float) {
        val f = 0.03f * H
        val top = aodTop * H - f
        val bottom = aodBottom * H + f
        val span = bottom - top
        blackPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0x00000000, 0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
            floatArrayOf(0f, f / span, 1f - f / span, 1f),
            Shader.TileMode.CLAMP
        )
        blackPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        c.drawRect(0f, top, W, bottom, blackPaint)
    }

    private fun drawGuides(c: Canvas, W: Float, H: Float, p: Float) {
        guidePaint.shader = null
        guidePaint.color = Color.argb((60 * p).toInt(), 255, 0, 0)
        c.drawRect(0f, coverTop * H, W, coverBottom * H, guidePaint)
        guidePaint.color = Color.argb((255 * p).toInt(), 255, 0, 0)
        c.drawRect(0f, coverTop * H - 3f, W, coverTop * H + 3f, guidePaint)
        c.drawRect(0f, coverBottom * H - 3f, W, coverBottom * H + 3f, guidePaint)
        val q = 1f - p
        guidePaint.color = Color.argb((70 * q).toInt(), 0, 160, 255)
        c.drawRect(0f, aodTop * H, W, aodBottom * H, guidePaint)
        guidePaint.color = Color.argb((255 * q).toInt(), 0, 160, 255)
        c.drawRect(0f, aodTop * H - 3f, W, aodTop * H + 3f, guidePaint)
        c.drawRect(0f, aodBottom * H - 3f, W, aodBottom * H + 3f, guidePaint)
    }

    // ---------- previews ----------

    fun drawBase(c: Canvas, W: Int, H: Int) {
        val wp = wallpaper
        val hf = H.toFloat()
        if (wp == null) {
            val g = Paint()
            g.shader = LinearGradient(0f, 0f, 0f, hf, 0xFF3B4A7A.toInt(), 0xFF14101F.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W.toFloat(), hf, g)
            return
        }
        c.drawColor(Color.BLACK)
        c.drawBitmap(wp, alignMatrix(W.toFloat(), hf), dimPaint)
    }

    fun drawPreview(c: Canvas, W: Int, H: Int, aod: Boolean) {
        val wf = W.toFloat()
        val hf = H.toFloat()
        if (aod) {
            c.drawColor(Color.BLACK)
            if (clockEnabled) clock.draw(c, wf, hf, 0f)
            return
        }
        drawBase(c, W, H)
        if (clockEnabled) clock.draw(c, wf, hf, 1f)
        drawDepth(c, W, H)
    }
}
