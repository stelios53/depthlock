package com.example.depthlock

import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.Path as APath
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Calendar

// ------------------------------------------------------------------ shared: hero + preview

@Composable
fun Hero() {
    val cs = MaterialTheme.colorScheme
    val blob = cs.tertiaryContainer
    val ring = cs.primary
    val dot = cs.secondary
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(cs.primaryContainer)
            .padding(26.dp)
    ) {
        Canvas(Modifier.align(Alignment.TopEnd).size(140.dp)) {
            val path = APath()
            Blob.cookie(path, size.width * 0.58f, size.height * 0.46f, size.width * 0.34f, 9, 0.07f, 12f)
            drawPath(path.asComposePath(), blob)
            drawRoundRect(
                color = ring,
                topLeft = Offset(size.width * 0.36f, size.height * 0.10f),
                size = Size(size.width * 0.26f, size.height * 0.62f),
                cornerRadius = CornerRadius(size.width * 0.13f),
                style = Stroke(width = 7.dp.toPx())
            )
            drawCircle(dot, radius = size.width * 0.10f, center = Offset(size.width * 0.49f, size.height * 0.58f))
        }
        Column(Modifier.padding(top = 70.dp)) {
            Text("DepthLock", style = MaterialTheme.typography.displaySmall, color = cs.onPrimaryContainer)
            Text(
                "Depth-effect clocks for your Pixel lock screen",
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onPrimaryContainer
            )
        }
    }
}

@Composable
fun PhonePreview(version: Int, aod: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val screen = remember { CutoutProcessor.screenSize(ctx) }
    val sw = screen.first
    val sh = screen.second
    val renderer = remember { OverlayRenderer(ctx) }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(version) {
        if (Prefs.clockEnabled(ctx) && Prefs.clockStyle(ctx) == ClockPainter.COOKIE) {
            while (true) withFrameNanos { frame = it }
        }
    }
    remember(version) {
        val w = File(ctx.filesDir, "wallpaper.jpg")
        val c = File(ctx.filesDir, "cutout.png")
        renderer.wallpaper = if (w.exists()) BitmapFactory.decodeFile(w.path) else null
        renderer.cutout = if (c.exists()) BitmapFactory.decodeFile(c.path) else null
        version
    }

    Canvas(
        modifier
            .aspectRatio(sw.toFloat() / sh)
            .clip(RoundedCornerShape(28.dp))
            .border(3.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp))
            .background(Color.Black)
    ) {
        frame
        drawIntoCanvas { cv ->
            val n = cv.nativeCanvas
            n.save()
            n.scale(size.width / sw, size.height / sh)
            renderer.loadPrefs()
            renderer.clock.setTime(Calendar.getInstance(), DateFormat.is24HourFormat(ctx))
            renderer.clock.tick = 1f
            renderer.drawPreview(n, sw, sh, aod)
            n.restore()
        }
    }
}

@Composable
fun PreviewCard(version: Int, width: Float = 0.6f) {
    var aod by remember { mutableStateOf(false) }
    SectionCard("Live preview") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !aod, onClick = { aod = false }, label = { Text("Lock screen") })
            FilterChip(selected = aod, onClick = { aod = true }, label = { Text("AOD") })
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PhonePreview(version, aod, Modifier.fillMaxWidth(width))
        }
    }
}

// ------------------------------------------------------------------ HOME

@Composable
fun HomePage(
    version: Int,
    a11yOn: Boolean,
    notifOn: Boolean,
    onA11y: () -> Unit,
    onNotif: () -> Unit,
    onGoWallpaper: () -> Unit
) {
    val ctx = LocalContext.current
    val hasAssets = File(ctx.filesDir, "wallpaper.jpg").exists() && File(ctx.filesDir, "cutout.png").exists()
    Hero()
    SectionCard("Setup") {
        StatusRow("Accessibility service", "Draws the overlay on the lock screen", a11yOn, if (a11yOn) "Open" else "Enable", onA11y)
        StatusRow("Wallpaper & cut-out", "A photo needs to be chosen in the Wallpaper tab", hasAssets, if (hasAssets) "Edit" else "Choose", onGoWallpaper)
        StatusRow("Notification access", "Optional: fallback for hiding on notifications", notifOn, if (notifOn) "Open" else "Enable", onNotif)
    }
    SectionCard("Quick switches") {
        PrefSwitch("Depth effect", "Subject in front of the clock", Prefs.enabled(ctx)) { Prefs.setEnabled(ctx, it) }
        PrefSwitch("Custom clock", "Replaces the stock clock (lock screen + AOD)", Prefs.clockEnabled(ctx)) { Prefs.setClockEnabled(ctx, it) }
    }
    PreviewCard(version)
}

// ------------------------------------------------------------------ CLOCK

