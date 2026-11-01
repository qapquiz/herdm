package dev.nullphase.ghostty.hc

import android.os.Handler
import android.os.Looper
import dev.nullphase.ghostty.GhosttyTerminalView
import dev.nullphase.ghostty.TerminalLink

/** Process-wide connection holder: exactly one herdr-core link per app run. */
object HcApp {
    @Volatile
    var connection: HcConnection? = null
}

/**
 * TerminalLink implementation over a herdr-core attach channel: the first
 * grid size triggers `terminal.attach`, later ones become RESIZE frames,
 * outbound bytes become DATA, inbound DATA feeds the view.
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
    private val pendingOut = ArrayDeque<ByteArray>()

    init {
        view.link = this
    }

    override fun onGridResized(c: Int, r: Int, cellWidthPx: Int, cellHeightPx: Int) {
        cols = c
        rows = r
        when {
            channel >= 0 -> connection.resize(channel, c, r)
            !attaching -> attach(c, r)
        }
    }

    private fun attach(c: Int, r: Int) {
        attaching = true
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
                    onClose = { reason -> main.post { onEnded(reason) } },
                )
                val facts = org.json.JSONObject()
                    .put("bg", "#000000")
                    .put("fg", "#ffffff")
                    .put("cell", org.json.JSONArray(listOf(cw(), chh())))
                connection.writeFacts(ch, facts.toString())
                synchronized(pendingOut) {
                    for (b in pendingOut) connection.sendData(ch, b)
                    pendingOut.clear()
                }
            },
            onError = { msg -> main.post { onEnded(msg) } },
        )
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
        val ch = channel
        if (ch >= 0) {
            channel = -1
            connection.closeChannel(ch)
        }
    }
}
