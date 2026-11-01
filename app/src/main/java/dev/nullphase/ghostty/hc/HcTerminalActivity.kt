package dev.nullphase.ghostty.hc

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.nullphase.ghostty.GhosttyTerminalView
import dev.nullphase.ghostty.NativeTerminal

/** Full-screen terminal over a herdr-core attach channel. */
class HcTerminalActivity : AppCompatActivity() {
    private lateinit var view: GhosttyTerminalView
    private lateinit var session: HcTerminalSession
    private var handle: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conn = HcApp.connection
        if (conn == null) {
            finish(); return
        }
        val address = intent.getStringExtra("address") ?: run { finish(); return }
        val title = intent.getStringExtra("title") ?: address
        this.title = title

        view = GhosttyTerminalView(this)
        val holder = FrameLayout(this)
        holder.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            holder,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )
        root.addView(
            AccessoryBar(
                this,
                onKey = { keyCode, meta -> view.sendEncodedKey(keyCode, meta) },
                onText = { text -> view.sendRawText(text) },
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(root)
        applyInsets(root)

        handle = NativeTerminal.nativeCreate(80, 24, 10L * 1024 * 1024)
        if (handle == 0L) {
            Toast.makeText(this, "native terminal init failed", Toast.LENGTH_LONG).show()
            finish(); return
        }
        session = HcTerminalSession(conn, view, address) { reason ->
            Toast.makeText(this, "ended: ${reason ?: "detached"}", Toast.LENGTH_LONG).show()
            finish()
        }
        view.attach(handle, session)
        view.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Detach (CLOSE frame, empty reason); the program keeps running.
        session.detach()
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (handle != 0L) NativeTerminal.nativeFree(handle)
        handle = 0
        super.onDestroy()
    }
}
