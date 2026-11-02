package com.qapquiz.herdm.hc

import android.os.Handler
import android.os.Looper
import com.qapquiz.herdm.GhosttyTerminalView
import com.qapquiz.herdm.TerminalLink

/** Process-wide connection holder: exactly one herdr-core link per app run. */
object HcApp {
    @Volatile
    var connection: HcConnection? = null
}

/**
 * TerminalLink implementation over a herdr-core attach channel: the first
 * grid size triggers `terminal.attach`, later ones become RESIZE frames,
 * outbound bytes become DATA, inbound DATA feeds the view. On connection
 * loss the supervisor reconnects and this session re-attaches with a full
 * repaint.
 */
class HcTerminalSession(
    private val connection: HcConnection,
    val view: GhosttyTerminalView,
    private val address: String,
    private val onEnded: (String?) -> Unit,
) : TerminalLink {
    private val main = Handler(Looper.getMainLooper())
    private var channel = -1
    private var attaching = false
    private var cols = 0
    private var rows = 0
    private var guardTrips = 0
    private val pendingOut = ArrayDeque<ByteArray>()
    private val reconnectId = connection.registerReconnect { main.post { reattach() } }

    init {
        view.link = this
    }

    override fun onGridResized(c: Int, r: Int, cellWidthPx: Int, cellHeightPx: Int) {
        val gridChanged = cols != c || rows != r
        cols = c
        rows = r
        when {
            channel >= 0 -> connection.resize(channel, c, r)
            !attaching -> attach(c, r, fullRepaint = gridChanged)
        }
    }

    private fun attach(c: Int, r: Int, fullRepaint: Boolean) {
        attaching = true
        if (fullRepaint) view.resetScreen()
        connection.attach(
            terminal = address,
            cols = c,
            rows = r,
            onChannel = { ch ->
                channel = ch
                attaching = false
                connection.watchChannel(
                    ch,
                    onData = { bytes -> main.post { view.feed(bytes) } },
                    onClose = { reason ->
                        main.post {
                            if (reason?.startsWith("rate guard") == true && guardTrips < 2) {
                                // Pathological output stream (rare upstream relay
                                // bug after transport loss): once with a fresh
                                // attach; give up only if it trips repeatedly.
                                guardTrips++
                                reattach()
                            } else {
                                onEnded(reason)
                            }
                        }
                    },
                    guardBytesPerSec = 10L * 1024 * 1024,
                    tripWindows = 1,
                )
                // Display facts so display-query responses match our surface.
                val facts = org.json.JSONObject()
                    .put("bg", "#000000")
                    .put("fg", "#ffffff")
                    .put("cell", org.json.JSONArray(listOf(view.cellWidthPx(), view.cellHeightPx())))
                connection.writeFacts(ch, facts.toString())
                synchronized(pendingOut) {
                    for (b in pendingOut) connection.sendData(ch, b)
                    pendingOut.clear()
                }
            },
            onError = { msg -> main.post { onEnded(msg) } },
        )
    }

    /** Re-attach after the supervisor re-established the connection. */
    private fun reattach() {
        channel = -1
        attaching = false
        view.resetScreen()
        if (cols > 0 && rows > 0) attach(cols, rows, fullRepaint = true)
    }

    private fun cw() = view.cellWidthPx()
    private fun chh() = view.cellHeightPx()

    override fun send(bytes: ByteArray) {
        val ch = channel
        if (ch >= 0) {
            connection.sendData(ch, bytes)
        } else {
            synchronized(pendingOut) {
                if (pendingOut.size < 256) pendingOut.add(bytes)
            }
        }
    }

    /** Detach from this terminal without dropping the connection. */
    fun detach() {
        connection.unregisterReconnect(reconnectId)
        val ch = channel
        if (ch >= 0) {
            channel = -1
            connection.closeChannel(ch)
        }
    }
}
