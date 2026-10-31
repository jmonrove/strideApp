package dev.stride.motorbench

interface BoardLink {
    enum class Kind { SIMULATED, PHYSICAL }
    val kind: Kind
    fun exchange(request: ByteArray): ByteArray?
    fun close()
}

/** Acknowledged targets and measured values are deliberately independent. */
class SimulatedBoard : BoardLink {
    override val kind = BoardLink.Kind.SIMULATED
    var model = 14116L
    var part = 388129L
    var keyPresent = false
        private set
    var noReply = false
    var corruptReply = false
    var freezeActual = false
    var denyRequests = false
    var ignoreModeWrites = false
    var minSpeed = .8
    var maxSpeed = 19.31
    var minGrade = -3.0
    var maxGrade = 15.0
    var mode = 8.0
    var targetSpeed = 0.0
    var targetGrade = 0.0
    var actualSpeed = 0.0
    var actualGrade = 0.0
    var closed = false
    val trace = mutableListOf<MotorWire.Request>()
    fun key(present: Boolean) {
        keyPresent=present
        if(!present) { mode=8.0;targetSpeed=0.0 } else if(mode==8.0) mode=1.0
    }
    fun advance(ms: Long) {
        require(ms >= 0)
        if(!freezeActual) {
            actualSpeed=approach(actualSpeed,if(keyPresent) targetSpeed else 0.0,ms*.0004)
            actualGrade=approach(actualGrade,targetGrade,ms*.0005)
        }
    }
    private fun approach(value: Double,target: Double,step: Double) = when {
        value<target -> minOf(target,value+step)
        value>target -> maxOf(target,value-step)
        else -> value
    }
    override fun exchange(request: ByteArray): ByteArray? {
        val q=MotorWire.parseRequest(request);trace.add(q)
        if(closed || noReply) return null
        if(denyRequests) return response(q.command,byteArrayOf(),8)
        q.writes.forEach { (field,value) -> when(field) {
            MotorWire.Field.MODE -> if(keyPresent && !ignoreModeWrites) mode=value
            MotorWire.Field.SPEED -> if(value==0.0 || keyPresent && mode==2.0) targetSpeed=value
            MotorWire.Field.GRADE -> targetGrade=value
            else -> error("Unexpected write")
        } }
        val payload=when(q.command) {
            129 -> ByteArray(13).also { p -> p[0]=82;p[1]=1;p[8]=4
                MotorWire.Field.entries.forEach { f -> p[9+f.id/8]=(MotorWire.u(p[9+f.id/8]) or (1 shl (f.id%8))).toByte() } }
            130 -> ByteArray(11).also { p -> put32(p,3,model);put32(p,7,part) }
            132 -> byteArrayOf(82,55,0)
            136 -> byteArrayOf(129.toByte(),130.toByte(),132.toByte(),136.toByte(),144.toByte())
            2 -> q.reads.flatMap { f -> MotorWire.encode(f,when(f) {
                MotorWire.Field.SPEED->targetSpeed;MotorWire.Field.GRADE->targetGrade;MotorWire.Field.MODE->mode
                MotorWire.Field.ACTUAL_SPEED->actualSpeed;MotorWire.Field.ACTUAL_GRADE->actualGrade
                MotorWire.Field.MAX_SPEED->maxSpeed;MotorWire.Field.MIN_SPEED->minSpeed
                MotorWire.Field.MAX_GRADE->maxGrade;MotorWire.Field.MIN_GRADE->minGrade
            }).toList() }.toByteArray()
            else -> error("Unsupported command")
        }
        return response(q.command,payload).also { if(corruptReply) it[it.lastIndex]=(it.last()+1).toByte() }
    }
    private fun response(command: Int,payload: ByteArray,status: Int=2) = MotorWire.frame(command,byteArrayOf(status.toByte())+payload)
    private fun put32(p: ByteArray,at: Int,value: Long) { repeat(4){p[at+it]=(value shr (8*it)).toByte()} }
    override fun close() { closed=true }
}
