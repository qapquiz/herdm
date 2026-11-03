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
 * Paste with a multi-line guard: a newline in pasted text acts as Enter,
 * so desktop terminals confirm before running lines. Single-line pastes
 * go straight through; [paste] is invoked on confirm.
 */
fun confirmPaste(activity: Context, text: String, paste: (String) -> Unit) {
    val newlines = text.count { it == '\n' }
    if (newlines == 0) {
        paste(text)
        return
    }
    AlertDialog.Builder(activity)
        .setTitle("Paste ${newlines + 1} lines?")
        .setMessage("Each newline acts as Enter in the terminal.")
        .setPositiveButton("Paste") { _, _ -> paste(text) }
        .setNegativeButton("Cancel", null)
        .show()
}
