package dev.stride.lab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LabSessionTest {
    @Test fun manualUiAndHttpCommandsShareNativeStateAndNeverInventActualFeedback() {
        var now = 1_000L
        val session = LabSession(monotonicNow = { now }, startClock = false)
        try {
            session.action(JSONObject("""{"action":"sensor","source":"PHONE"}"""))
            session.action(JSONObject("""{"action":"startManual","minutes":10}"""))
            now = 2_000L
            session.engine.tick(now)
            val view = session.status()
            assertTrue(view.getBoolean("previewOnly"))
            assertFalse(view.getBoolean("motorConnected"))
            assertTrue(view.getBoolean("manualSession"))
            assertTrue(view.isNull("heartRateBpm"))
            assertFalse(view.getBoolean("heartRateFresh"))
            assertEquals(0, view.getJSONArray("hrSamples").length())
            assertEquals(2.0, view.getDouble("targetSpeedMph"), .000001)
            session.action(JSONObject("""{"action":"manual","speedMph":1.2,"inclinePercent":1.5}"""))
            assertEquals(1.2, session.status().getDouble("targetSpeedMph"), .000001)
            session.action(JSONObject("""{"action":"pause"}"""))
            assertTrue(session.status().getBoolean("canResumeManual"))
            session.action(JSONObject("""{"action":"resumeManual"}"""))
            assertEquals("MANUAL", session.status().getString("state"))
            session.action(JSONObject("""{"action":"stop"}"""))
            try { session.action(JSONObject("""{"action":"resumeManual"}""")); fail() }
            catch (_: IllegalStateException) { }
        } finally { session.close() }
    }
    @Test fun configureRejectsMalformedOutOfBoundsAndActiveChangesWithoutMutatingExistingLimits() {
        val session = LabSession(monotonicNow = { 0 }, startClock = false)
        try {
            for (json in listOf(
                """{"action":"configure","maxSpeedMph":"3","maxInclinePercent":4,"adaptiveSpeed":false}""",
                """{"action":"configure","maxSpeedMph":3,"maxInclinePercent":4,"adaptiveSpeed":"true"}""",
                """{"action":"configure","maxSpeedMph":0.7,"maxInclinePercent":4,"adaptiveSpeed":false}""",
                """{"action":"configure","maxSpeedMph":12,"maxInclinePercent":4,"adaptiveSpeed":false}""",
                """{"action":"configure","maxSpeedMph":3,"maxInclinePercent":16,"adaptiveSpeed":false}""",
            )) {
                try { session.action(JSONObject(json)); fail(json) } catch (_: IllegalArgumentException) { }
                assertEquals(3.0, session.engine.config.maxSpeedMph, .000001)
                assertEquals(4.0, session.engine.config.maxIncline, .000001)
            }
            session.action(JSONObject("""{"action":"configure","maxSpeedMph":1.5,"maxInclinePercent":0,"adaptiveSpeed":true}"""))
            assertEquals(1.5, session.engine.config.baseSpeedMph, .000001)
            assertTrue(session.engine.config.adaptiveSpeed)
            assertEquals(110, session.engine.config.targetLowBpm)
            session.action(JSONObject("""{"action":"startManual","minutes":10}"""))
            try {
                session.action(JSONObject("""{"action":"configure","maxSpeedMph":2,"maxInclinePercent":1,"adaptiveSpeed":false}"""))
                fail()
            } catch (_: IllegalStateException) { }
            assertEquals(1.5, session.engine.config.maxSpeedMph, .000001)
        } finally { session.close() }
    }
    @Test fun strictlyValidatesMinutesAndKeepsAllRoutinePhasesAvailableForProfileVisualization() {
        val session = LabSession(monotonicNow = { 0 }, startClock = false)
        try {
            for (minutes in listOf("0", "121", "1.5", "\"10\"")) {
                try { session.action(JSONObject("""{"action":"startManual","minutes":$minutes}""")); fail() }
                catch (_: IllegalArgumentException) { }
            }
            val routines = session.routines()
            assertEquals(4, routines.length())
            for (i in 0 until routines.length()) {
                val phases = routines.getJSONObject(i).getJSONArray("phases")
                assertTrue(phases.length() >= 3)
                assertEquals(1.0, phases.getJSONObject(phases.length() - 1).getDouble("endFraction"), .000001)
                assertTrue(phases.getJSONObject(0).has("grade"))
                assertTrue(phases.getJSONObject(0).has("speedFactor"))
            }
        } finally { session.close() }
    }
    @Test fun closingSessionRejectsLaterQueuedCommandsAndNeverRestartsWorkload() {
        val session = LabSession(monotonicNow = { 0 }, startClock = false)
        session.action(JSONObject("""{"action":"startManual","minutes":10}"""))
        session.close()
        for (body in listOf(
            """{"action":"startManual","minutes":10}""",
            """{"action":"resumeManual"}""",
            """{"action":"manual","speedMph":2,"inclinePercent":2}""",
        )) {
            try { session.action(JSONObject(body)); fail() } catch (_: IllegalStateException) { }
        }
        assertEquals("STOPPED", session.status().getString("state"))
        assertEquals(0.0, session.status().getDouble("proposedSpeedMph"), .000001)
    }

    @Test fun graphSegmentIdentityIsIncludedInTheNativeAndHttpStatusContract() {
        val session = LabSession(monotonicNow = { 2_000 }, startClock = false)
        try {
            session.engine.startManual(10, 0)
            session.engine.acceptHeartRate(120, 1_000, 1, WorkoutEngine.Sensor.SIMULATED)
            session.engine.tick(1_000)
            val point = session.status().getJSONArray("hrSamples").getJSONObject(0)
            assertEquals(0, point.getInt("segmentId"))
            assertEquals("SIMULATED", point.getString("source"))
        } finally { session.close() }
    }

}
