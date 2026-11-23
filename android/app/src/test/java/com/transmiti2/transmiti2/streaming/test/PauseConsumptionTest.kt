package com.transmiti2.transmiti2.streaming.test

import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Consumo de la pausa (Revisión 2 §2.9 pruebas 1a–1f; Revisión 3 §2.4:
 * la 1a queda sustituida por 1a-1 y 1a-2).
 *
 * AUSENCIA DECLARADA — prueba 1a-3: NO existe ninguna prueba para la
 * cancelación emitida entre la escritura de `PAUSE_ENTERED` y la llamada
 * de suspensión. No hay punto de suspensión en ese intervalo, así que
 * ningún test puede aterrizar una cancelación externa ahí de forma
 * determinista; intentarlo con un bucle de cancelación desde otro hilo
 * sería flaky, no una prueba. Queda cubierto por construcción
 * (`ensureActive()` antes de la escritura + `PAUSE_CANCELLED` con
 * `wasSuspended=false` + la regla de veredicto: sin resumen `DONE` no
 * hay aprobación), y la regla de veredicto se verifica con las
 * secuencias sintéticas de la prueba 1g (Dart, fuera de este grupo).
 *
 * Harness local con `-ea`, `runBlocking`, timeouts inyectados cortos
 * (nunca los 120 s reales) y FS falso para el archivo de intención.
 */
class PauseConsumptionTest {

    private class FakeTestClock(
        private var monotonicValue: Long = 1_000_000L,
        private var wallValue: Long = 1_792_000_000_000L,
    ) : TestClock {
        override fun monotonicMs(): Long = monotonicValue
        override fun wallMs(): Long = wallValue
        fun advance(monotonicDelta: Long = 100L, wallDelta: Long = 100L) {
            monotonicValue += monotonicDelta
            wallValue += wallDelta
        }
    }

    private enum class DeleteBehavior {
        NORMAL,
        FAIL,
        LATCH,
    }

    /** FS falso para `armed-pause.json`, con borrado controlable por latch. */
    private class FakeIntentAccess : ArmedPauseFileAccess {
        var content: String? = null
        var deleteBehavior: DeleteBehavior = DeleteBehavior.NORMAL
        var deleteCalls = 0
        val deleteStarted = CountDownLatch(1)
        val releaseDelete = CountDownLatch(1)

        override fun read(): String? = content
        override fun write(text: String) {
            content = text
        }

        override fun delete(): Boolean {
            deleteCalls++
            return when (deleteBehavior) {
                DeleteBehavior.NORMAL -> {
                    content = null
                    true
                }
                DeleteBehavior.FAIL -> false
                DeleteBehavior.LATCH -> {
                    deleteStarted.countDown()
                    releaseDelete.await()
                    content = null
                    true
                }
            }
        }
    }

    private class Harness(pauseTimeoutMs: Long) {
        val clock = FakeTestClock()
        val captured = mutableListOf<TestEvent>()
        val pauseEnteredLatch = CountDownLatch(1)
        val instrumentation = TestInstrumentation(enabled = { true }, clock = clock)
        val access = FakeIntentAccess()
        val syncPoints = TestSyncPoints(
            enabled = { true },
            instrumentation = instrumentation,
            clock = clock,
            intentAccess = access,
            pauseTimeoutMs = pauseTimeoutMs,
        )
        val builder = TestReportBuilder()

        init {
            instrumentation.sink = { event ->
                captured += event
                if (event.event == TestEventType.PAUSE_ENTERED) {
                    pauseEnteredLatch.countDown()
                }
            }
        }

        suspend fun startSessionWithIntent(point: PausePoint): TestSession {
            val session = instrumentation.createSession()
            instrumentation.startSession(session)
            syncPoints.arm(point)
            return session
        }

        fun report(session: TestSession) = builder.buildSessionReport(
            sessionId = session.sessionId,
            testRunId = session.testRunId,
            events = captured.toList(),
            armedIntent = syncPoints.peekIntent(),
        )
    }

    @Before
    fun setUp() {
        Transmiti2Logger.sink = { _, _ -> }
    }

    @After
    fun tearDown() {
        Transmiti2Logger.sink = null
    }

    // ------------------------------------------------------------------
    // 1a-1 — cancelación durante el borrado en E/S (flujo integrado real)
    // ------------------------------------------------------------------

