package dev.stride.motorbench

/** FitPro1 framing only. No connect/unlock/reset/firmware command exists here. */
object MotorWire {
    const val ADDRESS = 4
    enum class Field(val id: Int, val width: Int, val signed: Boolean = false, val scale: Int = 100) {
        SPEED(0,2), GRADE(1,2,true), MODE(12,1,false,1), ACTUAL_SPEED(16,2), ACTUAL_GRADE(17,2,true),
        MAX_GRADE(27,2,true), MIN_GRADE(28,2,true), MAX_SPEED(30,2), MIN_SPEED(31,2)
    }
    val STATUS = listOf(Field.SPEED,Field.GRADE,Field.MODE,Field.ACTUAL_SPEED,Field.ACTUAL_GRADE)
    val LIMITS = listOf(Field.MAX_GRADE,Field.MIN_GRADE,Field.MAX_SPEED,Field.MIN_SPEED)
    private val writable = setOf(Field.SPEED,Field.GRADE,Field.MODE)
    private val info = setOf(129,130,132,136)
    data class Request(val command: Int, val writes: Map<Field,Double>, val reads: List<Field>)
    fun information(command: Int): ByteArray {
        require(command in info)
        return frame(command,if(command in setOf(130,132)) byteArrayOf(0,0) else byteArrayOf())
    }
    fun values(reads: List<Field> = STATUS, writes: Map<Field,Double> = emptyMap()): ByteArray {
        require(reads.distinct().size == reads.size && (reads.isNotEmpty() || writes.isNotEmpty()))
        require(writes.keys.all { it in writable })
        require(!(Field.MODE in writes && writes.size > 1)) { "Mode and motion must be separate transactions" }
        if(Field.MODE in writes) require(writes[Field.MODE] in setOf(1.0,2.0,3.0))
        val ordered = writes.keys.sortedBy { it.id }
        val body = section(ordered).toMutableList()
        ordered.forEach { body.addAll(encode(it,writes.getValue(it)).toList()) }
        body.addAll(section(reads).toList())
        return frame(2,body.toByteArray())
    }
    fun encode(field: Field, value: Double): ByteArray {
        require(value.isFinite())
        require(when(field) {
            Field.SPEED,Field.ACTUAL_SPEED,Field.MAX_SPEED,Field.MIN_SPEED -> value in 0.0..100.0
            Field.GRADE,Field.ACTUAL_GRADE,Field.MAX_GRADE,Field.MIN_GRADE -> value in -100.0..100.0
            Field.MODE -> value in setOf(1.0,2.0,3.0,8.0) // Simulator may report DMK; requests below restrict writes.
        })
        require(field != Field.MODE || value % 1 == 0.0)
        val raw = (value * field.scale).toInt() // Match inspected vendor truncation, including signed grade.
        return ByteArray(field.width) { ((raw shr (it*8)) and 255).toByte() }
    }
    fun quantize(field: Field, value: Double): Double = decode(field,encode(field,value),0)
    fun decode(field: Field, bytes: ByteArray, at: Int): Double {
        require(at >= 0 && at + field.width <= bytes.size)
        var raw = 0
        repeat(field.width) { raw = raw or (u(bytes[at+it]) shl (8*it)) }
        if(field.signed && raw and (1 shl (field.width*8-1)) != 0) raw -= 1 shl (field.width*8)
        return raw.toDouble()/field.scale
    }
    fun frame(command: Int, payload: ByteArray): ByteArray {
        require(command == 2 || command in info)
        require(payload.size <= 60)
        val out = byteArrayOf(ADDRESS.toByte(),(payload.size+4).toByte(),command.toByte()) + payload + byteArrayOf(0)
        out[out.lastIndex] = checksum(out,out.size)
        return out
    }
    private fun section(fields: List<Field>): ByteArray {
        if(fields.isEmpty()) return byteArrayOf(0)
        val n = fields.maxOf { it.id }/8+1
        return ByteArray(n+1).also { b -> b[0]=n.toByte();fields.forEach { b[1+it.id/8]=(u(b[1+it.id/8]) or (1 shl (it.id%8))).toByte() } }
    }
    fun parseRequest(bytes: ByteArray): Request {
        val payload=validated(bytes,false);val command=u(bytes[2])
        if(command in info) {
            require(payload.contentEquals(if(command in setOf(130,132)) byteArrayOf(0,0) else byteArrayOf()))
            return Request(command,emptyMap(),emptyList())
        }
        require(command == 2)
        var cursor=0
        fun fields(): List<Field> {
            require(cursor<payload.size);val n=u(payload[cursor++]);require(n<=4 && cursor+n<=payload.size)
            val result=mutableListOf<Field>()
            repeat(n){section -> val bits=u(payload[cursor++]);repeat(8){bit -> if(bits and (1 shl bit)!=0) {
                val id=section*8+bit;result.add(Field.entries.firstOrNull { it.id==id } ?: error("Unsupported field $id"))
            }}}
            return result.sortedBy { it.id }
        }
        val writeFields=fields();require(writeFields.all { it in writable })
        val writes=writeFields.associateWith { f -> decode(f,payload,cursor).also { cursor+=f.width } }
        require(!(Field.MODE in writes && writes.size>1))
        if(Field.MODE in writes) require(writes[Field.MODE] in setOf(1.0,2.0,3.0))
        val reads=fields();require(cursor==payload.size && (reads.isNotEmpty() || writes.isNotEmpty()))
        return Request(2,writes,reads)
    }
    fun reply(request: ByteArray, bytes: ByteArray): ByteArray {
        val payload=validated(bytes,true)
        require(u(bytes[2])==u(request[2])) { "Unexpected response command" }
        require(u(bytes[3])==2) { "Board status ${u(bytes[3])}; no unlock/retry" }
        return payload
    }
    fun readValues(request: ByteArray, bytes: ByteArray): Map<Field,Double?> {
        val parsed=parseRequest(request);require(parsed.command==2)
        val payload=reply(request,bytes);require(payload.size==parsed.reads.sumOf { it.width })
        var at=0
        return parsed.reads.associateWith { f ->
            val missing=(at until at+f.width).all { u(payload[it])==255 }
            (if(missing) null else decode(f,payload,at)).also { at+=f.width }
        }
    }
    private fun validated(bytes: ByteArray, response: Boolean): ByteArray {
        require(bytes.size>=if(response) 5 else 4) { "Short frame" }
        val length=u(bytes[1]);require(length in (if(response) 5 else 4)..64 && length<=bytes.size)
        require(response || length==bytes.size) { "Trailing request bytes" }
        require(u(bytes[0])==ADDRESS && (u(bytes[2])==2 || u(bytes[2]) in info))
        require(checksum(bytes,length)==bytes[length-1]) { "Checksum mismatch" }
        return bytes.copyOfRange(if(response) 4 else 3,length-1)
    }
    private fun checksum(bytes: ByteArray, length: Int) = (0 until length-1).sumOf { u(bytes[it]) }.toByte()
    fun u(b: Byte)=b.toInt() and 255
}

/** Immutable build policy: even constructing the USB port cannot enable live writes. */
object HardwareGate {
    const val LIVE_MOTOR_WRITES = false
    fun check(request: ByteArray) {
        val parsed=MotorWire.parseRequest(request)
        check(LIVE_MOTOR_WRITES || parsed.writes.isEmpty()) { "Physical motor writes are disabled in this simulator build" }
    }
}
