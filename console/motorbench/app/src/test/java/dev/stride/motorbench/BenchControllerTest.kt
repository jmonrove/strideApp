package dev.stride.motorbench
import org.junit.Assert.*
import org.junit.Test
class BenchControllerTest {
 private class Rig(val board:SimulatedBoard=SimulatedBoard(),kind:BoardLink.Kind=BoardLink.Kind.SIMULATED) {
  var now=10000L
  val link=object:BoardLink {override val kind=kind;override fun exchange(request:ByteArray)=board.exchange(request);override fun close()=board.close()}
  val c=BenchController(link)
  fun step(ms:Long=200,n:Int=1) {repeat(n){now+=ms;board.advance(ms);c.tick(now)}}
  fun ready() {board.key(true);c.connect(now);step(n=2)}
  fun run() {ready();c.arm(now);c.start(.5,now);repeat(30){if(c.phase!=BenchController.Phase.RUNNING)step()};assertEquals(BenchController.Phase.RUNNING,c.phase)}
  fun controls()=board.trace.filter {it.writes.isNotEmpty()}
 }
 private fun rejected(block:()->Unit) {try{block();fail("Expected guard rejection")}catch(_:IllegalStateException){}catch(_:IllegalArgumentException){}}
 @Test fun connectingAndPollingNeverStartOrUnlock() {
  val h=Rig();h.c.connect(h.now);h.step(n=10)
  assertTrue(h.controls().isEmpty());assertEquals(BenchController.Phase.READY,h.c.phase)
  assertFalse(h.board.trace.any {it.command in setOf(4,5,9,56,144,146)})
 }
 @Test fun wrongIdentityOrLimitsFailBeforeAnyControlRequest() {
  for(b in listOf(SimulatedBoard().apply {model=1},SimulatedBoard().apply {part=1},SimulatedBoard().apply {maxSpeed=.2})) {
   val h=Rig(b);h.c.connect(h.now);assertEquals(BenchController.Phase.FAULT,h.c.phase);assertTrue(h.controls().isEmpty())
  }
 }
 @Test fun securityBlockDoesNotTriggerAnUnlockOrAutomaticRetry() {
  val h=Rig();h.board.denyRequests=true;h.c.connect(h.now)
  assertEquals(BenchController.Phase.FAULT,h.c.phase);assertEquals(1,h.board.trace.size);assertTrue(h.controls().isEmpty())
 }
 @Test fun physicalConnectionsCannotArmEvenWithValidIdentityAndStationaryFeedback() {
  val h=Rig(kind=BoardLink.Kind.PHYSICAL);h.ready();rejected {h.c.arm(h.now)};assertTrue(h.controls().isEmpty())
 }
 @Test fun keyIdleAndStationaryFeedbackAreRequiredBeforeArming() {
  val h=Rig();h.c.connect(h.now);h.step(n=3);rejected {h.c.arm(h.now)}
  h.board.key(true);h.c.connect(h.now);rejected {h.c.arm(h.now)};h.step(n=2);h.c.arm(h.now)
  assertEquals(BenchController.Phase.ARMED,h.c.phase);assertTrue(h.controls().isEmpty())
 }
 @Test fun duplicatePollClockCannotFakeAStationaryWindow() {
  val h=Rig();h.board.key(true);h.c.connect(h.now);repeat(6){h.c.tick(h.now)};rejected {h.c.arm(h.now)}
 }
 @Test fun armRequiresAnExplicitStartAndExpires() {
  val h=Rig();h.ready();h.c.arm(h.now);h.step(n=51)
  assertEquals(BenchController.Phase.READY,h.c.phase);rejected {h.c.start(.5,h.now)};assertTrue(h.controls().isEmpty())
 }
 @Test fun modeConfirmationAndSpeedRequestAreSeparateFromMeasuredMotion() {
  val h=Rig();h.ready();h.c.arm(h.now);h.c.start(.5,h.now)
  assertEquals(BenchController.Phase.ENABLING,h.c.phase);assertEquals(0.0,h.board.targetSpeed,0.0)
  assertEquals(setOf(MotorWire.Field.MODE),h.controls().single().writes.keys)
  h.step();assertEquals(BenchController.Phase.STARTING,h.c.phase);assertEquals(.8,h.board.targetSpeed,0.0)
  assertEquals(0.0,h.c.feedback!!.actualSpeed,0.0)
  val trace=h.board.trace;val mode=trace.indexOfFirst {MotorWire.Field.MODE in it.writes};val speed=trace.indexOfFirst {(it.writes[MotorWire.Field.SPEED]?:0.0)>0}
  assertTrue(trace.subList(mode+1,speed).any {it.command==2 && it.writes.isEmpty()})
 }
 @Test fun ignoredModeWriteNeverAllowsAPositiveSpeedWrite() {
  val h=Rig();h.ready();h.board.ignoreModeWrites=true;h.c.arm(h.now);h.c.start(.5,h.now);h.step(n=17)
  assertEquals(BenchController.Phase.FAULT,h.c.phase);assertFalse(h.controls().any {(it.writes[MotorWire.Field.SPEED]?:0.0)>0})
 }
 @Test fun acknowledgedSpeedWithoutMeasuredMotionTimesOutAndStops() {
  val h=Rig();h.ready();h.board.freezeActual=true;h.c.arm(h.now);h.c.start(.5,h.now);h.step(n=28)
  assertEquals(BenchController.Phase.FAULT,h.c.phase);assertEquals(0.0,h.board.targetSpeed,0.0)
  assertTrue(h.c.reason.contains("measured motion"))
 }
 @Test fun benchCapsAndRateLimitsAreIndependentOfBoardsLargerLimits() {
  val h=Rig();h.run();rejected {h.c.speed(5.0,h.now)};rejected {h.c.grade(15.0,h.now)};rejected {h.c.grade(-1.0,h.now)}
  rejected {h.c.speed(Double.NaN,h.now)};h.step(n=3);h.c.speed(h.c.targetMph()+.1,h.now)
  rejected {h.c.speed(h.c.targetMph()+.1,h.now)};rejected {h.c.grade(.5,h.now)}
  assertTrue(h.board.targetSpeed<=1.6);assertTrue(h.board.targetGrade<=2.0)
 }
 @Test fun stopWaitsForActualFeedbackThenRequiresNewArming() {
  val h=Rig();h.run();h.c.stop(h.now);assertEquals(BenchController.Phase.STOPPING,h.c.phase)
  assertTrue(h.c.stopAcknowledged);assertTrue(h.c.feedback!!.actualSpeed>0)
  h.step(n=22);assertEquals(BenchController.Phase.READY,h.c.phase);assertEquals(0.0,h.board.actualSpeed,0.0)
  rejected {h.c.start(.5,h.now)}
 }
 @Test fun stopDoesNotAutomaticallyLowerTheDeckToZero() {
  val h=Rig();h.run();h.step(n=3);h.c.grade(.5,h.now);h.step(n=2)
  val measured=h.c.feedback!!.actualGrade;assertTrue(measured>0)
  h.c.stop(h.now);assertEquals(measured,h.board.targetGrade,.011)
 }
 @Test fun stopCancelsModeEnablementWithoutEverRequestingPositiveSpeed() {
  val h=Rig();h.ready();h.c.arm(h.now);h.c.start(.5,h.now);h.c.stop(h.now);h.step(n=5)
  assertEquals(BenchController.Phase.READY,h.c.phase);assertFalse(h.controls().any {(it.writes[MotorWire.Field.SPEED]?:0.0)>0})
 }
 @Test fun removingKeyFaultsAndReinsertionDoesNotResume() {
  val h=Rig();h.run();h.board.key(false);h.step();assertEquals(BenchController.Phase.FAULT,h.c.phase)
  val count=h.controls().size;h.board.key(true);h.step(n=10)
  assertEquals(BenchController.Phase.FAULT,h.c.phase);assertEquals(count,h.controls().size);rejected {h.c.start(.5,h.now)}
 }
 @Test fun timeoutAndChecksumFaultsDoNotRetryOrRecoverAutomatically() {
  for(corrupt in listOf(false,true)) {
   val h=Rig();h.run();if(corrupt)h.board.corruptReply=true else h.board.noReply=true
   h.step();assertEquals(BenchController.Phase.FAULT,h.c.phase);val count=h.controls().size
   h.board.corruptReply=false;h.board.noReply=false;h.step(n=10)
   assertEquals(BenchController.Phase.FAULT,h.c.phase);assertEquals(count,h.controls().size)
  }
 }
 @Test fun clockGapOrRollbackNeverContinuesAnActiveSession() {
  for(delta in listOf(1000L,-1L)) {
   val h=Rig();h.run();h.c.tick(h.now+delta);assertEquals(BenchController.Phase.FAULT,h.c.phase)
   assertEquals(0.0,h.board.targetSpeed,0.0)
  }
 }
 @Test fun leavingDisarmsOrRequestsStopWithoutAutomaticResume() {
  val h=Rig();h.ready();h.c.arm(h.now);h.c.leave(h.now);assertEquals(BenchController.Phase.READY,h.c.phase)
  h.c.arm(h.now);h.c.start(.5,h.now);h.c.leave(h.now);assertEquals(0.0,h.board.targetSpeed,0.0)
  assertEquals(BenchController.Phase.STOPPING,h.c.phase)
 }
 @Test fun startingOnAnAlreadyMovingBoardCannotBecomeArmed() {
  val h=Rig();h.board.key(true);h.board.mode=2.0;h.board.targetSpeed=.8;h.board.actualSpeed=.8
  h.c.connect(h.now);assertEquals(BenchController.Phase.FAULT,h.c.phase);rejected {h.c.arm(h.now)}
 }
 @Test fun aNewExplicitRunGetsItsOwnFaultStopAttempt() {
  val h=Rig();h.run();h.board.key(false);h.step();h.board.key(true);h.step(n=10)
  h.c.stop(h.now);h.step(n=8);h.c.arm(h.now);h.c.start(.5,h.now);h.step(n=15)
  val count=h.controls().size;h.board.key(false);h.step();assertTrue(h.controls().size>count)
 }
 @Test fun unexpectedMotionWhileDisarmedLatchesAFaultAndRequestsStop() {
  val h=Rig();h.ready();h.board.mode=2.0;h.board.targetSpeed=.8;h.step()
  assertEquals(BenchController.Phase.FAULT,h.c.phase);assertEquals(0.0,h.board.targetSpeed,0.0)
  assertTrue(h.c.reason.contains("Unexpected motion"))
 }
 @Test fun keyRemovalCancelsArmingEvenWhenReinsertedBeforeStart() {
  val h=Rig();h.ready();h.c.arm(h.now);h.board.key(false);h.step();h.board.key(true);h.step()
  assertEquals(BenchController.Phase.READY,h.c.phase);rejected {h.c.start(.5,h.now)};assertTrue(h.controls().isEmpty())
 }
 @Test fun measuredOverspeedOrGradeOutsideBenchEnvelopeLatchesAFault() {
  for(grade in listOf(false,true)) {
   val h=Rig();h.run();h.board.freezeActual=true
   if(grade)h.board.actualGrade=2.5 else h.board.actualSpeed=2.0
   h.step();assertEquals(BenchController.Phase.FAULT,h.c.phase);assertEquals(0.0,h.board.targetSpeed,0.0)
   assertTrue(h.c.reason.contains("bench envelope"))
  }
 }
}
