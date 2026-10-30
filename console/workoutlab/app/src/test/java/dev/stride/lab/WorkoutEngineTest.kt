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
    @Test fun manualSessionStartsAndResumesWithoutInventingSensorReadings() {
        val e = WorkoutEngine()
        e.selectSensor(WorkoutEngine.Sensor.PHONE)
        e.startManual(10, 0)
        val initial = e.snapshot(0)
        assertTrue(initial.manualSession)
        assertNull(initial.routineId)
        assertNull(initial.bpm)
        assertFalse(initial.sensorFresh)
        assertEquals(2.0, initial.targetSpeedMph, .0001)
        assertEquals(WorkoutEngine.State.MANUAL, e.tick(1_000).state)
        e.pause()
        assertTrue(e.snapshot(1_000).canResumeManual)
        try { e.resume(1_000); fail() } catch (_: IllegalStateException) { }
        e.resumeManual(20_000)
        val after = e.tick(21_000)
        assertEquals(WorkoutEngine.State.MANUAL, after.state)
        assertEquals(2.0, after.elapsedSec, .0001)
        assertFalse(after.sensorFresh)
        e.stop()
        try { e.resumeManual(22_000); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun pausedGuidedManualOverrideCanResumeManuallyWithStaleHr() {
        val e = WorkoutEngine()
        warm(e); e.start("steady", 20, 4_000)
        e.manual(1.5, 2.0); e.tick(5_000); e.pause()
        assertTrue(e.snapshot(20_000).canResumeManual)
        try { e.resume(20_000); fail() } catch (_: IllegalStateException) { }
        e.resumeManual(20_000)
        assertEquals(WorkoutEngine.State.MANUAL, e.tick(21_000).state)
        assertEquals(1.5, e.snapshot(21_000).targetSpeedMph, .0001)
    }
    @Test fun changingSettingsDuringAnyActiveSessionIsRejected() {
        val e = WorkoutEngine()
        e.reconfigure(e.config.copy(maxSpeedMph = 2.2, maxIncline = 1.0))
        e.startManual(1, 0)
        for (pause in listOf(false, true)) {
            if (pause) e.pause()
            try { e.reconfigure(e.config.copy(maxSpeedMph = 3.0)); fail() }
            catch (_: IllegalStateException) { }
            assertEquals(2.2, e.config.maxSpeedMph, .0001)
        }
        e.stop()
        e.reconfigure(e.config.copy(maxSpeedMph = 3.0))
        assertEquals(0.0, e.snapshot(0).speedMph, .0001)
        assertEquals(0.0, e.snapshot(0).incline, .0001)
        assertEquals(WorkoutEngine.State.STOPPED, e.snapshot(0).state)
    }
    @Test fun distanceUsesRampedProposalAndDoesNotCatchUpPausedOrMissingClockTime() {
        val e = WorkoutEngine()
        e.startManual(10, 0)
        for (i in 1..20) e.tick(i * 1_000L)
        val before = e.snapshot(20_000)
        assertEquals(1.0, before.speedMph, .00001)
        assertEquals(10.0 / 3600.0, before.distanceMiles, .000001)
        val gap = e.tick(100_000)
        assertEquals(WorkoutEngine.State.PAUSED, gap.state)
        assertEquals(before.distanceMiles, gap.distanceMiles, .000001)
        assertEquals(before.elapsedSec, gap.elapsedSec, .000001)
        e.resumeManual(101_000); e.tick(102_000)
        assertEquals(before.distanceMiles + .025 / 3600.0, e.snapshot(102_000).distanceMiles, .000001)
        e.stop(); e.tick(103_000)
        val firstSessionId = e.snapshot(103_000).sessionId
        e.startManual(10, 104_000)
        assertEquals(0.0, e.snapshot(104_000).distanceMiles, .000001)
        assertTrue(e.snapshot(104_000).sessionId > firstSessionId)
    }
    @Test fun heartRateSummaryIsBoundedAndNeverMixesPhoneAndSyntheticSources() {
        val e = WorkoutEngine()
        e.startManual(10, 0)
        for (i in 1..300) {
            e.acceptHeartRate(if (i == 150) 160 else 120, i * 1_000L, i.toLong(), WorkoutEngine.Sensor.SIMULATED)
            e.tick(i * 1_000L)
        }
        val before = e.snapshot(300_000)
        assertTrue(before.hrSamples.size in 1..120)
        assertEquals(160, before.peakHeartRateBpm)
        assertEquals(120.0 + 40.0 / 300, before.averageHeartRateBpm!!, .000001)
        assertTrue(before.hrSamples.all { it.source == WorkoutEngine.Sensor.SIMULATED })
        e.selectSensor(WorkoutEngine.Sensor.PHONE)
        assertNull(e.snapshot(300_000).averageHeartRateBpm)
        assertTrue(e.snapshot(300_000).hrSamples.isEmpty())
        e.acceptHeartRate(140, 301_000, 1, WorkoutEngine.Sensor.PHONE)
        e.acceptHeartRate(200, 301_100, 2, WorkoutEngine.Sensor.PHONE)
        assertEquals(140.0, e.snapshot(301_100).averageHeartRateBpm!!, .000001)
        assertEquals(1, e.snapshot(301_100).hrSamples.size)
        e.resetSensorHistory()
        assertNull(e.snapshot(301_100).peakHeartRateBpm)
        assertTrue(e.snapshot(301_100).hrSamples.isEmpty())
    }

    @Test fun sessionSummaryExcludesReadingsBeforeStartWhilePausedAndAfterStop() {
        val e = WorkoutEngine()
        warm(e)
        assertTrue(e.snapshot(4_000).hrSamples.isEmpty())
        e.start("steady", 10, 4_000)
        e.acceptHeartRate(121, 5_000, 5, WorkoutEngine.Sensor.SIMULATED)
        e.tick(5_000)
        e.pause()
        e.acceptHeartRate(200, 6_000, 6, WorkoutEngine.Sensor.SIMULATED)
        assertEquals(121.0, e.snapshot(6_000).averageHeartRateBpm!!, .000001)
        e.stop()
        e.acceptHeartRate(220, 7_000, 7, WorkoutEngine.Sensor.SIMULATED)
        assertEquals(121, e.snapshot(7_000).peakHeartRateBpm)
        assertEquals(1, e.snapshot(7_000).hrSamples.size)
        e.startManual(10, 8_000)
        assertNull(e.snapshot(8_000).averageHeartRateBpm)
        assertTrue(e.snapshot(8_000).hrSamples.isEmpty())
    }

    @Test fun downsampledContinuousHeartRateKeepsOneConnectedSegment() {
        val e = WorkoutEngine()
        e.startManual(120, 0)
        for (i in 1..1_800) {
            e.acceptHeartRate(120 + i % 4, i * 1_000L, i.toLong(), WorkoutEngine.Sensor.SIMULATED)
            e.tick(i * 1_000L)
        }
        val points = e.snapshot(1_800_000).hrSamples
        assertTrue(points.size in 2..120)
        assertEquals(setOf(0), points.map { it.segmentId }.toSet())
        assertTrue(points.zipWithNext().any { (a, b) -> b.elapsedSec - a.elapsedSec > 5 })
        assertTrue(points.zipWithNext().all { (a, b) -> a.segmentId == b.segmentId })
    }
    @Test fun pauseAndResumeCreateAGraphBreakEvenThoughWorkoutElapsedTimeWasFrozen() {
        val e = WorkoutEngine()
        e.startManual(10, 0)
        for (i in 1..5) {
            e.acceptHeartRate(120, i * 1_000L, i.toLong(), WorkoutEngine.Sensor.SIMULATED)
            e.tick(i * 1_000L)
        }
        val before = e.snapshot(5_000).hrSamples.last()
        e.pause()
        e.acceptHeartRate(125, 20_000, 20, WorkoutEngine.Sensor.SIMULATED)
        e.resumeManual(20_000)
        e.acceptHeartRate(124, 21_000, 21, WorkoutEngine.Sensor.SIMULATED)
        e.tick(21_000)
        val points = e.snapshot(21_000).hrSamples
        assertEquals(before.elapsedSec + 1.0, points.last().elapsedSec, .000001)
        assertTrue(points.last().segmentId > before.segmentId)
        e.acceptHeartRate(123, 22_000, 22, WorkoutEngine.Sensor.SIMULATED)
        assertEquals(points.last().segmentId, e.snapshot(22_000).hrSamples.last().segmentId)
    }
    @Test fun offWristInvalidStaleAndMissingReadingsBreakTheGraphWithoutSplittingFreshContinuity() {
        val e = WorkoutEngine()
        e.startManual(10, 0)
        fun reading(at: Long, sequence: Long = at / 1_000, age: Long = 0, present: Boolean = true) {
            e.acceptHeartRate(120, at, sequence, WorkoutEngine.Sensor.SIMULATED, ageMs = age, bodyPresent = present)
            e.tick(at)
        }
        reading(1_000); reading(2_000)
        assertEquals(0, e.snapshot(2_000).hrSamples.last().segmentId)
        reading(3_000, present = false); reading(4_000)
        val offWristSegment = e.snapshot(4_000).hrSamples.last().segmentId
        assertTrue(offWristSegment > 0)
        reading(5_000, age = 6_000); reading(6_000)
        val staleSegment = e.snapshot(6_000).hrSamples.last().segmentId
        assertTrue(staleSegment > offWristSegment)
        for (i in 7..12) e.tick(i * 1_000L)
        reading(13_000)
        val missingSegment = e.snapshot(13_000).hrSamples.last().segmentId
        assertTrue(missingSegment > staleSegment)
        reading(14_000)
        assertEquals(missingSegment, e.snapshot(14_000).hrSamples.last().segmentId)
    }
    @Test fun controllerHeartbeatGapCreatesANewHistorySegmentAndNewSummaryRestoresSegmentZero() {
        val e = WorkoutEngine()
        e.startManual(10, 0)
        e.acceptHeartRate(120, 1_000, 1, WorkoutEngine.Sensor.SIMULATED); e.tick(1_000)
        val firstSegment = e.snapshot(1_000).hrSamples.last().segmentId
        e.tick(20_000)
        assertEquals(WorkoutEngine.State.PAUSED, e.snapshot(20_000).state)
        e.resumeManual(20_000)
        e.acceptHeartRate(121, 21_000, 21, WorkoutEngine.Sensor.SIMULATED); e.tick(21_000)
        assertTrue(e.snapshot(21_000).hrSamples.last().segmentId > firstSegment)
        e.stop(); e.startManual(10, 22_000)
        e.acceptHeartRate(122, 23_000, 23, WorkoutEngine.Sensor.SIMULATED)
        assertEquals(0, e.snapshot(23_000).hrSamples.single().segmentId)
    }

}
