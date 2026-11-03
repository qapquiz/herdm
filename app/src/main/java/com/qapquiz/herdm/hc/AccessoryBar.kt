package com.qapquiz.herdm.hc

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Phone accessory bar: Esc / Tab / sticky Ctrl+Alt / arrows / nav keys /
 * common symbols. Key codes go through libghostty's key encoder so DECCKM
 * and Kitty-keyboard modes are respected; symbols without Android keycodes
 * go as literal text. Sticky modifiers stay on until pressed again.
 */
class AccessoryBar(
    context: Context,
    private val onKey: (keyCode: Int, meta: Int) -> Unit,
    private val onText: (text: String) -> Unit,
    private val onPaste: (text: String) -> Unit,
) : HorizontalScrollView(context) {
    private var stickyCtrl = false
    private var stickyAlt = false
    private lateinit var ctrlButton: TextView
    private lateinit var altButton: TextView

    init {
        isHorizontalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(42),
        )
        setBackgroundColor(0xFF141414.toInt())

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        fun key(label: String, keyCode: Int) {
            row.addView(button(label) { onKey(keyCode, currentMods()) })
        }

        fun text(label: String, literal: String) {
            row.addView(button(label) { onText(literal) })
        }

        // Paste reads the clipboard here; wiring confirms multi-line pastes.
        row.addView(
            button("paste") {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (clip.isNullOrEmpty()) {
                    android.widget.Toast.makeText(context, "Clipboard is empty", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    onPaste(clip)
                }
            },
        )

        key("esc", android.view.KeyEvent.KEYCODE_ESCAPE)
        key("tab", android.view.KeyEvent.KEYCODE_TAB)

        ctrlButton = button("ctrl") {
            stickyCtrl = !stickyCtrl
            ctrlButton.alpha = if (stickyCtrl) 1f else 0.55f
        }.apply { alpha = 0.55f }
        row.addView(ctrlButton)
        altButton = button("alt") {
            stickyAlt = !stickyAlt
            altButton.alpha = if (stickyAlt) 1f else 0.55f
        }.apply { alpha = 0.55f }
        row.addView(altButton)

        key("↑", android.view.KeyEvent.KEYCODE_DPAD_UP)
        key("↓", android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        key("←", android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        key("→", android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        key("home", android.view.KeyEvent.KEYCODE_MOVE_HOME)
        key("end", android.view.KeyEvent.KEYCODE_MOVE_END)
        key("pgup", android.view.KeyEvent.KEYCODE_PAGE_UP)
        key("pgdn", android.view.KeyEvent.KEYCODE_PAGE_DOWN)
        text("|", "|")
        text("~", "~")
        key("/", android.view.KeyEvent.KEYCODE_SLASH)

        addView(row)
    }

    private fun currentMods(): Int {
        var mods = 0
        if (stickyCtrl) mods = mods or android.view.KeyEvent.META_CTRL_ON
        if (stickyAlt) mods = mods or android.view.KeyEvent.META_ALT_ON
        return mods
    }

    private fun button(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFE0E0E0.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xFF2A2A2A.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(34)).also {
                it.rightMargin = dp(6)
            }
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
