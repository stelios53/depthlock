package com.example.depthlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.view.WindowManager
import kotlin.math.ceil
import kotlin.math.max

object CutoutProcessor {

    fun screenSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        return b.width() to b.height()
    }

    /** Decodes [uri] so that its longest side is at most [maxDim]. */
    fun decodeMax(ctx: Context, uri: Uri, maxDim: Int): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            decoder.setTargetSampleSize(max(1, ceil(longest / maxDim.toFloat()).toInt()))
        }
    }
}

/** Maths shared by the wallpaper editor and the final render, so the preview is exact. */
object EditMath {
    fun baseScale(sw: Int, sh: Int, W: Int, H: Int) = max(W / sw.toFloat(), H / sh.toFloat())

    fun clampPan(sw: Int, sh: Int, W: Int, H: Int, zoom: Float, px: Float, py: Float): Pair<Float, Float> {
        val s = baseScale(sw, sh, W, H) * zoom
        val ex = max(0f, (sw * s - W) / 2f)
        val ey = max(0f, (sh * s - H) / 2f)
        return px.coerceIn(-ex, ex) to py.coerceIn(-ey, ey)
    }

    /** Matrix mapping source pixels to screen pixels. */
    fun matrix(sw: Int, sh: Int, W: Int, H: Int, zoom: Float, px: Float, py: Float): Matrix {
        val s = baseScale(sw, sh, W, H) * zoom
        val m = Matrix()
        m.setScale(s, s)
        m.postTranslate(W / 2f + px - sw * s / 2f, H / 2f + py - sh * s / 2f)
        return m
    }

    fun render(src: Bitmap, W: Int, H: Int, zoom: Float, px: Float, py: Float): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, matrix(src.width, src.height, W, H, zoom, px, py), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
