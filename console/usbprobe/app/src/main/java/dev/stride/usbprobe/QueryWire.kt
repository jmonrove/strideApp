package dev.stride.usbprobe

/** Bounded transport policy, independently testable without Android or a board. */
class QueryWire(
    private val write: (ByteArray) -> Int,
    private val read: (Int) -> ByteArray?,
    private val wait: (Long) -> Unit,
    private val report: (String) -> Unit,
) {
    /** Consume already-queued replies with IN reads only. Never send a flush/reset packet. */
    fun discardPendingInput() {
        repeat(8) { count ->
            val pending = read(100)
            if (pending == null || pending.isEmpty()) {
                report("Input quiet after $count pending report(s); no flush command sent.")
                return
            }
            report("Discarded pending input: ${header(pending)}")
        }
        error("Input did not become quiet within 8 reports; no queries sent.")
    }

    fun exchange(query: BoardReads.Query): BoardReads.Reply {
        val request = BoardReads.request(query)
        report("Query device=${query.device} command=${BoardReads.command(query)} bytes=${request.size}")
        val sent = write(request)
        check(sent == request.size) { "Incomplete USB request ($sent/${request.size}); stopped." }
        wait(BoardReads.delayMs(query))
        val bytes = read(1000)
        check(bytes != null && bytes.isNotEmpty()) { "No reply; stopped without retry." }
        report("Reply ${header(bytes)}")
        return BoardReads.reply(query, bytes).also { it.requireDone() }
    }

    private fun header(bytes: ByteArray): String = if (bytes.size < 4) "short (${bytes.size} bytes)" else
        "device=${bytes[0].toInt() and 255} length=${bytes[1].toInt() and 255} command=${bytes[2].toInt() and 255} status=${bytes[3].toInt() and 255} received=${bytes.size} bytes"
}
