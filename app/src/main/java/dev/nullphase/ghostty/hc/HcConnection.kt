package dev.nullphase.ghostty.hc

import android.os.Handler
import android.os.Looper
import android.util.Log
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.Security
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One SSH-tunneled herdr-core connection speaking the framed JSON-RPC
 * protocol (`ssh host hc connect`): line-mode `hello`, then
 * [channel u32][kind u8][len u32][payload] frames — channel 0 is JSON-RPC,
 * attach/events channels carry terminal bytes.
 *
 * All callbacks are invoked on the main thread. Reader thread does I/O only.
 */
class HcConnection {
    companion object {
        private const val TAG = "hc-client"
        private const val CREDIT_BATCH = 64 * 1024

        init {
            // Android ships an old "BC" provider that shadows the modern
            // BouncyCastle sshj needs (ed25519 etc.).
            runCatching {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 2)
            }
        }

        private fun loadKeyProvider(path: String, passphrase: String?): FileKeyProvider {
            val f = File(path)
            val finder = PasswordUtils.createOneOff(passphrase?.toCharArray() ?: charArrayOf())
            val text = f.readText()
            val provider: FileKeyProvider =
                if (text.contains("BEGIN OPENSSH PRIVATE KEY")) OpenSSHKeyFile() else PKCS8KeyFile()
            provider.init(f, finder)
            return provider
        }
    }

    interface Listener {
        /** Events-channel line (JSON) or channel-0 notification, main thread. */
        fun onEvent(obj: JSONObject) {}
        /** The SSH/exec stream ended, main thread. */
        fun onConnectionClosed(reason: String?) {}
    }

    @Volatile var listener: Listener? = null
    /** Fingerprint accepted during the latest connect (TOFU store side). */
    @Volatile var negotiatedHostKey: String? = null
        private set
    @Volatile var hostKeyMismatch = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (JSONObject?, JSONObject?) -> Unit>()
    private val consumed = ConcurrentHashMap<Int, Long>()
    private val dataListeners = ConcurrentHashMap<Int, (ByteArray) -> Unit>()
    private val closeListeners = ConcurrentHashMap<Int, (String?) -> Unit>()
    /** DATA that arrived before a listener registered (first paint race). */
    private val pendingData = ConcurrentHashMap<Int, MutableList<ByteArray>>()
    private val pendingClose = ConcurrentHashMap<Int, String?>()
    private val lineAssemblers = ConcurrentHashMap<Int, LineAssembler>()
    private val ch0 = JsonAssembler()
    private var ssh: SSHClient? = null
    private var exec: Session.Command? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "hc-writer").apply { isDaemon = true }
    }

    fun connect(host: HostConfig, onReady: (JSONObject) -> Unit, onError: (String) -> Unit) {
        negotiatedHostKey = null
        hostKeyMismatch = false
        Thread {
            try {
                doConnect(host, onReady)
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                val msg = if (hostKeyMismatch) {
                    "HOST KEY CHANGED for ${host.hostname}: expected ${host.hostKeyFingerprint}, got $negotiatedHostKey. " +
                        "Edit the host to accept the new key."
                } else {
                    e.message ?: e.toString()
                }
                main.post { onError(msg) }
                disconnect()
            }
        }.start()
    }

    private fun doConnect(host: HostConfig, onReady: (JSONObject) -> Unit) {
        SecurityUtils.setRegisterBouncyCastle(false) // we insert BC ourselves
        val client = SSHClient()
        client.connectTimeout = 15_000
        client.timeout = 0 // streams are long-lived
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: java.security.PublicKey): Boolean {
                val actual = runCatching {
                    val blob = net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(key).getCompactData()
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
                    "SHA256:" + android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                }.getOrElse { key.toString() }
                negotiatedHostKey = actual
                val expected = host.hostKeyFingerprint
                return when {
                    expected.isNullOrEmpty() -> true // TOFU: first contact; caller persists it
                    expected == actual -> true
                    else -> {
                        hostKeyMismatch = true
                        false
                    }
                }
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        client.connect(host.hostname, host.port)

        if (host.authType == "key") {
            val kp = loadKeyProvider(host.keyPath ?: error("no key file set"), host.keyPassphrase)
            client.authPublickey(host.username, kp)
        } else {
            client.authPassword(host.username, host.password ?: "")
        }

        val session = client.startSession()
        val cmd = session.exec(host.command)
        ssh = client
        exec = cmd

        // Line mode: hello handshake (JSON-RPC, one message per line).
        val hello = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 0)
            .put("method", "hello")
            .put(
                "params",
                JSONObject()
                    .put("generation", 1)
                    .put("framed", true)
                    .put("calls_you", "ghostty-droid"),
            )
        val input = cmd.inputStream
        out = cmd.outputStream
        synchronized(writeLock) {
            out!!.write((hello.toString() + "\n").toByteArray(Charsets.UTF_8))
            out!!.flush()
        }

        running.set(true)
        readLoop(input, onReady)
    }

    private fun readLoop(input: InputStream, onReady: (JSONObject) -> Unit) {
        try {
            // Hello reply: first line on the wire.
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw java.io.EOFException("connection closed during handshake")
                if (b == '\n'.code) break
                line.append(b.toChar())
            }
            val reply = JSONObject(line.toString())
            val error = reply.optJSONObject("error")
            if (error != null) throw java.io.EOFException("hello rejected: $error")
            val result = reply.optJSONObject("result")
                ?: throw java.io.EOFException("hello returned no result")
            if (!result.optBoolean("framed")) {
                throw java.io.EOFException("core refused framed mode")
            }
            main.post { onReady(result) }

            // Framed mode from here on.
            val reader = Framed.Reader(input)
            while (running.get()) {
                val frame = reader.next() ?: break
                when (frame.kind) {
                    Framed.DATA -> {
                        if (frame.channel == 0) {
                            for (obj in ch0.push(frame.payload)) handleControlLine(obj)
                        } else {
                            credit(frame.channel, frame.payload.size)
                            val cb = dataListeners[frame.channel]
                            if (cb != null) main.post { cb(frame.payload) }
                            else pendingData
                                .getOrPut(frame.channel) { mutableListOf() }
                                .add(frame.payload)
                        }
                    }
                    Framed.CLOSE -> {
                        val reason = String(frame.payload, Charsets.UTF_8).ifEmpty { null }
                        val cb = closeListeners.remove(frame.channel)
                        dataListeners.remove(frame.channel)
                        lineAssemblers.remove(frame.channel)
                        pendingData.remove(frame.channel)
                        if (cb != null) main.post { cb(reason) }
                        else pendingClose[frame.channel] = reason
                    }
                    else -> {} // CREDIT/RESIZE/FOCUS/FACTS from core: nothing to do
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "reader ended", e)
        } finally {
            val wasRunning = running.getAndSet(false)
            if (wasRunning) main.post { listener?.onConnectionClosed(null) }
            pending.clear()
            main.post {
                dataListeners.clear()
                closeListeners.clear()
            }
        }
    }

    private fun handleControlLine(obj: JSONObject) {
        if (obj.has("id") && obj.opt("id") != 0) {
            val id = obj.getInt("id")
            val cb = pending.remove(id)
            if (cb != null) main.post { cb(obj.optJSONObject("result"), obj.optJSONObject("error")) }
        } else if (obj.has("method")) {
            val params = obj.optJSONObject("params") ?: JSONObject()
            main.post { listener?.onEvent(params) }
        }
    }

    // ------------------------------------------------------------- writes

    /** All outbound frames go through one background thread: FIFO order, no
     * main-thread I/O (NetworkOnMainThreadException) and no races with reads. */
    private fun writeFrame(channel: Int, kind: Byte, payload: ByteArray) {
        if (out == null) return
        runCatching {
            writer.execute {
                val o = out ?: return@execute
                synchronized(writeLock) {
                    o.write(Framed.encode(channel, kind, payload))
                    o.flush()
                }
            }
        }
    }

    /** True while the SSH stream and reader are alive. */
    val alive: Boolean get() = running.get()

    /** JSON-RPC request over channel 0; callback receives (result, error). */
    fun request(method: String, params: JSONObject = JSONObject(), cb: ((JSONObject?, JSONObject?) -> Unit)? = null) {
        if (!running.get()) {
            cb?.invoke(null, JSONObject().put("kind", "disconnected"))
            return
        }
        val id = nextId.getAndIncrement()
        if (cb != null) pending[id] = cb
        val msg = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        writeFrame(0, Framed.DATA, (msg.toString() + "\n").toByteArray(Charsets.UTF_8))
    }

    /**
     * Attach a terminal: opens a DATA stream channel; [onChannel] fires on
     * the main thread with the channel id after the initial RESIZE.
     */
    fun attach(
        terminal: String,
        cols: Int,
        rows: Int,
        history: Int = 1000,
        onChannel: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        val params = JSONObject()
            .put("terminal", terminal)
            .put("cols", cols)
            .put("rows", rows)
            .put("history", history)
            .put(
                "display",
                JSONObject()
                    .put("bg", "#000000")
                    .put("fg", "#ffffff"),
            )
        request("terminal.attach", params) { result, error ->
            if (error != null || result == null) {
                onError(error?.optString("kind") ?: "attach failed")
                return@request
            }
            val channel = result.optInt("channel", -1)
            if (channel <= 0) {
                onError("no channel in attach result")
                return@request
            }
            // Size before first paint so the program lays out for us.
            writeFrame(channel, Framed.RESIZE, Framed.resizePayload(cols, rows))
            onChannel(channel)
        }
    }

    fun watchChannel(channel: Int, onData: (ByteArray) -> Unit, onClose: (String?) -> Unit) {
        dataListeners[channel] = onData
        closeListeners[channel] = onClose
        val buffered = pendingData.remove(channel)
        if (buffered != null) {
            for (chunk in buffered) main.post { onData(chunk) }
        }
        val earlyClose = pendingClose.remove(channel)
        if (earlyClose != null || (buffered != null && closeListeners[channel] == null)) {
            if (earlyClose != null) main.post { onClose(earlyClose) }
        }
    }

    /** Route terminal bytes (typed input, query replies) to the core. */
    fun sendData(channel: Int, bytes: ByteArray) = writeFrame(channel, Framed.DATA, bytes)

    fun resize(channel: Int, cols: Int, rows: Int) =
        writeFrame(channel, Framed.RESIZE, Framed.resizePayload(cols, rows))

    /** Viewer display facts (colors, cell size) as a FACTS frame. */
    fun writeFacts(channel: Int, json: String) =
        writeFrame(channel, Framed.FACTS, json.toByteArray(Charsets.UTF_8))

    fun sendFocus(channel: Int, focused: Boolean) =
        writeFrame(channel, Framed.FOCUS, if (focused) byteArrayOf(1) else byteArrayOf(0))

    /** Detach: empty CLOSE from the client ends our stream side. */
    fun closeChannel(channel: Int) {
        dataListeners.remove(channel)
        closeListeners.remove(channel)
        pendingData.remove(channel)
        pendingClose.remove(channel)
        writeFrame(channel, Framed.CLOSE, ByteArray(0))
    }

    private fun credit(channel: Int, bytes: Int) {
        val total = (consumed[channel] ?: 0L) + bytes
        if (total >= CREDIT_BATCH) {
            consumed[channel] = 0L
            writeFrame(channel, Framed.CREDIT, Framed.creditPayload(total.toInt()))
        } else {
            consumed[channel] = total
        }
    }

    fun disconnect() {
        running.set(false)
        writer.execute {
            runCatching { exec?.close() }
            runCatching { ssh?.disconnect() }
            out = null
            exec = null
            ssh = null
        }
        writer.shutdown()
    }
}
