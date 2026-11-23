package com.transmiti2.transmiti2.streaming.test

import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Instrumentación en memoria (diseño base §6, tests nº 2, 3 y partes de
 * nº 1, 6 y 11 que corresponden al núcleo). Harness local con `-ea`,
 * `runBlocking`, sin `kotlinx-coroutines-test` (no existe en el
 * proyecto) y sin Robolectric.
 */
class TestInstrumentationTest {

    private class FakeTestClock(
        private var monotonicValue: Long = 1_000_000L,
        private var wallValue: Long = 1_792_000_000_000L,
    ) : TestClock {
        override fun monotonicMs(): Long = monotonicValue
        override fun wallMs(): Long = wallValue
        fun advance(monotonicDelta: Long = 10L, wallDelta: Long = 10L) {
            monotonicValue += monotonicDelta
            wallValue += wallDelta
        }
    }

    private lateinit var clock: FakeTestClock
    private lateinit var captured: MutableList<TestEvent>
    private lateinit var instrumentation: TestInstrumentation

    @Before
    fun setUp() {
        Transmiti2Logger.sink = { _, _ -> }
        clock = FakeTestClock()
        captured = mutableListOf()
        instrumentation = TestInstrumentation(
            enabled = { true },
            clock = clock,
            sink = { captured += it },
        )
    }

    @After
    fun tearDown() {
        Transmiti2Logger.sink = null
    }

    private suspend fun startNewSession(): TestSession {
        val session = instrumentation.createSession()
        instrumentation.startSession(session)
        return session
    }

    @Test
    fun `enabledRecordsOrderedEventsWithSessionId`() = runBlocking {
        val session = startNewSession()
        clock.advance()
        instrumentation.record(
            TestEventType.RECONCILIATION_STARTED,
            operation = "reconcile",
            detail = mapOf("runId" to "run-1"),
        )
        clock.advance()
        instrumentation.record(
            TestEventType.RECORDING_STARTED,
            operation = "startRecording",
            recordingId = "rec_20261123_150000_aaaa",
        )
        clock.advance()
        instrumentation.record(
            TestEventType.SEGMENT_VERIFIED,
            operation = "verifySegment",
            recordingId = "rec_20261123_150000_aaaa",
            detail = mapOf("index" to 0),
        )

        val events = instrumentation.events()
        // SESSION_STARTED + 3 registrados.
        assertEquals(4, events.size)
        assertTrue(events.all { it.sessionId == session.sessionId })
        assertTrue(events.all { it.testRunId == session.testRunId })
        assertTrue(events.all { it.origin == TestEventOrigin.OBSERVED })
        val monotonic = events.map { it.monotonicMs }
        assertEquals(monotonic, monotonic.sorted())
        assertEquals(monotonic.distinct().size, monotonic.size)
        assertEquals(
            listOf(
                TestEventType.SESSION_STARTED,
                TestEventType.RECONCILIATION_STARTED,
                TestEventType.RECORDING_STARTED,
                TestEventType.SEGMENT_VERIFIED,
            ),
            events.map { it.event },
        )
        assertEquals("rec_20261123_150000_aaaa", events[2].recordingId)
        assertNull(events[1].recordingId)
        // El sink recibió exactamente los mismos eventos, en orden.
        assertEquals(events, captured)
    }

    @Test
    fun `twoSessionsNeverShareEvents`() = runBlocking {
        val first = startNewSession()
        instrumentation.record(TestEventType.RECORDING_STARTED, recordingId = "rec_a")
        instrumentation.endSession()
        val second = startNewSession()
        instrumentation.record(TestEventType.RECORDING_STARTED, recordingId = "rec_b")

        assertNotEquals(first.sessionId, second.sessionId)
        assertNotEquals(first.testRunId, second.testRunId)
        val firstEvents = instrumentation.eventsOf(first.sessionId)
        val secondEvents = instrumentation.eventsOf(second.sessionId)
        assertTrue(firstEvents.all { it.recordingId != "rec_b" })
        assertTrue(secondEvents.all { it.recordingId != "rec_a" })
        assertTrue(firstEvents.none { it.sessionId == second.sessionId })
        assertTrue(secondEvents.none { it.sessionId == first.sessionId })
        assertEquals(3, firstEvents.size) // SESSION_STARTED + RECORDING_STARTED + SESSION_ENDED
        assertTrue(firstEvents.any { it.event == TestEventType.SESSION_ENDED })
        assertTrue(secondEvents.none { it.event == TestEventType.SESSION_ENDED })
    }

