package com.qapquiz.herdm.hc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watches agent status across all terminals on one [HcConnection] and posts
 * heads-up notifications when a terminal enters `blocked` or `error`, and
 * cancels them when the state clears, the terminal finishes, or it is
 * removed.
 *
 * Runs its own `events.subscribe` channel — independent of any activity's —
 * so it works with or without UI. All callbacks arrive on the main thread
 * ([HcConnection] posts there); notification calls are safe from main.
 *
 * Transition policy: one live notification per terminal (id = address
 * hash). `blocked`↔`error` updates in place without re-alerting
 * (setOnlyAlertOnce). Reconnects and unknown facts reconcile against a
 * fresh `terminal.list`; alerts that already existed are not re-posted.
 *
 * Callers must [provideContext] once before the first [bind].
 */
object AgentWatch {
    private const val TAG = "agent-watch"
    const val CHANNEL_ALERTS = "agent_status"

    private val ALERT_STATES = setOf("blocked", "error")

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ctx: Context? = null
    private var conn: HcConnection? = null
    private var channel = -1
    private var reconnectId = -1
    private var resubscribing = false
    private var refreshScheduled = false

    /** address -> last known Status object (absent = no live status). */
    private val status = HashMap<String, JSONObject>()
    /** address -> terminal name/title for notification text. */
    private val names = HashMap<String, String>()
    /** address -> alerting state; exactly the set of posted notifications. */
    private val active = HashMap<String, String>()

    /** Called on the main thread whenever the alert set changes. */
    @Volatile var onAlertsChanged: (() -> Unit)? = null

    fun provideContext(context: Context) {
        ctx = context.applicationContext
    }

    fun alertCount(): Int = active.size

    fun isWatching(c: HcConnection): Boolean = conn === c

    /** Idempotently start watching [c]. Re-binding to another connection
     *  releases the previous one (its alerts are cancelled). */
    fun bind(c: HcConnection) {
        if (conn === c) return
        if (conn != null) unbind(conn!!)
        conn = c
        status.clear()
        names.clear()
        reconnectId = c.registerReconnect { resubscribe() }
        resubscribe()
        refresh()
    }

    /** Stop watching [c]; cancels all alert notifications. No-op if [c] is not the bound one. */
    fun unbind(c: HcConnection) {
        if (conn !== c) return
        if (reconnectId >= 0) c.unregisterReconnect(reconnectId)
        if (channel > 0) c.closeChannel(channel)
        conn = null
        channel = -1
        reconnectId = -1
        cancelAll()
        status.clear()
        names.clear()
    }

    private fun resubscribe() {
        val c = conn ?: return
        if (resubscribing) return
        resubscribing = true
        c.request("events.subscribe") { result, error ->
            resubscribing = false
            if (conn !== c) return@request
            if (error != null) {
                Log.w(TAG, "events.subscribe failed: ${error.opt("kind")}")
                return@request
            }
            val ch = result?.optInt("channel", -1) ?: -1
            if (ch <= 0) return@request
            channel = ch
            val lines = LineAssembler()
            c.watchChannel(
                ch,
                onData = { bytes ->
                    for (line in lines.push(bytes)) {
                        val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        // A bare snapshot line ({"terminals":[...],...}) reconciles everything.
                        val snapshot = obj.optJSONArray("terminals")
                        if (snapshot != null) {
                            reconcile(snapshot)
                            continue
                        }
                        val params = obj.optJSONObject("params") ?: obj
                        applyEvent(params)
                    }
                },
                // Core closed our subscriber channel while the transport is
                // up (abnormal): try to get a new one.
                onClose = { _ -> if (conn === c) resubscribe() },
                // Facts are tiny; the guard exists for runaways only.
                guardBytesPerSec = 1L * 1024 * 1024,
                tripWindows = 3,
            )
        }
    }

    /** Fresh full state from `terminal.list` (bind, reconnect, catch-all). */
    private fun refresh() {
        val c = conn ?: return
        c.request("terminal.list", JSONObject().put("all", true)) { result, _ ->
            if (conn !== c || result == null) return@request
            reconcile(result.optJSONArray("terminals") ?: JSONArray())
        }
    }

