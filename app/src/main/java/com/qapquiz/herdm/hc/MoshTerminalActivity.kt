package com.qapquiz.herdm.hc

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.qapquiz.herdm.GhosttyTerminalView
import com.qapquiz.herdm.Pty
import com.qapquiz.herdm.TerminalSession
import java.io.File

/** Full-screen terminal over a bundled mosh-client (UDP/SSP). */
class MoshTerminalActivity : AppCompatActivity() {
    private lateinit var view: GhosttyTerminalView
    private var session: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ip = intent.getStringExtra("ip") ?: run { finish(); return }
        val port = intent.getStringExtra("port") ?: run { finish(); return }
        val key = intent.getStringExtra("key") ?: run { finish(); return }
        val hostName = intent.getStringExtra("hostName") ?: "mosh"
        title = "$hostName · mosh"

        // mosh-client needs a terminfo entry for $TERM; unpack once.
        val terminfoDir = File(filesDir, "terminfo")
        val termFile = File(terminfoDir, "x/xterm-256color")
        if (!termFile.exists()) {
            termFile.parentFile?.mkdirs()
            assets.open("terminfo/x/xterm-256color").use { input ->
                termFile.outputStream().use { input.copyTo(it) }
            }
        }

        val nativeDir = applicationInfo.nativeLibraryDir
        val env = mapOf(
            "TERM" to "xterm-256color",
            "TERMINFO" to terminfoDir.absolutePath,
            "LD_LIBRARY_PATH" to nativeDir,
            "LD_PRELOAD" to "$nativeDir/libandroid-support.so",
            "MOSH_KEY" to key,
            "HOME" to filesDir.absolutePath,
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "PATH" to "/system/bin",
            "TMPDIR" to cacheDir.absolutePath,
        )

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
                onPaste = { text -> confirmPaste(this, text) { view.paste(text) } },
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(root)
        applyInsets(root)

        session = TerminalSession(
            this,
            view,
            program = "$nativeDir/libmosh_client.so",
            programArgs = listOf(ip, port),
            programEnv = env,
            programCwd = filesDir.absolutePath,
            onFinish = { status ->
                val code = Pty.exitCodeOf(status)
                Toast.makeText(this, "mosh ended ($code)", Toast.LENGTH_LONG).show()
                finish()
            },
        )
    }

    override fun onDestroy() {
        session?.dispose()
        session = null
        super.onDestroy()
    }
}
