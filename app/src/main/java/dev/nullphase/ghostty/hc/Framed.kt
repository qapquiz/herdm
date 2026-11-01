package dev.nullphase.ghostty.hc

import java.io.EOFException
import java.io.InputStream
import org.json.JSONObject

/**
 * herdr-core framed wire protocol (`crates/herdr-core/src/wire.rs`):
 * [channel u32 LE][kind u8][len u32 LE][payload]. Channel 0 carries JSON-RPC
 * lines; terminal.attach and events.subscribe open their own channels.
 */
object Framed {
    const val HEADER = 9
    const val MAX_PAYLOAD = 16 shl 20

    const val DATA: Byte = 0
    const val CREDIT: Byte = 1
    const val CLOSE: Byte = 2
    const val RESIZE: Byte = 3
    const val FOCUS: Byte = 4
    const val FACTS: Byte = 5

    data class Frame(val channel: Int, val kind: Byte, val payload: ByteArray)

    fun encode(channel: Int, kind: Byte, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER + payload.size)
        out[0] = (channel and 0xff).toByte()
        out[1] = ((channel shr 8) and 0xff).toByte()
        out[2] = ((channel shr 16) and 0xff).toByte()
        out[3] = ((channel shr 24) and 0xff).toByte()
        out[4] = kind
        val len = payload.size
        out[5] = (len and 0xff).toByte()
        out[6] = ((len shr 8) and 0xff).toByte()
        out[7] = ((len shr 16) and 0xff).toByte()
        out[8] = ((len shr 24) and 0xff).toByte()
        payload.copyInto(out, HEADER)
        return out
    }

    fun resizePayload(cols: Int, rows: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = (cols and 0xff).toByte()
        b[1] = ((cols shr 8) and 0xff).toByte()
        b[2] = (rows and 0xff).toByte()
        b[3] = ((rows shr 8) and 0xff).toByte()
        return b
    }

    fun creditPayload(bytes: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = (bytes and 0xff).toByte()
        b[1] = ((bytes shr 8) and 0xff).toByte()
        b[2] = ((bytes shr 16) and 0xff).toByte()
        b[3] = ((bytes shr 24) and 0xff).toByte()
        return b
    }

    /** Blocking frame reader over any stream; null on clean EOF between frames. */
    class Reader(private val input: InputStream) {
        private val header = ByteArray(HEADER)

        fun next(): Frame? {
            if (!readFully(header)) return null
            val channel = (header[0].toInt() and 0xff) or
                ((header[1].toInt() and 0xff) shl 8) or
                ((header[2].toInt() and 0xff) shl 16) or
                ((header[3].toInt() and 0xff) shl 24)
            val kind = header[4]
            val len = (header[5].toInt() and 0xff) or
                ((header[6].toInt() and 0xff) shl 8) or
                ((header[7].toInt() and 0xff) shl 16) or
                ((header[8].toInt() and 0xff) shl 24)
            if (len > MAX_PAYLOAD) throw EOFException("frame of $len bytes exceeds limit")
            val payload = ByteArray(len)
            if (len > 0 && !readFully(payload)) throw EOFException("eof inside frame payload")
            return Frame(channel, kind, payload)
        }

        private fun readFully(buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return off == 0 // clean EOF only between frames
                if (n == 0) throw EOFException("zero read")
                off += n
            }
            return true
        }
    }
}

/** Reassembles JSON lines out of DATA frames on one channel. */
class LineAssembler {
    private val buf = java.io.ByteArrayOutputStream()

    fun push(bytes: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                if (i > start) buf.write(bytes, start, i - start)
                lines.add(buf.toString("UTF-8"))
                buf.reset()
                start = i + 1
            }
        }
        if (start < bytes.size) buf.write(bytes, start, bytes.size - start)
        return lines
    }
}

/**
 * herdr-core writes framed channel-0 replies as bare JSON objects *without*
 * trailing newlines (server.rs: `encode(0, DATA, reply.to_string())`), so
 * this assembles messages by brace depth instead of lines.
 */
class JsonAssembler {
    private val buf = java.io.ByteArrayOutputStream()

    /** Drop any buffered partial message (fresh transport). */
    fun reset() {
        buf.reset()
    }

    fun push(bytes: ByteArray): List<JSONObject> {
        buf.write(bytes)
        val text = buf.toString("UTF-8")
        val out = mutableListOf<JSONObject>()
        var depth = 0
        var inStr = false
        var esc = false
        var start = -1
        for (i in text.indices) {
            val c = text[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> if (depth > 0) inStr = true
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && start >= 0) {
                            runCatching { JSONObject(text.substring(start, i + 1)) }
                                .getOrNull()?.let(out::add)
                            start = -1
                        }
                    }
                }
            }
        }
        buf.reset()
        if (start >= 0) buf.write(text.substring(start).toByteArray(Charsets.UTF_8))
        return out
    }
}
