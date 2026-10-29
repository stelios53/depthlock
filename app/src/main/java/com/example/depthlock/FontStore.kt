package com.example.depthlock

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import java.io.File

object FontStore {

    data class Entry(val id: String, val name: String)

    private val cache = HashMap<String, Typeface>()

    private val aliasNames = linkedMapOf(
        "sans-serif" to "System sans-serif",
        "sans-serif-condensed" to "System condensed",
        "sans-serif-medium" to "System medium",
        "sans-serif-black" to "System black",
        "serif" to "System serif",
        "monospace" to "System monospace",
        "casual" to "System casual",
        "cursive" to "System cursive"
    )

    fun fontsDir(ctx: Context) = File(ctx.filesDir, "fonts").also { it.mkdirs() }

    fun listFonts(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        aliasNames.forEach { (k, v) -> out.add(Entry("alias:$k", v)) }

        fontsDir(ctx).listFiles()?.sortedBy { it.name.lowercase() }?.forEach {
            out.add(Entry("file:${it.path}", it.nameWithoutExtension + " (imported)"))
        }

        try {
            val sys = SystemFonts.getAvailableFonts().mapNotNull { f ->
                val file = f.file ?: return@mapNotNull null
                val n = file.nameWithoutExtension
                val lower = n.lowercase()
                val skip = lower.contains("emoji") || lower.contains("symbols") || lower.contains("cjk") ||
                    lower.contains("color") ||
                    (lower.startsWith("noto") && !Regex("^noto(sans|serif)(mono|display)?-.*", RegexOption.IGNORE_CASE).matches(n))
                if (skip) null else Entry("file:${file.path}", n)
            }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
            out.addAll(sys)
        } catch (_: Throwable) {}
        return out
    }

    fun nameOf(ctx: Context, id: String): String {
        if (id.startsWith("alias:")) return aliasNames[id.removePrefix("alias:")] ?: id.removePrefix("alias:")
        return File(id.removePrefix("file:")).nameWithoutExtension
    }

    /** Variable fonts get the weight through the 'wght' axis; system families through Typeface weights. */
    fun typeface(ctx: Context, id: String, weight: Int): Typeface {
        val key = "$id|$weight"
        cache[key]?.let { return it }
        val tf: Typeface = try {
            if (id.startsWith("file:")) {
                Typeface.Builder(File(id.removePrefix("file:")))
                    .setFontVariationSettings("'wght' $weight")
                    .build() ?: Typeface.DEFAULT
            } else {
                val base = Typeface.create(id.removePrefix("alias:"), Typeface.NORMAL)
                Typeface.create(base, weight.coerceIn(1, 1000), false)
            }
        } catch (_: Throwable) {
            Typeface.DEFAULT
        }
        cache[key] = tf
        return tf
    }
}
