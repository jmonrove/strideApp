package dev.stride.lab

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Proposed setpoints only. This package has no USB or motor-command implementation. */
class WorkoutEngine(val config: Config = Config()) {
    data class Config(
        val baseSpeedMph: Double = 2.0,
        val minSpeedMph: Double = 0.8,
        val maxSpeedMph: Double = 3.0,
        val minIncline: Double = 0.0,
        val maxIncline: Double = 4.0,
        val targetLowBpm: Int = 110,
        val targetHighBpm: Int = 130,
        val speedRateMphPerSec: Double = 0.05,
        val inclineRatePercentPerSec: Double = 0.10,
        val decisionIntervalMs: Long = 10_000,
        val settlingMs: Long = 30_000,
        val freshForMs: Long = 5_000,
        val adaptiveSpeed: Boolean = false,
    ) {
        init {
            val values = listOf(baseSpeedMph, minSpeedMph, maxSpeedMph, minIncline,
                maxIncline, speedRateMphPerSec, inclineRatePercentPerSec)
            require(values.all { it.isFinite() }) { "Limits must be finite" }
            require(minSpeedMph > 0 && maxSpeedMph >= minSpeedMph)
            require(baseSpeedMph in minSpeedMph..maxSpeedMph)
            require(minIncline <= 0 && maxIncline >= 0 && maxIncline >= minIncline)
            require(targetLowBpm in 30..239 && targetHighBpm in (targetLowBpm + 1)..240)
            require(speedRateMphPerSec > 0 && inclineRatePercentPerSec > 0)
            require(decisionIntervalMs >= 1_000 && settlingMs >= decisionIntervalMs)
            require(freshForMs in 1_000..10_000)
        }
    }

    enum class State { IDLE, RUNNING, SENSOR_HOLD, MANUAL, PAUSED, STOPPED, COMPLETE }
    enum class Sensor { SIMULATED, PHONE }
    data class Phase(val label: String, val endFraction: Double, val speedFactor: Double, val grade: Double)
    data class Routine(val id: String, val name: String, val phases: List<Phase>)
    data class View(
        val state: State, val routineId: String?, val phase: String, val elapsedSec: Double,
        val durationSec: Double, val speedMph: Double, val incline: Double,
        val bpm: Double?, val sensorFresh: Boolean, val sensor: Sensor, val reason: String,
        val adaptiveSpeed: Boolean,
    )

    companion object {
        // These are demonstration profiles, not prescribed heart-rate targets or workouts.
        val ROUTINES = listOf(
            Routine("steady", "Steady cardio", listOf(
                Phase("Warm up", .15, .70, 0.0), Phase("Steady", .85, 1.0, 1.0), Phase("Cool down", 1.0, .65, 0.0))),
            Routine("hills", "Walking hills", listOf(
                Phase("Warm up", .15, .70, 0.0), Phase("First hill", .35, .90, 2.0),
                Phase("Recover", .50, .85, .5), Phase("Second hill", .75, .90, 3.0),
                Phase("Ease down", .85, .85, 1.0), Phase("Cool down", 1.0, .65, 0.0))),
            Routine("intervals", "Effort and recovery", listOf(
                Phase("Warm up", .15, .70, 0.0), Phase("Effort 1", .30, 1.05, 2.0),
                Phase("Recover 1", .45, .80, 0.0), Phase("Effort 2", .60, 1.05, 2.0),
                Phase("Recover 2", .75, .80, 0.0), Phase("Final effort", .85, 1.0, 1.5),
                Phase("Cool down", 1.0, .65, 0.0))),
            Routine("recovery", "Easy recovery", listOf(
                Phase("Warm up", .20, .65, 0.0), Phase("Easy walk", .80, .80, .5), Phase("Cool down", 1.0, .60, 0.0))),
        )
        private const val MIN_SAMPLES = 5
        private const val MIN_HISTORY_MS = 4_000L
        private const val MAX_CLOCK_GAP_MS = 5_000L
        fun mphToKph(mph: Double) = mph * 1.609344
    }

