package dev.stride.lab

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONException
import org.json.JSONObject

/** LAN preview interface only; the token never grants access to a treadmill motor adapter. */
class PreviewServer(private val session: LabSession, private val page: String, val port: Int = 8780) {
    private var listener: ServerSocket? = null
    private val clients = java.util.Collections.synchronizedSet(HashSet<Socket>())
    private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(4), ThreadPoolExecutor.AbortPolicy())
    @Volatile private var running = false
    private var acceptThread: Thread? = null

    val boundPort: Int get() = listener?.localPort ?: port

    fun start() {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
        } catch (e: Exception) {
            socket.close()
            workers.shutdownNow()
            throw e
        }
        listener = socket
        running = true
        acceptThread = Thread({
            while (running) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                clients.add(client)
                try { workers.execute { try { serve(client) } finally { clients.remove(client) } } }
                catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
            }
        }, "workout-preview-http").apply { start() }
    }

    private fun serve(client: Socket) = client.use { socket ->
        socket.soTimeout = 3_000
        try {
            val request = HttpProtocol.read(socket.getInputStream())
            if (request.method == "GET" && request.path == "/") {
                reply(socket, 200, page, "text/html; charset=utf-8")
                return@use
            }
            val token = request.headers["authorization"]?.takeIf { it.startsWith("Bearer ") }?.substring(7)
            if (!session.authorized(token)) {
                reply(socket, 401, "{\"error\":\"Pairing token required\"}")
                return@use
            }
            // No CORS allowance: a random web page must not drive even a paired preview.
            when {
                request.method == "GET" && request.path == "/status" -> reply(socket, 200, session.status().toString())
                request.method == "GET" && request.path == "/routines" -> reply(socket, 200, session.routines().toString())
                request.method == "POST" && request.path == "/action" -> {
                    require(request.headers["content-type"]?.substringBefore(';') == "application/json") { "JSON required" }
                    check(running) { "Preview server stopped" }
                    session.action(JSONObject(request.body))
                    reply(socket, 200, session.status().toString())
                }
                request.method == "POST" && request.path == "/api/hr-display" -> {
                    require(request.headers["content-type"]?.substringBefore(';') == "application/json") { "JSON required" }
                    val body = JSONObject(request.body)
                    require(body.opt("displayOnly") == true && body.optString("quality") == "acquisition_unknown" &&
                        body.has("acquisitionAgeMs") && body.isNull("acquisitionAgeMs")) { "Display-only quality required" }
                    require(body.has("bpm")) { "bpm is required" }
                    val bpm = if (body.isNull("bpm")) null else integer(body, "bpm", 30, 240).toInt()
                    val accepted = session.displayHeartRate.accept(bpm, session.now(), integer(body, "sequence", 1, Long.MAX_VALUE),
                        integer(body, "pollAgeMs", 0, Long.MAX_VALUE), body.getString("source"), body.getString("sessionId"), body.getString("status"))
                    reply(socket, if (accepted) 200 else 409,
                        JSONObject().put("accepted", accepted).put("displayOnly", true).toString())
                }
                request.method == "POST" && request.path == "/api/hr" -> {
                    require(request.headers["content-type"]?.substringBefore(';') == "application/json") { "JSON required" }
                    val body = JSONObject(request.body)
                    require(body.has("bpm") && body.has("sequence") && body.has("ageMs") && body.has("source") && body.has("sessionId")) {
                        "bpm, sequence, ageMs, source and sessionId are required"
                    }
                    val bpm = integer(body, "bpm", 30, 240).toInt()
                    val sequence = integer(body, "sequence", 0, Long.MAX_VALUE)
                    val age = integer(body, "ageMs", 0, Long.MAX_VALUE)
                    val accepted = session.phoneInput.accept(bpm, session.now(), sequence,
                        age, body.getString("source"), body.getString("sessionId"))
                    reply(socket, if (accepted) 200 else 409,
                        JSONObject().put("accepted", accepted).put("previewOnly", true).toString())
                }
                else -> reply(socket, 404, "{\"error\":\"Unknown endpoint\"}")
            }
        } catch (e: IllegalStateException) {
            reply(socket, 409, error(e))
        } catch (e: IllegalArgumentException) {
            reply(socket, 400, error(e))
        } catch (e: JSONException) {
            reply(socket, 400, "{\"error\":\"Invalid JSON request\"}")
        } catch (_: java.io.IOException) {
            // A disconnected or timed-out client must not stop the controller clock.
        }
    }

    private fun integer(body: JSONObject, key: String, low: Long, high: Long): Long {
        val value = body.get(key)
        require(value is Int || value is Long) { "$key must be an integer" }
        return (value as Number).toLong().also { require(it in low..high) { "$key is outside the allowed range" } }
    }
    private fun error(e: Exception) = JSONObject().put("error", e.message ?: "Invalid request").toString()
    private fun reply(socket: Socket, status: Int, text: String, type: String = "application/json") {
        val body = text.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 409 -> "Conflict"; else -> "Not Found" }
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        try { socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() } }
        catch (_: java.io.IOException) { }
    }
    fun close() {
        running = false
        try { listener?.close() } catch (_: java.io.IOException) { }
        synchronized(clients) {
            clients.forEach { try { it.close() } catch (_: java.io.IOException) { } }
            clients.clear()
        }
        workers.shutdownNow()
    }
}
