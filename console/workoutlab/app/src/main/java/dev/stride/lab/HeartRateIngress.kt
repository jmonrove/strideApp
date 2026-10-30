package dev.stride.lab

/** One active watch stream; an old session cannot become current again and freshen a replay. */
class HeartRateIngress(private val engine: WorkoutEngine) {
    private var currentSession: String? = null
    private val retired = HashSet<String>()

    @Synchronized fun accept(
        bpm: Int, receivedAt: Long, sequence: Long, ageMs: Long, source: String, sessionId: String,
    ): Boolean {
        require(source == "sense2") { "Unsupported sensor source" }
        require(sessionId.length in 8..80 && sessionId.all { it.isLetterOrDigit() || it in "-_:" }) {
            "Invalid session ID"
        }
        require(bpm in 30..240 && receivedAt >= 0 && sequence >= 0 && ageMs >= 0)
        if (engine.snapshot(receivedAt).sensor != WorkoutEngine.Sensor.PHONE) return false
        if (sessionId in retired) return false
        if (currentSession != sessionId) {
            // Bound memory without evicting replay protection. Reopening the lab starts a new pairing.
            if (retired.size >= 64) return false
            currentSession?.let { retired.add(it) }
            currentSession = sessionId
            engine.resetSensorHistory()
        }
        return engine.acceptHeartRate(bpm, receivedAt, sequence, WorkoutEngine.Sensor.PHONE, ageMs)
    }
}
