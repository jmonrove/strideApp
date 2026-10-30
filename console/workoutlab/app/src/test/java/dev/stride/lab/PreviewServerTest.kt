package dev.stride.lab

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreviewServerTest {
    private fun request(port: Int, path: String, token: String? = null, body: String? = null): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000; connection.readTimeout = 2_000
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            status to stream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    @Test fun requiresBearerForAllDataAndControlEndpoints() {
        val session = LabSession(monotonicNow = { 5_000L }, startClock = false)
        val server = PreviewServer(session, "preview pairing page", 0)
        server.start()
        try {
            assertEquals(200, request(server.boundPort, "/").first)
            assertEquals(401, request(server.boundPort, "/status").first)
            assertEquals(401, request(server.boundPort, "/action", "wrong", "{\"action\":\"stop\"}").first)
            val paired = request(server.boundPort, "/status", session.token)
            assertEquals(200, paired.first)
            assertTrue(JSONObject(paired.second).getBoolean("previewOnly"))
            assertFalse(JSONObject(paired.second).getBoolean("motorConnected"))
            assertEquals(200, request(server.boundPort, "/routines", session.token).first)
        } finally { server.close(); session.close() }
    }
    @Test fun validatesWatchContractAndCannotStartBeforeFreshReadings() {
        var now = 10_000L
        val session = LabSession(monotonicNow = { now }, startClock = false)
        val server = PreviewServer(session, "preview", 0)
        server.start()
        fun post(path: String, body: String) = request(server.boundPort, path, session.token, body)
        try {
            assertEquals(409, post("/action", "{\"action\":\"start\",\"routine\":\"steady\",\"minutes\":20}").first)
            assertEquals(200, post("/action", "{\"action\":\"sensor\",\"source\":\"PHONE\"}").first)
            assertEquals(400, post("/api/hr", "{\"bpm\":120,\"ageMs\":-1,\"source\":\"sense2\",\"sequence\":0,\"sessionId\":\"session-1\"}").first)
            assertEquals(400, post("/api/hr", "{\"bpm\":120.5,\"ageMs\":0,\"source\":\"sense2\",\"sequence\":0,\"sessionId\":\"session-1\"}").first)
            for (i in 0..4) {
                now = 10_000L + i * 1_000
                assertEquals(200, post("/api/hr", "{\"bpm\":120,\"ageMs\":0,\"source\":\"sense2\",\"sequence\":$i,\"sessionId\":\"session-1\"}").first)
            }
            assertEquals(200, post("/action", "{\"action\":\"start\",\"routine\":\"steady\",\"minutes\":20}").first)
            assertEquals(409, post("/api/hr", "{\"bpm\":120,\"ageMs\":0,\"source\":\"sense2\",\"sequence\":4,\"sessionId\":\"session-1\"}").first)
            assertEquals(400, post("/action", "{\"action\":\"manual\",\"speedMph\":\"NaN\",\"inclinePercent\":0}").first)
            assertEquals(200, post("/action", "{\"action\":\"stop\"}").first)
            assertEquals(409, post("/action", "{\"action\":\"resume\"}").first)
        } finally { server.close(); session.close() }
    }
}
