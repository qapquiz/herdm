package dev.nullphase.ghostty

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.inputmethod.BaseInputConnection
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * What the view needs from a byte transport: local PTY ([TerminalSession])
 * or a herdr-core attach channel ([dev.nullphase.ghostty.hc.HcTerminalSession]).
 */
interface TerminalLink {
    /** Bytes destined for the program (typed input, query replies). */
    fun send(bytes: ByteArray)

    fun sendUtf8(text: String) = send(text.toByteArray(Charsets.UTF_8))

    /** Grid size changed; transport resizes its side. */
    fun onGridResized(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int)

    fun onTitleChanged(title: ByteArray?) {}
    fun onBellReceived() {}
}

/**
 * Terminal rendering surface. Owns the dirty-row Canvas bitmap that
 * nativeSnapshot patches, the touch/scroll handling, IME input, and hardware
 * key encoding. All state mutations happen on the main thread.
 */
class GhosttyTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // Cell flag bits; must mirror kFlag* in ghostty_jni.cpp.
    private object CellFlags {
        const val BOLD = 1
        const val ITALIC = 1 shl 1
        const val FAINT = 1 shl 2
        const val UNDERLINE = 1 shl 3
        const val STRIKETHROUGH = 1 shl 4
        const val INVERSE = 1 shl 5
        const val INVISIBLE = 1 shl 6
        const val WIDE = 1 shl 7
        const val SPACER = 1 shl 8
        const val FG_DEFAULT = 1 shl 9
        const val BG_NONE = 1 shl 10
    }

    // Cursor visual styles from GhosttyRenderStateCursorVisualStyle.
    private object CursorStyle {
        const val BAR = 0
        const val BLOCK = 1
        const val UNDERLINE = 2
        const val BLOCK_HOLLOW = 3
    }

    private companion object {
        const val HEADER_INTS = 13
        const val ROW_HEADER_BYTES = 16
        const val CELL_RECORD_BYTES = 16
        const val MAX_CELL_TEXT_UNITS = 32
        const val BLINK_MS = 530L
    }

    internal var link: TerminalLink? = null
    internal var handle: Long = 0

    private var fontSizeSp = 14f
    private var cellW = 0
    private var cellH = 0
    private var baseline = 0
    private var cols = 0
    private var rows = 0

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val bgPaint = Paint()
    private val selectionPaint = Paint().apply { color = 0x4080C0FF.toInt() }
    private val cursorPaint = Paint()
    private val scrollbarPaint = Paint().apply { color = 0x40FFFFFF.toInt() }

    private val monoItalic: Typeface by lazy { Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC) }

    private var snapshot: ByteBuffer? = null
    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    // Cached header values from the last snapshot.
    private var defBg = Color.BLACK
    private var defFg = Color.WHITE
    private var cursorX = -1
    private var cursorY = -1
    private var cursorStyle = CursorStyle.BLOCK
    private var cursorVisible = false
    private var cursorBlinking = false
    private var cursorColor = 0
    private var scrollbar: LongArray? = null

    /** True while the viewport sits at the bottom (auto-follow output). */
    private var pinned = true

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        computeMetrics()
    }

    internal fun attach(handle: Long, link: TerminalLink) {
        this.handle = handle
        this.link = link
    }

    /** Cell size in pixels, for herdr-core display facts. */
    internal fun cellWidthPx() = cellW
    internal fun cellHeightPx() = cellH

    /** Clear the painted bitmap (fresh attach / reconnect repaint). */
    internal fun resetScreen() {
        bitmap?.eraseColor(defBg)
        cursorX = -1
        cursorY = -1
        invalidate()
    }

    /** Encode an abstract key (accessory bar) and send it through the link. */
    internal fun sendEncodedKey(keyCode: Int, meta: Int): Boolean {
        if (handle == 0L) return false
        val encoded = NativeTerminal.nativeEncodeKey(handle, keyCode, 1, meta, 0, null)
        if (encoded != null) {
            link?.send(encoded)
            return true
        }
        return false
    }

    /** Literal text (accessory bar symbols) straight to the program. */
    internal fun sendRawText(text: String) {
        if (handle != 0L) link?.sendUtf8(text)
    }

    var fontSize: Float
        get() = fontSizeSp
        set(value) {
            if (fontSizeSp == value) return
            fontSizeSp = value
            computeMetrics()
            requestLayout()
        }

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun dp(v: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)

    private fun computeMetrics() {
        textPaint.textSize = sp(fontSizeSp)
        val fm = textPaint.fontMetricsInt
        cellH = fm.descent - fm.ascent
        baseline = -fm.ascent
        cellW = textPaint.measureText("W").roundToInt().coerceAtLeast(1)
    }

    private fun ensureBuffers() {
        if (cols <= 0 || rows <= 0) return
        val b = bitmap
        if (b != null && b.width >= cols * cellW && b.height >= rows * cellH && snapshot != null) return
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(cols * cellW, rows * cellH, Bitmap.Config.ARGB_8888)
        bitmapCanvas = Canvas(bitmap!!)
        snapshot = ByteBuffer
            .allocateDirect(
                HEADER_INTS * 4 +
                    rows * (ROW_HEADER_BYTES + cols * CELL_RECORD_BYTES + cols * MAX_CELL_TEXT_UNITS * 2 + 4),
            )
            .order(ByteOrder.LITTLE_ENDIAN)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cellW <= 0 || cellH <= 0 || w <= 0 || h <= 0) return
        val newCols = (w / cellW).coerceAtLeast(1)
        val newRows = (h / cellH).coerceAtLeast(1)
        if (newCols == cols && newRows == rows) return
        cols = newCols
        rows = newRows
        ensureBuffers()
        pinned = true
        link?.onGridResized(cols, rows, cellW, cellH)
        invalidate()
    }

    // ---------------------------------------------------------------- output

    /** Feed PTY output into the terminal. Main thread only. */
    internal fun feed(bytes: ByteArray) {
        if (handle == 0L) return
        NativeTerminal.nativeWrite(handle, bytes)?.let { link?.send(it) }

        val events = NativeTerminal.nativeTakeEventFlags(handle)
        if (events and NativeTerminal.EVENT_TITLE != 0) {
            link?.onTitleChanged(NativeTerminal.nativeGetTitle(handle))
        }
        if (events and NativeTerminal.EVENT_BELL != 0) {
            link?.onBellReceived()
        }
        if (pinned) NativeTerminal.nativeScrollToBottom(handle)
        scrollbar = NativeTerminal.nativeScrollbar(handle)
        pinned = scrollbar == null || scrollbar!![1] == 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (handle == 0L || bitmap == null) {
            canvas.drawColor(defBg)
            return
        }
        if (parseIntoBitmap()) {
            scrollbar = NativeTerminal.nativeScrollbar(handle)
        }
        canvas.drawBitmap(bitmap!!, 0f, 0f, null)
        drawCursor(canvas)
        drawScrollbar(canvas)
        if (cursorVisible && cursorBlinking) postInvalidateDelayed(BLINK_MS)
    }

    /** Runs nativeSnapshot and paints dirty rows into the bitmap. */
    private fun parseIntoBitmap(): Boolean {
        val snapshot = this.snapshot ?: return false
        val rowCount = NativeTerminal.nativeSnapshot(handle, snapshot)
        if (rowCount < 0) return false
        snapshot.rewind()
        if (rowCount == 0) return false

        snapshot.int // version
        val dirty = snapshot.int
        val cols = snapshot.int
        val rows = snapshot.int
        defBg = snapshot.int
        defFg = snapshot.int
        cursorX = snapshot.int
        cursorY = snapshot.int
        cursorStyle = snapshot.int
        cursorVisible = snapshot.int == 1
        @Suppress("UNUSED_VARIABLE") val rowCountHeader = snapshot.int
        cursorBlinking = snapshot.int == 1
        cursorColor = snapshot.int

        val bmp = bitmapCanvas ?: return false
        if (dirty == 2) bmp.drawColor(defBg)

        val rowChars = CharArray(cols * MAX_CELL_TEXT_UNITS)
        var off = HEADER_INTS * 4
        repeat(rowCount) {
            val rowIndex = snapshot.getInt(off)
            val selStart = snapshot.getInt(off + 4)
            val selEnd = snapshot.getInt(off + 8)
            val textUnits = snapshot.getInt(off + 12)
            val cellsBase = off + ROW_HEADER_BYTES
            val textBase = cellsBase + cols * CELL_RECORD_BYTES

            paintRow(bmp, rowIndex, cols, selStart, selEnd, cellsBase, textBase, textUnits, rowChars)

            off = textBase + textUnits * 2
            if (off % 4 != 0) off += 2
        }
        return true
    }

    private fun paintRow(
        bmp: Canvas,
        rowIndex: Int,
        cols: Int,
        selStart: Int,
        selEnd: Int,
        cellsBase: Int,
        textBase: Int,
        textUnits: Int,
        rowChars: CharArray,
    ) {
        val snapshot = this.snapshot ?: return
        val top = rowIndex * cellH

        for (i in 0 until textUnits) {
            rowChars[i] = snapshot.getChar(textBase + 2 * i)
        }

        val y0 = top.toFloat()
        val y1 = (top + cellH).toFloat()

        // Background runs (incl. selection overlay), then styled text runs.
        var col = 0
        while (col < cols) {
            val fg = snapshot.getInt(cellsBase + col * CELL_RECORD_BYTES)
            val bg = snapshot.getInt(cellsBase + col * CELL_RECORD_BYTES + 4)
            val flags = snapshot.getShort(cellsBase + col * CELL_RECORD_BYTES + 12).toInt() and 0xffff

            val wide = flags and CellFlags.WIDE != 0
            val runWidthCells = if (wide) 2 else 1
            var end = col
            while (end < cols) {
                val f = snapshot.getShort(cellsBase + end * CELL_RECORD_BYTES + 12).toInt() and 0xffff
                val w = if (f and CellFlags.WIDE != 0) 2 else 1
                val b = snapshot.getInt(cellsBase + end * CELL_RECORD_BYTES + 4)
                if (f and CellFlags.SPACER != 0 || (w == 2) != wide || b != bg) break
                end += if (w == 2 && end + 1 < cols) 2 else 1
            }

            var effBg = if (flags and CellFlags.BG_NONE != 0) defBg else bg
            var effFg = if (flags and CellFlags.FG_DEFAULT != 0) defFg else fg
            if (flags and CellFlags.INVERSE != 0) {
                val t = effBg
                effBg = effFg
                effFg = t
            }

            val x0 = col * cellW
            val x1 = (end * cellW).toFloat()
            if (effBg != defBg) {
                bgPaint.color = effBg
                bmp.drawRect(x0.toFloat(), y0, x1, y1, bgPaint)
            }
            if (selStart >= 0 && selEnd >= 0 && end > selStart && col < selEnd) {
                val sx = (maxOf(col, selStart) * cellW).toFloat()
                val ex = (minOf(end, selEnd) * cellW).toFloat()
                bmp.drawRect(sx, y0, ex, y1, selectionPaint)
            }

            // Text runs within [col, end).
            var tcol = col
            while (tcol < end) {
                val rec = cellsBase + tcol * CELL_RECORD_BYTES
                val tFlags = snapshot.getShort(rec + 12).toInt() and 0xffff
                if (tFlags and CellFlags.SPACER != 0) {
                    tcol++
                    continue
                }
                val tOff = snapshot.getShort(rec + 8).toInt() and 0xffff
                val tLen = snapshot.getShort(rec + 10).toInt() and 0xffff

                val rFg = snapshot.getInt(rec)
                val rBg = snapshot.getInt(rec + 4)
                var rEffFg = if (tFlags and CellFlags.FG_DEFAULT != 0) defFg else rFg
                var rEffBg = if (tFlags and CellFlags.BG_NONE != 0) defBg else rBg
                if (tFlags and CellFlags.INVERSE != 0) {
                    val t = rEffBg
                    rEffBg = rEffFg
                    rEffFg = t
                }

                var runEnd = tcol
                var runLen = 0
                while (runEnd < end) {
                    val rr = cellsBase + runEnd * CELL_RECORD_BYTES
                    val f = snapshot.getShort(rr + 12).toInt() and 0xffff
                    if (f and CellFlags.SPACER != 0) break
                    val l = snapshot.getShort(rr + 10).toInt() and 0xffff
                    if (f != tFlags || snapshot.getInt(rr) != rFg || snapshot.getInt(rr + 4) != rBg) break
                    runLen += l
                    runEnd += if (f and CellFlags.WIDE != 0) 2 else 1
                }

                if (tLen > 0 && runLen > 0 && tFlags and CellFlags.INVISIBLE == 0) {
                    textPaint.color = rEffFg
                    textPaint.isFakeBoldText = tFlags and CellFlags.BOLD != 0
                    textPaint.typeface = if (tFlags and CellFlags.ITALIC != 0) monoItalic else Typeface.MONOSPACE
                    textPaint.isUnderlineText = tFlags and CellFlags.UNDERLINE != 0
                    textPaint.isStrikeThruText = tFlags and CellFlags.STRIKETHROUGH != 0
                    if (tFlags and CellFlags.FAINT != 0) {
                        textPaint.alpha = 140
                    } else {
                        textPaint.alpha = Color.alpha(rEffFg)
                    }
                    val str = String(rowChars, tOff, runLen)
                    bmp.drawText(str, tcol * cellW.toFloat(), top + baseline.toFloat(), textPaint)
                }
                tcol = if (runEnd > tcol) runEnd else tcol + 1
            }

            col = if (end > col) end else col + 1
        }
    }

    private fun drawCursor(canvas: Canvas) {
        if (!cursorVisible || cursorX < 0 || cursorY < 0) return
        if (cursorBlinking && System.currentTimeMillis() / BLINK_MS % 2 == 0L) return
        val x = cursorX * cellW
        val y = cursorY * cellH
        cursorPaint.color = if (cursorColor != 0) cursorColor else defFg
        when (cursorStyle) {
            CursorStyle.BAR ->
                canvas.drawRect(x.toFloat(), y.toFloat(), x + dp(2), (y + cellH).toFloat(), cursorPaint)
            CursorStyle.UNDERLINE ->
                canvas.drawRect(
                    x.toFloat(), (y + cellH - dp(2)).toFloat(),
                    (x + cellW).toFloat(), (y + cellH).toFloat(), cursorPaint,
                )
            CursorStyle.BLOCK_HOLLOW -> {
                cursorPaint.style = Paint.Style.STROKE
                cursorPaint.strokeWidth = dp(2)
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + cellW).toFloat(), (y + cellH).toFloat(), cursorPaint)
                cursorPaint.style = Paint.Style.FILL
            }
            else -> {
                cursorPaint.alpha = 0x66
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + cellW).toFloat(), (y + cellH).toFloat(), cursorPaint)
                cursorPaint.alpha = 0xFF
            }
        }
    }

    private fun drawScrollbar(canvas: Canvas) {
        val sb = scrollbar ?: return
        val total = sb[0]
        val offset = sb[1]
        val len = sb[2]
        if (total <= 0 || len <= 0 || len >= total) return
        val hgt = height.toFloat()
        val thumbH = (len.toFloat() / total * hgt).coerceAtLeast(dp(24))
        val thumbY = hgt - (offset + len).toFloat() / total * hgt - thumbH
        val w = width.toFloat()
        canvas.drawRect(w - dp(4), thumbY, w, thumbY + thumbH, scrollbarPaint)
    }

    // ----------------------------------------------------------------- input

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (handle == 0L) return true
                val deltaRows = (distanceY / cellH).roundToInt()
                if (deltaRows != 0) scrollByRows(-deltaRows)
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                performClick()
                requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this@GhosttyTerminalView, 0)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (handle == 0L) return
                val col = (e.x / cellW).toInt()
                val row = (e.y / cellH).toInt()
                if (NativeTerminal.nativeSelectWord(handle, col, row)) {
                    copySelectionToClipboard()
                }
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    internal fun scrollByRows(deltaRows: Int) {
        if (handle == 0L) return
        NativeTerminal.nativeScroll(handle, deltaRows)
        scrollbar = NativeTerminal.nativeScrollbar(handle)
        pinned = scrollbar == null || scrollbar!![1] == 0L
        invalidate()
    }

    private fun copySelectionToClipboard() {
        val text = NativeTerminal.nativeSelectionText(handle) ?: return
        val str = String(text, Charsets.UTF_8)
        if (str.isEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("selection", str))
        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    private inner class TerminalInputConnection :
        BaseInputConnection(this@GhosttyTerminalView, false) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            link?.sendUtf8(text.toString())
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength.coerceAtMost(64)) { link?.send(byteArrayOf(0x7f)) }
            return true
        }
    }

    private val inputConnection: TerminalInputConnection by lazy { TerminalInputConnection() }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return inputConnection
    }

    /** Encode a hardware key through libghostty's key encoder (DECCKM /
     * Kitty-keyboard aware) and hand the escape bytes to the link. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handle != 0L && event.action == KeyEvent.ACTION_DOWN && encodeAndSend(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun encodeAndSend(event: KeyEvent): Boolean {
        val meta = event.metaState
        val printable = event.isPrintingKey
        val unicode = if (printable) event.getUnicodeChar(meta) else 0
        val utf8 = if (unicode != 0) {
            String(Character.toChars(unicode)).toByteArray(Charsets.UTF_8)
        } else {
            null
        }
        val unshifted = if (printable) event.getUnicodeChar(0) else 0

        val encoded = NativeTerminal.nativeEncodeKey(handle, event.keyCode, 1, meta, unshifted, utf8)
        if (encoded != null) {
            link?.send(encoded)
            return true
        }
        // No terminal encoding applied (plain printable char, no modes in
        // effect) — send the character itself when unmodified.
        if (utf8 != null && meta and (KeyEvent.META_CTRL_ON or
                KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) == 0
        ) {
            link?.send(utf8)
            return true
        }
        return false
    }
}
