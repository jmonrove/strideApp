package dev.stride.lab

import java.io.InputStream
import java.nio.charset.Charset

/** Small bounded HTTP/1.1 parser for a single-request, connection-close preview API. */
object HttpProtocol {
    const val MAX_BODY = 4096
    private const val MAX_HEADER = 8192
    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    fun read(input: InputStream): Request {
        val bytes = ArrayList<Byte>()
        var matched = 0
        val separator = byteArrayOf(13, 10, 13, 10)
        while (matched < separator.size) {
            val c = input.read()
            require(c >= 0) { "Incomplete request headers" }
            bytes.add(c.toByte())
            require(bytes.size <= MAX_HEADER) { "Request headers too large" }
            matched = if (c.toByte() == separator[matched]) matched + 1 else if (c == 13) 1 else 0
        }
        val header = bytes.toByteArray().toString(Charsets.US_ASCII).dropLast(4)
        val lines = header.split("\r\n")
        val first = lines.first().split(' ')
        require(first.size == 3 && first[2] in setOf("HTTP/1.0", "HTTP/1.1")) { "Invalid request line" }
        require(first[1].startsWith('/') && !first[1].contains('?')) { "Use paths without query parameters" }
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val at = line.indexOf(':')
            require(at > 0) { "Invalid header" }
            val key = line.substring(0, at).lowercase(java.util.Locale.US)
            require(!headers.containsKey(key)) { "Duplicate headers are not accepted" }
            headers[key] = line.substring(at + 1).trim()
        }
        require(!headers.containsKey("transfer-encoding")) { "Chunked requests are not supported" }
        val length = headers["content-length"]?.toIntOrNull() ?: if (headers.containsKey("content-length"))
            throw IllegalArgumentException("Invalid content length") else 0
        require(length in 0..MAX_BODY) { "Request body too large" }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            require(count > 0) { "Incomplete request body" }
            read += count
        }
        return Request(first[0], first[1], headers, body.toString(Charset.forName("UTF-8")))
    }
}
