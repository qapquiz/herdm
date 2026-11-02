package dev.nullphase.ghostty.hc

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.security.Security
import kotlin.concurrent.thread

/**
 * Mosh bootstrap: SSH to the host, start `mosh-server new`, parse its
 * "MOSH CONNECT <port> <key>" line, then hand (ip, port, key) to the bundled
 * mosh-client, which speaks UDP/SSP directly with the server. The SSH leg
 * closes after the handshake; the UDP session roams on its own.
 */
object MoshBootstrap {
    private const val TAG = "hc-client"

    /** Fingerprint accepted by the verifier during the latest start(). */
    @Volatile
    var negotiatedHostKey: String? = null
        private set

    data class MoshSession(val ip: String, val port: String, val key: String)

    init {
        runCatching {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 2)
        }
    }

    suspend fun start(host: HostConfig): MoshSession = withContext(Dispatchers.IO) {
        val client = SSHClient()
        client.connectTimeout = 15_000
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: java.security.PublicKey): Boolean {
                val actual = runCatching {
                    val blob = net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(key).getCompactData()
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
                    "SHA256:" + android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                }.getOrElse { key.toString() }
                val expected = host.hostKeyFingerprint
                return when {
                    expected.isNullOrEmpty() -> true // TOFU; caller persists
                    expected == actual -> true
                    else -> throw IllegalStateException(
                        "HOST KEY CHANGED for ${host.hostname}: expected $expected, got $actual",
                    )
                }
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        try {
            client.connect(host.hostname, host.port)
            when (host.authType) {
                "key" -> {
                    val f = File(host.keyPath ?: error("no key file set"))
                    val finder = PasswordUtils.createOneOff(host.keyPassphrase?.toCharArray() ?: charArrayOf())
                    val text = f.readText()
                    val provider: FileKeyProvider =
                        if (text.contains("BEGIN OPENSSH PRIVATE KEY")) OpenSSHKeyFile() else PKCS8KeyFile()
                    provider.init(f, finder)
                    client.authPublickey(host.username, provider)
                }

                else -> client.authPassword(host.username, host.password ?: "")
            }

            val session = client.startSession()
            val cmd = session.exec("TERM=xterm-256color LC_ALL=C.UTF-8 mosh-server new")

            val connectLine = readLineContaining(cmd.inputStream, "MOSH CONNECT", timeoutMs = 20_000)
                ?: throw IllegalStateException(
                    "mosh-server did not report MOSH CONNECT (is mosh installed on ${host.hostname}? locale UTF-8?)",
                )
            val parts = connectLine.split(" ")
            val idx = parts.indexOf("CONNECT")
            val port = parts[idx + 1]
            val key = parts[idx + 2]
            val ip = InetAddress.getByName(host.hostname).hostAddress ?: host.hostname

            // mosh-server keeps running detached from SSH; drop the tunnel.
            cmd.close()
            client.disconnect()
            Log.i(TAG, "mosh bootstrap ok: ip=$ip port=$port")
            MoshSession(ip = ip, port = port, key = key)
        } catch (e: Exception) {
            Log.w(TAG, "mosh bootstrap failed: ${e.message}")
            runCatching { client.disconnect() }
            throw e
        }
    }

    /** Reads lines until one contains [needle]; null on EOF/timeout. */
    private fun readLineContaining(input: InputStream, needle: String, timeoutMs: Long): String? {
        val lines = java.util.concurrent.LinkedBlockingQueue<String>()
        thread(isDaemon = true, name = "mosh-handshake") {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) {
                    if (sb.isNotEmpty()) lines.add(sb.toString())
                    break
                }
                if (b == '\n'.code) {
                    lines.add(sb.toString())
                    sb.clear()
                } else {
                    sb.append(b.toChar())
                }
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val l = lines.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            if (l.contains(needle)) return l
        }
        return null
    }
}
