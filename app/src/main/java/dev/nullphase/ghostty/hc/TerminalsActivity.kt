package dev.nullphase.ghostty.hc

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

/** Main app after connect: live terminal list with OSC 7501 status dots. */
class TerminalsActivity : AppCompatActivity() {
    private lateinit var conn: HcConnection
    private lateinit var hostName: String
    private lateinit var list: ListView
    private lateinit var header: TextView
    private val main = Handler(Looper.getMainLooper())
    private var terminals: JSONArray = JSONArray()
    private var refreshScheduled = false
    private var eventsChannel = -1

    private val statusColor = mapOf(
        "idle" to 0xFF4CAF50.toInt(),
        "working" to 0xFF2196F3.toInt(),
        "done" to 0xFF9E9E9E.toInt(),
        "blocked" to 0xFFFF5252.toInt(),
        "error" to 0xFFFF5252.toInt(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conn = HcApp.connection
        if (conn == null) {
            finish(); return
        }
        this.conn = conn
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
        list.setOnItemClickListener { _, _, pos, _ -> openTerminal(at(pos)) }
        list.setOnItemLongClickListener { _, _, pos, _ -> killTerminal(at(pos)); true }
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
            override fun onConnectionClosed(reason: String?) {
                AlertDialog.Builder(this@TerminalsActivity)
                    .setTitle("Disconnected")
                    .setMessage(reason ?: "The SSH stream ended.")
                    .setPositiveButton("Home") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }

        subscribeEvents()
        refresh()
    }

    private fun at(pos: Int): JSONObject = terminals.getJSONObject(pos)

    private fun subscribeEvents() {
        conn.request("events.subscribe") { result, _ ->
            val ch = result?.optInt("channel", -1) ?: -1
            if (ch > 0) {
                eventsChannel = ch
                val lines = LineAssembler()
                conn.watchChannel(
                    ch,
                    onData = { bytes ->
                        val got = lines.push(bytes).isNotEmpty()
                        if (got) scheduleRefresh()
                    },
                    onClose = { },
                )
            }
        }
    }

    /** Debounced: any event storm collapses into one terminal.list call. */
    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        main.postDelayed({ refreshScheduled = false; refresh() }, 300)
    }

    private fun refresh() {
        conn.request("terminal.list") { result, error ->
            if (error != null) return@request
            terminals = result?.optJSONArray("terminals") ?: JSONArray()
            header.text = "$hostName · ${terminals.length()} terminals"
            (list.adapter as TermAdapter).notifyDataSetChanged()
        }
    }

    private fun newTerminal() {
        val env = JSONObject()
            .put("TERM", "xterm-256color")
            .put("COLORTERM", "truecolor")
            .put("TERM_PROGRAM", "ghostty-droid")
        conn.request("terminal.run", JSONObject().put("env", env)) { _, error ->
            if (error != null) toast("run failed: ${error.opt("kind")}")
            // refresh arrives via events
        }
    }

    private fun openTerminal(t: JSONObject) {
        val address = t.optString("address")
        if (address.isEmpty()) return
        val finished = !t.isNull("finished_ms")
        if (finished) {
            toast("finished (exit ${t.optInt("exit_code", -1)}) — new one instead")
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
            else scheduleRefresh()
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
        val label = state + progress + if (msg.isNotEmpty()) " · $msg" else ""
        return Triple(state, color, label)
    }

    private inner class TermAdapter : BaseAdapter() {
        override fun getCount() = terminals.length()
        override fun getItem(p: Int) = terminals.optJSONObject(p)
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@TerminalsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, dp(12))
                gravity = Gravity.CENTER_VERTICAL
                val dot = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).also {
                        it.rightMargin = dp(12)
                    }
                }
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val t = TextView(context).apply { textSize = 16f }
                val s = TextView(context).apply { textSize = 13f; alpha = 0.6f }
                col.addView(t); col.addView(s)
                addView(dot)
                addView(col)
                tag = arrayOf(dot, t, s)
            }
            val views = row.tag as Array<TextView>
            val t = at(p)
            views[1].text = t.optString("name")
                .takeUnless { it.isEmpty() || it == "null" }
                ?: t.optString("address")
            val (state, color, label) = statusOf(t)
            views[0].background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
            views[2].text = label + "  ·  " + t.optString("cwd")
            return row
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            if (eventsChannel > 0) conn.closeChannel(eventsChannel)
            conn.disconnect()
            HcApp.connection = null
        }
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
