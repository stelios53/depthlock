package com.example.depthlock

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

object NotifState {
    @Volatile var count = 0
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile var instance: NotifListener? = null
    fun recountNow() { instance?.recount() }
}

/** Fallback signal: used only when the lockscreen scan can't find the notification list itself. */
class NotifListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onListenerConnected() { NotifState.instance = this; recount() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = recount()

    override fun onListenerDisconnected() {
        NotifState.instance = null
        publish(0)
        try { requestRebind(ComponentName(this, NotifListener::class.java)) } catch (_: Exception) {}
    }

    fun recount() {
        val includeSilent = Prefs.includeSilent(this)
        val ranking = Ranking()
        val list = try { activeNotifications ?: emptyArray() } catch (e: Exception) { emptyArray() }
        var n = 0
        for (sbn in list) {
            val nf = sbn.notification
            if (sbn.isOngoing) continue
            if (nf.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            if (nf.visibility == Notification.VISIBILITY_SECRET) continue
            val imp = if (currentRanking.getRanking(sbn.key, ranking)) ranking.importance
                      else NotificationManager.IMPORTANCE_DEFAULT
            if (imp >= NotificationManager.IMPORTANCE_DEFAULT ||
                (includeSilent && imp >= NotificationManager.IMPORTANCE_LOW)) n++
        }
        publish(n)
    }

    private fun publish(n: Int) {
        NotifState.count = n
        main.post { NotifState.onChange?.invoke() }
    }
}
