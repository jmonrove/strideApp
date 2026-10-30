package dev.stride.lab

import org.junit.Assert.*
import org.junit.Test

class WorkoutEngineTest {
    private fun warm(e: WorkoutEngine, bpm: Int = 120) {
        for (i in 0L..4L) assertTrue(e.acceptHeartRate(bpm, i * 1_000, i, e.snapshot(4_000).sensor))
    }
    private fun advance(e: WorkoutEngine, from: Long, seconds: Int, bpm: Int = 120, seq: Long = 10): WorkoutEngine.View {
        var v = e.snapshot(from)
        for (i in 1..seconds) {
            e.acceptHeartRate(bpm, from + i * 1_000, seq + i, v.sensor)
            v = e.tick(from + i * 1_000)
        }
        return v
    }
    @Test fun requiresExplicitStartWithFreshHistory() {
        val e = WorkoutEngine()
        try { e.start("steady", 20, 0); fail() } catch (_: IllegalStateException) { }
        warm(e)
        assertEquals(WorkoutEngine.State.IDLE, e.snapshot(4_000).state)
        e.start("steady", 20, 4_000)
        assertEquals(0.0, e.snapshot(4_000).speedMph, .0001)
        assertEquals(.05, advance(e, 4_000, 1).speedMph, .0001)
        assertEquals(1.609344, WorkoutEngine.mphToKph(1.0), .000001)
    }
    @Test fun staleSensorHoldsUntilExplicitResume() {
        val e = WorkoutEngine()
        warm(e); e.start("steady", 20, 4_000); advance(e, 4_000, 10)
        for (i in 1..6) e.tick(14_000L + i * 1_000)
        val hold = e.snapshot(20_000)
        assertEquals(WorkoutEngine.State.SENSOR_HOLD, hold.state)
        val after = advance(e, 20_000, 8, seq = 40)
        assertEquals(hold.speedMph, after.speedMph, .0001)
        assertEquals(hold.elapsedSec, after.elapsedSec, .0001)
        assertEquals(WorkoutEngine.State.SENSOR_HOLD, after.state)
        e.resume(28_000)
        assertEquals(WorkoutEngine.State.RUNNING, e.snapshot(28_000).state)
    }
    @Test fun offWristAndReplaysCannotKeepSensorFresh() {
        val e = WorkoutEngine()
        warm(e)
        assertFalse(e.acceptHeartRate(120, 9_000, 4, WorkoutEngine.Sensor.SIMULATED))
        assertFalse(e.snapshot(10_000).sensorFresh)
        e.start("steady", 20, 4_000)
        assertFalse(e.acceptHeartRate(120, 5_000, 5, WorkoutEngine.Sensor.SIMULATED, bodyPresent = false))
        assertEquals(WorkoutEngine.State.SENSOR_HOLD, e.tick(5_000).state)
        assertFalse(e.acceptHeartRate(120, 6_000, 6, WorkoutEngine.Sensor.SIMULATED, ageMs = 6_000))
    }
    @Test fun controllerGapCannotJumpSetpointsOrRoutineTime() {
        val e = WorkoutEngine()
        warm(e); e.start("steady", 20, 4_000)
        val before = advance(e, 4_000, 10)
        e.acceptHeartRate(120, 120_000, 100, WorkoutEngine.Sensor.SIMULATED)
        val after = e.tick(120_000)
        assertEquals(WorkoutEngine.State.SENSOR_HOLD, after.state)
        assertEquals(before.speedMph, after.speedMph, .0001)
        assertEquals(before.elapsedSec, after.elapsedSec, .0001)
    }
    @Test fun manualOverrideClampsAndStaysInControl() {
        val e = WorkoutEngine()
        warm(e); e.start("steady", 20, 4_000); e.manual(100.0, 100.0)
        val v = advance(e, 4_000, 100, bpm = 50)
        assertEquals(WorkoutEngine.State.MANUAL, v.state)
        assertEquals(3.0, v.speedMph, .0001)
        assertEquals(4.0, v.incline, .0001)
    }
    @Test fun pauseFreezesTimeAndStopNeverRestarts() {
        val e = WorkoutEngine()
        warm(e); e.start("steady", 20, 4_000); advance(e, 4_000, 5); e.pause()
        assertEquals(0.0, e.snapshot(9_000).speedMph, .0001)
        assertEquals(5.0, advance(e, 9_000, 5, seq = 40).elapsedSec, .0001)
        e.stop()
        val after = advance(e, 14_000, 20, seq = 80)
        assertEquals(WorkoutEngine.State.STOPPED, after.state)
        assertEquals(0.0, after.speedMph, .0001)
        try { e.resume(34_000); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun everyRoutineRespectsLimitsAndCompletes() {
        for (routine in WorkoutEngine.ROUTINES) {
            val e = WorkoutEngine(WorkoutEngine.Config(maxSpeedMph = 2.2, maxIncline = 2.0, adaptiveSpeed = true))
            warm(e); e.start(routine.id, 1, 4_000)
            var previous = e.snapshot(4_000)
            for (i in 1..60) {
                e.acceptHeartRate(90, 4_000L + i * 1_000, 10L + i, WorkoutEngine.Sensor.SIMULATED)
                val v = e.tick(4_000L + i * 1_000)
                assertTrue(v.speedMph in 0.0..2.2)
                assertTrue(v.incline in 0.0..2.0)
                if (v.state != WorkoutEngine.State.COMPLETE)
                    assertTrue(kotlin.math.abs(v.speedMph - previous.speedMph) <= .050001)
                assertTrue(kotlin.math.abs(v.incline - previous.incline) <= .100001)
                previous = v
            }
            assertEquals(WorkoutEngine.State.COMPLETE, previous.state)
            assertEquals(0.0, previous.speedMph, .0001)
        }
    }
    @Test fun belowBandNeedsSettlingBeforeProposedIncrease() {
        val e = WorkoutEngine(WorkoutEngine.Config(adaptiveSpeed = true))
        warm(e, 90); e.start("steady", 20, 4_000)
        assertEquals(0.0, advance(e, 4_000, 29, 90).incline, .0001)
        val after = advance(e, 33_000, 1, 90, seq = 100)
        assertTrue(after.incline > 0.0 && after.incline <= .100001)
    }
    @Test fun sustainedHighHeartRateReducesPreviouslyAddedWorkload() {
        val e = WorkoutEngine(WorkoutEngine.Config(adaptiveSpeed = true))
        warm(e, 90); e.start("steady", 20, 4_000)
        val low = advance(e, 4_000, 100, 90)
        val high = advance(e, 104_000, 40, 180, seq = 200)
        assertTrue(high.incline < low.incline)
        assertTrue(high.speedMph < low.speedMph)
    }
    @Test fun rejectsNonFiniteNumbersAndBackwardClock() {
        try { WorkoutEngine(WorkoutEngine.Config(maxSpeedMph = Double.NaN)); fail() }
        catch (_: IllegalArgumentException) { }
        val e = WorkoutEngine(); warm(e); e.start("steady", 20, 4_000)
        try { e.manual(Double.POSITIVE_INFINITY, 0.0); fail() } catch (_: IllegalArgumentException) { }
        e.tick(5_000)
        try { e.tick(4_999); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(WorkoutEngine.State.RUNNING, e.snapshot(5_000).state)
    }
}
