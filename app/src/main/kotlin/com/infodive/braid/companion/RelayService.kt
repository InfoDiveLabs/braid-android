package com.infodive.braid.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

/**
 * Keeps sharing alive with the screen off. A foreground service is what keeps
 * the process out of the freezer and its network un-blocked in Doze.
 *
 * Typed connectedDevice rather than dataSync: it serves another device over a
 * network connection, which is what that type describes, and dataSync is cut
 * off after six hours a day on Android 15.
 */
class RelayService : Service() {
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var notifications: NotificationManager
    private var lastShown = 0L
    private val refresh: () -> Unit = ::refreshThrottled

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_SHARING, "Sharing", NotificationManager.IMPORTANCE_LOW),
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_PAIRING, "Pairing requests", NotificationManager.IMPORTANCE_HIGH),
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "braid:relay")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setServing(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(ID_SHARING, sharingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            RelayHost.start(this)
        } catch (e: Exception) {
            // Android may refuse a restart from the background. Sharing stays
            // wanted, so the next time the app opens it starts again.
            stopSelf()
            return START_NOT_STICKY
        }
        RelayHost.onActiveChanged = { active -> if (active > 0) wakeLock.acquire(WAKE_TIMEOUT_MS) else wakeLock.release() }
        RelayHost.phone(this).onPairingRequested = ::announcePairing
        RelayHost.watch(refresh)
        return START_STICKY
    }

    override fun onDestroy() {
        RelayHost.unwatch(refresh)
        RelayHost.onActiveChanged = null
        RelayHost.phone(this).onPairingRequested = null
        RelayHost.stop()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    private fun refreshThrottled() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShown < REFRESH_MS) return
        lastShown = now
        notifications.notify(ID_SHARING, sharingNotification())
    }

    /** Says plainly which networks are being lent and how much has gone through each. */
    private fun sharingNotification(): Notification {
        val phone = RelayHost.phone(this)
        val lanes = phone.lanes.ids.filter { phone.lanes.isWanted(it) }
        val text = if (lanes.isEmpty()) "No networks switched on. Paired computers can't use this phone."
        else lanes.joinToString(" · ") { "${phone.lanes.label(it)}: ${RelayHost.size(RelayHost.used(it))}" }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RelayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_SHARING)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(
                if (phone.lanes.isWanted(NetworkLanes.CELL)) "Sharing this phone's mobile data" else "Braid sharing is on",
            )
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop sharing", stop).build())
            .build()
    }

    private fun announcePairing(request: PairingPrompt.Request) {
        val name = request.desktop.take(60).replace(Regex("\\p{Cntrl}"), " ")
        notifications.notify(
            ID_PAIRING,
            Notification.Builder(this, CHANNEL_PAIRING)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Pair with “$name”?")
                .setContentText("A computer at ${request.from} wants to use this phone. Tap to review.")
                .setContentIntent(openApp())
                .setCategory(Notification.CATEGORY_CALL)
                .setAutoCancel(true)
                .setTimeoutAfter(PAIR_NOTICE_MS)
                .build(),
        )
        request.answer.whenComplete { _, _ -> notifications.cancel(ID_PAIRING) }
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val ACTION_STOP = "com.infodive.braid.companion.STOP"
        private const val CHANNEL_SHARING = "sharing"
        private const val CHANNEL_PAIRING = "pairing"
        private const val ID_SHARING = 1
        private const val ID_PAIRING = 2
        private const val REFRESH_MS = 2_000L
        private const val PAIR_NOTICE_MS = 55_000L

        /** Renewed on every change in active connections; the timeout only matters if a release is ever missed. */
        private const val WAKE_TIMEOUT_MS = 60 * 60 * 1000L

        fun isServing(context: Context) =
            context.getSharedPreferences("service", Context.MODE_PRIVATE).getBoolean("serving", false)

        private fun setServing(context: Context, serving: Boolean) =
            context.getSharedPreferences("service", Context.MODE_PRIVATE).edit().putBoolean("serving", serving).apply()

        fun start(context: Context) {
            setServing(context, true)
            context.startForegroundService(Intent(context, RelayService::class.java))
        }

        fun stop(context: Context) {
            setServing(context, false)
            context.startService(Intent(context, RelayService::class.java).setAction(ACTION_STOP))
        }
    }
}
