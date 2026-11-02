package com.qapquiz.herdm.hc

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject

/**
 * Main app after connect: live terminal list with OSC 7501 status dots,
 * grouped by machine, updated in place from events.subscribe facts.
 */
class TerminalsActivity : AppCompatActivity() {
    private lateinit var conn: HcConnection
    private lateinit var hostName: String
    private lateinit var list: ListView
    private lateinit var header: TextView
    private val main = Handler(Looper.getMainLooper())
    private var terminals: JSONArray = JSONArray()
    private var machines: JSONArray = JSONArray()
    private var rows: List<Row> = emptyList()
    private var refreshScheduled = false
    private var eventsChannel = -1
    private var reconnectId = -1

    private data class Row(val header: String?, val terminal: JSONObject?)

    private val statusColor = mapOf(
        "idle" to 0xFF4CAF50.toInt(),
        "working" to 0xFF2196F3.toInt(),
        "done" to 0xFF9E9E9E.toInt(),
        "blocked" to 0xFFFF5252.toInt(),
        "error" to 0xFFFF5252.toInt(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conn = HcApp.connection ?: run {
            finish(); return
        }
        hostName = intent.getStringExtra("hostName") ?: "core"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        header = TextView(this).apply {
            textSize = 22f
            setPadding(0, dp(8), 0, dp(4))
            text = hostName
        }
        root.addView(header)
        root.addView(
            TextView(this).apply {
                textSize = 13f
                alpha = 0.6f
                text = "terminals · tap to attach · long-press to kill"
            },
        )
        list = ListView(this).apply {
            divider = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).also { it.setMargins(0, dp(8), 0, dp(8)) }
        }
        list.adapter = TermAdapter()
        list.setOnItemClickListener { _, _, pos, _ -> rows.getOrNull(pos)?.let { it.terminal?.let(::openTerminal) } }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            rows.getOrNull(pos)?.terminal?.let(::killTerminal)
            true
        }
        root.addView(list)
        root.addView(
            MaterialButton(this).apply {
                text = "+ new terminal"
                setOnClickListener { newTerminal() }
            },
        )
        setContentView(root)
        applyInsets(root)

        conn.listener = object : HcConnection.Listener {
            override fun onReconnecting(attempt: Int) {
                header.text = "$hostName · reconnecting (attempt $attempt)…"
            }

            override fun onReconnected() {
                toast("reconnected")
                subscribeEvents()
                refresh()
            }

            override fun onDisconnected(reason: String?) {
                if (isFinishing) return
                AlertDialog.Builder(this@TerminalsActivity)
                    .setTitle("Disconnected")
                    .setMessage(reason ?: "Stopped.")
                    .setPositiveButton("Home") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }

        reconnectId = conn.registerReconnect {
            // Re-established events channel arrives via subscribeEvents below.
        }

        subscribeEvents()
        refresh()
    }

    private fun at(pos: Int): JSONObject = rows[pos].terminal ?: JSONObject()

    private fun subscribeEvents() {
        conn.request("events.subscribe") { result, _ ->
            val ch = result?.optInt("channel", -1) ?: -1
            if (ch > 0) {
                eventsChannel = ch
                val lines = LineAssembler()
                conn.watchChannel(
                    ch,
                    onData = { bytes ->
                        for (line in lines.push(bytes)) {
                            runCatching { JSONObject(line) }.getOrNull()?.let { notification ->
                                val params = notification.optJSONObject("params") ?: notification
                                applyEvent(params)
                            }
                        }
                    },
                    onClose = { },
                    guardBytesPerSec = 1L * 1024 * 1024,
                    tripWindows = 3,
                )
            }
        }
    }