    @Test(timeout = 15_000)
    fun `cancelDuringIoDeleteLeavesConsumedWithoutPause`() = runBlocking {
        val harness = Harness(pauseTimeoutMs = 5_000L)
        val session = harness.startSessionWithIntent(PausePoint.P0)
        harness.access.deleteBehavior = DeleteBehavior.LATCH

        // La misma función que llama la reconciliación, en una corrutina
        // con un Job real.
        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val deferred = scope.async {
            harness.syncPoints.awaitPauseIfArmed(PausePoint.P0, "run-1")
        }
        // Esperar a que el borrado esté en vuelo, cancelar, liberar.
        assertTrue(harness.access.deleteStarted.await(5, TimeUnit.SECONDS))
        job.cancel()
        harness.access.releaseDelete.countDown()

        val thrown = try {
            deferred.await()
            null
        } catch (e: CancellationException) {
            e
        }
        assertNotNull("se esperaba CancellationException", thrown)
        assertNull("el archivo de intención debe estar borrado", harness.access.read())
        assertTrue(harness.captured.any { it.event == TestEventType.INTENT_CONSUMED })
        assertFalse(harness.captured.any { it.event == TestEventType.PAUSE_ENTERED })

        val report = harness.report(session)
        assertEquals(TestReportBuilder.Verdict.INCOMPLETE, report.verdict)
        assertEquals("intent_consumed_without_pause", report.reason)
        val evidence = TestReportBuilder.ReconciliationEvidence(
            runId = "run-1",
            state = "DONE",
        )
        val criteria = harness.builder.evaluateCriteria(harness.captured.toList(), evidence)
        assertTrue(criteria.all { it.status == TestReportBuilder.CriterionStatus.NOT_EVALUABLE })
        assertTrue(criteria.none { it.status == TestReportBuilder.CriterionStatus.PASSED })
        assertTrue(criteria.none { it.status == TestReportBuilder.CriterionStatus.FAILED })
    }

    // ------------------------------------------------------------------
    // 1a-2 — Job pre-cancelado: nunca se consume la intención
    // ------------------------------------------------------------------

    @Test(timeout = 15_000)
    fun `precancelledJobNeverConsumesIntent`() = runBlocking {
        val harness = Harness(pauseTimeoutMs = 5_000L)
        val session = harness.startSessionWithIntent(PausePoint.P0)
        val job = Job().also { it.cancel() }

        val thrown = try {
            withContext(job + Dispatchers.Default) {
                harness.syncPoints.awaitPauseIfArmed(PausePoint.P0, "run-1")
            }
            null
        } catch (e: CancellationException) {
            e
        }

        assertNotNull("se esperaba CancellationException", thrown)
        // El borrado nunca corrió: la intención sigue intacta.
        assertNotNull(harness.access.read())
        assertEquals(0, harness.access.deleteCalls)
        assertFalse(harness.captured.any { it.event == TestEventType.INTENT_CONSUMED })
        assertFalse(harness.captured.any { it.event == TestEventType.PAUSE_ENTERED })

        val report = harness.report(session)
        assertEquals(TestReportBuilder.Verdict.INCOMPLETE, report.verdict)
        assertEquals("intent_not_consumed", report.reason)
    }

    // ------------------------------------------------------------------
    // 1b — camino normal: consumir, suspender, reanudar
    // ------------------------------------------------------------------

    @Test(timeout = 15_000)
    fun `consumeSuspendResumeRecordsPauseEnteredAndCriteriaAreEvaluable`() = runBlocking {
        val harness = Harness(pauseTimeoutMs = 5_000L)
        val session = harness.startSessionWithIntent(PausePoint.P0)

        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val deferred = scope.async {
            harness.syncPoints.awaitPauseIfArmed(PausePoint.P0, "run-1")
        }
        assertTrue(harness.pauseEnteredLatch.await(5, TimeUnit.SECONDS))
        harness.clock.advance()
        harness.instrumentation.record(
            TestEventType.RECORDING_STARTED,
            recordingId = "rec_b",
            detail = mapOf("runId" to "run-1"),
        )
        harness.clock.advance()
        assertTrue(harness.syncPoints.resume(PausePoint.P0))
        assertTrue(deferred.await())
        harness.clock.advance()
        harness.instrumentation.record(
            TestEventType.RECONCILIATION_FINISHED,
            detail = mapOf("runId" to "run-1"),
        )
        harness.clock.advance()
        harness.instrumentation.record(
            TestEventType.RECORDING_STOPPED,
            recordingId = "rec_b",
        )

        val pauseEntered = harness.captured.single {
            it.event == TestEventType.PAUSE_ENTERED
        }
        assertEquals("P0", pauseEntered.detail["point"])
        assertEquals("run-1", pauseEntered.detail["runId"])
        val released = harness.captured.single {
            it.event == TestEventType.PAUSE_RELEASED
        }
        assertTrue(released.monotonicMs > pauseEntered.monotonicMs)

        val evidence = TestReportBuilder.ReconciliationEvidence(
            runId = "run-1",
            state = "DONE",
            groupsSkippedActive = listOf("rec_b"),
        )
        val criteria = harness.builder.evaluateCriteria(harness.captured.toList(), evidence)
        val c1 = criteria.single { it.name == "C1" }
        val c2 = criteria.single { it.name == "C2" }
        assertEquals(TestReportBuilder.CriterionStatus.NOT_EVALUABLE, c1.status)
        assertEquals(TestReportBuilder.CriterionStatus.PASSED, c2.status)
        val report = harness.builder.buildSessionReport(
            sessionId = session.sessionId,
            testRunId = session.testRunId,
            events = harness.captured.toList(),
            reconciliationEvidence = evidence,
        )
        assertTrue(report.verdict != TestReportBuilder.Verdict.INCOMPLETE)
    }