    private data class Reading(val bpm: Int, val measuredAt: Long)
    private val readings = ArrayDeque<Reading>()
    private var selectedSensor = Sensor.SIMULATED
    private var latestSequence = -1L
    private var state = State.IDLE
    private var routine: Routine? = null
    private var durationSec = 20.0 * 60
    private var elapsedSec = 0.0
    private var speed = 0.0
    private var grade = 0.0
    private var lastTick: Long? = null
    private var lastDecision = 0L
    private var lastLoadChange = 0L
    private var bias = 0.0
    private var phaseIndex = 0
    private var manualSpeed = 0.0
    private var manualGrade = 0.0
    private var sensorInvalid = false
    private var reason = "Preview only — no treadmill connection"

    @Synchronized fun selectSensor(sensor: Sensor) {
        if (sensor == selectedSensor) return
        selectedSensor = sensor
        readings.clear()
        latestSequence = -1
        sensorInvalid = false
        if (state == State.RUNNING) state = State.SENSOR_HOLD
        reason = "Sensor changed; fresh readings and explicit resume required"
    }

    @Synchronized fun resetSensorHistory() {
        readings.clear()
        latestSequence = -1L
        sensorInvalid = false
        if (state == State.RUNNING) state = State.SENSOR_HOLD
        reason = "Sensor session changed; fresh readings and explicit resume required"
    }

    /** Receiver time is monotonic. Age is reported by the source; arrival alone cannot freshen old data. */
    @Synchronized fun acceptHeartRate(
        bpm: Int, receivedAt: Long, sequence: Long, source: Sensor,
        ageMs: Long = 0, bodyPresent: Boolean = true,
    ): Boolean {
        require(receivedAt >= 0 && sequence >= 0 && ageMs >= 0)
        if (source != selectedSensor || sequence <= latestSequence) return false
        latestSequence = sequence
        val measuredAt = receivedAt - ageMs
        if (!bodyPresent || bpm !in 30..240 || measuredAt < 0 || ageMs > config.freshForMs) {
            readings.clear()
            sensorInvalid = true
            reason = "Heart-rate reading invalid, stale, or off wrist"
            return false
        }
        if (readings.lastOrNull()?.measuredAt?.let { measuredAt <= it } == true) return false
        readings.addLast(Reading(bpm, measuredAt))
        while (readings.size > 10) readings.removeFirst()
        sensorInvalid = false
        return true
    }

    @Synchronized fun start(routineId: String, minutes: Int, now: Long) {
        require(now >= 0 && minutes in 1..120)
        check(state in setOf(State.IDLE, State.STOPPED, State.COMPLETE)) { "Stop the current preview before starting another" }
        val selected = ROUTINES.firstOrNull { it.id == routineId }
            ?: throw IllegalArgumentException("Unknown routine")
        check(fresh(now)) { "Wait for five fresh readings spanning four seconds" }
        routine = selected
        durationSec = minutes * 60.0
        elapsedSec = 0.0
        speed = 0.0
        grade = 0.0
        bias = 0.0
        phaseIndex = 0
        lastTick = now
        lastDecision = now
        lastLoadChange = now
        state = State.RUNNING
        reason = "Preview running"
    }

    @Synchronized fun pause() {
        if (state in setOf(State.RUNNING, State.MANUAL, State.SENSOR_HOLD)) {
            state = State.PAUSED
            speed = 0.0
            reason = "Paused; resume must be explicit"
        }
    }

    @Synchronized fun stop() {
        state = State.STOPPED
        speed = 0.0
        bias = 0.0
        reason = "Stopped; no automatic restart"
    }

    @Synchronized fun manual(speedMph: Double, incline: Double) {
        require(speedMph.isFinite() && incline.isFinite())
        check(state in setOf(State.RUNNING, State.MANUAL, State.SENSOR_HOLD)) { "Start the preview before manual adjustment" }
        manualSpeed = clampSpeed(speedMph)
        manualGrade = incline.coerceIn(config.minIncline, config.maxIncline)
        state = State.MANUAL
        bias = 0.0
        reason = "Manual override; automatic changes disabled until resume"
    }

    @Synchronized fun resume(now: Long) {
        require(now >= 0)
        check(state in setOf(State.PAUSED, State.MANUAL, State.SENSOR_HOLD)) { "No paused automation to resume" }
        check(fresh(now)) { "Fresh heart-rate history is required to resume" }
        state = State.RUNNING
        bias = 0.0
        lastTick = now
        lastDecision = now
        lastLoadChange = now
        reason = "Automatic preview resumed"
    }

