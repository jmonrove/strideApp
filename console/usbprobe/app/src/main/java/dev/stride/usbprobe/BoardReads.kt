package dev.stride.usbprobe

/** Independent query-only FitPro1 codec. It has no control, connect, unlock or reset builder. */
object BoardReads {
    enum class Info(val code: Int, val waitMs: Long = 300) {
        DEVICES(128), IDENTITY(129), SYSTEM(130), VERSION(132, 400), COMMANDS(136)
    }

    enum class Field(val id: Int, val size: Int, val scale: Double, val signed: Boolean = false) {
        TARGET_KPH(0, 2, .01), TARGET_GRADE(1, 2, .01, true), RPM(5, 2, 1.0),
        MODE(12, 1, 1.0), ACTUAL_KPH(16, 2, .01), ACTUAL_GRADE(17, 2, .01, true),
        MAX_GRADE(27, 2, .01, true), MIN_GRADE(28, 2, .01, true),
        MAX_KPH(30, 2, .01), MIN_KPH(31, 2, .01)
    }

    sealed class Query private constructor(val device: Int) {
        init { require(device in setOf(2, 4, 5)) { "Only main/treadmill/incline-trainer addresses allowed" } }
        class Information(device: Int, val kind: Info) : Query(device)
        class Values(device: Int, fields: List<Field>) : Query(device) {
            val fields = fields.toList().sortedBy { it.id }
            init { require(fields.isNotEmpty() && fields.distinct().size == fields.size) }
        }
    }

    fun command(query: Query): Int = when (query) {
        is Query.Information -> query.kind.code
        is Query.Values -> 2
    }

    fun delayMs(query: Query): Long = when (query) {
        is Query.Information -> query.kind.waitMs
        is Query.Values -> 80
    }

    /** All command-2 frames start with an empty write section. Only fixed metadata queries exist. */
    fun request(query: Query): ByteArray {
        val payload: ByteArray = when (query) {
            is Query.Information -> if (query.kind in setOf(Info.SYSTEM, Info.VERSION)) byteArrayOf(0, 0) else byteArrayOf()
            is Query.Values -> {
                val sections = query.fields.maxOf { it.id } / 8 + 1
                ByteArray(2 + sections).also { bytes ->
                    bytes[0] = 0 // No write fields or write values, ever.
                    bytes[1] = sections.toByte()
                    query.fields.forEach { f ->
                        val offset = 2 + f.id / 8
                        bytes[offset] = (bytes[offset].toInt() or (1 shl (f.id % 8))).toByte()
                    }
                }
            }
        }
        val length = payload.size + 4
        require(length <= 64)
        return ByteArray(length).also {
            it[0] = query.device.toByte(); it[1] = length.toByte(); it[2] = command(query).toByte()
            payload.copyInto(it, 3)
            it[length - 1] = checksum(it, length)
        }
    }

    private fun checksum(bytes: ByteArray, length: Int): Byte =
        (0 until length - 1).sumOf { bytes[it].toInt() and 255 }.toByte()

    data class Reply(val device: Int, val status: Int, val payload: ByteArray) {
        fun requireDone() { require(status == 2) { "Board status $status; stopped without unlock/retry" } }
    }

    fun reply(query: Query, bytes: ByteArray): Reply {
        require(bytes.size >= 5) { "Short response" }
        val length = bytes[1].toInt() and 255
        require(length in 5..64 && length <= bytes.size) { "Invalid response length" }
        require(bytes[length - 1] == checksum(bytes, length)) { "Invalid response checksum" }
        require((bytes[2].toInt() and 255) == command(query)) { "Unexpected response command; possible other controller" }
        val device = bytes[0].toInt() and 255
        val discovery = query is Query.Information && query.device == 2
        require(device == query.device || (discovery && device in setOf(4, 5))) { "Unexpected response device" }
        return Reply(device, bytes[3].toInt() and 255, bytes.copyOfRange(4, length - 1))
    }

    data class Identity(val device: Int, val software: Int, val hardware: Int, val manufacturer: Int, val fields: Set<Int>)

    fun identity(reply: Reply): Identity {
        reply.requireDone()
        val p = reply.payload
        require(p.size >= 9) { "Short identity payload" }
        val sections = u8(p, 8)
        require(sections <= 32 && p.size == 9 + sections) { "Invalid identity field mask" }
        val fields = mutableSetOf<Int>()
        repeat(sections) { section -> repeat(8) { bit ->
            if (u8(p, 9 + section) and (1 shl bit) != 0) fields.add(section * 8 + bit)
        } }
        // Bytes 2..5 contain a serial number; it is deliberately omitted from reports.
        return Identity(reply.device, u8(p, 0), u8(p, 1), u16(p, 6), fields)
    }

    fun devices(reply: Reply): List<Int> {
        reply.requireDone()
        val p = reply.payload
        require(p.isNotEmpty() && p.size == 1 + u8(p, 0)) { "Invalid device list" }
        return p.drop(1).map { it.toInt() and 255 }
    }

    fun commands(reply: Reply): Set<Int> { reply.requireDone(); return reply.payload.map { it.toInt() and 255 }.toSet() }

    fun values(query: Query.Values, reply: Reply): Map<Field, Double?> {
        reply.requireDone()
        require(reply.payload.size == query.fields.sumOf { it.size }) { "Unexpected status payload size" }
        var offset = 0
        return query.fields.associateWith { field ->
            val missing = (offset until offset + field.size).all { u8(reply.payload, it) == 255 }
            var raw = 0
            repeat(field.size) { raw = raw or (u8(reply.payload, offset + it) shl (8 * it)) }
            if (field.signed && raw and (1 shl (field.size * 8 - 1)) != 0) raw -= (1 shl (field.size * 8))
            offset += field.size
            if (missing) null else raw * field.scale
        }
    }

    fun system(reply: Reply): String {
        reply.requireDone(); val p = reply.payload
        require(p.size >= 11) { "Short system payload" }
        return "model=${u32(p, 3)} part=${u32(p, 7)}"
    }

    fun version(reply: Reply): String {
        reply.requireDone(); val p = reply.payload
        require(p.size >= 3) { "Short version payload" }
        return "master library=${u8(p, 0)}.${u16(p, 1)}"
    }

    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 255
    private fun u16(b: ByteArray, at: Int) = u8(b, at) or (u8(b, at + 1) shl 8)
    private fun u32(b: ByteArray, at: Int): Long = (0 until 4).fold(0L) { n, i -> n or (u8(b, at + i).toLong() shl (i * 8)) }
}
