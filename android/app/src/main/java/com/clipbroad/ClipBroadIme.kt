package com.clipbroad

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ClipBroadIme : InputMethodService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private companion object {
        const val PREF_STATE = "bridge_state"
        const val PREF_ERROR = "bridge_error"
    }

    private var socket: Socket? = null
    private var writer: DataOutputStream? = null
    private lateinit var clipboard: ClipboardManager
    private lateinit var prefs: SharedPreferences
    private var secret = ""
    private var ignoreNextClipboardEvent = false
    private val origin = UUID.randomUUID().toString()
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveredHost: String? = null
    private var discoveredPort: Int = -1
    private var keyboardView: LinearLayout? = null
    private var statusText: TextView? = null
    private val letterButtons = mutableListOf<Pair<Button, Char>>()
    private var shiftOn = false

    override fun onCreate() {
        super.onCreate()

        prefs = getSharedPreferences("clipbroad", 0)
        prefs.registerOnSharedPreferenceChangeListener(this)
        reloadSecret()

        clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener {
            if (!isLocked() && clipboard.hasPrimaryClip()) {
                if (ignoreNextClipboardEvent) {
                    ignoreNextClipboardEvent = false
                } else {
                    sendLocal()
                }
            }
        }

        discover()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        stopDiscovery()
        closeConnection()
        super.onDestroy()
    }

    override fun onCreateInputView(): View? {
        keyboardView?.let { return it }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        statusText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        root.addView(statusText)

        root.addView(makeRow("1234567890".map { it.toString() }))
        root.addView(makeRow("qwertyuiop".map { it.toString() }))
        root.addView(makeRow("asdfghjkl".map { it.toString() }))
        root.addView(
            makeRow(
                listOf("Shift", "z", "x", "c", "v", "b", "n", "m", "⌫")
            )
        )
        root.addView(
            makeRow(
                listOf("Space", "Enter"),
                weights = listOf(5f, 2f)
            )
        )

        keyboardView = root
        refreshKeyboardStatus()
        refreshKeyLabels()
        return root
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == "secret") {
            reloadSecret()
        }
    }

    private fun reloadSecret() {
        secret = prefs.getString("secret", "") ?: ""
        if (secret.isNotEmpty()) {
            setBridgeState("Secret saved. Discovering Mac on Wi‑Fi…")
            reconnectToDiscoveredService()
        } else {
            setBridgeState("No pairing code saved.")
            closeConnection()
        }
    }

    private fun discover() {
        if (discoveryListener != null) return

        val manager = getSystemService(NSD_SERVICE) as NsdManager
        nsdManager = manager

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {
                setBridgeState("Discovery stopped.")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                setBridgeError("Discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                setBridgeError("Discovery stop failed: $errorCode")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                setBridgeState("Found ${info.serviceName}. Resolving…")
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        setBridgeError("Resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        discoveredHost = serviceInfo.host?.hostAddress
                        discoveredPort = serviceInfo.port
                        setBridgeState("Resolved ${discoveredHost ?: "unknown"}:${discoveredPort}. Connecting…")
                        reconnectToDiscoveredService()
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                discoveredHost = null
                discoveredPort = -1
                setBridgeState("Mac service lost.")
                closeConnection()
            }
        }

        discoveryListener = listener
        setBridgeState("Discovering ClipBroad Mac service…")
        manager.discoverServices("_clipbroad._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsdManager?.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
    }

    private fun reconnectToDiscoveredService() {
        val host = discoveredHost
        val port = discoveredPort
        if (host == null || port <= 0 || secret.isEmpty()) return
        if (socket?.isConnected == true) return
        closeConnection()
        setBridgeState("Connecting to $host:$port…")
        connect(host, port)
    }

    private fun connect(host: String?, port: Int) {
        if (host == null || secret.isEmpty()) return
        Thread {
            try {
                val s = Socket(host, port)
                socket = s
                writer = DataOutputStream(s.getOutputStream())
                setConnectionState(true)
                setBridgeState("Paired and connected.")
                receive(s)
            } catch (_: Exception) {
                setBridgeError("Connection failed to $host:$port")
                closeConnection()
            }
        }.start()
    }

    private fun receive(s: Socket) {
        try {
            val input = DataInputStream(s.getInputStream())
            while (!s.isClosed) {
                val n = input.readInt()
                if (n !in 1..1048576) break
                val bytes = ByteArray(n)
                input.readFully(bytes)
                val obj = JSONObject(String(decrypt(bytes)))
                if (obj.getString("origin") != origin) {
                    Handler(Looper.getMainLooper()).post { apply(obj) }
                }
            }
        } catch (_: Exception) {
        } finally {
            closeConnection()
        }
    }

    private fun closeConnection() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        writer = null
        setConnectionState(false)
        refreshKeyboardStatus()
    }

    private fun setConnectionState(connected: Boolean) {
        prefs.edit()
            .putBoolean("connected", connected)
            .putLong("last_connection_update", System.currentTimeMillis())
            .apply()
        refreshKeyboardStatus()
    }

    private fun setBridgeState(message: String) {
        prefs.edit()
            .putString(PREF_STATE, message)
            .remove(PREF_ERROR)
            .apply()
        refreshKeyboardStatus()
    }

    private fun setBridgeError(message: String) {
        prefs.edit()
            .putString(PREF_ERROR, message)
            .putString(PREF_STATE, message)
            .apply()
        refreshKeyboardStatus()
    }

    private fun refreshKeyboardStatus() {
        val connected = prefs.getBoolean("connected", false)
        val state = prefs.getString(PREF_STATE, null).orEmpty()
        val error = prefs.getString(PREF_ERROR, null).orEmpty()
        statusText?.text = when {
            connected -> "ClipBroad: connected"
            error.isNotBlank() -> "ClipBroad: $error"
            state.isNotBlank() -> "ClipBroad: $state"
            else -> "ClipBroad: ready"
        }
    }

    private fun refreshKeyLabels() {
        letterButtons.forEach { (button, ch) ->
            button.text = if (shiftOn) ch.uppercaseChar().toString() else ch.toString()
        }
    }

    private fun makeRow(labels: List<String>, weights: List<Float>? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        labels.forEachIndexed { index, label ->
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener {
                    when (label) {
                        "Shift" -> {
                            shiftOn = !shiftOn
                            refreshKeyLabels()
                            refreshKeyboardStatus()
                        }
                        "⌫" -> currentInputConnection?.deleteSurroundingText(1, 0)
                        "Space" -> commitText(" ")
                        "Enter" -> commitText("\n")
                        else -> {
                            val out = if (shiftOn && label.length == 1 && label[0].isLetter()) label.uppercase() else label
                            commitText(out)
                            if (shiftOn && label.length == 1 && label[0].isLetter()) {
                                shiftOn = false
                                refreshKeyLabels()
                            }
                        }
                    }
                }
            }

            if (label.length == 1 && label[0].isLetter()) {
                letterButtons.add(button to label[0])
            }

            val weight = weights?.getOrNull(index) ?: 1f
            row.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                    marginEnd = dp(4)
                    topMargin = dp(4)
                }
            )
        }
        return row
    }

    private fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    override fun onWindowShown() {
        super.onWindowShown()
        refreshKeyboardStatus()
    }

    private fun sendLocal() {
        val clip = clipboard.primaryClip ?: return
        val item = clip.getItemAt(0)
        val uri = item.uri

        if (uri != null && clip.description?.hasMimeType("image/*") == true) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
                send(
                    JSONObject()
                        .put("kind", "image")
                        .put("mime", clip.description?.getMimeType(0) ?: "image/png")
                        .put("data", Base64.getEncoder().encodeToString(bytes))
                        .put("origin", origin)
                )
                return
            } catch (_: Exception) {
            }
        }

        val text = item.coerceToText(this).toString()
        if (TextUtils.isEmpty(text) || isSensitive(text)) return
        send(
            JSONObject()
                .put("kind", "text")
                .put("mime", "text/plain")
                .put("data", Base64.getEncoder().encodeToString(text.toByteArray()))
                .put("origin", origin)
        )
    }

    private fun apply(obj: JSONObject) {
        if (isLocked()) return

        val data = Base64.getDecoder().decode(obj.getString("data"))
        ignoreNextClipboardEvent = true
        if (obj.getString("kind") == "text") {
            val clip = ClipData.newPlainText("ClipBroad", String(data))
            markSensitiveIfNeeded(clip)
            clipboard.setPrimaryClip(clip)
        } else {
            try {
                val dir = File(cacheDir, "clipboard")
                dir.mkdirs()
                val file = File(dir, "incoming-image")
                file.writeBytes(data)
                val uri = FileProvider.getUriForFile(this, "com.clipbroad.files", file)
                clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "ClipBroad", uri))
            } catch (_: Exception) {
            }
        }
    }

    private fun isSensitive(text: String): Boolean {
        return Regex("(?i)(password|token|secret)\\s*[:=]").containsMatchIn(text) ||
            Regex("^sk-[A-Za-z0-9_-]{20,}$").matches(text)
    }

    private fun markSensitiveIfNeeded(clip: ClipData) {
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
    }

    private fun isLocked(): Boolean {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        return if (Build.VERSION.SDK_INT >= 23) km.isDeviceLocked else km.isKeyguardLocked
    }

    private fun send(obj: JSONObject) {
        Thread {
            try {
                val b = encrypt(obj.toString().toByteArray())
                writer?.writeInt(b.size)
                writer?.write(b)
                writer?.flush()
            } catch (_: Exception) {
                closeConnection()
            }
        }.start()
    }

    private fun key(): SecretKeySpec {
        val d = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
        return SecretKeySpec(d, "AES")
    }

    private fun encrypt(p: ByteArray): ByteArray {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return iv + c.doFinal(p)
    }

    private fun decrypt(p: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, p.copyOfRange(0, 12)))
        return c.doFinal(p.copyOfRange(12, p.size))
    }
}
