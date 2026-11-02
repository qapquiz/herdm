package com.qapquiz.herdm.hc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saved herdr-core hosts, persisted as JSON in filesDir. Secrets (password,
 * key passphrase) are stored unencrypted on purpose for this learning
 * project; the file lives in app-private storage.
 */
data class HostConfig(
    var id: String,
    var name: String,
    var hostname: String,
    var port: Int,
    var username: String,
    var authType: String, // "password" | "key"
    var password: String?,
    var keyPath: String?,
    var keyPassphrase: String?,
    var command: String = "hc connect",
    var session: String = "default",
    var hostKeyFingerprint: String? = null,
    var transport: String = "ssh", // "ssh" (herdr) | "mosh"
)

object HostStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "hosts.json")

    fun load(ctx: Context): MutableList<HostConfig> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HostConfig(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    hostname = o.getString("hostname"),
                    port = o.optInt("port", 22),
                    username = o.getString("username"),
                    authType = o.optString("authType", "password"),
                    password = o.optString("password", "").ifEmpty { null },
                    keyPath = o.optString("keyPath", "").ifEmpty { null },
                    keyPassphrase = o.optString("keyPassphrase", "").ifEmpty { null },
                    command = o.optString("command", "hc connect").ifEmpty { "hc connect" },
                    session = o.optString("session", "default").ifEmpty { "default" },
                    hostKeyFingerprint = o.optString("hostKeyFingerprint", "").ifEmpty { null },
                    transport = o.optString("transport", "ssh").ifEmpty { "ssh" },
                )
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun save(ctx: Context, hosts: List<HostConfig>) {
        val arr = JSONArray()
        for (h in hosts) {
            arr.put(
                JSONObject()
                    .put("id", h.id)
                    .put("name", h.name)
                    .put("hostname", h.hostname)
                    .put("port", h.port)
                    .put("username", h.username)
                    .put("authType", h.authType)
                    .put("password", h.password ?: "")
                    .put("keyPath", h.keyPath ?: "")
                    .put("keyPassphrase", h.keyPassphrase ?: "")
                    .put("command", h.command)
                    .put("session", h.session)
                    .put("hostKeyFingerprint", h.hostKeyFingerprint ?: "")
                    .put("transport", h.transport),
            )
        }
        file(ctx).writeText(arr.toString(2))
    }

    fun update(ctx: Context, host: HostConfig) {
        val hosts = load(ctx)
        val i = hosts.indexOfFirst { it.id == host.id }
        if (i >= 0) hosts[i] = host else hosts.add(host)
        save(ctx, hosts)
    }

    fun newId() = "h" + System.currentTimeMillis().toString(36)
}
