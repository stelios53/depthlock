package com.example.depthlock

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    fun sp(c: Context): SharedPreferences = c.getSharedPreferences("depth", Context.MODE_PRIVATE)

    private fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    private fun putInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    private fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    private fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    private fun str(c: Context, k: String, d: String) = sp(c).getString(k, d) ?: d
    private fun putStr(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()

    // ---------- depth effect ----------
    fun enabled(c: Context) = bool(c, "enabled", true)
    fun setEnabled(c: Context, v: Boolean) = putBool(c, "enabled", v)
    fun region(c: Context) = int(c, "region2", 62)
    fun setRegion(c: Context, v: Int) = putInt(c, "region2", v)
    fun dx(c: Context) = int(c, "dx", 0)
    fun setDx(c: Context, v: Int) = putInt(c, "dx", v)
    fun dy(c: Context) = int(c, "dy", 0)
    fun setDy(c: Context, v: Int) = putInt(c, "dy", v)
    fun scale(c: Context) = int(c, "scale", 1000)
    fun setScale(c: Context, v: Int) = putInt(c, "scale", v)
    fun dim(c: Context) = int(c, "dim", 25)
    fun setDim(c: Context, v: Int) = putInt(c, "dim", v)
    fun fade(c: Context) = int(c, "fade", 15)
    fun setFade(c: Context, v: Int) = putInt(c, "fade", v)

    // ---------- behaviour ----------
    /** 0 = never hide, 1 = smart (read the lock screen, fall back to notification count), 2 = notification count only */
    fun notifMode(c: Context) = int(c, "notifMode", 1)
    fun setNotifMode(c: Context, v: Int) = putInt(c, "notifMode", v)
    fun includeSilent(c: Context) = bool(c, "includeSilent", false)
    fun setIncludeSilent(c: Context, v: Boolean) = putBool(c, "includeSilent", v)
    fun hideKeypad(c: Context) = bool(c, "hideKeypad", true)
    fun setHideKeypad(c: Context, v: Boolean) = putBool(c, "hideKeypad", v)
    /** 0 = hide when the lock screen is dismissed, 1 = hide the moment a biometric unlock succeeds (restores itself if you stay on the lock screen) */
    fun unlockMode(c: Context) = int(c, "unlockMode", 1)
    fun setUnlockMode(c: Context, v: Int) = putInt(c, "unlockMode", v)

    // ---------- custom clock ----------
    fun clockEnabled(c: Context) = bool(c, "clockOn", false)
    fun setClockEnabled(c: Context, v: Boolean) = putBool(c, "clockOn", v)
    fun clockStyle(c: Context) = int(c, "clockStyle", 0)
    fun setClockStyle(c: Context, v: Int) = putInt(c, "clockStyle", v)
    fun clockColor(c: Context) = int(c, "clockColor", 0)
    fun setClockColor(c: Context, v: Int) = putInt(c, "clockColor", v)
    fun clockSize(c: Context) = int(c, "clockSize", 100)
    fun setClockSize(c: Context, v: Int) = putInt(c, "clockSize", v)
    fun clockY(c: Context) = int(c, "clockY", 42)
    fun setClockY(c: Context, v: Int) = putInt(c, "clockY", v)
    fun aodClock(c: Context) = bool(c, "aodClock", true)
    fun setAodClock(c: Context, v: Boolean) = putBool(c, "aodClock", v)
    fun aodY(c: Context) = int(c, "aodY", 22)
    fun setAodY(c: Context, v: Int) = putInt(c, "aodY", v)
    fun aodAlpha(c: Context) = int(c, "aodAlpha", 80)
    fun setAodAlpha(c: Context, v: Int) = putInt(c, "aodAlpha", v)

    // ---------- fonts (per clock style) ----------
    private val defWeight = intArrayOf(200, 900, 700, 400)
    private val defWidth = intArrayOf(60, 92, 92, 100)
    fun fontId(c: Context, style: Int) = str(c, "fontId$style", "alias:sans-serif")
    fun setFontId(c: Context, style: Int, v: String) = putStr(c, "fontId$style", v)
    fun fontWeight(c: Context, style: Int) = int(c, "fontW$style", defWeight[style.coerceIn(0, 3)])
    fun setFontWeight(c: Context, style: Int, v: Int) = putInt(c, "fontW$style", v)
    fun fontWidth(c: Context, style: Int) = int(c, "fontX$style", defWidth[style.coerceIn(0, 3)])
    fun setFontWidth(c: Context, style: Int, v: Int) = putInt(c, "fontX$style", v)

    // ---------- regions that hide the stock clock (% of screen height) ----------
    fun coverTop(c: Context) = int(c, "coverTop", 20)
    fun setCoverTop(c: Context, v: Int) = putInt(c, "coverTop", v)
    fun coverBottom(c: Context) = int(c, "coverBottom", 64)
    fun setCoverBottom(c: Context, v: Int) = putInt(c, "coverBottom", v)
    fun aodCoverTop(c: Context) = int(c, "aodCoverTop", 8)
    fun setAodCoverTop(c: Context, v: Int) = putInt(c, "aodCoverTop", v)
    fun aodCoverBottom(c: Context) = int(c, "aodCoverBottom", 40)
    fun setAodCoverBottom(c: Context, v: Int) = putInt(c, "aodCoverBottom", v)
    fun debugCover(c: Context) = bool(c, "debugCover", false)
    fun setDebugCover(c: Context, v: Boolean) = putBool(c, "debugCover", v)
    fun calibrate(c: Context) = bool(c, "calibrate", false)
    fun setCalibrate(c: Context, v: Boolean) = putBool(c, "calibrate", v)
    fun calibStatus(c: Context) = str(c, "calibStatus", "")
    fun setCalibStatus(c: Context, v: String) = putStr(c, "calibStatus", v)

    // ---------- wallpaper editor ----------
    fun edZoom(c: Context) = int(c, "edZoom", 1000)
    fun edPx(c: Context) = int(c, "edPx", 0)
    fun edPy(c: Context) = int(c, "edPy", 0)
    fun setEdit(c: Context, zoomPermille: Int, px: Int, py: Int) =
        sp(c).edit().putInt("edZoom", zoomPermille).putInt("edPx", px).putInt("edPy", py).apply()

    // ---------- previews ----------
    fun previewUntil(c: Context) = sp(c).getLong("previewUntil", 0L)
    fun setPreviewUntil(c: Context, v: Long) = sp(c).edit().putLong("previewUntil", v).apply()
    fun previewAodUntil(c: Context) = sp(c).getLong("previewAodUntil", 0L)
    fun setPreviewAodUntil(c: Context, v: Long) = sp(c).edit().putLong("previewAodUntil", v).apply()
}
