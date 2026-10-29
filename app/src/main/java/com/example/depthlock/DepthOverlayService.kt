package com.example.depthlock

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two overlay windows:
 *  - the clock window (stock-clock cover + custom clock), added first so it sits underneath
 *  - the depth cut-out window, added second so it sits on top
 * Both follow the same show / hide rules.
 */
class DepthOverlayService : AccessibilityService() {

    companion object {
        private const val SYSTEMUI = "com.android.systemui"
        private const val FADE_IN_MS = 250L
        private const val FADE_IN_DELAY_MS = 40L
        private const val UNLOCK_FADE_MS = 0L
        private const val HIDE_FADE_MS = 100L
        private const val SCAN_DELAY_MS = 50L
        private const val SCAN_WAIT_MAX_MS = 350L
        private const val POLL_MS = 20L
        private const val RECOVER_MS = 450L
        private const val TO_LOCK_MS = 420L
        private const val TO_AOD_MS = 450L
    }

    private enum class Mode { NONE, AOD, LOCK }

    private class Scan(
        val keypad: Boolean,
        val qsKnown: Boolean,
        val shadeVisible: Boolean,
        val scrollerFound: Boolean,
        val notifRows: Int,
        val clockTop: Int,
        val clockBottom: Int,
        val line: String,
        val ids: String
    )

    private class Acc {
        val digits = HashSet<String>()
        val ids = LinkedHashSet<String>()
        val clocks = ArrayList<Triple<String, Int, Int>>()
        var pinView = false
        var qsKnown = false
        var qsVisible = false
        var scroller = false
        var rows = 0
    }

    private lateinit var wm: WindowManager
    private lateinit var dm: DisplayManager
    private lateinit var km: KeyguardManager
    private lateinit var bg: Handler
    private var scanThread: HandlerThread? = null
    private val handler = Handler(Looper.getMainLooper())
    private val rend by lazy { OverlayRenderer(this) }

    private var rendKey = ""
    private var cutView: CutoutView? = null
    private var cutKey = ""
    private var clockView: ClockView? = null
    private var clockKey = ""
    private var clockMode = Mode.NONE
    private var pAnim: ValueAnimator? = null

    private var cachedCut: Bitmap? = null
    private var cutStamp = 0L
    private var cachedWall: Bitmap? = null
    private var wallStamp = 0L

    private var dismissed = false
    private var shadeEvent = false
    @Volatile private var scan: Scan? = null
    private var scanDone = false
    private var wakeAt = 0L
    private var wasOn = false
    private var onCached = false
    private var lockedCached = false
    private var lastScanLine = ""
    private var lastState = ""

    private var deviceLockedAtShow = false
    private var pollRunning = false
    private var earlyPending = false

    private var calibAodDone = false
    private var calibLockDone = false
    private var aodScanPending = false

    private val scanScheduled = AtomicBoolean(false)
    @Volatile private var scanDirty = false
    private val evLog = ArrayDeque<String>()

    private val refreshRunnable = Runnable { refresh() }
    private val previewRunnable = Runnable { refresh() }

    private fun sysString(name: String, fallback: String): String = try {
        val res = packageManager.getResourcesForApplication(SYSTEMUI)
        val id = res.getIdentifier(name, "string", SYSTEMUI)
        if (id != 0) res.getString(id) else fallback
    } catch (e: Exception) { fallback }

    private fun norm(s: String) = s.lowercase().trim().trimEnd('.')

    private val shadeKeys by lazy {
        listOf(
            norm(sysString("accessibility_desc_notification_shade", "Notification shade")),
            norm(sysString("accessibility_desc_quick_settings", "Quick settings")),
            "notification shade", "quick settings"
        ).distinct()
    }
    private val lockKeys by lazy {
        listOf(norm(sysString("accessibility_desc_lock_screen", "Lock screen")), "lock screen").distinct()
    }

    // ---------- unlock handling ----------

