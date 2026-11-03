package com.qapquiz.herdm.hc

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Pad a root view by system-bar insets (edge-to-edge, targetSdk 35+). */
fun applyInsets(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

/**
 * Paste with a safety guard: newlines in pasted text act as Enter and other
 * control bytes can act on the shell, so desktop terminals confirm first.
 * [needsConfirm] comes from the terminal's paste-safety check; safe pastes
 * go straight through and [paste] is invoked on confirm.
 */
fun confirmPaste(activity: Context, text: String, needsConfirm: Boolean, paste: (String) -> Unit) {
    if (!needsConfirm) {
        paste(text)
        return
    }
    val newlines = text.count { it == '\n' }
    val title = if (newlines > 0) "Paste ${newlines + 1} lines?" else "Paste?"
    AlertDialog.Builder(activity)
        .setTitle(title)
        .setMessage("Newlines act as Enter in the terminal.")
        .setPositiveButton("Paste") { _, _ -> paste(text) }
        .setNegativeButton("Cancel", null)
        .show()
}