@Composable
fun StyleCard(index: Int, name: String, selected: Boolean, colorMode: Int, version: Int, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val painter = remember(index) { ClockPainter(ctx) }
    val cs = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier
            .width(150.dp)
            .clip(shape)
            .background(cs.surfaceContainerHighest)
            .border(if (selected) 3.dp else 0.dp, if (selected) cs.primary else Color.Transparent, shape)
            .clickable { onClick() }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(Color(0xFF16141C))
        ) {
            val ver = version
            drawIntoCanvas { cv ->
                val n = cv.nativeCanvas
                painter.style = index
                painter.colorMode = colorMode
                painter.sizeScale = 1f
                painter.lockY = 0.5f
                painter.setFont(
                    FontStore.typeface(ctx, Prefs.fontId(ctx, index), Prefs.fontWeight(ctx, index)),
                    Prefs.fontWidth(ctx, index) / 100f
                )
                painter.setTime(Calendar.getInstance(), DateFormat.is24HourFormat(ctx))
                painter.tick = 1f
                n.save()
                n.translate(0f, -0.45f * size.height)
                painter.draw(n, size.width, size.height * 1.9f, 1f)
                n.restore()
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun FontCard(style: Int, fontVersion: Int, onImport: () -> Unit) {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(false) }
    key(style, fontVersion) {
        var id by remember { mutableStateOf(Prefs.fontId(ctx, style)) }
        var weight by remember { mutableIntStateOf(Prefs.fontWeight(ctx, style)) }
        SectionCard(
            "Font · ${ClockPainter.NAMES[style]}",
            "Real font files. Pick any installed font, or import your own .ttf / .otf."
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(FontStore.nameOf(ctx, id), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "10:45",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily(FontStore.typeface(ctx, id, weight))
                        )
                    )
                }
                FilledTonalButton(onClick = { show = true }) { Text("Change") }
            }
            PrefSlider("Weight", weight, 100, 900, { "$it" }) { weight = it; Prefs.setFontWeight(ctx, style, it) }
            PrefSlider("Width", Prefs.fontWidth(ctx, style), 40, 130, { "$it%" }) { Prefs.setFontWidth(ctx, style, it) }
            Text(
                "Weight works with variable fonts (e.g. Google Sans Flex, Roboto Flex).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (show) {
            FontPickerDialog(
                fontVersion = fontVersion,
                currentId = id,
                weight = weight,
                onPick = { id = it; Prefs.setFontId(ctx, style, it); show = false },
                onImport = onImport,
                onDismiss = { show = false }
            )
        }
    }
}

@Composable
fun ClockPage(version: Int, fontVersion: Int, onImportFont: () -> Unit) {
    val ctx = LocalContext.current
    var style by remember { mutableIntStateOf(Prefs.clockStyle(ctx)) }
    var colorMode by remember { mutableIntStateOf(Prefs.clockColor(ctx)) }
    PageTitle("Clock", "Your own clock on the lock screen and AOD")
    PreviewCard(version + fontVersion, 0.5f)
    SectionCard("Style", "Pick a clock") {
        PrefSwitch("Enable custom clock", "Hides the stock clock and draws this one instead", Prefs.clockEnabled(ctx)) {
            Prefs.setClockEnabled(ctx, it)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(ClockPainter.NAMES) { i, name ->
                StyleCard(i, name, style == i, colorMode, version + fontVersion) { style = i; Prefs.setClockStyle(ctx, i) }
            }
        }
        ChoiceRow("Colour", listOf("Material You", "White"), colorMode) { colorMode = it; Prefs.setClockColor(ctx, it) }
    }
    if (style != ClockPainter.DOTS) FontCard(style, fontVersion, onImportFont)
    SectionCard("Size & position") {
        PrefSlider("Clock size", Prefs.clockSize(ctx), 60, 140, { "$it%" }) { Prefs.setClockSize(ctx, it) }
        PrefSlider("Lock screen position", Prefs.clockY(ctx), 25, 60, { "$it%" }) { Prefs.setClockY(ctx, it) }
    }
    SectionCard("Always-on display", "The small version that morphs into the big one when you wake the phone") {
        PrefSwitch("Show custom clock on AOD", null, Prefs.aodClock(ctx)) { Prefs.setAodClock(ctx, it) }
        PrefSlider("AOD position", Prefs.aodY(ctx), 8, 45, { "$it%" }) { Prefs.setAodY(ctx, it) }
        PrefSlider("AOD brightness", Prefs.aodAlpha(ctx), 30, 100, { "$it%" }) { Prefs.setAodAlpha(ctx, it) }
    }
}

// ------------------------------------------------------------------ DEPTH

