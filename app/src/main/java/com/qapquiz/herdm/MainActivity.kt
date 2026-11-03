package com.qapquiz.herdm

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.qapquiz.herdm.hc.AccessoryBar
import com.qapquiz.herdm.hc.applyInsets
import com.qapquiz.herdm.hc.confirmPaste

class MainActivity : Activity() {
    private var session: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = GhosttyTerminalView(this)
        val holder = android.widget.FrameLayout(this)
        holder.addView(
            view,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        root.addView(
            holder,
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )
        root.addView(
            AccessoryBar(
                this,
                onKey = { keyCode, meta -> view.sendEncodedKey(keyCode, meta) },
                onText = { text -> view.sendRawText(text) },
                onPaste = { text -> confirmPaste(this, text) { view.paste(text) } },
            ),
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(root)
        applyInsets(root)
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
