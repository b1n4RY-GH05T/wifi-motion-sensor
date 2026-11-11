package nz.phantom.wifimotionsensor

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var rssiText: TextView
    private lateinit var frequencyText: TextView
    private lateinit var variationText: TextView
    private lateinit var serverInput: EditText
    private lateinit var portInput: EditText
    private lateinit var thresholdInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var sendSamplesCheck: CheckBox

    private var running = false
    private var baseline = Double.NaN
    private val recent = ArrayDeque<Int>()
    private var lastEventMs = 0L
    private var sampleCount = 0

    private val sampler = object : Runnable {
        override fun run() {
            if (running) {
                sampleWifi()
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 22, 26, 18)
            setBackgroundColor(0xFFF2F5F9.toInt())
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(content)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(text("REDMI WI-FI MOTION SENSOR", 22f, true))
        content.addView(text("RSSI prototype • Android 15 • 5 GHz Wi-Fi", 14f, false))

        statusText = text(
            "Stopped. Connect to your eero 6 Wi-Fi, then configure the ThinkPad receiver.",
            15f,
            true
        ).apply {
            setPadding(0, 18, 0, 14)
        }

        content.addView(statusText)

        rssiText = metric("Wi-Fi signal: — dBm")
        frequencyText = metric("Wi-Fi frequency: —")
        variationText = metric("Recent signal range: — dB")

        content.addView(rssiText)
        content.addView(frequencyText)
        content.addView(variationText)

        content.addView(section("THINKPAD RECEIVER"))

        serverInput = edit("ThinkPad IPv4 address", "192.168.4.25")
        portInput = edit("Receiver port", "8765")

        content.addView(serverInput)
        content.addView(portInput)

        content.addView(section("DETECTION"))

        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Area disturbance (experimental)",
                    "Doorway crossing (not implemented in RSSI v1)"
                )
            )
        }

        content.addView(mode)

        thresholdInput = edit("Movement threshold in dB (start with 5)", "5")
        content.addView(thresholdInput)

        sendSamplesCheck = CheckBox(this).apply {
            text = "Send live signal samples to ThinkPad dashboard"
            isChecked = true
        }

        content.addView(sendSamplesCheck)

        content.addView(
            text(
                "This app reads the current connected Wi-Fi RSSI. " +
                    "It does not scan networks, use the camera, or use the microphone. " +
                    "RSSI may update slowly or in coarse steps; this is an experiment, " +
                    "not a guaranteed security sensor.",
                13f,
                false
            ).apply {
                setPadding(0, 12, 0, 12)
            }
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        startButton = Button(this).apply {
            text = "START MONITORING"
            setOnClickListener {
                startMonitoring()
            }
        }

        stopButton = Button(this).apply {
            text = "STOP"
            isEnabled = false
            setOnClickListener {
                stopMonitoring()
            }
        }

        buttons.addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f))

        content.addView(buttons)

        setContentView(outer)
    }

    private fun text(s: String, size: Float, bold: Boolean): TextView {
        return TextView(this).apply {
            text = s
            textSize = size
            setTextColor(0xFF172435.toInt())

            if (bold) {
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
        }
    }

    private fun metric(s: String): TextView {
        return text(s, 19f, true).apply {
            setPadding(0, 8, 0, 8)
        }
    }

    private fun section(s: String): TextView {
        return text(s, 14f, true).apply {
            setPadding(0, 18, 0, 6)
        }
    }

    // FIXED: use setSingleLine(true) instead of singleLine = true
    private fun edit(h: String, value: String): EditText {
        return EditText(this).apply {
            hint = h
            setText(value)
            textSize = 15f
            setSingleLine(true)
            setPadding(12, 8, 12, 8)
        }
    }

    private fun startMonitoring() {
        if (serverInput.text.toString().trim().isEmpty()) {
            statusText.text = "Enter your ThinkPad's local IPv4 address."
            return
        }

        running = true
        baseline = Double.NaN
        recent.clear()
        sampleCount = 0
        lastEventMs = 0L

        startButton.isEnabled = false
        stopButton.isEnabled = true

        statusText.text = "Monitoring started. Keep this screen open and awake."

        handler.post(sampler)
    }

    private fun stopMonitoring() {
        running = false
        handler.removeCallbacks(sampler)

        if (::startButton.isInitialized) {
            startButton.isEnabled = true
        }

        if (::stopButton.isInitialized) {
            stopButton.isEnabled = false
        }

        if (::statusText.isInitialized) {
            statusText.text = "Monitoring stopped."
        }
    }

    @Suppress("DEPRECATION")
    private fun currentWifiInfo(): WifiInfo? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null

        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return null
        }

        return caps.transportInfo as? WifiInfo
    }

    private fun sampleWifi() {
        val info = currentWifiInfo()

        if (info == null) {
            statusText.text =
                "No active Wi-Fi connection information. Check Wi-Fi is enabled and connected."
            return
        }

        val rssi = info.rssi
        val frequency = info.frequency

        if (rssi <= -127 || rssi == 0) {
            statusText.text =
                "Android has not supplied a valid RSSI reading yet."
            return
        }

        sampleCount++

        recent.addLast(rssi)

        while (recent.size > 8) {
            recent.removeFirst()
        }

        val range = if (recent.size >= 2) {
            recent.maxOrNull()!! - recent.minOrNull()!!
        } else {
            0
        }

        if (baseline.isNaN()) {
            baseline = rssi.toDouble()
        }

        val delta = abs(rssi - baseline)
        baseline = baseline * 0.92 + rssi * 0.08

        rssiText.text = "Wi-Fi signal: $rssi dBm"
        frequencyText.text = "Wi-Fi frequency: ${frequency} MHz"
        variationText.text = "Recent signal range: $range dB"

        statusText.text =
            "Connected Wi-Fi RSSI sampling • $sampleCount samples • 1 sample/second"

        val threshold = thresholdInput.text.toString()
            .toDoubleOrNull()
            ?.coerceIn(2.0, 20.0) ?: 5.0

        val candidate = recent.size >= 4 &&
            range >= threshold &&
            delta >= threshold * 0.45

        val now = System.currentTimeMillis()

        if (candidate && now - lastEventMs >= 5000L) {
            lastEventMs = now

            statusText.text =
                "POSSIBLE SIGNAL DISTURBANCE — not proof of a person."

            sendPayload("event", rssi, frequency, range, delta)
        }

        if (sendSamplesCheck.isChecked) {
            sendPayload("sample", rssi, frequency, range, delta)
        }
    }

    private fun sendPayload(
        type: String,
        rssi: Int,
        frequency: Int,
        range: Int,
        delta: Double
    ) {
        val host = serverInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: 8765

        val body = JSONObject().apply {
            put("type", type)
            put("timestamp_ms", System.currentTimeMillis())
            put("rssi_dbm", rssi)
            put("frequency_mhz", frequency)
            put("range_db", range)
            put("baseline_delta_db", delta)
            put("sample_count", sampleCount)

            if (type == "event") {
                put("event", "possible_rssi_disturbance")
            }
        }.toString()

        networkExecutor.execute {
            var conn: HttpURLConnection? = null

            try {
                conn = (
                    URL("http://$host:$port/api/$type").openConnection()
                    as HttpURLConnection
                ).apply {
                    requestMethod = "POST"
                    connectTimeout = 1500
                    readTimeout = 1500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }

                conn.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode

                if (code !in 200..299) {
                    runOnUiThread {
                        statusText.text =
                            "Receiver returned HTTP $code. Check IP and port."
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    if (running) {
                        statusText.text =
                            "Receiver unreachable (${e.javaClass.simpleName}). " +
                                "Check IP, firewall and server."
                    }
                }

            } finally {
                conn?.disconnect()
            }
        }
    }

    override fun onDestroy() {
        stopMonitoring()
        networkExecutor.shutdownNow()
        super.onDestroy()
    }
}
