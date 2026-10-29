package com.example.depthlock

import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private var status by mutableStateOf("")
    private var a11yOn by mutableStateOf(false)
    private var notifOn by mutableStateOf(false)
    private var assetVersion by mutableIntStateOf(0)
    private var fontVersion by mutableIntStateOf(0)

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) importPhoto(uri) }

    private val fontPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importFont(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DepthLockTheme {
                AppScreen(
                    status = status,
                    a11yOn = a11yOn,
                    notifOn = notifOn,
                    assetVersion = assetVersion,
                    fontVersion = fontVersion,
                    onPick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onApplyEdit = { z, x, y -> applyEdit(z, x, y) },
                    onImportFont = { fontPicker.launch(arrayOf("*/*")) },
                    onA11y = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onNotif = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        a11yOn = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(packageName) == true
        notifOn = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun updateStatus(t: String) = runOnUiThread { status = t }

    // ---------- fonts ----------

    private fun importFont(uri: Uri) {
        executor.execute {
            try {
                var name = "font_${System.currentTimeMillis()}.ttf"
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) name = c.getString(i)
                }
                val dest = File(FontStore.fontsDir(this), name)
                contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                runOnUiThread { fontVersion++ }
                updateStatus("Imported font: $name")
            } catch (e: Exception) {
                updateStatus("Could not import font: ${e.message}")
            }
        }
    }

    // ---------- photo + cut-out ----------

    private fun importPhoto(uri: Uri) {
        updateStatus("Reading photo…")
        executor.execute {
            try {
                val src = CutoutProcessor.decodeMax(this, uri, 2800)
                File(filesDir, "source.jpg").outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                updateStatus("Finding the subject…")
                runOnUiThread { segment(src) }
            } catch (e: Exception) {
                updateStatus("Failed: ${e.message}")
            }
        }
    }

    private fun segment(src: Bitmap) {
        val options = SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        SubjectSegmentation.getClient(options)
            .process(InputImage.fromBitmap(src, 0))
            .addOnSuccessListener { result ->
                val fg = result.foregroundBitmap
                executor.execute {
                    val cut = fg ?: Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                    File(filesDir, "source_cutout.png").outputStream().use { cut.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Prefs.setEdit(this, 1000, 0, 0)
                    applyEdit(1f, 0f, 0f, if (fg == null) "No subject found, wallpaper set without the depth effect." else null)
                }
            }
            .addOnFailureListener {
                updateStatus("Segmentation failed (the model may still be downloading, retry in a minute): ${it.message}")
            }
    }

    private fun applyEdit(zoom: Float, px: Float, py: Float, note: String? = null) {
        executor.execute {
            try {
                val srcFile = File(filesDir, "source.jpg")
                val cutFile = File(filesDir, "source_cutout.png")
                val src = BitmapFactory.decodeFile(srcFile.path) ?: throw IllegalStateException("no source photo")
                val cut = BitmapFactory.decodeFile(cutFile.path)
                val (w, h) = CutoutProcessor.screenSize(this)

                val wall = EditMath.render(src, w, h, zoom, px, py)
                File(filesDir, "wallpaper.jpg").outputStream().use { wall.compress(Bitmap.CompressFormat.JPEG, 96, it) }
                if (cut != null) {
                    val c = EditMath.render(cut, w, h, zoom, px, py)
                    File(filesDir, "cutout.png").outputStream().use { c.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                WallpaperManager.getInstance(this).setBitmap(wall, null, true, WallpaperManager.FLAG_LOCK)
                Prefs.setEdit(this, (zoom * 1000f).roundToInt(), px.roundToInt(), py.roundToInt())
                updateStatus(note ?: "Applied. Lock the phone to see it.")
                runOnUiThread { assetVersion++ }
            } catch (e: Exception) {
                updateStatus("Failed: ${e.message}")
            }
        }
    }
}
