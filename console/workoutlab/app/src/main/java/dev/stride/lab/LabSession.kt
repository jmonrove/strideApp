package dev.stride.lab

import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Owns the preview clock and an isolated input stream. No dependency on the live STRIDE activity. */
class LabSession(
    val engine: WorkoutEngine = WorkoutEngine(),
    private val monotonicNow: () -> Long = { SystemClock.elapsedRealtime() },
    startClock: Boolean = true,
) {
    val phoneInput = HeartRateIngress(engine)
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private val clock = Executors.newSingleThreadScheduledExecutor()
    @Volatile var simulatedBpm = 120
        private set
    @Volatile private var simulatedInput = true
    private var simulationSequence = 0L
    @Volatile private var closed = false

    init {
        if (startClock) clock.scheduleWithFixedDelay({
            if (!closed) {
                val now = now()
                if (simulatedInput) engine.acceptHeartRate(simulatedBpm, now,
                    simulationSequence++, WorkoutEngine.Sensor.SIMULATED)
                engine.tick(now)
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    fun now(): Long = monotonicNow()

    fun authorized(candidate: String?): Boolean = candidate != null && MessageDigest.isEqual(
        token.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))

    @Synchronized fun selectSensor(sensor: WorkoutEngine.Sensor) {
        simulatedInput = sensor == WorkoutEngine.Sensor.SIMULATED
        engine.selectSensor(sensor)
    }

    @Synchronized fun setSimulatedBpm(bpm: Int) {
        require(bpm in 30..240)
        simulatedBpm = bpm
    }

    fun status(now: Long = now()): JSONObject {
        val view = engine.snapshot(now)
        return JSONObject()
            .put("previewOnly", true)
            .put("motorConnected", false)
            .put("state", view.state.name)
            .put("routine", view.routineId ?: JSONObject.NULL)
            .put("phase", view.phase)
            .put("elapsedSec", view.elapsedSec)
            .put("durationSec", view.durationSec)
            .put("proposedSpeedMph", view.speedMph)
            .put("proposedSpeedKph", WorkoutEngine.mphToKph(view.speedMph))
            .put("proposedInclinePercent", view.incline)
            .put("heartRateBpm", view.bpm ?: JSONObject.NULL)
            .put("heartRateFresh", view.sensorFresh)
            .put("sensor", view.sensor.name)
            .put("reason", view.reason)
            .put("adaptiveSpeed", view.adaptiveSpeed)
            .put("targetBandDemoOnly", true)
            .put("targetLowBpm", engine.config.targetLowBpm)
            .put("targetHighBpm", engine.config.targetHighBpm)
            .put("limits", JSONObject()
                .put("maxSpeedMph", engine.config.maxSpeedMph)
                .put("maxInclinePercent", engine.config.maxIncline)
                .put("speedRateMphPerSec", engine.config.speedRateMphPerSec)
                .put("inclineRatePercentPerSec", engine.config.inclineRatePercentPerSec))
    }

    fun routines(): JSONArray = JSONArray().also { out ->
        WorkoutEngine.ROUTINES.forEach { out.put(JSONObject().put("id", it.id).put("name", it.name)) }
    }

    fun close() {
        closed = true
        engine.stop()
        clock.shutdownNow()
    }
}
