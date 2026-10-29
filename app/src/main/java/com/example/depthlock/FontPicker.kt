package com.example.depthlock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun FontPickerDialog(
    fontVersion: Int,
    currentId: String,
    weight: Int,
    onPick: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val entries = remember(fontVersion) { FontStore.listFonts(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose font") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onImport) { Text("Import font file…") } },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(440.dp)) {
                items(entries) { e ->
                    val tf = remember(e.id, weight) { FontStore.typeface(ctx, e.id, weight) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(e.id) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            e.name + if (e.id == currentId) "  ✓" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (e.id == currentId) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "10:45  0123456789",
                            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily(tf))
                        )
                    }
                }
            }
        }
    )
}
