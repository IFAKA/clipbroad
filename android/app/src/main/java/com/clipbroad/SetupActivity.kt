package com.clipbroad

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.inputmethod.InputMethodManager

private const val CLIPBROAD_IME_ID = "com.clipbroad/.ClipBroadIme"

private data class StepCard(
    val root: LinearLayout,
    val state: TextView,
    val body: TextView,
)

class SetupActivity : Activity() {
    private companion object {
        const val REQUEST_DISCOVERY_PERMISSION = 1001
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var secretField: EditText
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button
    private lateinit var refreshButton: Button
    private lateinit var stepOne: StepCard
    private lateinit var stepTwo: StepCard
    private lateinit var stepThree: StepCard
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            refreshUi()
            refreshHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs = getSharedPreferences("clipbroad", Context.MODE_PRIVATE)

        status = TextView(this).apply {
            textSize = 20f
            setPadding(0, 0, 0, 24)
        }

        secretField = EditText(this).apply {
            hint = "Pairing code"
            setText(prefs.getString("secret", "") ?: "")
            inputType = 0x81
        }

        stepOne = makeStepCard("1. Enter code")
        stepTwo = makeStepCard("2. Enable keyboard")
        stepThree = makeStepCard("3. Connected")

        primaryButton = Button(this)
        secondaryButton = Button(this)
        refreshButton = Button(this)

        primaryButton.setOnClickListener {
            if (!hasDiscoveryPermission()) {
                requestDiscoveryPermission()
                return@setOnClickListener
            }
            prefs.edit().putString("secret", secretField.text.toString()).apply()
            refreshUi()
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        secondaryButton.setOnClickListener {
            secretField.requestFocus()
        }

        refreshButton.text = "Refresh status"
        refreshButton.setOnClickListener { refreshUi() }

        val codeTitle = TextView(this).apply {
            text = "Pairing code"
            setPadding(0, 16, 0, 8)
        }

        val codeHelper = TextView(this).apply {
            text = "Use the same code on the Mac. For testing, `123` is fine."
            setPadding(0, 0, 0, 16)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(status)
            addView(stepOne.root)
            addView(codeTitle)
            addView(secretField)
            addView(codeHelper)
            addView(primaryButton)
            addView(secondaryButton)
            addView(refreshButton)
            addView(stepTwo.root)
            addView(stepThree.root)
        }

        val scroll = ScrollView(this)
        scroll.addView(body)
        setContentView(scroll)

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.removeCallbacks(refreshTick)
        refreshHandler.post(refreshTick)
        refreshUi()
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshTick)
        super.onPause()
    }

    private fun refreshUi() {
        val savedSecret = prefs.getString("secret", "").orEmpty()
        val connected = prefs.getBoolean("connected", false)
        val lastUpdate = prefs.getLong("last_connection_update", 0L)
        val bridgeState = prefs.getString("bridge_state", "").orEmpty()
        val bridgeError = prefs.getString("bridge_error", "").orEmpty()
        val hasSecret = savedSecret.isNotBlank()
        val discoveryPermissionGranted = hasDiscoveryPermission()
        val keyboardEnabled = isClipBroadKeyboardEnabled()
        val keyboardSelected = isClipBroadKeyboardDefault()
        val keyboardReady = keyboardEnabled && keyboardSelected

        status.text = when {
            !discoveryPermissionGranted -> "Allow nearby device access so ClipBroad can find the Mac."
            connected -> "Paired and connected."
            hasSecret && keyboardReady && bridgeError.isNotBlank() -> bridgeError
            hasSecret && keyboardReady && bridgeState.isNotBlank() -> bridgeState
            hasSecret && keyboardReady -> "Code saved. Keyboard ready. Waiting for the Mac connection."
            hasSecret -> "Code saved. Enable ClipBroad Keyboard next."
            else -> "Setup not finished."
        }

        stepOne.state.text = if (hasSecret) "Done" else "Pending"
        stepOne.body.text = if (hasSecret) {
            "Saved code: $savedSecret"
        } else {
            "Enter the same code that is set on the Mac, then save it here."
        }

        stepTwo.state.text = if (keyboardReady) "Done" else "Pending"
        stepTwo.body.text = when {
            keyboardReady -> "ClipBroad Keyboard is enabled and selected."
            keyboardEnabled -> "ClipBroad Keyboard is enabled. Set it as the current keyboard too."
            else -> "Open Android keyboard settings and enable ClipBroad Keyboard."
        }

        stepThree.state.text = if (connected) "Done" else "Pending"
        stepThree.body.text = when {
            !discoveryPermissionGranted -> "Grant nearby device access so the phone can discover the Mac on Wi‑Fi."
            connected -> "The phone is connected to the Mac."
            bridgeError.isNotBlank() -> bridgeError
            bridgeState.isNotBlank() -> bridgeState
            keyboardReady -> "Keyboard is ready. Return here and wait for the Mac connection."
            lastUpdate > 0L -> "Waiting for the Mac to connect."
            else -> "This turns on only after the keyboard is enabled and the Mac is online."
        }

        primaryButton.text = when {
            !discoveryPermissionGranted -> "Allow nearby device access"
            !hasSecret -> "Save code and open keyboard settings"
            !keyboardReady -> "Open keyboard settings"
            else -> "Open keyboard settings"
        }

        secondaryButton.visibility = if (hasSecret) View.VISIBLE else View.GONE
        secondaryButton.text = if (hasSecret) "Change code" else ""
    }

    private fun isClipBroadKeyboardEnabled(): Boolean {
        val imm = getSystemService(InputMethodManager::class.java)
        return imm.enabledInputMethodList.any { it.getId() == CLIPBROAD_IME_ID }
    }

    private fun isClipBroadKeyboardDefault(): Boolean {
        val imm = getSystemService(InputMethodManager::class.java)
        val current = imm.currentInputMethodInfo
        return current?.getId() == CLIPBROAD_IME_ID
    }

    private fun hasDiscoveryPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestDiscoveryPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), REQUEST_DISCOVERY_PERMISSION)
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_DISCOVERY_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_DISCOVERY_PERMISSION) {
            refreshUi()
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putString("secret", secretField.text.toString()).apply()
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }
    }

    private fun makeStepCard(title: String): StepCard {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setPadding(0, 0, 16, 0)
        }

        val stateView = TextView(this).apply {
            text = "Pending"
            textSize = 14f
        }

        topRow.addView(titleView)
        topRow.addView(stateView)

        val bodyView = TextView(this).apply {
            text = ""
            setPadding(0, 8, 0, 0)
        }

        root.addView(topRow)
        root.addView(bodyView)
        return StepCard(root, stateView, bodyView)
    }
}
