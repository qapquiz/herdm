package com.qapquiz.herdm.hc

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import com.qapquiz.herdm.R
import java.io.File
import java.util.UUID

class HomeActivity : AppCompatActivity() {
    private lateinit var hosts: MutableList<HostConfig>
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var watchToggle: MaterialSwitch
    private var pendingKeyTarget: TextView? = null
    private var dialogHost: HostConfig? = null

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("No notification permission: alerts stay silent")
        }

    private val keyPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val target = pendingKeyTarget ?: return@registerForActivityResult
        runCatching {
            val keysDir = File(filesDir, "keys").apply { mkdirs() }
            val out = File(keysDir, "${UUID.randomUUID()}.pem")
            contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
            target.text = out.absolutePath
            target.tag = out.absolutePath
        }.onFailure { toast("Could not copy key: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        applyInsets(findViewById(R.id.home_root))
        AgentWatch.provideContext(this)
        hosts = HostStore.load(this)
        list = findViewById(R.id.hosts)
        empty = findViewById(R.id.empty)
        watchToggle = findViewById(R.id.watch_toggle)
        list.adapter = HostAdapter()
        list.setOnItemClickListener { _, _, pos, _ -> confirmConnect(hosts[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            hostOptions(hosts[pos]); true
        }
        findViewById<View>(R.id.add).setOnClickListener { showHostDialog(null) }

        // Toggle reflects persisted state; when enabled and not running
        // (fresh process), restart the background watch on the last host.
        watchToggle.isChecked = AgentWatchService.enabled(this)
        watchToggle.setOnCheckedChangeListener { _, checked ->
            AgentWatchService.setEnabled(this, checked)
            if (checked) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val live = HcApp.connection?.takeIf { it.alive }?.hostConfig?.id
                AgentWatchService.start(this, live)
            } else {
                AgentWatchService.stop(this)
            }
        }
        if (AgentWatchService.enabled(this) && !AgentWatchService.running) {
            val live = HcApp.connection?.takeIf { it.alive }?.hostConfig?.id
            AgentWatchService.start(this, live)
        }

        render()
    }

    private fun render() {
        (list.adapter as HostAdapter).notifyDataSetChanged()
        empty.visibility = if (hosts.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (hosts.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class HostAdapter : BaseAdapter() {
        override fun getCount() = hosts.size
        override fun getItem(p: Int) = hosts[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(14), 0, dp(14))
                val t = TextView(context).apply { textSize = 16f }
                val s = TextView(context).apply { textSize = 13f; alpha = 0.6f }
                addView(t); addView(s)
                tag = arrayOf(t, s)
            }
            val views = row.tag as Array<TextView>
            val h = hosts[p]
            views[0].text = h.name
            views[1].text = "${h.username}@${h.hostname}:${h.port}  ·  ${h.authType}"
            return row
        }
    }

    private fun hostOptions(host: HostConfig) {
        val options = arrayOf("Connect", "Edit", "Forget host key", "Delete")
        AlertDialog.Builder(this)
            .setTitle(host.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> connect(host)
                    1 -> showHostDialog(host)
                    2 -> {
                        host.hostKeyFingerprint = null
                        HostStore.update(this, host)
                        toast("Host key forgotten")
                    }
                    3 -> {
                        hosts.removeAll { it.id == host.id }
                        HostStore.save(this, hosts)
                        render()
                    }
                }
            }
            .show()
    }

    private fun confirmConnect(host: HostConfig) = connect(host)

    private fun showHostDialog(existing: HostConfig?) {
        dialogHost = existing
        val view = layoutInflater.inflate(R.layout.dialog_host, null)
        val name = view.findViewById<TextInputEditText>(R.id.f_name)
        val hostname = view.findViewById<TextInputEditText>(R.id.f_hostname)
        val port = view.findViewById<TextInputEditText>(R.id.f_port)
        val user = view.findViewById<TextInputEditText>(R.id.f_user)
        val password = view.findViewById<TextInputEditText>(R.id.f_password)
        val keyPath = view.findViewById<TextView>(R.id.f_key_path)
        val passphrase = view.findViewById<TextInputEditText>(R.id.f_key_passphrase)
        val passwordLayout = view.findViewById<View>(R.id.f_password_layout)
        val keyLayout = view.findViewById<View>(R.id.f_key_layout)
        val radioPassword = view.findViewById<android.widget.RadioButton>(R.id.f_auth_password)
        val radioKey = view.findViewById<android.widget.RadioButton>(R.id.f_auth_key)
        val radioSsh = view.findViewById<android.widget.RadioButton>(R.id.f_transport_ssh)
        val radioMosh = view.findViewById<android.widget.RadioButton>(R.id.f_transport_mosh)

        fun syncAuth() {
            val useKey = radioKey.isChecked
            passwordLayout.visibility = if (useKey) View.GONE else View.VISIBLE
            keyLayout.visibility = if (useKey) View.VISIBLE else View.GONE
        }
        radioPassword.setOnCheckedChangeListener { _, _ -> syncAuth() }
        radioKey.setOnCheckedChangeListener { _, _ -> syncAuth() }

        if (existing != null) {
            name.setText(existing.name)
            hostname.setText(existing.hostname)
            port.setText(existing.port.toString())
            user.setText(existing.username)
            if (existing.authType == "key") radioKey.isChecked = true
            if (existing.transport == "mosh") radioMosh.isChecked = true
            password.setText(existing.password)
            keyPath.text = existing.keyPath
            keyPath.tag = existing.keyPath
            passphrase.setText(existing.keyPassphrase)
        }
        syncAuth()

        view.findViewById<View>(R.id.f_pick_key).setOnClickListener {
            pendingKeyTarget = keyPath
            keyPicker.launch(arrayOf("*/*"))
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add host" else "Edit host")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val picked = keyPath.tag as? String
                val authType = if (radioKey.isChecked) "key" else "password"
                val transport = if (radioMosh.isChecked) "mosh" else "ssh"
                val host = (existing ?: HostConfig(id = HostStore.newId(), name = "", hostname = "", port = 22, username = "", authType = authType, password = null, keyPath = null, keyPassphrase = null)).copy(
                    name = name.text.toString().ifBlank { hostname.text.toString() },
                    hostname = hostname.text.toString().trim(),
                    port = port.text.toString().toIntOrNull() ?: 22,
                    username = user.text.toString().trim(),
                    authType = authType,
                    transport = transport,
                    password = if (authType == "password") password.text?.toString() else null,
                    keyPath = if (authType == "key") picked else existing?.keyPath,
                    keyPassphrase = if (authType == "key") passphrase.text?.toString() else null,
                )
                if (host.hostname.isBlank() || host.username.isBlank()) {
                    toast("Hostname and user are required")
                    return@setPositiveButton
                }
                HostStore.update(this, host)
                hosts = HostStore.load(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectMosh(host: HostConfig) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Starting mosh to ${host.name}")
            .setView(TextView(this).apply {
                setPadding(dp(24), dp(16), dp(24), dp(8))
                text = "ssh → mosh-server new → UDP session"
            })
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            try {
                val m = MoshBootstrap.start(host)
                val fp = MoshBootstrap.negotiatedHostKey
                if (fp != null && host.hostKeyFingerprint == null) {
                    host.hostKeyFingerprint = fp
                    HostStore.update(this@HomeActivity, host)
                }
                dialog.dismiss()
                startActivity(
                    Intent(this@HomeActivity, MoshTerminalActivity::class.java)
                        .putExtra("ip", m.ip)
                        .putExtra("port", m.port)
                        .putExtra("key", m.key)
                        .putExtra("hostName", host.name),
                )
            } catch (e: Exception) {
                dialog.dismiss()
                AlertDialog.Builder(this@HomeActivity)
                    .setTitle("Mosh failed")
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun connect(host: HostConfig) {
        if (host.transport == "mosh") {
            connectMosh(host)
            return
        }
        val existing = HcApp.connection
        if (existing != null && existing.alive && existing.hostConfig == host) {
            // Already live (e.g. the background watch owns it): use as-is.
            AgentWatch.bind(existing)
            startActivity(
                Intent(this, TerminalsActivity::class.java)
                    .putExtra("hostId", host.id)
                    .putExtra("hostName", host.name),
            )
            return
        }
        // Replacing a connection (different host, or a dead shell): the
        // invariant is whoever replaces HcApp.connection stops the old one.
        if (existing != null) {
            existing.listener = null // no spurious "disconnected" toast
            AgentWatch.unbind(existing)
            existing.stop()
            HcApp.connection = null
        }
        val conn = HcConnection().also {
            HcApp.connection = it
            it.listener = object : HcConnection.Listener {
                override fun onDisconnected(reason: String?) {
                    runOnUiThread { toast("Disconnected: ${reason ?: "stream ended"}") }
                }
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Connecting to ${host.name}")
            .setView(TextView(this).apply { setPadding(dp(24), dp(16), dp(24), dp(8)); text = "SSH → ${host.hostname}:${host.port} → ${host.command}" })
            .setCancelable(false)
            .show()

        conn.start(
            host,
            onReady = { hello ->
                dialog.dismiss()
                // TOFU: persist the fingerprint accepted on first contact.
                val fp = conn.negotiatedHostKey
                if (fp != null && host.hostKeyFingerprint == null) {
                    host.hostKeyFingerprint = fp
                    HostStore.update(this, host)
                }
                AgentWatchService.rememberHost(this, host.id)
                AgentWatch.bind(conn)
                toast("connected: core ${hello.optString("version")} (gen ${hello.opt("generation")})")
                startActivity(
                    Intent(this, TerminalsActivity::class.java)
                        .putExtra("hostId", host.id)
                        .putExtra("hostName", host.name),
                )
            },
            onError = { msg ->
                dialog.dismiss()
                Log.w("hc-client", "connect error: $msg")
                if (conn.hostKeyMismatch) {
                    AlertDialog.Builder(this)
                        .setTitle("Host key changed")
                        .setMessage(msg)
                        .setPositiveButton("Accept new key") { _, _ ->
                            host.hostKeyFingerprint = conn.negotiatedHostKey
                            HostStore.update(this, host)
                            connect(host)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    AlertDialog.Builder(this).setTitle("Connect failed").setMessage(msg).setPositiveButton("OK", null).show()
                }
            },
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
