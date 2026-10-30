package dev.stride.lab

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class HttpProtocolTest {
    private fun parse(s: String) = HttpProtocol.read(ByteArrayInputStream(s.toByteArray(Charsets.UTF_8)))
    private fun rejects(s: String) {
        try { parse(s); fail("Malformed HTTP accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun parsesJsonAndNormalizesHeaders() {
        val r = parse("POST /api/hr HTTP/1.1\r\nAuthorization: Bearer test\r\nContent-Length: 2\r\n\r\n{}")
        assertEquals("POST", r.method); assertEquals("/api/hr", r.path)
        assertEquals("Bearer test", r.headers["authorization"]); assertEquals("{}", r.body)
    }
    @Test fun rejectsTruncatedOversizedDuplicateAndChunkedRequests() {
        rejects("POST /api/hr HTTP/1.1\r\nContent-Length: 999999\r\n\r\n")
        rejects("POST /api/hr HTTP/1.1\r\nContent-Length: 3\r\n\r\n{}")
        rejects("GET /status HTTP/1.1\r\nAuthorization: x\r\nAuthorization: y\r\n\r\n")
        rejects("POST /api/hr HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n")
        rejects("GET /status?token=not-allowed HTTP/1.1\r\n\r\n")
        rejects("GET /status HTTP/1.1\r\nX-Big: " + "x".repeat(9000) + "\r\n\r\n")
    }
}
