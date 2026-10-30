package dev.stride.lab

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Device-local choices and explicitly saved preview sessions; never restores a running engine. */
class ConsolePreferences(private val prefs: SharedPreferences) {
    fun settings(): JSONObject = JSONObject()
        .put("units", prefs.getString("units", "mph"))
        .put("visual", prefs.getString("visual", "track"))
        .put("maxSpeedMph", limit("maxSpeedMph", 3.0, .8, 19.31 / 1.609344))
        .put("maxInclinePercent", limit("maxInclinePercent", 4.0, 0.0, 15.0))
        .put("adaptiveSpeed", prefs.getBoolean("adaptiveSpeed", false))

    private fun limit(key: String, fallback: Double, low: Double, high: Double): Double {
        val value = prefs.getString(key + "Exact", null)?.toDoubleOrNull()
            ?: prefs.getFloat(key, fallback.toFloat()).toDouble()
        return if (value.isFinite()) value.coerceIn(low, high) else fallback
    }
    fun savePresentation(body: JSONObject) {
        val edit = prefs.edit()
        if (body.has("units")) {
            val units = body.getString("units")
            require(units in listOf("mph", "kph"))
            edit.putString("units", units)
        }
        if (body.has("visual")) {
            val visual = body.getString("visual")
            require(visual in listOf("track", "profile", "hr"))
            edit.putString("visual", visual)
        }
        edit.apply()
    }
    fun saveConfig(config: WorkoutEngine.Config) {
        prefs.edit().putString("maxSpeedMphExact", config.maxSpeedMph.toString())
            .putString("maxInclinePercentExact", config.maxIncline.toString())
            .putFloat("maxSpeedMph", config.maxSpeedMph.toFloat())
            .putFloat("maxInclinePercent", config.maxIncline.toFloat())
            .putBoolean("adaptiveSpeed", config.adaptiveSpeed).apply()
    }
    fun history(): JSONArray = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
    private fun id(state: JSONObject, runId: String) = "$runId:${state.optLong("sessionId")}" 
    fun hasSession(state: JSONObject, runId: String): Boolean {
        val key = id(state, runId)
        val records = history()
        return (0 until records.length()).any { records.optJSONObject(it)?.optString("id") == key }
    }
    fun saveSession(state: JSONObject, runId: String) {
        check(state.getString("state") in listOf("STOPPED", "COMPLETE")) { "Stop the session before saving it" }
        check((!state.isNull("routine") || state.optBoolean("manualSession")) && state.optDouble("elapsedSec") > 0) { "There is no completed session to save" }
        if (hasSession(state, runId)) return
        val record = JSONObject().put("id", id(state, runId)).put("savedAtMs", System.currentTimeMillis())
            .put("previewOnly", true)
        listOf("routine", "manualSession", "elapsedSec", "durationSec", "distanceMiles", "sensor",
            "averageHeartRateBpm", "peakHeartRateBpm", "hrSamples", "targetLowBpm", "targetHighBpm").forEach {
            record.put(it, state.opt(it) ?: JSONObject.NULL)
        }
        val previous = history()
        val next = JSONArray().put(record)
        for (i in 0 until minOf(previous.length(), 19)) next.put(previous.getJSONObject(i))
        prefs.edit().putString("history", next.toString()).apply()
    }
}
