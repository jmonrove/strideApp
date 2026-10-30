package dev.stride.lab
import org.junit.Assert.*
import org.junit.Test
class DisplayHeartRateTest {
 @Test fun expiresPollsAndRejectsReplayWithoutChangingTheEngine() {
  val d=DisplayHeartRate()
  assertTrue(d.accept(120,10000,1,100,"sense2_exercise","watch-001","display_only"))
  assertEquals(120,d.snapshot(10000).getInt("bpm")); assertTrue(d.snapshot(10000).isNull("acquisitionAgeMs"))
  assertFalse(d.accept(121,11000,1,0,"sense2_exercise","watch-001","display_only"))
  assertTrue(d.snapshot(15001).isNull("bpm")); assertEquals("expired",d.snapshot(15001).getString("status"))
  assertFalse(d.accept(122,16000,2,5001,"sense2_exercise","watch-001","display_only"))
  assertTrue(d.accept(null,16000,3,0,"sense2_exercise","watch-001","inactive")); assertTrue(d.snapshot(16000).isNull("bpm"))
  assertTrue(d.accept(122,17000,1,0,"sense2_exercise","watch-002","display_only"))
  assertFalse(d.accept(123,18000,4,0,"sense2_exercise","watch-001","display_only"))
 }
 @Test fun displaySamplesCannotSatisfyGuidancePreflight() {
  var time=10000L
  val session=LabSession(monotonicNow={time},startClock=false)
  try {
   session.selectSensor(WorkoutEngine.Sensor.PHONE)
   for(i in 1L..6L){time+=1000;session.displayHeartRate.accept(120,time,i,0,"sense2_exercise","watch-001","display_only")}
   assertFalse(session.status().getBoolean("heartRateFresh"));assertTrue(session.status().isNull("heartRateBpm"))
   assertEquals(120,session.status().getJSONObject("displayHeartRate").getInt("bpm"))
   try {session.engine.start("steady",20,time);fail("Display input started guidance")}catch(_:IllegalStateException){}
  } finally {session.close()}
 }
}