@Composable
fun DepthPage(version: Int) {
    val ctx = LocalContext.current
    PageTitle("Depth effect", "Match the cut-out to your wallpaper, then choose when it hides")
    PreviewCard(version, 0.5f)
    SectionCard("Cut-out appearance", "Tune these on the real lock screen until the cut-out is invisible against the wallpaper") {
        PrefSwitch("Depth effect", null, Prefs.enabled(ctx)) { Prefs.setEnabled(ctx, it) }
        PrefSlider("Size", Prefs.scale(ctx), 900, 1300, { "%.1f%%".format(it / 10f) }) { Prefs.setScale(ctx, it) }
        PrefSlider("Brightness dim", Prefs.dim(ctx), 0, 70, { "$it%" }) { Prefs.setDim(ctx, it) }
        PrefSlider("Bottom fade", Prefs.fade(ctx), 0, 60, { "$it%" }) { Prefs.setFade(ctx, it) }
        PrefSlider("Cut-out height", Prefs.region(ctx), 10, 100, { "$it% of screen" }) { Prefs.setRegion(ctx, it) }
        PrefSlider("Nudge X", Prefs.dx(ctx), -150, 150, { "$it px" }) { Prefs.setDx(ctx, it) }
        PrefSlider("Nudge Y", Prefs.dy(ctx), -150, 150, { "$it px" }) { Prefs.setDy(ctx, it) }
    }
    SectionCard("When to hide", "Applies to the depth effect and the custom clock") {
        ChoiceRow("Notifications", listOf("Never hide", "Smart", "Count only"), Prefs.notifMode(ctx)) { Prefs.setNotifMode(ctx, it) }
        PrefSwitch("Count silent notifications", "Only used by the notification-count modes", Prefs.includeSilent(ctx)) { Prefs.setIncludeSilent(ctx, it) }
        PrefSwitch("Hide for PIN keypad", null, Prefs.hideKeypad(ctx)) { Prefs.setHideKeypad(ctx, it) }
        ChoiceRow("On unlock", listOf("When dismissed", "Instantly (biometric)"), Prefs.unlockMode(ctx)) { Prefs.setUnlockMode(ctx, it) }
        Text(
            "Instantly hides the moment a fingerprint or face is accepted. With Face Unlock waiting for a swipe, it comes back after a moment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ------------------------------------------------------------------ ADVANCED

@Composable
fun AdvancedPage(version: Int) {
    val ctx = LocalContext.current
    var log by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current
    PageTitle("Advanced", "Stock clock cover, tests and diagnostics")
    SectionCard(
        "Stock clock cover",
        "The area painted over the stock clock (your wallpaper on the lock screen, black on the AOD)"
    ) {
        val status = Prefs.calibStatus(ctx).also { version }
        FilledTonalButton(onClick = {
            Prefs.setCalibStatus(ctx, "Waiting… lock the phone, let the AOD appear, then wake it again.")
            Prefs.setCalibrate(ctx, true)
        }) { Text("Auto-detect stock clock area") }
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        PrefSwitch("Show guides on the lock screen", "Red = lock screen area, blue = AOD area", Prefs.debugCover(ctx)) {
            Prefs.setDebugCover(ctx, it)
        }
        key(status) {
            PrefSlider("Lock screen: top", Prefs.coverTop(ctx), 0, 45, { "$it%" }) { Prefs.setCoverTop(ctx, it) }
            PrefSlider("Lock screen: bottom", Prefs.coverBottom(ctx), 30, 90, { "$it%" }) { Prefs.setCoverBottom(ctx, it) }
            PrefSlider("AOD: top", Prefs.aodCoverTop(ctx), 0, 50, { "$it%" }) { Prefs.setAodCoverTop(ctx, it) }
            PrefSlider("AOD: bottom", Prefs.aodCoverBottom(ctx), 10, 80, { "$it%" }) { Prefs.setAodCoverBottom(ctx, it) }
        }
    }
    SectionCard("Tests") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Prefs.setPreviewUntil(ctx, System.currentTimeMillis() + 5000) }) { Text("Lock overlay 5 s") }
            OutlinedButton(onClick = { Prefs.setPreviewAodUntil(ctx, System.currentTimeMillis() + 5000) }) { Text("AOD overlay 5 s") }
        }
    }
    SectionCard("Diagnostics", "If the overlay ever fails to appear, the log says why") {
        val files = listOf("wallpaper.jpg", "cutout.png", "source.jpg", "source_cutout.png")
            .joinToString("   ") { (if (File(ctx.filesDir, it).exists()) "✓ " else "✗ ") + it }
        Text(files, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val f = File(ctx.filesDir, "events.log")
                log = if (f.exists()) f.readText() else "(no log yet)"
            }) { Text("Show log") }
            OutlinedButton(onClick = { clip.setText(AnnotatedString(log)) }) { Text("Copy") }
            OutlinedButton(onClick = { File(ctx.filesDir, "events.log").delete(); log = "" }) { Text("Clear") }
        }
        if (log.isNotEmpty()) {
            SelectionContainer { Text(log, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp) }
        }
    }
}
