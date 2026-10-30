package dev.stride.usbprobe

import org.junit.Assert.*
import org.junit.Test

class QueryWireTest {
    private fun frame(command: Int): ByteArray = byteArrayOf(2, 5, command.toByte(), 2, (9 + command).toByte())

    @Test fun pendingInputIsDrainedWithoutWritingAnything() {
        val reads = mutableListOf<ByteArray?>(frame(2), frame(129), null)
        var writes = 0
        QueryWire(write = { writes++; it.size }, read = { reads.removeAt(0) }, wait = {}, report = {}).discardPendingInput()
        assertEquals(0, writes)
        assertTrue(reads.isEmpty())
    }

    @Test fun continuousPendingInputAbortsAtEightReadsWithoutSendingAQuery() {
        var reads = 0; var writes = 0
        val wire = QueryWire(write = { writes++; it.size }, read = { reads++; frame(2) }, wait = {}, report = {})
        try { wire.discardPendingInput(); fail() } catch (_: IllegalStateException) { }
        assertEquals(8, reads); assertEquals(0, writes)
    }

    @Test fun mismatchNeverCausesAnAutomaticRetryOrControlRequest() {
        val sent = mutableListOf<ByteArray>()
        val wire = QueryWire(write = { sent.add(it); it.size }, read = { frame(2) }, wait = {}, report = {})
        try { wire.exchange(BoardReads.Query.Information(2, BoardReads.Info.IDENTITY)); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, sent.size)
        assertArrayEquals(byteArrayOf(2, 4, 129.toByte(), 135.toByte()), sent.single())
    }

    @Test fun partialRequestAbortsBeforeReadingOrRetrying() {
        var writes = 0; var reads = 0
        val wire = QueryWire(write = { writes++; 1 }, read = { reads++; frame(129) }, wait = {}, report = {})
        try { wire.exchange(BoardReads.Query.Information(2, BoardReads.Info.IDENTITY)); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(1, writes); assertEquals(0, reads)
    }
}
