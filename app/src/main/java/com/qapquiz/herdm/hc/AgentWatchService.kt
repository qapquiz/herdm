package com.qapquiz.herdm.hc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the process (and the herdr-core connection)
 * alive so [AgentWatch] alerts survive swipe-away and screen lock.
 *
 * Connection ownership rules — the invariant is that **whoever replaces
 * `HcApp.connection` stops the previous one first**:
 *
 * - [ensureWatch] reuses a live `HcApp.connection` (UI-created, or its own
 *   from an earlier run); only creates one when none is alive, and
 *   remembers it as "mine".
 * - HomeActivity replacing the connection (user picked a different host)
 *   keeps the watch working transparently: [AgentWatch] re-binds to the new
 *   connection from Home's `onReady`.
 * - [onDestroy] (user toggled off, or Android stopped the service): unbind
 *   the watcher and stop the connection **only if it is still the one this
 *   service created**. A UI-owned connection keeps running with in-app
 *   alerts until the UI tears it down (existing behavior).
 *
 * Restart: START_STICKY; after a system kill the redelivered null intent
 * re-reads the persisted host id and reconnects.
 */
class AgentWatchService : Service() {
    companion object {
        private const val TAG = "agent-watch"
        const val ACTION_START = "com.qapquiz.herdm.hc.action.START_WATCH"
        const val ACTION_STOP = "com.qapquiz.herdm.hc.action.STOP_WATCH"
        const val EXTRA_HOST_ID = "hostId"
        const val CHANNEL_WATCH = "agent_watch_service"
        private const val FGS_ID = 41
        private const val PREFS = "herdm"
        private const val KEY_ENABLED = "agent_watch_enabled"
        private const val KEY_LAST_HOST = "last_watch_host_id"

        @Volatile
        var running = false
            private set

        fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, value: Boolean) {
            prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        }

        /** Persist the host a later (re)start should watch. */
        fun rememberHost(context: Context, hostId: String) {
            prefs(context).edit().putString(KEY_LAST_HOST, hostId).apply()
        }

        /** Start watching [hostId] (null: whatever was persisted last). */
        fun start(context: Context, hostId: String?) {
            if (hostId != null) rememberHost(context, hostId)
            val intent = Intent(context, AgentWatchService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HOST_ID, hostId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AgentWatchService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    /** The connection this service created, if any. */
    private var myConn: HcConnection? = null

    override fun onCreate() {
        super.onCreate()
        AgentWatch.provideContext(this)
        AgentWatch.onAlertsChanged = { if (running) updateForegroundNotification() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        startInForeground()
        val requestedHost = intent?.getStringExtra(EXTRA_HOST_ID)
        if (requestedHost != null) rememberHost(this, requestedHost)
        ensureWatch()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        AgentWatch.onAlertsChanged = null
        val current = HcApp.connection
        val mine = myConn
        if (mine != null && mine === current) {
            AgentWatch.unbind(current)
            current.stop()
            HcApp.connection = null
        }
        myConn = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Attach the watcher to a live connection, or create one for the persisted host. */
    private fun ensureWatch() {
        val existing = HcApp.connection
        if (existing != null && existing.alive) {
            AgentWatch.bind(existing)
            return
        }
        if (existing != null) {
            // Dead shell of a previous connection: replace it cleanly.
            AgentWatch.unbind(existing)
            existing.stop()
            HcApp.connection = null
        }
        val lastHost = prefs(this).getString(KEY_LAST_HOST, null)
        val host = HostStore.load(this).firstOrNull { it.id == lastHost }
        if (host == null) {
            Log.w(TAG, "no host to watch; stopping service")
            stopSelf()
            return
        }
        val conn = HcConnection().also {
            myConn = it
            HcApp.connection = it
        }
        conn.listener = object : HcConnection.Listener {
            override fun onDisconnected(reason: String?) {
                Log.w(TAG, "watch connection ended: ${reason ?: "stream ended"}")
            }
        }
        conn.start(
            host,
            onReady = {
                rememberHost(this, host.id)
                AgentWatch.bind(conn)
            },
            onError = { msg ->
                Log.w(TAG, "watch connect failed: $msg")
                stopSelf()
            },
        )
    }

    // -------------------------------------------------------- notification

    private fun watchNotification(): android.app.Notification {
        ensureChannel(this)
        val context = this
        val alerts = AgentWatch.alertCount()
        val text = if (alerts == 0) "Watching terminals for agent status" else "$alerts need you"
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, TerminalsActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_WATCH)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("herdm")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun startInForeground() {
        val n = watchNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FGS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FGS_ID, n)
        }
    }

    private fun updateForegroundNotification() {
        if (!running) return
        val n = watchNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FGS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FGS_ID, n)
        }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_WATCH) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WATCH,
                    "Background watch",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply { description = "Keeps the terminal watch alive" },
            )
        }
    }
}
