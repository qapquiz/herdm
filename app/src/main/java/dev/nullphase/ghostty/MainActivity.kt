package dev.nullphase.ghostty

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class MainActivity : Activity() {
    private var session: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = GhosttyTerminalView(this)
        val root = android.widget.FrameLayout(this)
        root.addView(
            view,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = androidx.core.view.WindowInsetsCompat
                .Type.systemBars()
                .let(insets::getInsets)
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        session = TerminalSession(view.context, view, onFinish = { status ->
            val code = Pty.exitCodeOf(status)
            Toast.makeText(this, "shell exited ($code)", Toast.LENGTH_LONG).show()
            finish()
        })
        session?.onTitle = { title ->
            this.title = title?.toString(Charsets.UTF_8) ?: getString(R.string.app_name)
        }
        session?.onBell = {
            Toast.makeText(this, "Bell", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        session?.dispose()
        session = null
        super.onDestroy()
    }
}
