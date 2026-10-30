package dev.stride.lab

import org.junit.Assert.*
import org.junit.Test

class HeartRateIngressTest {
    @Test fun requiresExplicitPhoneSelection() {
        val e = WorkoutEngine()
        assertFalse(HeartRateIngress(e).accept(120, 1_000, 0, 0, "sense2", "session-1"))
        assertEquals(WorkoutEngine.Sensor.SIMULATED, e.snapshot(1_000).sensor)
    }
    @Test fun retiredSessionAndSequenceCannotRearmAutomation() {
        val e = WorkoutEngine(); e.selectSensor(WorkoutEngine.Sensor.PHONE)
        val input = HeartRateIngress(e)
        for (i in 0L..4L) assertTrue(input.accept(120, i * 1_000, i, 0, "sense2", "session-1"))
        e.start("steady", 20, 4_000)
        assertFalse(input.accept(120, 5_000, 4, 0, "sense2", "session-1"))
        assertTrue(input.accept(120, 6_000, 0, 0, "sense2", "session-2"))
        assertEquals(WorkoutEngine.State.SENSOR_HOLD, e.snapshot(6_000).state)
        assertFalse(input.accept(120, 7_000, 10, 0, "sense2", "session-1"))
        assertFalse(e.snapshot(7_000).sensorFresh)
    }
    @Test fun ageAndNegativeAgeAreNotSilentlyFreshened() {
        val e = WorkoutEngine(); e.selectSensor(WorkoutEngine.Sensor.PHONE)
        val input = HeartRateIngress(e)
        assertFalse(input.accept(120, 10_000, 0, 9_000, "sense2", "session-1"))
        try { input.accept(120, 10_000, 1, -1, "sense2", "session-1"); fail() }
        catch (_: IllegalArgumentException) { }
        assertFalse(e.snapshot(10_000).sensorFresh)
    }
}