    /** Apply one event fact to the in-memory model; no network round-trip. */
    private fun applyEvent(params: JSONObject) {
        val type = params.optString("type").takeUnless { it.isEmpty() || it == "null" } ?: return
        val address = params.optString("terminal").takeUnless { it.isEmpty() || it == "null" }
        val data = params.optJSONObject("data") ?: JSONObject()

        when (type) {
            "created" -> upsert(data) // carries the full terminal object
            "removed" -> removeTerminal(address)
            "finished" -> patch(address) {
                it.putOpt("exit_code", data.opt("exit_code"))
                it.putOpt("finished_ms", data.opt("finished_ms"))
            }
            "status" -> patch(address) {
                if (data.has("status")) it.putOpt("status", data.opt("status"))
                else if (data.has("state")) it.putOpt("status", data)
            }
            "labels" -> patch(address) { it.putOpt("labels", data.opt("labels")) }
            "title" -> patch(address) { it.putOpt("title", data) }
            "foreground" -> patch(address) { it.putOpt("foreground", data) }
            "size" -> patch(address) {
                it.putOpt("cols", data.opt("cols"))
                it.putOpt("rows", data.opt("rows"))
            }
            "turn" -> patch(address) { it.putOpt("turn", data.opt("turn")) }
            else -> scheduleRefresh() // unknown/missed facts: full refresh fallback
        }
        renderRows()
    }

    private fun upsert(t: JSONObject) {
        val address = t.optString("address")
        for (i in 0 until terminals.length()) {
            if (terminals.optJSONObject(i)?.optString("address") == address) {
                terminals.put(i, t)
                return
            }
        }
        terminals.put(t)
    }

    private fun removeTerminal(address: String?) {
        if (address == null) return
        for (i in terminals.length() - 1 downTo 0) {
            if (terminals.optJSONObject(i)?.optString("address") == address) {
                terminals.remove(i)
            }
        }
    }

    private fun patch(address: String?, patch: (JSONObject) -> Unit) {
        if (address == null) return
        for (i in 0 until terminals.length()) {
            val t = terminals.optJSONObject(i) ?: continue
            if (t.optString("address") == address) {
                patch(t)
                return
            }
        }
    }

    private fun renderRows() {
        rows = buildRows()
        header.text = "$hostName · ${liveCount()} terminals"
        (list.adapter as TermAdapter).notifyDataSetChanged()
    }

    private fun liveCount(): Int {
        var n = 0
        for (i in 0 until terminals.length()) {
            if (terminals.optJSONObject(i)?.isNull("finished_ms") == true) n++
        }
        return n
    }