    @Test
    fun `disabledInstrumentationRecordsNothing`() = runBlocking {
        val disabled = TestInstrumentation(enabled = { false }, clock = clock)
        val session = disabled.createSession()
        disabled.startSession(session)
        val recorded = disabled.record(TestEventType.RECORDING_STARTED, recordingId = "rec_x")

        assertNull(recorded)
        assertTrue(disabled.events().isEmpty())
        assertEquals(0L, disabled.droppedCount)
        assertTrue(disabled.sessionActive)
        // Sin sesión activa tampoco se registra nada (aunque esté habilitada).
        instrumentation.endSession()
        assertNull(instrumentation.record(TestEventType.ERROR))
    }

    @Test
    fun `ringBufferDropsOldestAndCountsDiscards`() = runBlocking {
        startNewSession()
        repeat(TestInstrumentation.MAX_RING_EVENTS + 5) { index ->
            instrumentation.record(
                TestEventType.RECORDING_SAMPLE,
                detail = mapOf("index" to index),
            )
        }

        val events = instrumentation.events()
        assertEquals(TestInstrumentation.MAX_RING_EVENTS, events.size)
        // 506 añadidos en total (SESSION_STARTED + 505 muestras): 6 descartes.
        assertEquals(6L, instrumentation.droppedCount)
        // Se descartaron los 6 más antiguos (SESSION_STARTED + muestras 0..4).
        assertEquals(5, (events.first().detail["index"] as? Number)?.toInt())
        assertEquals(
            TestInstrumentation.MAX_RING_EVENTS + 4,
            (events.last().detail["index"] as? Number)?.toInt(),
        )
    }

    @Test
    fun `testRunIdHasExpectedFormatAndIsUniqueAgainstTakenIds`() {
        runBlocking {
            val suffixes = listOf("ab12", "ab12", "cd34").iterator()
            val id = instrumentation.generateTestRunId(
                isTaken = { candidate -> candidate.endsWith("_ab12") },
                nowWallMs = 1_792_000_000_000L,
                suffixSupplier = { suffixes.next() },
            )

            assertTrue(id, id.matches(Regex("run_\\d{8}_\\d{6}_[0-9a-f]{4}")))
            assertTrue(id, id.endsWith("_cd34"))
            // Agotados los 10 intentos, falla cerrado (nunca reutiliza un id).
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    instrumentation.generateTestRunId(
                        isTaken = { true },
                        suffixSupplier = { "ffff" },
                    )
                }
            }
        }
    }

    @Test
    fun `sessionEndClearsActiveStateAndEmitsSessionEnded`() = runBlocking {
        val session = startNewSession()
        val ended = instrumentation.endSession("PASSED")

        assertEquals(session, ended)
        assertFalse(instrumentation.sessionActive)
        assertNull(instrumentation.currentSessionId)
        assertNull(instrumentation.endSession())
        val last = instrumentation.events().last()
        assertEquals(TestEventType.SESSION_ENDED, last.event)
        assertEquals("PASSED", last.detail["verdict"])
    }

    @Test
    fun `eventJsonRoundTripPreservesAllFields`() = runBlocking {
        startNewSession()
        val recorded = instrumentation.record(
            TestEventType.RECORDING_SAMPLE,
            operation = "sample",
            recordingId = "rec_20261123_150000_aaaa",
            result = TestEventResult.OK,
            durationMs = 5_000L,
            detail = mapOf(
                "segmentDurationMs" to 5_000L,
                "tmpSizeBytes" to 12_682_353L,
                "dartState" to "recording",
                "nested" to mapOf("a" to 1L),
                "tags" to listOf("x", "y"),
            ),
            origin = TestEventOrigin.OBSERVED,
        )
        assertNotNull(recorded)

        val json = TestJson.encode(recorded!!.toJsonMap())
        val parsed = TestJson.parseObject(json)
        assertNotNull(parsed)
        val restored = TestEvent.fromJsonMap(parsed!!)
        assertEquals(recorded, restored)
        // Cada evento lleva testRunId, sessionId y origin en su JSON.
        assertNotNull(parsed.str("testRunId"))
        assertNotNull(parsed.str("sessionId"))
        assertEquals("observed", parsed.str("origin"))
    }

    @Test
    fun `throwingSinkNeverBreaksRecording`() = runBlocking {
        instrumentation.sink = { throw IllegalStateException("sink roto") }
        startNewSession()
        val recorded = instrumentation.record(TestEventType.RECORDING_STARTED)

        assertNotNull(recorded)
        assertEquals(2, instrumentation.events().size)
    }
}
