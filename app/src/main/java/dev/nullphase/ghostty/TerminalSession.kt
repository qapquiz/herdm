package dev.nullphase.ghostty

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Wires a [Pty] to a [GhosttyTerminalView] through a libghostty-vt terminal.
 *
 * Threading: the reader thread only moves bytes; every NativeTerminal call
 * (feed/resize/scroll/keys) happens on the main thread, either directly from
 * UI events or via [mainHandler] posts.
 */
class TerminalSession(
    context: Context,
    val view: GhosttyTerminalView,
    private val onFinish: (Int) -> Unit = {},
) : TerminalLink {
    private companion object {
        const val SHELL = "/system/bin/sh"
        const val MAX_SCROLLBACK = 10L * 1024 * 1024 // ~10 MB of scrollback bytes
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var handle: Long = 0
    private var pty: Pty? = null
    private var reader: Thread? = null

    @Volatile
    private var closing = false

    init {
        handle = NativeTerminal.nativeCreate(80, 24, MAX_SCROLLBACK)
        require(handle != 0L) { "failed to create native terminal" }

        val env = mapOf(
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "HOME" to context.filesDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "PATH" to "/system/bin:/system/xbin:/vendor/bin",
            "LANG" to "C.UTF-8",
        )
        val cwd = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
        val p = Pty.open(80, 24, SHELL, listOf("-l"), cwd, env)
        requireNotNull(p) { "failed to open pty for $SHELL" }
        pty = p

        view.attach(handle, this)
        reader = thread(name = "pty-reader") { readLoop(p) }
    }

    var onTitle: ((ByteArray?) -> Unit)? = null

    var onBell: (() -> Unit)? = null

    private fun readLoop(p: Pty) {
        val buf = ByteArray(16 * 1024)
        while (!closing) {
            val n = p.read(buf)
            if (n <= 0) break
            val chunk = buf.copyOf(n)
            mainHandler.post { if (!closing) view.feed(chunk) }
        }
        val status = p.wait()
        mainHandler.post { if (!closing) onFinish(status) }
    }

    /** Write bytes to the PTY. Main thread only (see view.feed / sendKey). */
    override fun send(bytes: ByteArray) {
        if (!closing) pty?.write(bytes)
    }

    override fun sendUtf8(text: String) = send(text.toByteArray(Charsets.UTF_8))

    /** Forward grid size changes from the view to the terminal and the PTY. */
    override fun onGridResized(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        if (closing) return
        NativeTerminal.nativeResize(handle, cols, rows, cellWidthPx, cellHeightPx)
        pty?.resize(cols, rows)
    }

    override fun onTitleChanged(title: ByteArray?) {
        onTitle?.invoke(title)
    }

    override fun onBellReceived() {
        onBell?.invoke()
    }

    fun dispose() {
        closing = true
        val p = pty
        if (p != null) {
            p.signal(Pty.SIGHUP_NUM)
            try {
                reader?.join(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            p.closeFd()
        }
        if (handle != 0L) NativeTerminal.nativeFree(handle)
        handle = 0
    }
}
