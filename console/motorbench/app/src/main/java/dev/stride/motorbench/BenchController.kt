package dev.stride.motorbench

import kotlin.math.abs

/** Manual-only bench policy. Physical arming is impossible in this build. */
class BenchController(private val link: BoardLink) {
    enum class Phase { DISCONNECTED, READY, ARMED, ENABLING, STARTING, RUNNING, STOPPING, FAULT }
    data class Feedback(val mode: Int,val targetSpeed: Double,val targetGrade: Double,val actualSpeed: Double,val actualGrade: Double,val at: Long)
    data class Limits(val minSpeed: Double,val maxSpeed: Double,val minGrade: Double,val maxGrade: Double)
    var phase=Phase.DISCONNECTED
        private set
    var reason="Connect the simulated board"
        private set
    var feedback: Feedback?=null
        private set
    var limits: Limits?=null
        private set
    var stopAcknowledged=false
        private set
    private var identified=false
    private var armDeadline=0L
    private var stageDeadline=0L
    private var lastTick=-1L
    private var lastChange=-1000L
    private var stationarySamples=0
    private var stationarySince=-1L
    private var desiredSpeed=0.0
    private var desiredGrade=0.0
    private var faultStopSent=false
    private val freshMs=750L
    fun connect(now: Long) {
        require(now>=0)
        check(phase in setOf(Phase.DISCONNECTED,Phase.FAULT,Phase.READY)) { "Stop before reconnecting" }
        phase=Phase.DISCONNECTED;identified=false;feedback=null;limits=null;stationarySamples=0;stationarySince=-1;lastTick=now
        try {
            val identity=info(129);require(identity.size>=9)
            val count=MotorWire.u(identity[8]);require(count<=32 && identity.size==9+count)
            val required=MotorWire.STATUS+MotorWire.LIMITS
            require(required.all { f -> f.id/8<count && MotorWire.u(identity[9+f.id/8]) and (1 shl (f.id%8)) != 0 }) { "Required fields missing" }
            val system=info(130);require(system.size>=11)
            require(u32(system,3)==14116L && u32(system,7)==388129L) { "Board identity does not match this console" }
            val version=info(132);require(version.size>=3)
            info(136) // Core command 2 need not appear in optional SupportedCommands.
            val values=read(MotorWire.LIMITS)
            val l=Limits(value(values,MotorWire.Field.MIN_SPEED),value(values,MotorWire.Field.MAX_SPEED),
                value(values,MotorWire.Field.MIN_GRADE),value(values,MotorWire.Field.MAX_GRADE))
            require(l.minSpeed in .1..1.6 && l.maxSpeed in l.minSpeed..30.0 && l.minGrade in -10.0..0.0 && l.maxGrade in 0.0..25.0) { "Invalid or unsupported board limits" }
            limits=l;identified=true;faultStopSent=false;stopAcknowledged=false
            phase=Phase.READY;reason="Simulator connected; obtain stationary feedback, then arm"
            poll(now)
            if(feedback!!.actualSpeed>0.02 || feedback!!.targetSpeed>0.0) fault("Already moving at connection; stop before arming",now)
        } catch(e: Exception) { phase=Phase.FAULT;reason=e.message ?: "Identification failed" }
    }
    private fun info(command: Int): ByteArray { val q=MotorWire.information(command);return MotorWire.reply(q,exchange(q)) }
    private fun exchange(q: ByteArray): ByteArray = link.exchange(q) ?: error("No board reply; no retry")
    private fun read(fields: List<MotorWire.Field>): Map<MotorWire.Field,Double?> {
        val q=MotorWire.values(fields);return MotorWire.readValues(q,exchange(q))
    }
    private fun value(values: Map<MotorWire.Field,Double?>,field: MotorWire.Field) = values[field] ?: error("Missing ${field.name} feedback")
    private fun write(writes: Map<MotorWire.Field,Double>) {
        val q=MotorWire.values(MotorWire.STATUS,writes)
        MotorWire.readValues(q,exchange(q)) // Validate ACK, but never use its cached targets as independent feedback.
    }
    private fun fresh(now: Long): Feedback {
        val f=feedback ?: error("No independent feedback")
        check(now>=f.at && now-f.at<=freshMs) { "Feedback stale or clock changed" }
        return f
    }
    fun arm(now: Long) {
        check(phase==Phase.READY && identified)
        check(link.kind==BoardLink.Kind.SIMULATED && !HardwareGate.LIVE_MOTOR_WRITES) { "Physical motion is not validated or enabled" }
        val f=fresh(now)
        check(f.mode==1 && f.targetSpeed==0.0 && f.actualSpeed<=.02 && stationarySamples>=3 && stationarySince>=0 && now-stationarySince>=400) { "Need stationary, idle feedback with key inserted" }
        check(f.actualGrade in 0.0..maxGrade() && abs(f.targetGrade-f.actualGrade)<=.1) { "Incline must be stationary inside bench limits" }
        faultStopSent=false;stopAcknowledged=false
        armDeadline=now+10000;phase=Phase.ARMED;reason="Armed for 10 seconds; Start requires a separate press"
    }
    fun start(mph: Double,now: Long) {
        check(phase==Phase.ARMED && now<armDeadline) { "Arm again before starting" }
        val f=fresh(now);check(f.mode==1 && f.actualSpeed<=.02 && f.targetSpeed==0.0)
        val target=checkedSpeed(mph)
        try {
            write(mapOf(MotorWire.Field.MODE to 2.0))
            desiredSpeed=target;desiredGrade=f.actualGrade
            phase=Phase.ENABLING;stageDeadline=now+3000;reason="Mode requested; waiting for independent running-mode feedback"
        } catch(e: Exception) { fault(e.message ?: "Mode request failed",now) }
    }
    fun speed(mph: Double,now: Long) {
        check(phase==Phase.RUNNING);val f=fresh(now);check(f.mode==2)
        val target=checkedSpeed(mph)
        require(now>=lastChange && now-lastChange>=500 && abs(target-desiredSpeed)<=.17) { "One 0.1 MPH step per 500 ms" }
        check(abs(f.actualSpeed-desiredSpeed)<=.2) { "Wait for measured speed to catch up" }
        try { write(mapOf(MotorWire.Field.SPEED to target));desiredSpeed=target;lastChange=now;reason="Target acknowledged; measured speed shown separately" }
        catch(e: Exception) { fault(e.message ?: "Speed request failed",now) }
    }
    fun grade(percent: Double,now: Long) {
        check(phase==Phase.RUNNING);val f=fresh(now);check(f.mode==2)
        require(percent.isFinite() && percent in 0.0..maxGrade())
        val target=MotorWire.quantize(MotorWire.Field.GRADE,percent)
        require(now>=lastChange && now-lastChange>=500 && abs(target-desiredGrade)<=.5) { "One 0.5 percent step per 500 ms" }
        check(abs(f.actualGrade-desiredGrade)<=.1) { "Wait for measured grade to catch up" }
        try { write(mapOf(MotorWire.Field.GRADE to target));desiredGrade=target;lastChange=now;reason="Incline target acknowledged; motion remains independently measured" }
        catch(e: Exception) { fault(e.message ?: "Grade request failed",now) }
    }
    private fun checkedSpeed(mph: Double): Double {
        require(mph.isFinite() && mph in 0.0..1.0) { "Bench cap is 1 MPH" }
        val target=MotorWire.quantize(MotorWire.Field.SPEED,mph*1.609344)
        require(target in (limits ?: error("No limits")).minSpeed..minOf(1.6,limits!!.maxSpeed)) { "Outside moving-speed limits" }
        return target
    }
    private fun maxGrade()=minOf(2.0,limits?.maxGrade ?: 0.0)
    fun stop(now: Long) {
        if(phase==Phase.DISCONNECTED) return
        if(phase in setOf(Phase.READY,Phase.ARMED)) { phase=Phase.READY;reason="Disarmed; nothing started";return }
        try {
            val f=fresh(now)
            write(mapOf(MotorWire.Field.SPEED to 0.0,MotorWire.Field.GRADE to f.actualGrade))
            stopAcknowledged=true;desiredSpeed=0.0;desiredGrade=f.actualGrade;stationarySamples=0;stationarySince=-1
            phase=Phase.STOPPING;stageDeadline=now+5000;reason="Stop requested; waiting for measured stationary speed and grade"
        } catch(e: Exception) { fault(e.message ?: "Stop unconfirmed",now) }
    }
    fun tick(now: Long) {
        require(now>=0)
        if(phase==Phase.DISCONNECTED) return
        if(lastTick>=0 && (now<lastTick || now-lastTick>freshMs) && phase in setOf(Phase.ENABLING,Phase.STARTING,Phase.RUNNING,Phase.STOPPING)) {
            lastTick=now;fault("Controller clock gap; no automatic resume",now);return
        }
        lastTick=now
        if(phase==Phase.ARMED && now>=armDeadline) { phase=Phase.READY;reason="Arming expired" }
        try {
            poll(now)
            val f=feedback!!
            if(phase in setOf(Phase.READY,Phase.ARMED) && (f.targetSpeed>0.0 || f.actualSpeed>.02)) {
                fault("Unexpected motion while disarmed; possible competing controller",now);return
            }
            if(phase==Phase.ARMED && f.mode!=1) {
                phase=Phase.READY;reason="Arming cancelled by board mode/key change; re-arm required"
            }
            if(phase in setOf(Phase.ENABLING,Phase.STARTING,Phase.RUNNING,Phase.STOPPING) &&
                (f.actualSpeed>1.7 || f.actualGrade < -.1 || f.actualGrade>maxGrade()+.1)) {
                fault("Measured motion outside simulator bench envelope",now);return
            }
            if((phase in setOf(Phase.STARTING,Phase.RUNNING) && f.mode!=2) ||
                (phase in setOf(Phase.ENABLING,Phase.STOPPING) && f.mode !in setOf(1,2))) { fault("Safety key removed or unexpected board mode",now);return }
            when(phase) {
                Phase.ENABLING -> if(f.mode==2) {
                    write(mapOf(MotorWire.Field.SPEED to desiredSpeed));lastChange=now
                    phase=Phase.STARTING;stageDeadline=now+5000;reason="Speed target acknowledged; waiting for measured motion"
                } else if(now>=stageDeadline) fault("Running mode never confirmed",now)
                Phase.STARTING -> if(f.actualSpeed>0 && abs(f.actualSpeed-desiredSpeed)<=.15) {
                    phase=Phase.RUNNING;reason="Simulated measured motion confirmed"
                } else if(now>=stageDeadline) fault("Target ACK did not produce measured motion",now)
                Phase.RUNNING -> if(abs(f.targetSpeed-desiredSpeed)>.02 || abs(f.targetGrade-desiredGrade)>.02) fault("Unexpected target change; possible competing controller",now)
                Phase.STOPPING -> if(stationarySamples>=3 && stationarySince>=0 && now-stationarySince>=400) {
                    write(mapOf(MotorWire.Field.MODE to 1.0))
                    phase=Phase.READY;reason="Measured stop confirmed; re-arm required";stopAcknowledged=true
                } else if(now>=stageDeadline) fault("Stop not confirmed by independent feedback",now)
                else -> Unit
            }
        } catch(e: Exception) { fault(e.message ?: "Invalid board response",now) }
    }
    private fun poll(now: Long) {
        val v=read(MotorWire.STATUS);val l=limits ?: error("No limits")
        val mode=value(v,MotorWire.Field.MODE);require(mode % 1 == 0.0)
        val f=Feedback(mode.toInt(),value(v,MotorWire.Field.SPEED),value(v,MotorWire.Field.GRADE),
            value(v,MotorWire.Field.ACTUAL_SPEED),value(v,MotorWire.Field.ACTUAL_GRADE),now)
        require(f.targetSpeed in 0.0..l.maxSpeed && f.actualSpeed in 0.0..l.maxSpeed && f.targetGrade in l.minGrade..l.maxGrade && f.actualGrade in l.minGrade..l.maxGrade)
        val previous=feedback
        if(previous==null || now>previous.at) {
            val stationary=f.actualSpeed<=.02 && abs(f.actualGrade-f.targetGrade)<=.05 &&
                (previous==null || abs(f.actualGrade-previous.actualGrade)<=.05)
            if(stationary) { if(stationarySince<0) stationarySince=now;stationarySamples++ }
            else { stationarySamples=0;stationarySince=-1 }
        }
        feedback=f
    }
    private fun fault(message: String,now: Long) {
        phase=Phase.FAULT;reason=message
        if(faultStopSent || !identified) return
        faultStopSent=true
        val f=feedback
        val writes=mutableMapOf(MotorWire.Field.SPEED to 0.0)
        if(f!=null && now>=f.at && now-f.at<=freshMs) writes[MotorWire.Field.GRADE]=f.actualGrade
        stopAcknowledged=try { write(writes);true } catch(_:Exception) { false }
        reason += if(stopAcknowledged) "; stop request ACK only; recheck motion" else "; STOP UNCONFIRMED"
    }
    fun leave(now: Long) { if(phase in setOf(Phase.READY,Phase.ARMED)) {phase=Phase.READY;reason="Disarmed on leaving"} else stop(now) }
    fun targetMph()=desiredSpeed/1.609344
    fun targetGrade()=desiredGrade
    private fun u32(p: ByteArray,at: Int)=(0 until 4).fold(0L){n,i->n or (MotorWire.u(p[at+i]).toLong() shl (8*i))}
}
