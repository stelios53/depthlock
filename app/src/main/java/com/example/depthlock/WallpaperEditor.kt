package com.example.depthlock

import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.Matrix as AMatrix
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Calendar

@Composable
fun WallpaperEditorCard(
    assetVersion: Int,
    version: Int,
    status: String,
    onPick: () -> Unit,
    onApply: (Float, Float, Float) -> Unit
) {
    val ctx = LocalContext.current
    val screen = remember { CutoutProcessor.screenSize(ctx) }
    val W = screen.first
    val H = screen.second
    val src = remember(assetVersion) {
        File(ctx.filesDir, "source.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    }
    val srcCut = remember(assetVersion) {
        File(ctx.filesDir, "source_cutout.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    }
    val renderer = remember { OverlayRenderer(ctx) }
    var zoom by remember(assetVersion) { mutableFloatStateOf(Prefs.edZoom(ctx) / 1000f) }
    var px by remember(assetVersion) { mutableFloatStateOf(Prefs.edPx(ctx).toFloat()) }
    var py by remember(assetVersion) { mutableFloatStateOf(Prefs.edPy(ctx).toFloat()) }

    SectionCard(
        "Wallpaper editor",
        "Drag to move, pinch to scale. The preview shows the clock and depth effect exactly as they will look."
    ) {
        val s = src
        if (s == null) {
            Text(
                "No photo yet. Choose one to start editing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier
                        .fillMaxWidth(0.72f)
                        .aspectRatio(W.toFloat() / H)
                        .clip(RoundedCornerShape(28.dp))
                        .border(3.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp))
                        .background(androidx.compose.ui.graphics.Color.Black)
                        .pointerInput(s) {
                            detectTransformGestures { centroid, pan, zoomChange, _ ->
                                val k = size.width.toFloat() / W
                                val cx = centroid.x / k - W / 2f
                                val cy = centroid.y / k - H / 2f
                                val nz = (zoom * zoomChange).coerceIn(1f, 8f)
                                val f = nz / zoom
                                val (nx, ny) = EditMath.clampPan(
                                    s.width, s.height, W, H, nz,
                                    cx + (px - cx) * f + pan.x / k,
                                    cy + (py - cy) * f + pan.y / k
                                )
                                zoom = nz; px = nx; py = ny
                            }
                        }
                ) {
                    val z = zoom
                    val x = px
                    val y = py
                    val ver = version
                    drawIntoCanvas { cv ->
                        val n = cv.nativeCanvas
                        n.save()
                        n.scale(size.width / W, size.height / H)
                        renderer.loadPrefs()
                        renderer.clock.setTime(Calendar.getInstance(), DateFormat.is24HourFormat(ctx))
                        renderer.clock.tick = 1f
                        val m = EditMath.matrix(s.width, s.height, W, H, z, x, y)
                        n.drawColor(AColor.BLACK)
                        n.drawBitmap(s, m, renderer.dimPaint)
                        if (renderer.clockEnabled) renderer.clock.draw(n, W.toFloat(), H.toFloat(), 1f)
                        val cut = srcCut
                        if (cut != null) {
                            val m2 = AMatrix(m)
                            m2.postConcat(renderer.alignMatrix(W.toFloat(), H.toFloat()))
                            renderer.drawCutout(n, W.toFloat(), H.toFloat(), cut, m2, 1f)
                        }
                        n.restore()
                    }
                }
            }
            Text("Scale ${"%.2f".format(zoom)}×", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = zoom,
                onValueChange = { z ->
                    val f = z / zoom
                    val (nx, ny) = EditMath.clampPan(s.width, s.height, W, H, z, px * f, py * f)
                    zoom = z; px = nx; py = ny
                },
                valueRange = 1f..8f
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { zoom = 1f; px = 0f; py = 0f }) { Text("Reset") }
                FilledTonalButton(onClick = { onApply(zoom, px, py) }) { Text("Apply to lock screen") }
            }
        }
        Button(
            onClick = onPick,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text(if (s == null) "Choose photo" else "Choose a different photo", style = MaterialTheme.typography.titleMedium) }
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
