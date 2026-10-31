package dev.stride.motorbench
import org.junit.Assert.*
import org.junit.Test
class MotorWireTest {
 private fun bytes(vararg ints:Int)=ints.map { it.toByte() }.toByteArray()
 private fun rejected(block:()->Unit) { try {block();fail("Expected rejection")}catch(_:IllegalArgumentException){} }
 @Test fun actualReadMatchesTheDocumentedGoldenFrame() {
  assertArrayEquals(bytes(4,9,2,0,3,0,0,3,0x15),MotorWire.values(listOf(MotorWire.Field.ACTUAL_SPEED,MotorWire.Field.ACTUAL_GRADE)))
 }
 @Test fun modeSpeedAndNegativeGradeMatchIndependentGoldenFrames() {
  assertArrayEquals(bytes(4,9,2,2,0,16,2,0,0x23),MotorWire.values(emptyList(),mapOf(MotorWire.Field.MODE to 2.0)))
  assertArrayEquals(bytes(4,9,2,1,1,80,0,0,0x61),MotorWire.values(emptyList(),mapOf(MotorWire.Field.SPEED to .8)))
  assertArrayEquals(bytes(4,9,2,1,2,0xD4,0xFE,0,0xE4),MotorWire.values(emptyList(),mapOf(MotorWire.Field.GRADE to -3.0)))
 }
 @Test fun vendorQuantizationTruncatesInsteadOfRounding() {
  assertEquals(.8,MotorWire.quantize(MotorWire.Field.SPEED,.804672),0.0)
  assertEquals(-1.23,MotorWire.quantize(MotorWire.Field.GRADE,-1.239),0.0)
 }
 @Test fun readonlyFieldsCannotBeWrittenAndModeCannotTravelWithMotion() {
  rejected {MotorWire.values(writes=mapOf(MotorWire.Field.ACTUAL_SPEED to 1.0))}
  rejected {MotorWire.values(writes=mapOf(MotorWire.Field.MODE to 2.0,MotorWire.Field.SPEED to .8))}
  rejected {MotorWire.values(writes=mapOf(MotorWire.Field.MODE to 8.0))}
  rejected {MotorWire.values(listOf(MotorWire.Field.MODE,MotorWire.Field.MODE))}
 }
 @Test fun noResetUnlockConnectOrFirmwareCommandCanBeBuilt() {
  for(command in listOf(0,4,5,9,56,128,144,146,255)) rejected {MotorWire.information(command)}
 }
 @Test fun nonFiniteAndOutOfEncodingRangeValuesFailBeforeSending() {
  for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,101.0)) rejected {MotorWire.values(writes=mapOf(MotorWire.Field.SPEED to value))}
  rejected {MotorWire.encode(MotorWire.Field.MODE,2.5)}
 }
 @Test fun simulatorRepliesRequireCommandIdentityStatusAndChecksum() {
  val b=SimulatedBoard();val q=MotorWire.values();val reply=b.exchange(q)!!
  assertEquals(0.0,MotorWire.readValues(q,reply)[MotorWire.Field.ACTUAL_SPEED]!!,0.0)
  val corrupt=reply.copyOf();corrupt[4]=(corrupt[4]+1).toByte();rejected {MotorWire.readValues(q,corrupt)}
  rejected {MotorWire.readValues(q,reply.copyOf(3))}
  rejected {MotorWire.reply(MotorWire.information(129),reply)}
  val wrongDevice=reply.copyOf();wrongDevice[0]=5;wrongDevice[wrongDevice.lastIndex]=(wrongDevice.last()+1).toByte();rejected {MotorWire.readValues(q,wrongDevice)}
  b.denyRequests=true;rejected {MotorWire.reply(q,b.exchange(q)!!)}
 }
 @Test fun allFFActualValuesAreMissingAndPaddedReportsRespectDeclaredLength() {
  val q=MotorWire.values(listOf(MotorWire.Field.ACTUAL_SPEED,MotorWire.Field.ACTUAL_GRADE))
  val reply=MotorWire.frame(2,bytes(2,255,255,255,255))
  assertTrue(MotorWire.readValues(q,reply.copyOf(64)).values.all { it==null })
 }
 @Test fun physicalGateRejectsEveryControlPacketIncludingStopBeforeIO() {
  assertFalse(HardwareGate.LIVE_MOTOR_WRITES)
  for(writes in listOf(mapOf(MotorWire.Field.MODE to 2.0),mapOf(MotorWire.Field.SPEED to .8),
   mapOf(MotorWire.Field.SPEED to 0.0),mapOf(MotorWire.Field.GRADE to .5))) {
    try {HardwareGate.check(MotorWire.values(writes=writes));fail("Physical control passed")}catch(_:IllegalStateException){}
  }
  HardwareGate.check(MotorWire.values());HardwareGate.check(MotorWire.information(129))
 }
 @Test fun malformedOrExtendedRequestsCannotBypassTheHardwareGate() {
  val q=MotorWire.values();rejected {HardwareGate.check(q+byteArrayOf(0))}
  val bad=q.copyOf();bad[3]=127;rejected {HardwareGate.check(bad)}
 }
}
