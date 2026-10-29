package com.example.depthlock

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun AppScreen(
    status: String,
    a11yOn: Boolean,
    notifOn: Boolean,
    assetVersion: Int,
    fontVersion: Int,
    onPick: () -> Unit,
    onApplyEdit: (Float, Float, Float) -> Unit,
    onImportFont: () -> Unit,
    onA11y: () -> Unit,
    onNotif: () -> Unit
) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val sp = Prefs.sp(ctx)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        sp.registerOnSharedPreferenceChangeListener(l)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
    val v = version + assetVersion

    Scaffold(
        bottomBar = {
            NavigationBar {
                val items = listOf("Home", "Wallpaper", "Clock", "Depth", "Advanced")
                items.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            Icon(
                                when (i) {
                                    0 -> Icons.Filled.Home
                                    1 -> Icons.Filled.Edit
                                    2 -> Icons.Filled.DateRange
                                    3 -> Icons.Filled.Face
                                    else -> Icons.Filled.Settings
                                },
                                contentDescription = label
                            )
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (tab) {
                0 -> HomePage(v, a11yOn, notifOn, onA11y, onNotif) { tab = 1 }
                1 -> {
                    PageTitle("Wallpaper", "Choose, crop, move and scale your photo")
                    WallpaperEditorCard(assetVersion, v, status, onPick, onApplyEdit)
                }
                2 -> ClockPage(v, fontVersion, onImportFont)
                3 -> DepthPage(v)
                else -> AdvancedPage(v)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
