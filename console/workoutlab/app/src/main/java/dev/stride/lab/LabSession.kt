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
    val displayHeartRate = DisplayHeartRate()
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

    /** Shared command validation for native UI and the authenticated LAN interface. */
    @Synchronized fun action(body: JSONObject) {
        check(!closed) { "Preview session stopped" }
        val currentTime = now()
        when (body.getString("action")) {
            "start" -> engine.start(body.getString("routine"), integer(body, "minutes", 1, 120).toInt(), currentTime)
            "startManual" -> engine.startManual(integer(body, "minutes", 1, 120).toInt(), currentTime)
            "pause" -> engine.pause()
            "stop" -> engine.stop()
            "resume" -> engine.resume(currentTime)
            "resumeManual" -> engine.resumeManual(currentTime)
            "manual" -> engine.manual(number(body, "speedMph"), number(body, "inclinePercent"))
            "sensor" -> selectSensor(WorkoutEngine.Sensor.valueOf(body.getString("source")))
            "simulate" -> setSimulatedBpm(integer(body, "bpm", 30, 240).toInt())
            "configure" -> {
                val maxSpeed = number(body, "maxSpeedMph")
                val maxIncline = number(body, "maxInclinePercent")
                val adaptive = body.get("adaptiveSpeed")
                require(adaptive is Boolean) { "adaptiveSpeed must be a boolean" }
                require(maxSpeed in .8..(19.31 / 1.609344)) { "Speed cap must be between 0.8 and about 12 MPH" }
                require(maxIncline in 0.0..15.0) { "Incline cap must be between 0 and 15 percent" }
                val previous = engine.config
                engine.reconfigure(previous.copy(baseSpeedMph = previous.baseSpeedMph.coerceIn(.8, maxSpeed),
                    minSpeedMph = .8, maxSpeedMph = maxSpeed, minIncline = 0.0,
                    maxIncline = maxIncline, adaptiveSpeed = adaptive))
            }
            else -> throw IllegalArgumentException("Unknown action")
        }
    }

    private fun number(body: JSONObject, key: String): Double {
        require(body.get(key) is Number) { "$key must be a number" }
        return body.getDouble(key).also { require(it.isFinite()) { "$key must be finite" } }
    }
    private fun integer(body: JSONObject, key: String, low: Long, high: Long): Long {
        val value = body.get(key)
        require(value is Int || value is Long) { "$key must be an integer" }
        return (value as Number).toLong().also { require(it in low..high) { "$key is outside the allowed range" } }
    }

    @Synchronized fun status(now: Long = now()): JSONObject {
        val view = engine.snapshot(now)
        return JSONObject()
            .put("previewOnly", true)
            .put("motorConnected", false)
            .put("displayHeartRate", displayHeartRate.snapshot(now))
            .put("state", view.state.name)
            .put("routine", view.routineId ?: JSONObject.NULL)
            .put("phase", view.phase)
            .put("elapsedSec", view.elapsedSec)
            .put("durationSec", view.durationSec)
            .put("proposedSpeedMph", view.speedMph)
            .put("proposedSpeedKph", WorkoutEngine.mphToKph(view.speedMph))
            .put("proposedInclinePercent", view.incline)
            .put("targetSpeedMph", view.targetSpeedMph)
            .put("targetInclinePercent", view.targetIncline)
            .put("manualSession", view.manualSession)
            .put("canResumeManual", view.canResumeManual)
            .put("distanceMiles", view.distanceMiles)
            .put("sessionId", view.sessionId)
            .put("heartRateSourceSequence", view.heartRateSourceSequence ?: JSONObject.NULL)
            .put("heartRateMeasuredAtMs", view.heartRateMeasuredAtMs ?: JSONObject.NULL)
            .put("heartRateAgeMs", view.heartRateMeasuredAtMs?.let { (now - it).coerceAtLeast(0) } ?: JSONObject.NULL)
            .put("heartRateSummarySource", view.sensor.name)
            .put("averageHeartRateBpm", view.averageHeartRateBpm ?: JSONObject.NULL)
            .put("peakHeartRateBpm", view.peakHeartRateBpm ?: JSONObject.NULL)
            .put("hrSamples", JSONArray().also { list -> view.hrSamples.forEach { sample ->
                list.put(JSONObject().put("elapsedSec", sample.elapsedSec).put("bpm", sample.bpm).put("source", sample.source.name).put("segmentId", sample.segmentId))
            } })
            .put("heartRateBpm", view.bpm ?: JSONObject.NULL)
            .put("heartRateFresh", view.sensorFresh)
            .put("sensor", view.sensor.name)
            .put("simulatedBpm", simulatedBpm)
            .put("reason", view.reason)
            .put("adaptiveSpeed", view.adaptiveSpeed)
            .put("targetBandDemoOnly", true)
            .put("targetLowBpm", engine.config.targetLowBpm)
            .put("targetHighBpm", engine.config.targetHighBpm)
            .put("limits", JSONObject()
                .put("baseSpeedMph", engine.config.baseSpeedMph)
                .put("minSpeedMph", engine.config.minSpeedMph)
                .put("maxSpeedMph", engine.config.maxSpeedMph)
                .put("maxInclinePercent", engine.config.maxIncline)
                .put("speedRateMphPerSec", engine.config.speedRateMphPerSec)
                .put("inclineRatePercentPerSec", engine.config.inclineRatePercentPerSec))
    }

    fun routines(): JSONArray = JSONArray().also { out ->
        WorkoutEngine.ROUTINES.forEach { routine ->
            out.put(JSONObject().put("id", routine.id).put("name", routine.name)
                .put("phases", JSONArray().also { phases -> routine.phases.forEach { phase ->
                    phases.put(JSONObject().put("label", phase.label).put("endFraction", phase.endFraction)
                        .put("speedFactor", phase.speedFactor).put("grade", phase.grade))
                } }))
        }
    }

    @Synchronized fun close() {
        closed = true
        engine.stop()
        clock.shutdownNow()
    }
}