    /** Local terminals first, then one section per machine in list order. */
    private fun buildRows(): List<Row> {
        val rows = mutableListOf<Row>()
        val local = mutableListOf<JSONObject>()
        val byMachine = linkedMapOf<String, MutableList<JSONObject>>()
        val machineNames = mutableListOf<String>()
        for (i in 0 until machines.length()) {
            val m = machines.optJSONObject(i) ?: continue
            val name = m.optString("name").takeUnless { it.isEmpty() || it == "null" } ?: continue
            machineNames.add(name)
            byMachine[name] = mutableListOf()
        }
        for (i in 0 until terminals.length()) {
            val t = terminals.optJSONObject(i) ?: continue
            val address = t.optString("address")
            val owner = machineNames.firstOrNull { address.startsWith("$it/") }
            if (owner != null) byMachine.getValue(owner).add(t) else local.add(t)
        }
        rows.add(Row(hostName, null))
        local.forEach { rows.add(Row(null, it)) }
        for (name in machineNames) {
            val mv = (0 until machines.length())
                .mapNotNull { machines.optJSONObject(it) }
                .firstOrNull { it.optString("name") == name }
            val offline = mv != null && !mv.isNull("since_ms")
            rows.add(Row("$name${if (offline) " (offline)" else ""}", null))
            byMachine[name]?.forEach { rows.add(Row(null, it)) }
        }
        return rows
    }

    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        main.postDelayed({ refreshScheduled = false; refresh() }, 300)
    }

    private fun refresh() {
        conn.request("terminal.list") { result, error ->
            if (error != null) return@request
            terminals = result?.optJSONArray("terminals") ?: JSONArray()
            machines = result?.optJSONArray("machines") ?: JSONArray()
            renderRows()
        }
    }

    private fun newTerminal() {
        val env = JSONObject()
            .put("TERM", "xterm-256color")
            .put("COLORTERM", "truecolor")
            .put("TERM_PROGRAM", "ghostty-droid")
        conn.request("terminal.run", JSONObject().put("env", env)) { _, error ->
            if (error != null) toast("run failed: ${error.opt("kind")}")
            // created event applies the row
        }
    }

    private fun openTerminal(t: JSONObject) {
        val address = t.optString("address")
        if (address.isEmpty()) return
        val finished = !t.isNull("finished_ms")
        if (finished) {
            toast("finished (exit ${t.optInt("exit_code", -1)})")
            return
        }
        startActivity(
            Intent(this, HcTerminalActivity::class.java)
                .putExtra("address", address)
                .putExtra("title", displayTitle(t)),
        )
    }

    private fun killTerminal(t: JSONObject) {
        val address = t.optString("address")
        conn.request("terminal.kill", JSONObject().put("terminal", address)) { _, error ->
            if (error != null) toast("kill failed: ${error.opt("kind")}")
        }
    }

    private fun displayTitle(t: JSONObject): String {
        val name = t.optString("name").takeUnless { it.isEmpty() || it == "null" }
        if (name != null) return name
        return t.optString("address")
    }

    private fun statusOf(t: JSONObject): Triple<String, Int, String> {
        if (!t.isNull("finished_ms")) {
            val code = t.optInt("exit_code", -1)
            return Triple("finished", 0xFF757575.toInt(), if (code == 0) "exit 0" else "exit $code")
        }
        val status = t.optJSONObject("status")
        val state = status?.optString("state") ?: ""
        if (state.isEmpty() || state == "null") {
            return Triple("shell", 0xFF616161.toInt(), "shell")
        }
        val color = statusColor[state] ?: 0xFF616161.toInt()
        val msg = status.optString("msg")
        val progress = if (!status.isNull("progress")) " ${status.optInt("progress")}%" else ""
        val label = state + progress + if (msg.isNotEmpty() && msg != "null") " · $msg" else ""
        return Triple(state, color, label)
    }

    private inner class TermAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[p]
            if (row.header != null) {
                val headerView = (convertView as? TextView)?.takeIf { it.tag == "header" }
                    ?: TextView(this@TerminalsActivity).apply {
                        tag = "header"
                        textSize = 13f
                        alpha = 0.55f
                        setPadding(0, dp(14), 0, dp(4))
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }
                headerView.text = row.header.uppercase()
                return headerView
            }
            val t = row.terminal ?: JSONObject()
            val item = (convertView as? LinearLayout)?.takeIf { it.tag != "header" }
                ?: LinearLayout(this@TerminalsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(12), 0, dp(12))
                    gravity = Gravity.CENTER_VERTICAL
                    val dot = TextView(this.context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).also {
                            it.rightMargin = dp(12)
                        }
                    }
                    val col = LinearLayout(this.context).apply { orientation = LinearLayout.VERTICAL }
                    val title = TextView(this.context).apply { textSize = 16f }
                    val sub = TextView(this.context).apply { textSize = 13f; alpha = 0.6f }
                    col.addView(title); col.addView(sub)
                    addView(dot)
                    addView(col)
                    tag = arrayOf(dot, title, sub)
                }
            val views = item.tag as Array<TextView>
            views[1].text = displayTitle(t)
            val (state, color, label) = statusOf(t)
            views[0].background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
            views[2].text = label + "  ·  " + t.optString("cwd")
            return item
        }
    }

    override fun onDestroy() {
        if (isFinishing && this::conn.isInitialized) {
            if (eventsChannel > 0) conn.closeChannel(eventsChannel)
            conn.unregisterReconnect(reconnectId)
            conn.stop()
            HcApp.connection = null
        }
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
