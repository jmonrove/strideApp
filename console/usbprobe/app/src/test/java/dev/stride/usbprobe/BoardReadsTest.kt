package dev.stride.usbprobe

import org.junit.Assert.*
import org.junit.Test

class BoardReadsTest {
    private fun response(device: Int, command: Int, payload: ByteArray, status: Int = 2): ByteArray {
        val frame = byteArrayOf(device.toByte(), (payload.size + 5).toByte(), command.toByte(), status.toByte()) + payload + byteArrayOf(0)
        frame[frame.lastIndex] = frame.dropLast(1).sumOf { it.toInt() and 255 }.toByte()
        return frame
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid input was accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun everyPossibleQueryContainsOnlyMetadataOrAnEmptyWriteSection() {
        for (device in listOf(2, 4, 5)) {
            for (kind in BoardReads.Info.values()) {
                val frame = BoardReads.request(BoardReads.Query.Information(device, kind))
                assertTrue((frame[2].toInt() and 255) in setOf(128, 129, 130, 132, 136))
                assertTrue(frame.size == 4 || (frame.size == 6 && frame[3] == 0.toByte() && frame[4] == 0.toByte()))
            }
            val all = BoardReads.Field.values()
            for (mask in 1 until (1 shl all.size)) {
                val fields = all.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
                val frame = BoardReads.request(BoardReads.Query.Values(device, fields))
                assertEquals(2, frame[2].toInt())
                assertEquals(0, frame[3].toInt()) // zero write-mask length
                assertEquals(7 + fields.maxOf { it.id } / 8, frame.size)
                assertEquals(frame.size, frame[1].toInt())
                assertEquals(frame.dropLast(1).sumOf { it.toInt() and 255 }.toByte(), frame.last())
            }
        }
    }

    @Test fun exactReadMaskRequestsTargetsFeedbackAndLimitsWithoutWritingThem() {
        val query = BoardReads.Query.Values(4, listOf(BoardReads.Field.MIN_KPH, BoardReads.Field.TARGET_KPH, BoardReads.Field.ACTUAL_GRADE))
        assertArrayEquals(byteArrayOf(4, 10, 2, 0, 4, 1, 0, 2, 0x80.toByte(), 0x97.toByte()), BoardReads.request(query))
    }

    @Test fun rejectsUnknownAddressesEmptyReadsAndDuplicates() {
        rejects { BoardReads.Query.Information(65, BoardReads.Info.IDENTITY) }
        rejects { BoardReads.Query.Values(4, emptyList()) }
        rejects { BoardReads.Query.Values(4, listOf(BoardReads.Field.MODE, BoardReads.Field.MODE)) }
    }

    @Test fun rejectsCorruptedTruncatedAndUnrelatedReplies() {
        val query = BoardReads.Query.Information(4, BoardReads.Info.IDENTITY)
        val valid = response(4, 129, byteArrayOf(1, 2, 3))
        for (size in 0 until valid.size) rejects { BoardReads.reply(query, valid.copyOf(size)) }
        rejects { BoardReads.reply(query, valid.copyOf().also { it[4] = 42 }) }
        rejects { BoardReads.reply(query, response(4, 132, byteArrayOf())) }
        rejects { BoardReads.reply(query, response(5, 129, byteArrayOf())) }
        assertEquals(3, BoardReads.reply(query, valid + ByteArray(56)).payload.size)
    }

    @Test fun discoversMainAliasButRejectsUnrelatedDevice() {
        val query = BoardReads.Query.Information(2, BoardReads.Info.IDENTITY)
        assertEquals(4, BoardReads.reply(query, response(4, 129, byteArrayOf())).device)
        rejects { BoardReads.reply(query, response(65, 129, byteArrayOf())) }
    }

    @Test fun blockedRepliesDoNotBecomeSuccessfulOrTriggerAnyUnlock() {
        val query = BoardReads.Query.Information(2, BoardReads.Info.IDENTITY)
        val reply = BoardReads.reply(query, response(2, 129, byteArrayOf(), status = 8))
        rejects { BoardReads.identity(reply) }
        assertEquals(8, reply.status)
    }

    @Test fun signedGradeUnsignedSpeedAndMissingValuesDecodeIndependently() {
        val query = BoardReads.Query.Values(4, listOf(BoardReads.Field.ACTUAL_KPH, BoardReads.Field.ACTUAL_GRADE, BoardReads.Field.MAX_KPH))
        val reply = BoardReads.reply(query, response(4, 2, byteArrayOf(0xf4.toByte(), 1, 0xd4.toByte(), 0xfe.toByte(), 0xff.toByte(), 0xff.toByte())))
        val values = BoardReads.values(query, reply)
        assertEquals(5.0, values[BoardReads.Field.ACTUAL_KPH]!!, .00001)
        assertEquals(-3.0, values[BoardReads.Field.ACTUAL_GRADE]!!, .00001)
        assertNull(values[BoardReads.Field.MAX_KPH])
        rejects { BoardReads.values(query, BoardReads.Reply(4, 2, byteArrayOf(1, 2))) }
    }

    @Test fun identityDecodesCapabilitiesWithoutReportingSerial() {
        val payload = byteArrayOf(82, 55, 1, 2, 3, 4, 0x3c, 0x21, 3, 3, 0x10, 3)
        val identity = BoardReads.identity(BoardReads.Reply(4, 2, payload))
        assertEquals(8508, identity.manufacturer)
        assertEquals(setOf(0, 1, 12, 16, 17), identity.fields)
        rejects { BoardReads.identity(BoardReads.Reply(4, 2, payload.copyOf(11))) }
    }

    @Test fun queryPlanSkipsUnadvertisedFieldsAndStopsOnSecurityBlock() {
        val queries = mutableListOf<BoardReads.Query>()
        val report = mutableListOf<String>()
        BoardInspection(exchange = { query ->
            queries.add(query)
            when (query) {
                is BoardReads.Query.Information -> when (query.kind) {
                    BoardReads.Info.IDENTITY -> BoardReads.Reply(4, 2, byteArrayOf(82, 55, 0, 0, 0, 0, 0x3c, 0x21, 1, 1))
                    BoardReads.Info.COMMANDS -> BoardReads.Reply(4, 2, byteArrayOf(2))
                    else -> error("Unadvertised query")
                }
                is BoardReads.Query.Values -> {
                    assertEquals(listOf(BoardReads.Field.TARGET_KPH), query.fields)
                    BoardReads.Reply(4, 2, byteArrayOf(0, 0))
                }
            }
        }, report = report::add).run()
        assertEquals(3, queries.size)
        assertTrue(report.any { it.startsWith("DONE") })
        var count = 0
        rejects {
            BoardInspection(exchange = { count++; BoardReads.Reply(2, 8, byteArrayOf()) }, report = {}).run()
        }
        assertEquals(1, count)
    }
}