    /** Unknown facts can hide missed transitions; coalesce the catch-up. */
    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        main.postDelayed({ refreshScheduled = false; refresh() }, 300)
    }

    private fun reconcile(terminals: JSONArray) {
        val seen = HashSet<String>()
        for (i in 0 until terminals.length()) {
            val t = terminals.optJSONObject(i) ?: continue
            val address = t.optString("address").takeUnless { it.isEmpty() || it == "null" } ?: continue
            seen.add(address)
            names[address] = displayName(t)
            if (!t.isNull("finished_ms")) {
                setStatus(address, null)
            } else {
                setStatus(address, t.optJSONObject("status"))
            }
        }
        // Terminals that vanished while we were watching: drop everything.
        for (address in (status.keys + names.keys).distinct()) {
            if (address !in seen) {
                status.remove(address)
                names.remove(address)
                cancel(address)
            }
        }
    }

    private fun applyEvent(params: JSONObject) {
        val type = params.optString("type").takeUnless { it.isEmpty() || it == "null" } ?: return
        val address = params.optString("terminal").takeUnless { it.isEmpty() || it == "null" } ?: return
        val data = params.optJSONObject("data") ?: JSONObject()
        when (type) {
            "status" -> setStatus(address, if (data.has("status")) data.optJSONObject("status") else data)
            "created" -> {
                names[address] = displayName(data)
                setStatus(address, if (data.isNull("finished_ms")) data.optJSONObject("status") else null)
            }
            "finished" -> setStatus(address, null)
            "removed" -> {
                status.remove(address)
                names.remove(address)
                cancel(address)
            }
            "title" -> {
                val name = data.optString("name").takeUnless { it.isEmpty() || it == "null" }
                    ?: data.optString("title").takeUnless { it.isEmpty() || it == "null" }
                if (name != null) names[address] = name
            }
            // lost/restarted/keep/labels/size/turn/foreground/doc or `missed`:
            // nothing directly actionable, but some imply missed transitions.
            else -> scheduleRefresh()
        }
    }

    /** The one transition point. A null [next] means the terminal has no
     *  live status (finished or cleared). */
    private fun setStatus(address: String, next: JSONObject?) {
        val state = next?.optString("state")?.takeUnless { it.isEmpty() || it == "null" } ?: ""
        val prevStatus = status[address]
        val prevState = prevStatus?.optString("state")?.takeUnless { it.isEmpty() || it == "null" } ?: ""

        if (state.isEmpty() || next == null) {
            status.remove(address)
        } else {
            status[address] = next
        }
        if (prevStatus == next) return

        val wasAlert = prevState in ALERT_STATES
        val isAlert = state in ALERT_STATES
        when {
            isAlert && !wasAlert -> notify(address, state, alertAgain = true)
            isAlert && wasAlert -> notify(address, state, alertAgain = prevState != state)
            else -> if (wasAlert) cancel(address)
        }
    }

    private fun displayName(t: JSONObject): String {
        val name = t.optString("name").takeUnless { it.isEmpty() || it == "null" }
        if (name != null) return name
        val title = t.optJSONObject("status")?.optString("title")?.takeUnless { it.isEmpty() || it == "null" }
        if (title != null) return title
        return t.optString("address")
    }

    // ----------------------------------------------------------- alerts

    private fun notify(address: String, state: String, alertAgain: Boolean) {
        val context = ctx ?: return
        ensureChannels(context)
        val s = status[address] ?: JSONObject()
        val app = s.optString("app").takeUnless { it.isEmpty() || it == "null" }
        val kind = s.optString("kind").takeUnless { it.isEmpty() || it == "null" }
        val msg = s.optString("msg").takeUnless { it.isEmpty() || it == "null" }
        val text = listOfNotNull(state, kind, msg).joinToString(" · ")
        val title = (names[address] ?: address) + if (app != null) " ($app)" else ""
        val intent = Intent(context, HcTerminalActivity::class.java)
            .putExtra("address", address)
            .putExtra("title", names[address] ?: address)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context,
            address.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            // The app ships no icon assets; use the platform error icon.
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(!alertAgain)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) {
            Log.w(TAG, "notifications disabled; alert suppressed")
            return
        }
        runCatching { nm.notify(address.hashCode(), n) }
            .onFailure { Log.w(TAG, "notify failed", it) }
        active[address] = state
        fireChanged()
    }

    private fun cancel(address: String) {
        ctx?.let { NotificationManagerCompat.from(it).cancel(address.hashCode()) }
        if (active.remove(address) != null) fireChanged()
    }

    private fun cancelAll() {
        val context = ctx
        if (context != null) {
            val nm = NotificationManagerCompat.from(context)
            for (address in active.keys) nm.cancel(address.hashCode())
        }
        if (active.isNotEmpty()) {
            active.clear()
            fireChanged()
        }
    }

    private fun fireChanged() {
        main.post { onAlertsChanged?.invoke() }
    }

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ALERTS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Agent status",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Terminals that are blocked or errored" },
            )
        }
    }
}
