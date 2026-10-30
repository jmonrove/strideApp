package dev.stride.lab

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Typeface
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

class WorkoutLabActivity : Activity() {
    private lateinit var session: LabSession
    private var server: PreviewServer? = null
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val paint = object : Runnable {
        override fun run() {
            if (!::session.isInitialized) return
            val s = session.engine.snapshot(SystemClock.elapsedRealtime())
            val updatedStatus = String.format(Locale.US,
                "%s · %s\nProposed %.2f MPH · %.1f%% grade\nHR %s · %s · %s\n%.0f / %.0f seconds\n%s",
                s.state, s.phase, s.speedMph, s.incline,
                s.bpm?.let { "%.0f".format(Locale.US, it) } ?: "—",
                s.sensor, if (s.sensorFresh) "fresh" else "waiting / stale", s.elapsedSec, s.durationSec, s.reason)
            if (status.text.toString() != updatedStatus) status.text = updatedStatus
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val cfg = try {
            WorkoutEngine.Config(
                baseSpeedMph = extraDouble("baseSpeedMph", 2.0),
                maxSpeedMph = extraDouble("maxSpeedMph", 3.0),
                maxIncline = extraDouble("maxInclinePercent", 4.0),
                targetLowBpm = intent.getIntExtra("targetLowBpm", 110),
                targetHighBpm = intent.getIntExtra("targetHighBpm", 130),
                adaptiveSpeed = intent.getBooleanExtra("adaptiveSpeed", false),
            )
        } catch (e: IllegalArgumentException) {
            setContentView(TextView(this).apply { text = "Invalid preview configuration: ${e.message}" })
            return
        }
        session = LabSession(WorkoutEngine(cfg))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 20) }
        fun label(text: String, size: Float = 17f) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8); root.addView(this) }
        label("STRIDE Workout Lab — SIMULATION ONLY", 24f)
        label("No motor connection. All speed and grade values are proposals.\nDemo target ${cfg.targetLowBpm}–${cfg.targetHighBpm} BPM; this is not a training prescription.")
        status = label("Starting preview clock…").apply { typeface = Typeface.MONOSPACE }
        val routines = Spinner(this).apply {
            adapter = ArrayAdapter(this@WorkoutLabActivity, android.R.layout.simple_spinner_dropdown_item,
                WorkoutEngine.ROUTINES.map { it.name })
        }
        root.addView(routines)
        val row = LinearLayout(this)
        fun button(title: String, action: () -> Unit) {
            row.addView(Button(this).apply { text = title; setOnClickListener {
                try { action() } catch (e: IllegalArgumentException) { toast(e.message) }
                catch (e: IllegalStateException) { toast(e.message) }
            } })
        }
        button("Start 20-min preview") { session.engine.start(WorkoutEngine.ROUTINES[routines.selectedItemPosition].id, 20, SystemClock.elapsedRealtime()) }
        button("Pause") { session.engine.pause() }
        button("Resume auto") { session.engine.resume(SystemClock.elapsedRealtime()) }
        button("STOP preview") { session.engine.stop() }
        root.addView(row)
        val sensorRow = LinearLayout(this)
        sensorRow.addView(Button(this).apply { text = "Use simulated HR"; setOnClickListener { session.selectSensor(WorkoutEngine.Sensor.SIMULATED) } })
        sensorRow.addView(Button(this).apply { text = "Use Sense 2 bridge"; setOnClickListener { session.selectSensor(WorkoutEngine.Sensor.PHONE) } })
        root.addView(sensorRow)
        val bpmLabel = label("Simulated heart rate: 120 BPM")
        root.addView(SeekBar(this).apply {
            max = 210; progress = 90
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    session.setSimulatedBpm(progress + 30); bpmLabel.text = "Simulated heart rate: ${progress + 30} BPM"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { }
                override fun onStopTrackingTouch(bar: SeekBar?) { }
            })
        })
        label("Phone preview: connect the phone to the same Wi-Fi, open the address below, then paste the pairing token. A new token is generated whenever this lab session opens.")
        val network = label("Starting local preview server…").apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE }
        try {
            val page = assets.open("phone.html").bufferedReader().use { it.readText() }
            server = PreviewServer(session, page).also { it.start() }
            val ips = Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.map { "http://${it.hostAddress}:8780/" }
            network.text = "${ips.joinToString("\n").ifEmpty { "No LAN address found" }}\n\nPairing token:\n${session.token}"
        } catch (e: Exception) { network.text = "Phone preview unavailable: ${e.message}. Native simulation still works." }
        setContentView(ScrollView(this).apply { addView(root) })
        handler.post(paint)
    }
    @Suppress("DEPRECATION")
    private fun extraDouble(key: String, fallback: Double): Double = (intent.extras?.get(key) as? Number)?.toDouble() ?: fallback
    private fun toast(text: String?) = Toast.makeText(this, text ?: "Action rejected", Toast.LENGTH_LONG).show()
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        server?.close()
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
}