    // ------------------------------------------------------------------
    // 1c — el borrado falla: fail-closed, sin pausa
    // ------------------------------------------------------------------

    @Test(timeout = 15_000)
    fun `consumeFailsFailClosedWithoutPause`() = runBlocking {
        val harness = Harness(pauseTimeoutMs = 5_000L)
        val session = harness.startSessionWithIntent(PausePoint.P0)
        harness.access.deleteBehavior = DeleteBehavior.FAIL

        val result = harness.syncPoints.awaitPauseIfArmed(PausePoint.P0, "run-1")

        assertFalse(result)
        assertTrue(harness.captured.any { it.event == TestEventType.ARMED_PAUSE_CONSUME_FAILED })
        assertFalse(harness.captured.any { it.event == TestEventType.PAUSE_ENTERED })
        assertNotNull(harness.access.read())
        val report = harness.report(session)
        assertEquals(TestReportBuilder.Verdict.INCOMPLETE, report.verdict)
        assertEquals("intent_consume_failed", report.reason)
    }

    // ------------------------------------------------------------------
    // 1d — segundo consumo: no encuentra intención, no pausa
    // ------------------------------------------------------------------

    @Test(timeout = 15_000)
    fun `secondConsumeFindsNoIntentAndDoesNotPause`() = runBlocking {
        val harness = Harness(pauseTimeoutMs = 5_000L)
        val session = harness.startSessionWithIntent(PausePoint.P0)

        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val deferred = scope.async {
            harness.syncPoints.awaitPauseIfArmed(PausePoint.P0, "run-1")
        }
        assertTrue(harness.pauseEnteredLatch.await(5, TimeUnit.SECONDS))
        assertTrue(harness.syncPoints.resume(PausePoint.P0))
        assertTrue(deferred.await())

        val second = harness.syncPoints.awaitPauseIfArmed(PausePoint.P0, "run-1")

        assertFalse(second)
        assertEquals(1, harness.captured.count { it.event == TestEventType.INTENT_CONSUMED })
        assertEquals(1, harness.captured.count { it.event == TestEventType.PAUSE_ENTERED })
        val report = harness.report(session)
        assertEquals(TestReportBuilder.Verdict.INCONCLUSIVE, report.verdict)
    }

    // ------------------------------------------------------------------
    // 1e — timeout sin inicio de grabación: INCONCLUSIVE
    // ------------------------------------------------------------------

    @Test(timeout = 15_000)
    fun `pauseTimeoutWithoutRecordingStartIsInconclusive`() = runBlocking {
        val harness = Harness(pauseTimeoutMs = 300L)
        val session = harness.startSessionWithIntent(PausePoint.P0)

        val result = harness.syncPoints.awaitPauseIfArmed(PausePoint.P0, "run-1")

        assertFalse(result)
        assertTrue(harness.captured.any { it.event == TestEventType.PAUSE_TIMEOUT })
        assertFalse(harness.captured.any { it.event == TestEventType.RECORDING_STARTED })
        val report = harness.report(session)
        assertEquals(TestReportBuilder.Verdict.INCONCLUSIVE, report.verdict)
        assertEquals(
            "pause_timeout_without_recording_start",
            report.reason,
        )
    }

    // ------------------------------------------------------------------
    // 1f — cancelación estando suspendida: PAUSE_CANCELLED
    // ------------------------------------------------------------------

    @Test(timeout = 15_000)
    fun `cancelWhileSuspendedRecordsPauseCancelled`() = runBlocking {
        val harness = Harness(pauseTimeoutMs = 5_000L)
        val session = harness.startSessionWithIntent(PausePoint.P0)

        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val deferred = scope.async {
            harness.syncPoints.awaitPauseIfArmed(PausePoint.P0, "run-1")
        }
        assertTrue(harness.pauseEnteredLatch.await(5, TimeUnit.SECONDS))
        job.cancel()

        val thrown = try {
            deferred.await()
            null
        } catch (e: CancellationException) {
            e
        }
        assertNotNull("se esperaba CancellationException", thrown)
        val cancelled = harness.captured.singleOrNull {
            it.event == TestEventType.PAUSE_CANCELLED
        }
        assertNotNull(cancelled)
        assertEquals(true, cancelled!!.wasSuspended)
        val report = harness.report(session)
        assertEquals(TestReportBuilder.Verdict.INCOMPLETE, report.verdict)
        assertEquals("pause_cancelled", report.reason)
    }
}
