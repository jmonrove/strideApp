package dev.stride.lab

import org.json.JSONObject

/** Untimestamped exercise HR for display only. Has no reference to WorkoutEngine. */
class DisplayHeartRate {
    private var session: String? = null
    private val retired = HashSet<String>()
    private var sequence = 0L
    private var receivedAt = 0L
    private var pollAgeMs = 0L
    private var bpm: Int? = null
    private var status = "waiting"

    @Synchronized fun accept(value: Int?, now: Long, nextSequence: Long, age: Long, source: String, id: String, nextStatus: String): Boolean {
        require(source == "sense2_exercise") { "Unsupported display source" }
        require(id.length in 8..80 && id.all { it.isLetterOrDigit() || it in "-_:" }) { "Invalid session ID" }
        require(now >= 0 && nextSequence >= 1 && age >= 0)
        require(nextStatus in setOf("display_only", "inactive", "paused", "off_wrist", "unavailable"))
        require(if (nextStatus == "display_only") value != null && value in 30..240 else value == null)
        if (age > 5_000 || id in retired) return false
        if (id != session) {
            if (retired.size >= 64) return false
            session?.let { retired.add(it) }
            session = id; sequence = 0
        }
        if (nextSequence <= sequence) return false
        sequence = nextSequence; receivedAt = now; pollAgeMs = age; bpm = value; status = nextStatus
        return true
    }

    @Synchronized fun snapshot(now: Long): JSONObject {
        val elapsed = if (now >= receivedAt) now - receivedAt else Long.MAX_VALUE
        val recent = session != null && elapsed <= 5_000 && pollAgeMs <= 5_000 - elapsed
        return JSONObject().put("displayOnly", true).put("acquisitionAgeMs", JSONObject.NULL)
            .put("quality", "acquisition_unknown").put("source", "sense2_exercise")
            .put("bpm", if (recent && status == "display_only") bpm else JSONObject.NULL)
            .put("status", if (session == null) "waiting" else if (!recent) "expired" else status)
            .put("pollAgeMs", if (recent) elapsed + pollAgeMs else JSONObject.NULL)
    }
}