    @Synchronized fun tick(now: Long): View {
        require(now >= 0)
        val previous = lastTick
        require(previous == null || now >= previous) { "Clock must be monotonic" }
        lastTick = now
        val gap = if (previous == null) 0L else now - previous
        if (state == State.RUNNING && (!fresh(now) || gap > MAX_CLOCK_GAP_MS)) {
            state = State.SENSOR_HOLD
            reason = if (gap > MAX_CLOCK_GAP_MS) "Controller heartbeat lost; explicit resume required"
                else "Heart-rate signal lost; workload held, explicit resume required"
        }
        if (state == State.RUNNING || state == State.MANUAL) {
            // Never catch up a suspended process by advancing a whole routine or jumping setpoints.
            if (gap > MAX_CLOCK_GAP_MS) {
                state = State.PAUSED
                speed = 0.0
                reason = "Controller heartbeat lost; explicit resume required"
                return view(now)
            }
            val dt = min(gap / 1000.0, 2.0)
            elapsedSec = min(durationSec, elapsedSec + dt)
            if (elapsedSec >= durationSec) {
                state = State.COMPLETE
                speed = 0.0
                reason = "Preview complete; no automatic restart"
                return view(now)
            }
            val phase = phase()
            val index = routine!!.phases.indexOf(phase)
            if (index != phaseIndex) {
                phaseIndex = index
                bias = 0.0
                lastLoadChange = now
                reason = "Phase changed; allowing heart rate to settle"
            }
            if (state == State.RUNNING && now - lastDecision >= config.decisionIntervalMs &&
                now - lastLoadChange >= config.settlingMs) {
                val average = mean()!!
                val next = when {
                    average < config.targetLowBpm -> min(1.0, bias + .10)
                    average > config.targetHighBpm -> max(-1.0, bias - .15)
                    else -> bias
                }
                lastDecision = now
                if (next != bias) {
                    bias = next
                    lastLoadChange = now
                    reason = if (average < config.targetLowBpm) "Sustained below-band reading: small proposed increase"
                        else "Sustained above-band reading: small proposed decrease"
                } else reason = "Holding workload"
            }
            val targetSpeed = if (state == State.MANUAL) manualSpeed else
                clampSpeed(config.baseSpeedMph * phase.speedFactor + if (config.adaptiveSpeed) bias * .5 else 0.0)
            val targetGrade = if (state == State.MANUAL) manualGrade else
                (phase.grade + bias * 2).coerceIn(config.minIncline, config.maxIncline)
            speed = approach(speed, targetSpeed, config.speedRateMphPerSec * dt)
            grade = approach(grade, targetGrade, config.inclineRatePercentPerSec * dt)
        }
        return view(now)
    }

    @Synchronized fun snapshot(now: Long): View = view(now)

    private fun phase(): Phase {
        val selected = routine ?: return Phase("Not started", 1.0, 0.0, 0.0)
        return selected.phases.firstOrNull { elapsedSec / durationSec < it.endFraction } ?: selected.phases.last()
    }
    private fun mean(): Double? = if (readings.isEmpty()) null else readings.map { it.bpm }.average()
    private fun fresh(now: Long): Boolean {
        if (sensorInvalid || readings.size < MIN_SAMPLES) return false
        val last = readings.last().measuredAt
        return now >= last && now - last <= config.freshForMs &&
            readings.last().measuredAt - readings.first().measuredAt >= MIN_HISTORY_MS &&
            now - readings.first().measuredAt <= 15_000
    }
    private fun view(now: Long) = View(state, routine?.id, phase().label, elapsedSec, durationSec,
        speed, grade, mean(), fresh(now), selectedSensor, reason, config.adaptiveSpeed)
    private fun clampSpeed(value: Double) = if (value <= 0.0) 0.0 else value.coerceIn(config.minSpeedMph, config.maxSpeedMph)
    private fun approach(from: Double, target: Double, maximum: Double) = when {
        abs(target - from) <= maximum -> target
        target > from -> from + maximum
        else -> from - maximum
    }
}