    private val unlockPoll = object : Runnable {
        override fun run() {
            if ((cutView == null && clockView == null) || clockMode == Mode.AOD) { pollRunning = false; return }
            if (Prefs.unlockMode(this@DepthOverlayService) == 1 && deviceLockedAtShow && !km.isDeviceLocked) {
                pollRunning = false
                onEarlyUnlock()
                return
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun startUnlockPoll() {
        if (pollRunning) return
        pollRunning = true
        deviceLockedAtShow = km.isDeviceLocked
        handler.postDelayed(unlockPoll, POLL_MS)
    }

    /** Biometric success: hide now. If we are still on the lock screen shortly after (Face Unlock waiting for a swipe), come back. */
    private fun onEarlyUnlock() {
        dismissed = true
        earlyPending = true
        scan = null
        hideAll(UNLOCK_FADE_MS)
        logState("hidden: early unlock")
        handler.removeCallbacks(recoverRunnable)
        handler.postDelayed(recoverRunnable, RECOVER_MS)
    }

    private val recoverRunnable = Runnable {
        if (!earlyPending) return@Runnable
        earlyPending = false
        val state = dm.getDisplay(Display.DEFAULT_DISPLAY)?.state
        if (state == Display.STATE_ON && km.isKeyguardLocked) {
            dismissed = false
            refresh()
        }
    }

    private fun onUnlocked() {
        earlyPending = false
        handler.removeCallbacks(recoverRunnable)
        dismissed = true
        scan = null
        hideAll(UNLOCK_FADE_MS)
    }

    private fun resetLatches() {
        earlyPending = false
        dismissed = false
        shadeEvent = false
        scan = null
        scanDone = false
        lastScanLine = ""
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> refresh()
                Intent.ACTION_USER_PRESENT -> onUnlocked()
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                    clockView?.updateTime(true)
            }
        }
    }

    private val keyguardListener = KeyguardManager.KeyguardLockedStateListener { locked ->
        if (locked) resetLatches() else onUnlocked()
        refresh()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) refresh()
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        dm = getSystemService(DisplayManager::class.java)
        km = getSystemService(KeyguardManager::class.java)
        scanThread = HandlerThread("depthlock-scan").also { it.start() }
        bg = Handler(scanThread!!.looper)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        dm.registerDisplayListener(displayListener, handler)
        try { km.addKeyguardLockedStateListener(mainExecutor, keyguardListener) } catch (_: Exception) {}
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        NotifState.onChange = { refresh() }
        logLine("${stamp()} SERVICE connected")
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName != SYSTEMUI) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            updateShadeEvent(event)
            logEvent(event)
            refresh()
        }
        requestScan()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        hideAll(0)
        NotifState.onChange = null
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        try { dm.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        try { km.removeKeyguardLockedStateListener(keyguardListener) } catch (_: Exception) {}
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        scanThread?.quitSafely()
        return super.onUnbind(intent)
    }

    // ---------- logging ----------

    private fun stamp() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun logLine(s: String) {
        synchronized(evLog) {
            evLog.addLast(s)
            while (evLog.size > 60) evLog.removeFirst()
            try { File(filesDir, "events.log").writeText(evLog.joinToString("\n")) } catch (_: Exception) {}
        }
    }

    private fun logEvent(e: AccessibilityEvent) {
        logLine("${stamp()} EVENT ${e.className} | ${e.text.joinToString(",")} | ${e.contentDescription} | shadeEvent=$shadeEvent")
    }

    /** Logs why the overlay is shown / hidden, but only when that changes. */
    private fun logState(s: String) {
        if (s == lastState) return
        lastState = s
        logLine("${stamp()} STATE $s")
    }

    // ---------- shade hint from window titles ----------

    private fun updateShadeEvent(e: AccessibilityEvent) {
        val t = norm(e.text.joinToString(" ") + " " + (e.contentDescription ?: ""))
        if (t.isBlank()) return
        if (lockKeys.any { t.contains(it) }) shadeEvent = false
        else if (shadeKeys.any { t.contains(it) }) shadeEvent = true
    }

    // ---------- lockscreen scan (background thread) ----------

    private fun requestScan(delayMs: Long = SCAN_DELAY_MS) {
        if (!onCached || !lockedCached || dismissed) return
        scanDirty = true
        if (!scanScheduled.compareAndSet(false, true)) return
        bg.postDelayed({
            scanDirty = false
            val s = try { scanSystemUi() } catch (e: Exception) { null }
            scanScheduled.set(false)
            handler.post {
                applyScan(s)
                if (scanDirty) requestScan()
            }
        }, delayMs)
    }

    private fun applyScan(s: Scan?) {
        if (dismissed || !onCached) return
        if (s != null) {
            scan = s
            if (s.line != lastScanLine) {
                lastScanLine = s.line
                logLine("${stamp()} SCAN ${s.line} | ${s.ids}")
            }
            if (Prefs.calibrate(this) && !calibLockDone && s.clockTop >= 0) {
                val h = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
                val top = (s.clockTop / h * 100f).toInt() - 3
                val bottom = (s.clockBottom / h * 100f).toInt() + 3
                Prefs.setCoverTop(this, top.coerceIn(0, 60))
                Prefs.setCoverBottom(this, bottom.coerceIn(20, 90))
                calibLockDone = true
                finishCalibration("Lock screen: $top–$bottom%")
            }
        }
        scanDone = true
        refresh()
    }

    private var calibText = ""
    private fun finishCalibration(part: String) {
        calibText = if (calibText.isEmpty()) part else "$calibText   •   $part"
        Prefs.setCalibStatus(this, calibText)
        if (calibLockDone && calibAodDone) Prefs.setCalibrate(this, false)
    }

    private fun scheduleAodCalibration() {
        if (aodScanPending || calibAodDone) return
        aodScanPending = true
        handler.postDelayed({
            bg.post {
                val s = try { scanSystemUi() } catch (e: Exception) { null }
                handler.post {
                    aodScanPending = false
                    if (s != null && s.clockTop >= 0 && !calibAodDone) {
                        val h = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
                        val top = (s.clockTop / h * 100f).toInt() - 3
                        val bottom = (s.clockBottom / h * 100f).toInt() + 3
                        Prefs.setAodCoverTop(this, top.coerceIn(0, 50))
                        Prefs.setAodCoverBottom(this, bottom.coerceIn(10, 80))
                        calibAodDone = true
                        finishCalibration("AOD: $top–$bottom%")
                    } else if (s == null) {
                        logLine("${stamp()} CALIB AOD scan unavailable")
                    }
                }
            }
        }, 900)
    }

    private fun scanSystemUi(): Scan? {
        val ws = windows ?: return null
        val acc = Acc()
        val d = resources.displayMetrics
        val minRow = 56f * d.density
        val minWidth = d.widthPixels * 0.5f
        val minClockH = d.heightPixels * 0.04f
        var found = false
        for (w in ws) {
            val root = w.root ?: continue
            if (root.packageName != SYSTEMUI) continue
            found = true
            walk(root, 0, acc, intArrayOf(700), minRow, minWidth, minClockH)
        }
        if (!found) return null
        val keypad = acc.pinView || acc.digits.size >= 8
        val clock = pickClock(acc.clocks)
        val line = "keypad=$keypad qsKnown=${acc.qsKnown} qsVisible=${acc.qsVisible} " +
            "scroller=${acc.scroller} rows=${acc.rows} digits=${acc.digits.size} clock=${clock?.first ?: -1}-${clock?.second ?: -1}"
        return Scan(
            keypad, acc.qsKnown, acc.qsVisible, acc.scroller, acc.rows,
            clock?.first ?: -1, clock?.second ?: -1, line, acc.ids.joinToString(" ")
        )
    }

    private fun pickClock(c: List<Triple<String, Int, Int>>): Pair<Int, Int>? {
        if (c.isEmpty()) return null
        val big = c.filter { it.first.contains("lockscreen_clock_view_large") }
        val med = c.filter { it.first.contains("lockscreen_clock_view") }
        val pool = when {
            big.isNotEmpty() -> big
            med.isNotEmpty() -> med
            else -> listOf(c.minByOrNull { it.third - it.second }!!)
        }
        return pool.minOf { it.second } to pool.maxOf { it.third }
    }

    private val interestingKeys =
        listOf("notification", "qs", "quick", "shade", "pin", "keyguard", "clock", "bouncer", "scroller", "lock")

    private fun walk(
        n: AccessibilityNodeInfo?, depth: Int, acc: Acc, budget: IntArray,
        minRow: Float, minWidth: Float, minClockH: Float
    ) {
        if (n == null || budget[0] <= 0 || depth > 45) return
        budget[0]--
        val vis = n.isVisibleToUser
        val id = n.viewIdResourceName?.substringAfterLast('/')?.lowercase() ?: ""
        if (id.isNotEmpty()) {
            if (id == "notification_stack_scroller") {
                acc.scroller = true
                acc.rows = countRows(n, minRow, minWidth)
            }
            if (id.contains("quick_settings") || id.startsWith("qs_") || id.contains("quick_qs")) {
                acc.qsKnown = true
                if (vis) acc.qsVisible = true
            }
            if (vis && (id == "keyguard_pin_view" || id == "keyguard_pattern_view" || id == "keyguard_password_view")) {
                acc.pinView = true
            }
            var tag = id + if (vis) "+" else "-"
            if (vis && id.contains("clock")) {
                val r = Rect()
                n.getBoundsInScreen(r)
                tag += "[${r.top}-${r.bottom}]"
                if (r.height() >= minClockH) acc.clocks.add(Triple(id, r.top, r.bottom))
            }
            if (acc.ids.size < 45 && interestingKeys.any { id.contains(it) }) acc.ids.add(tag)
        }
        if (vis) {
            val t = n.text?.toString()
            val cd = n.contentDescription?.toString()
            if (t != null && t.length == 1 && t[0].isDigit()) acc.digits.add(t)
            if (cd != null && cd.length == 1 && cd[0].isDigit()) acc.digits.add(cd)
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, acc, budget, minRow, minWidth, minClockH)
    }

    private fun countRows(scroller: AccessibilityNodeInfo, minRow: Float, minWidth: Float): Int {
        var rows = 0
        val r = Rect()
        for (i in 0 until scroller.childCount) {
            val c = scroller.getChild(i) ?: continue
            if (!c.isVisibleToUser) continue
            c.getBoundsInScreen(r)
            if (r.height() >= minRow && r.width() >= minWidth) rows++
        }
        return rows
    }

    // ---------- state machine ----------

    private fun lockscreenShowsNotifications(): Boolean = try {
        Settings.Secure.getInt(contentResolver, "lock_screen_show_notifications", 1) == 1
    } catch (e: Exception) { true }

    private fun refresh() {
        val now = System.currentTimeMillis()
        val previewLockEnd = Prefs.previewUntil(this)
        val previewAodEnd = Prefs.previewAodUntil(this)
        val previewing = now < previewLockEnd
        val previewingAod = now < previewAodEnd
        handler.removeCallbacks(previewRunnable)
        val end = maxOf(previewLockEnd, previewAodEnd)
        if (now < end) handler.postDelayed(previewRunnable, end - now + 100)

        val state = dm.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_OFF
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val locked = km.isKeyguardLocked
        val on = state == Display.STATE_ON && interactive
        val aodLike = !on && (state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND ||
            (state == Display.STATE_ON && !interactive))
        onCached = on
        lockedCached = locked

        if (aodLike) {
            resetLatches()
            wasOn = false
            hideCutout(0)
            if (Prefs.clockEnabled(this) && Prefs.aodClock(this)) {
                showClock(Mode.AOD)
                logState("AOD: custom clock")
            } else {
                hideClock(0)
                logState("AOD: off")
            }
            if (Prefs.calibrate(this)) scheduleAodCalibration()
            return
        }
        if (!on) {
            if (locked) resetLatches()
            wasOn = false
            hideAll(0)
            logState("screen off")
            return
        }
        if (previewingAod) {
            hideCutout(0)
            showClock(Mode.AOD)
            return
        }

        if (!wasOn) {
            wasOn = true
            wakeAt = now
            scanDone = false
            if (Prefs.calibrate(this)) { calibLockDone = false; calibAodDone = false; calibText = "" }
            NotifState.recountNow()
            requestScan(0)
        }
        if (!locked) dismissed = true

        val sc = scan
        val keypad = Prefs.hideKeypad(this) && sc?.keypad == true
        val shade = if (sc != null && sc.qsKnown) sc.shadeVisible else shadeEvent
        val listenerNotifs = lockscreenShowsNotifications() && NotifState.count > 0
        val notifBlock = when (Prefs.notifMode(this)) {
            0 -> false
            1 -> if (sc != null && sc.scrollerFound) sc.notifRows > 0 else listenerNotifs
            else -> listenerNotifs
        }
        val depthOn = Prefs.enabled(this)
        val clockOn = Prefs.clockEnabled(this)

        val lockedOk = (depthOn || clockOn) && locked && !dismissed && !shade && !notifBlock && !keypad
        val shouldShow = previewing || lockedOk
        val fromAod = clockView != null && clockMode == Mode.AOD

        if (shouldShow && !previewing && !scanDone && !fromAod && now - wakeAt < SCAN_WAIT_MAX_MS) {
            handler.removeCallbacks(refreshRunnable)
            handler.postDelayed(refreshRunnable, 40)
            return
        }

        if (shouldShow) {
            if (clockOn) showClock(Mode.LOCK) else hideClock(0)
            if (depthOn || previewing) showCutout(previewing) else hideCutout(0)
            if (cutView != null || clockView != null) startUnlockPoll()
            logState("showing")
        } else {
            val fade = if (!locked || dismissed) UNLOCK_FADE_MS else HIDE_FADE_MS
            hideAll(fade)
            val reason = when {
                !(depthOn || clockOn) -> "both effects disabled"
                !locked -> "unlocked"
                dismissed -> "dismissed"
                keypad -> "PIN keypad"
                shade -> "notification shade"
                notifBlock -> "notifications (rows=${sc?.notifRows}, listener=${NotifState.count})"
                else -> "unknown"
            }
            logState("hidden: $reason")
        }
    }

    // ---------- assets ----------

    private fun loadCutout(): Bitmap? {
        val f = File(filesDir, "cutout.png")
        if (!f.exists()) return null
        if (cachedCut == null || cutStamp != f.lastModified()) {
            cachedCut = BitmapFactory.decodeFile(f.path)
            cutStamp = f.lastModified()
        }
        return cachedCut
    }

    private fun loadWallpaper(): Bitmap? {
        val f = File(filesDir, "wallpaper.jpg")
        if (!f.exists()) return null
        if (cachedWall == null || wallStamp != f.lastModified()) {
            cachedWall = BitmapFactory.decodeFile(f.path)
            wallStamp = f.lastModified()
        }
        return cachedWall
    }

    /** Reloads settings + patch when anything changed. */
    private fun syncRenderer() {
        val b = wm.maximumWindowMetrics.bounds
        val cut = loadCutout()
        val wall = loadWallpaper()
        val key = "${b.width()}x${b.height()}|$cutStamp|$wallStamp|${Prefs.sp(this).all}"
        if (key != rendKey) {
            rend.cutout = cut
            rend.wallpaper = wall
            rend.loadPrefs()
            rend.rebuildPatch(b.width(), b.height())
            rendKey = key
        }
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    // ---------- depth cut-out window ----------

    private fun showCutout(force: Boolean) {
        if ((!Prefs.enabled(this) && !force) || loadCutout() == null) { hideCutout(0); return }
        syncRenderer()
        val b = wm.maximumWindowMetrics.bounds
        val h = (b.height() * rend.regionFrac()).toInt().coerceAtLeast(1)
        val lp = overlayParams(b.width(), h)
        val existing = cutView
        if (existing != null) {
            if (cutKey != rendKey) {
                existing.fullW = b.width(); existing.fullH = b.height()
                try { wm.updateViewLayout(existing, lp) } catch (_: Exception) {}
                existing.invalidate()
                cutKey = rendKey
            }
            return
        }
        val v = CutoutView(this, rend)
        v.fullW = b.width(); v.fullH = b.height()
        v.alpha = 0f
        cutView = v
        cutKey = rendKey
        try {
            wm.addView(v, lp)
            v.animate().alpha(1f).setStartDelay(FADE_IN_DELAY_MS).setDuration(FADE_IN_MS).start()
        } catch (e: Exception) {
            cutView = null
            logLine("${stamp()} ERROR adding cut-out window: ${e.message}")
        }
    }

    private fun hideCutout(fadeMs: Long) {
        val v = cutView ?: return
        cutView = null
        cutKey = ""
        v.animate().cancel()
        if (fadeMs <= 0L) { removeView(v); return }
        v.animate().alpha(0f).setStartDelay(0).setDuration(fadeMs).start()
        handler.postDelayed({ removeView(v) }, fadeMs + 30)
    }

    // ---------- custom clock window ----------

    private fun showClock(mode: Mode) {
        if (!Prefs.clockEnabled(this)) { hideClock(0); return }
        syncRenderer()
        val b = wm.maximumWindowMetrics.bounds
        val h = (b.height() * rend.clockWindowFrac()).toInt().coerceAtLeast(1)
        val lp = overlayParams(b.width(), h)
        val existing = clockView

        if (existing == null) {
            // keep the cut-out above the clock: re-add it afterwards
            if (cutView != null) hideCutout(0)
            val v = ClockView(this, rend)
            v.fullW = b.width(); v.fullH = b.height()
            v.p = if (mode == Mode.LOCK) 1f else 0f
            v.updateTime(false)
            v.alpha = if (mode == Mode.LOCK) 0f else 1f
            clockView = v
            clockMode = mode
            clockKey = rendKey
            try {
                wm.addView(v, lp)
                if (mode == Mode.LOCK) {
                    v.animate().alpha(1f).setStartDelay(FADE_IN_DELAY_MS).setDuration(FADE_IN_MS).start()
                }
            } catch (e: Exception) {
                clockView = null
                clockMode = Mode.NONE
                logLine("${stamp()} ERROR adding clock window: ${e.message}")
            }
            return
        }

        if (clockKey != rendKey) {
            existing.fullW = b.width(); existing.fullH = b.height()
            existing.updateTime(false)
            try { wm.updateViewLayout(existing, lp) } catch (_: Exception) {}
            existing.invalidate()
            clockKey = rendKey
        }
        if (mode != clockMode) {
            clockMode = mode
            if (mode == Mode.LOCK) {
                existing.animate().cancel()
                existing.alpha = 1f
                animateTo(existing, 1f, TO_LOCK_MS)
            } else {
                animateTo(existing, 0f, TO_AOD_MS)
            }
        }
    }

    private fun animateTo(v: ClockView, target: Float, duration: Long) {
        pAnim?.cancel()
        if (v.p == target) return
        pAnim = ValueAnimator.ofFloat(v.p, target).apply {
            this.duration = duration
            interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
            addUpdateListener { v.p = it.animatedValue as Float }
            start()
        }
    }

    private fun hideClock(fadeMs: Long) {
        val v = clockView ?: return
        clockView = null
        clockMode = Mode.NONE
        clockKey = ""
        pAnim?.cancel()
        v.animate().cancel()
        if (fadeMs <= 0L) { removeView(v); return }
        v.animate().alpha(0f).setStartDelay(0).setDuration(fadeMs).start()
        handler.postDelayed({ removeView(v) }, fadeMs + 30)
    }

    private fun hideAll(fadeMs: Long) {
        hideCutout(fadeMs)
        hideClock(fadeMs)
        handler.removeCallbacks(unlockPoll)
        pollRunning = false
    }

    private fun removeView(v: View) {
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
    }
}
