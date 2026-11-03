package com.qapquiz.herdm.hc

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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * One SSH-tunneled herdr-core connection speaking the framed JSON-RPC
 * protocol (`ssh host hc connect`): line-mode `hello`, then
 * [channel u32][kind u8][len u32][payload] frames — channel 0 is JSON-RPC,
 * attach/events channels carry terminal bytes.
 *
 * A supervisor thread keeps the connection alive: after a drop it reconnects
 * with backoff, fires [Listener.onReconnected] (sessions re-attach), and
 * keeps trying until [stop]. All callbacks run on the main thread; I/O
 * happens on the reader/writer threads.
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
        /** Events-channel notification payload, main thread. */
        fun onEvent(obj: JSONObject) {}
        /** Lost the stream and a reconnect attempt is starting, main thread. */
        fun onReconnecting(attempt: Int) {}
        /** Stream re-established and hello succeeded again, main thread. */
        fun onReconnected() {}
        /** Stopped for good (stop() or unrecoverable), main thread. */
        fun onDisconnected(reason: String?) {}
    }

    @Volatile var listener: Listener? = null

    @Volatile
    var alive = false
        private set

    /** The host this connection is configured for (null until start()). */
    @Volatile
    var hostConfig: HostConfig? = null
        private set

    /** Fingerprint accepted during the latest connect (TOFU store side). */
    @Volatile var negotiatedHostKey: String? = null
        private set
    @Volatile var hostKeyMismatch = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false) // current transport alive
    private val wantConnection = AtomicBoolean(false)
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (JSONObject?, JSONObject?) -> Unit>()
    private val consumed = ConcurrentHashMap<Int, Long>()
    private val dataListeners = ConcurrentHashMap<Int, (ByteArray) -> Unit>()
    private val closeListeners = ConcurrentHashMap<Int, (String?) -> Unit>()
    /** DATA that arrived before a listener registered (first paint race).
     *  Capped: a misbehaving/core-side flood must not OOM the app. */
    private val pendingData = ConcurrentHashMap<Int, MutableList<ByteArray>>()
    private val pendingDataOverflow = ConcurrentHashMap<Int, Boolean>()
    private val pendingClose = ConcurrentHashMap<Int, String?>()
    private val lineAssemblers = ConcurrentHashMap<Int, LineAssembler>()
    private val ch0 = JsonAssembler()
    private val reconnectListeners = ConcurrentHashMap<Int, () -> Unit>()
    private val reconnectIds = AtomicInteger(1)

    // Rate guard for one channel (reader-thread-only state; trips when the
    // channel sustains more than guardBytesPerSec — a runaway stream must
    // never OOM the app through the main-thread post queue).
    private var guardChannel = -1
    private var guardLimit = Long.MAX_VALUE
    private var guardWindowStart = 0L
    private var guardWindowBytes = 0L
    private var guardTripsNeeded = 1

    private var host: HostConfig? = null
    private var ssh: SSHClient? = null
    private var exec: Session.Command? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    private var writer: java.util.concurrent.ExecutorService? = null
    private var supervisor: Thread? = null

    // ---------------------------------------------------------- lifecycle

    /**
     * Connect and keep the connection alive. [onReady] fires once on the
     * first successful hello; every later re-established hello fires
     * [Listener.onReconnected]. [onError] fires only when the *initial*
     * connect fails (auth, unreachable, host-key mismatch).
     */
    fun start(host: HostConfig, onReady: (JSONObject) -> Unit, onError: (String) -> Unit) {
        this.host = host
        this.hostConfig = host
        negotiatedHostKey = null
        hostKeyMismatch = false
        wantConnection.set(true)
        supervisor?.interrupt()
        supervisor = thread(name = "hc-supervisor") {
            var attempt = 0
            var everReady = false
            while (wantConnection.get()) {
                if (attempt > 0) {
                    val delay = (1000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(15_000)
                    main.post { listener?.onReconnecting(attempt) }
                    try {
                        Thread.sleep(delay)
                    } catch (_: InterruptedException) {
                        return@thread
                    }
                    if (!wantConnection.get()) return@thread
                }
                try {
                    doConnect(host) { hello ->
                        alive = true
                        attempt = 0
                        if (!everReady) {
                            everReady = true
                            main.post { onReady(hello) }
                        } else {
                            main.post {
                                reconnectListeners.values.forEach { it() }
                                listener?.onReconnected()
                            }
                        }
                    }
                    // doConnect returns when the stream ends.
                    if (wantConnection.get()) attempt++
                } catch (e: Exception) {
                    alive = false
                    if (!everReady) {
                        Log.w(TAG, "connect failed", e)
                        val msg = if (hostKeyMismatch) {
                            "HOST KEY CHANGED for ${host.hostname}: expected ${host.hostKeyFingerprint}, " +
                                "got $negotiatedHostKey. Edit the host to accept the new key."
                        } else {
                            e.message ?: e.toString()
                        }
                        wantConnection.set(false)
                        main.post { onError(msg) }
                        return@thread
                    }
                    if (wantConnection.get()) attempt++
                } finally {
                    teardownTransport()
                }
            }
            main.post { listener?.onDisconnected(null) }
        }
    }

    /** Stop supervising and drop the transport. */
    fun stop() {
        wantConnection.set(false)
        supervisor?.interrupt()
        supervisor = null
        teardownTransport()
    }

    private fun teardownTransport() {
        alive = false
        runCatching { exec?.close() }
        runCatching { ssh?.disconnect() }
        out = null
        exec = null
        ssh = null
        writer?.shutdown()
        writer = null
        main.post {
            failAllPending("disconnected")
            dataListeners.clear()
            closeListeners.clear()
            pendingData.clear()
            pendingDataOverflow.clear()
            pendingClose.clear()
            lineAssemblers.clear()
        }
    }

    private fun failAllPending(kind: String) {
        for ((_, cb) in pending) main.post { cb(null, JSONObject().put("kind", kind)) }
        pending.clear()
    }

    fun registerReconnect(cb: () -> Unit): Int {
        val id = reconnectIds.getAndIncrement()
        reconnectListeners[id] = cb
        return id
    }

    fun unregisterReconnect(id: Int) {
        reconnectListeners.remove(id)
    }

    // ------------------------------------------------------------- connect

    private fun doConnect(host: HostConfig, onReady: (JSONObject) -> Unit) {
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
        writer = Executors.newSingleThreadExecutor { r ->
            Thread(r, "hc-writer").apply { isDaemon = true }
        }

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
        // Line mode until hello completes: raw line, NOT a frame.
        val o = out
        synchronized(writeLock) {
            o!!.write((hello.toString() + "\n").toByteArray(Charsets.UTF_8))
            o.flush()
        }

        running.set(true)
        // Fresh per-transport state.
        pendingData.clear()
        pendingClose.clear()
        consumed.clear()
        ch0.reset()
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
                            if (frame.channel == guardChannel) {
                                val now = System.currentTimeMillis()
                                if (now - guardWindowStart >= 1000) {
                                    if (guardWindowBytes > guardLimit) {
                                        guardWindowBytes = 0
                                        guardTripsNeeded--
                                        Log.w(TAG, "rate guard window exceeded on ch=${frame.channel}")
                                        if (guardTripsNeeded <= 0) {
                                            val reason = "rate guard tripped"
                                            dataListeners.remove(frame.channel)
                                            closeListeners.remove(frame.channel)
                                            guardChannel = -1
                                            writeFrame(frame.channel, Framed.CLOSE, reason.toByteArray(Charsets.UTF_8))
                                            main.post {
                                                closeListeners[frame.channel]?.invoke(reason)
                                                closeListeners.remove(frame.channel)
                                            }
                                            continue
                                        }
                                    } else {
                                        guardWindowBytes = 0
                                    }
                                    guardWindowStart = now
                                }
                                guardWindowBytes += frame.payload.size
                            }
                            credit(frame.channel, frame.payload.size)
                            val cb = dataListeners[frame.channel]
                            if (cb != null) main.post { cb(frame.payload) }
                            else {
                                val bufList = pendingData.getOrPut(frame.channel) { mutableListOf() }
                                if (bufList.size < 512) {
                                    bufList.add(frame.payload)
                                } else if (pendingDataOverflow.putIfAbsent(frame.channel, true) == null) {
                                    Log.w(TAG, "channel ${frame.channel} overflow while unwatched; dropping bytes")
                                }
                            }
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
            running.set(false)
            failAllPending("disconnected")
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
            writer?.execute {
                val o = out ?: return@execute
                // Transport may die between queueing and running; a failed
                // write never kills the writer thread (supervisor reconnects).
                runCatching {
                    synchronized(writeLock) {
                        o.write(Framed.encode(channel, kind, payload))
                        o.flush()
                    }
                }
            }
        }
    }

    /** JSON-RPC request over channel 0; callback receives (result, error). */
    fun request(method: String, params: JSONObject = JSONObject(), cb: ((JSONObject?, JSONObject?) -> Unit)? = null) {
        if (!alive) {
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

    /**
     * Watch a channel. Optionally guard it against a runaway stream: if the
     * channel sustains more than [guardBytesPerSec] for [tripWindows]
     * consecutive 1 s windows, the channel is closed reader-side and
     * [onClose] receives "rate guard tripped". The attach channel of a
     * healthy terminal never approaches this.
     */
    fun watchChannel(
        channel: Int,
        onData: (ByteArray) -> Unit,
        onClose: (String?) -> Unit,
        guardBytesPerSec: Long = 0,
        tripWindows: Int = 3,
    ) {
        dataListeners[channel] = onData
        closeListeners[channel] = onClose
        if (guardBytesPerSec > 0) {
            guardChannel = channel
            guardLimit = guardBytesPerSec
            guardTripsNeeded = tripWindows
            guardWindowStart = System.currentTimeMillis()
            guardWindowBytes = 0
        }
        if (pendingDataOverflow.remove(channel) == true) {
            pendingData.remove(channel)
            main.post { onClose("overflow while unwatched") }
            return
        }
        val buffered = pendingData.remove(channel)
        if (buffered != null) {
            for (chunk in buffered) main.post { onData(chunk) }
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
        pendingDataOverflow.remove(channel)
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
}
