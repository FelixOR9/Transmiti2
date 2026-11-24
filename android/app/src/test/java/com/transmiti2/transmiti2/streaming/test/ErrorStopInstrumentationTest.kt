package com.transmiti2.transmiti2.streaming.test

import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Instrumentación de error-stop (J3-c, 3.1.4, plan §1.3 paso 6 y
 * §3.1): los nuevos tipos de evento (`ERROR_STOP_TRIGGERED`,
 * `FGS_STOPPED`, `ARMED_PAUSE_WRITE_FAILED`) se registran con sus
 * campos y sobreviven al ida y vuelta JSONL; `TEST_RUN_CONTINUED`
 * se emite solo al adoptar una campaña (el emisor que faltaba); el
 * fallo de escritura de `armed-pause.json` deja evento y relanza;
 * el hook de inyección de error del canal responde por su puerta
 * (`testMode`) y su seam antes de tocar el motor.
 *
 * Lo que NO prueba este archivo (vive donde está su harness): la
 * emisión real de `ERROR_STOP_TRIGGERED` y del hook en la sesión
 * (`RecordingSessionTest`, con encoder/muxer falsos) y la de
 * `FGS_STOPPED` en el motor (`StreamEngineRecordingTest`, con FGS
 * falso). Aquí se prueba la capa de instrumentación y el canal.
 *
 * Harness local con `-ea`, `runBlocking`, reloj falso y directorios
 * temporales (nunca el `filesDir` real), como `TestModeGateTest`.
 */
class ErrorStopInstrumentationTest {

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

    private class CapturingResult : MethodChannel.Result {
        var successValue: Any? = null
        var errorCode: String? = null
        var errorMessage: String? = null
        var errorDetails: Any? = null
        var notImplementedCalled = false
        var calls = 0

        override fun success(result: Any?) {
            successValue = result
            calls++
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            this.errorCode = errorCode
            this.errorMessage = errorMessage
            this.errorDetails = errorDetails
            calls++
        }

        override fun notImplemented() {
            notImplementedCalled = true
            calls++
        }
    }

    /** Acceso falso a `armed-pause.json` cuya escritura falla. */
    private class ThrowingWriteAccess : ArmedPauseFileAccess {
        var content: String? = null
        var writeCalls = 0

        override fun read(): String? = content

        override fun write(text: String) {
            writeCalls++
            throw IOException("disco lleno (prueba)")
        }

        override fun delete(): Boolean {
            content = null
            return true
        }
    }

    private lateinit var clock: FakeTestClock
    private lateinit var captured: MutableList<TestEvent>
    private lateinit var instrumentation: TestInstrumentation

    private lateinit var baseDir: File
    private lateinit var filesDir: File
    private lateinit var exportRoot: File
    private var handler: TestChannelHandler? = null

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
        baseDir = File(
            System.getProperty("java.io.tmpdir"),
            "error-stop-instrumentation-${System.nanoTime()}",
        )
        filesDir = File(baseDir, "files").apply { mkdirs() }
        exportRoot = File(baseDir, "export").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        handler?.close()
        handler = null
        baseDir.deleteRecursively()
        Transmiti2Logger.sink = null
    }

    private suspend fun startNewSession(): TestSession {
        val session = instrumentation.createSession()
        instrumentation.startSession(session)
        return session
    }

    private fun newHandler(
        testMode: Boolean,
        errorInjectionProvider: ((String) -> Map<String, Any?>)? = null,
    ): TestChannelHandler =
        TestChannelHandler(
            filesDir = filesDir,
            testMode = testMode,
            clock = clock,
            exportRoot = exportRoot,
            errorInjectionProvider = errorInjectionProvider,
        ).also { handler = it }

    private fun call(
        method: String,
        args: Map<String, Any?>? = null,
    ): CapturingResult {
        val result = CapturingResult()
        handler!!.onMethodCall(MethodCall(method, args), result)
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun successMap(result: CapturingResult): Map<String, Any?> {
        assertNull(result.errorCode)
        assertFalse(result.notImplementedCalled)
        assertEquals(1, result.calls)
        return result.successValue as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun successList(result: CapturingResult): List<Map<String, Any?>> {
        assertNull(result.errorCode)
        assertFalse(result.notImplementedCalled)
        assertEquals(1, result.calls)
        return (result.successValue as List<*>)
            .filterIsInstance<Map<String, Any?>>()
    }

    // ------------------------------------------------------------------
    // Nuevos tipos de evento: registro y vuelta JSONL
    // ------------------------------------------------------------------

    @Test
    fun `los nuevos tipos usan los nombres de cable del plan`() {
        assertEquals("ERROR_STOP_TRIGGERED", TestEventType.ERROR_STOP_TRIGGERED.name)
        assertEquals("FGS_STOPPED", TestEventType.FGS_STOPPED.name)
        assertEquals(
            "ARMED_PAUSE_WRITE_FAILED",
            TestEventType.ARMED_PAUSE_WRITE_FAILED.name,
        )
        assertEquals("TEST_RUN_CONTINUED", TestEventType.TEST_RUN_CONTINUED.name)
    }

    @Test
    fun `ERROR_STOP_TRIGGERED registra el trigger completo y sobrevive al JSONL`() =
        runBlocking {
            val session = startNewSession()
            val triggerDetail = mapOf<String, Any?>(
                "code" to "encoder_error",
                "message" to "Error del codificador: codec roto",
                "stage" to "encoder",
                "segmentIndex" to 0,
                "exceptionClass" to "IllegalStateException",
                "triggerSite" to "onError",
                "timestampMs" to 1_792_000_000_000L,
                "freeBytes" to null,
            )
            val recorded = instrumentation.record(
                TestEventType.ERROR_STOP_TRIGGERED,
                operation = "scheduleErrorStop",
                recordingId = "rec_20261124_120000_ab12",
                detail = triggerDetail,
            )
            assertNotNull(recorded)

            val encoded = TestJson.encode(recorded!!.toJsonMap())
            val parsed = TestEvent.fromJsonMap(
                TestJson.parseObject(encoded)!!,
            )
            assertNotNull(parsed)
            assertEquals(session.sessionId, parsed!!.sessionId)
            assertEquals(session.testRunId, parsed.testRunId)
            assertEquals(TestEventType.ERROR_STOP_TRIGGERED, parsed.event)
            assertEquals("rec_20261124_120000_ab12", parsed.recordingId)
            assertEquals("scheduleErrorStop", parsed.operation)
            assertEquals("encoder_error", parsed.detail["code"])
            assertEquals("encoder", parsed.detail["stage"])
            assertEquals(0L, parsed.detail["segmentIndex"])
            assertEquals("IllegalStateException", parsed.detail["exceptionClass"])
            assertEquals("onError", parsed.detail["triggerSite"])
            assertEquals(1_792_000_000_000L, parsed.detail["timestampMs"])
            assertNull(parsed.detail["freeBytes"])
        }

    @Test
    fun `FGS_STOPPED registra el origen y sobrevive al JSONL`() = runBlocking {
        startNewSession()
        for (origin in listOf("error_stop", "normal_stop", "camera_error", "notification", "start_failed")) {
            instrumentation.record(
                TestEventType.FGS_STOPPED,
                operation = "fgs",
                recordingId = "rec_20261124_120000_ab12",
                detail = mapOf("origin" to origin),
            )
        }
        val events = instrumentation.events()
            .filter { it.event == TestEventType.FGS_STOPPED }
        assertEquals(5, events.size)
        assertEquals(
            listOf("error_stop", "normal_stop", "camera_error", "notification", "start_failed"),
            events.map { it.detail["origin"] },
        )
        // Ida y vuelta de uno con `reason` (parada desde la
        // notificación: `origin` distinto del `reason`).
        val notification = events.first { it.detail["origin"] == "notification" }
        val reparsed = TestEvent.fromJsonMap(
            TestJson.parseObject(TestJson.encode(notification.toJsonMap()))!!,
        )
        assertNotNull(reparsed)
        assertEquals(TestEventType.FGS_STOPPED, reparsed!!.event)
        assertEquals("notification", reparsed.detail["origin"])
    }

    @Test
    fun `ARMED_PAUSE_WRITE_FAILED registra punto y clase de excepcion`() =
        runBlocking {
            startNewSession()
            val recorded = instrumentation.record(
                TestEventType.ARMED_PAUSE_WRITE_FAILED,
                operation = "reconcile",
                result = TestEventResult.FAILED,
                detail = mapOf(
                    "point" to "P0",
                    "testRunId" to "run_20261124_120000_ab12",
                    "exceptionClass" to "IOException",
                ),
            )
            assertNotNull(recorded)
            assertEquals(TestEventResult.FAILED, recorded!!.result)
            val reparsed = TestEvent.fromJsonMap(
                TestJson.parseObject(TestJson.encode(recorded.toJsonMap()))!!,
            )
            assertNotNull(reparsed)
            assertEquals(
                TestEventType.ARMED_PAUSE_WRITE_FAILED,
                reparsed!!.event,
            )
            assertEquals("P0", reparsed.detail["point"])
            assertEquals("IOException", reparsed.detail["exceptionClass"])
        }

    // ------------------------------------------------------------------
    // TEST_RUN_CONTINUED: solo al adoptar una campaña
    // ------------------------------------------------------------------

    @Test
    fun `recordTestRunContinued emite con adopcion y sesion anterior`() =
        runBlocking {
            val session = startNewSession()
            // Una sesión nueva sin adopción no lo emite.
            assertTrue(
                instrumentation.events().none {
                    it.event == TestEventType.TEST_RUN_CONTINUED
                },
            )
            val continued = instrumentation.recordTestRunContinued(
                adoptedTestRunId = session.testRunId,
                previousSessionId = "sess_anterior",
            )
            assertNotNull(continued)
            assertEquals(TestEventType.TEST_RUN_CONTINUED, continued!!.event)
            assertEquals(session.sessionId, continued.sessionId)
            assertEquals(session.testRunId, continued.testRunId)
            assertEquals(session.testRunId, continued.detail["adoptedTestRunId"])
            assertEquals("sess_anterior", continued.detail["previousSessionId"])
        }

    @Test
    fun `recordTestRunContinued sin sesion anterior omite la clave`() =
        runBlocking {
            val session = startNewSession()
            val continued = instrumentation.recordTestRunContinued(
                adoptedTestRunId = session.testRunId,
            )
            assertNotNull(continued)
            assertEquals(session.testRunId, continued!!.detail["adoptedTestRunId"])
            assertFalse(continued.detail.containsKey("previousSessionId"))
        }

    @Test
    fun `startTestSession con adoptTestRunId emite TEST_RUN_CONTINUED`() {
        newHandler(testMode = true)
        val adopted = "run_20261124_120000_ab12"
        val started = successMap(
            call(
                TestChannelHandler.START_TEST_SESSION,
                mapOf("adoptTestRunId" to adopted),
            ),
        )
        assertEquals(adopted, started["testRunId"])
        val events = successList(call(TestChannelHandler.GET_TEST_EVENTS))
        val continued = events.filter { it["event"] == "TEST_RUN_CONTINUED" }
        assertEquals(1, continued.size)
        @Suppress("UNCHECKED_CAST")
        val detail = continued[0]["detail"] as Map<String, Any?>
        assertEquals(adopted, detail["adoptedTestRunId"])
        assertFalse(detail.containsKey("previousSessionId"))
    }

    @Test
    fun `startTestSession sin adopcion no emite TEST_RUN_CONTINUED`() {
        newHandler(testMode = true)
        successMap(call(TestChannelHandler.START_TEST_SESSION))
        val events = successList(call(TestChannelHandler.GET_TEST_EVENTS))
        assertTrue(events.none { it["event"] == "TEST_RUN_CONTINUED" })
        assertTrue(events.any { it["event"] == "SESSION_STARTED" })
    }

    @Test
    fun `adoptCampaignIfArmed emite TEST_RUN_CONTINUED con la sesion anterior`() {
        // Proceso 1: sesión + intención armada en P0 (el flujo del
        // incidente P0: el proceso muere sin desarmar).
        val first = newHandler(testMode = true)
        val started = successMap(call(TestChannelHandler.START_TEST_SESSION))
        val sessionId = started["sessionId"] as String
        val testRunId = started["testRunId"] as String
        val armed = successMap(
            call(
                TestChannelHandler.ARM_RECONCILIATION_PAUSE,
                mapOf("point" to "P0"),
            ),
        )
        assertEquals(testRunId, armed["testRunId"])
        first.close()
        handler = null

        // Proceso 2 (reinicio): mismo `filesDir`, manejador nuevo.
        val second = TestChannelHandler(
            filesDir = filesDir,
            testMode = true,
            clock = clock,
            exportRoot = exportRoot,
        ).also { handler = it }
        val adopted = second.adoptCampaignIfArmed()
        assertNotNull(adopted)
        assertEquals(testRunId, adopted!!.testRunId)
        assertTrue(adopted.sessionId != sessionId)

        val events = successList(call(TestChannelHandler.GET_TEST_EVENTS))
        val continued = events.filter { it["event"] == "TEST_RUN_CONTINUED" }
        assertEquals(1, continued.size)
        @Suppress("UNCHECKED_CAST")
        val detail = continued[0]["detail"] as Map<String, Any?>
        assertEquals(testRunId, detail["adoptedTestRunId"])
        assertEquals(sessionId, detail["previousSessionId"])
    }

    // ------------------------------------------------------------------
    // Fallo de escritura de armed-pause.json
    // ------------------------------------------------------------------

    @Test
    fun `arm con escritura fallida registra ARMED_PAUSE_WRITE_FAILED y relanza`() =
        runBlocking {
            val syncPoints = TestSyncPoints(
                enabled = { true },
                instrumentation = instrumentation,
                clock = clock,
                intentAccess = ThrowingWriteAccess(),
            )
            startNewSession()
            try {
                syncPoints.arm(PausePoint.P0)
                fail("Se esperaba IOException")
            } catch (e: IOException) {
                assertEquals("disco lleno (prueba)", e.message)
            }
            val failed = captured.filter {
                it.event == TestEventType.ARMED_PAUSE_WRITE_FAILED
            }
            assertEquals(1, failed.size)
            assertEquals(TestEventResult.FAILED, failed[0].result)
            assertEquals("reconcile", failed[0].operation)
            assertEquals("P0", failed[0].detail["point"])
            assertEquals("IOException", failed[0].detail["exceptionClass"])
            // Sin `PAUSE_ARMED`: el armado no se completó.
            assertTrue(captured.none { it.event == TestEventType.PAUSE_ARMED })
        }

    // ------------------------------------------------------------------
    // Hook de inyección de error en el canal
    // ------------------------------------------------------------------

    @Test
    fun `injectTestError con testMode false responde test_mode_unavailable`() {
        newHandler(
            testMode = false,
            errorInjectionProvider = { mapOf("injected" to true) },
        )
        val result = call(
            TestChannelHandler.INJECT_TEST_ERROR,
            mapOf("kind" to "encoder_error"),
        )
        assertEquals(
            TestChannelHandler.ERR_TEST_MODE_UNAVAILABLE,
            result.errorCode,
        )
        assertEquals(1, result.calls)
    }

    @Test
    fun `injectTestError sin proveedor responde test_not_available`() {
        newHandler(testMode = true)
        val result = call(
            TestChannelHandler.INJECT_TEST_ERROR,
            mapOf("kind" to "encoder_error"),
        )
        assertEquals(
            TestChannelHandler.ERR_TEST_NOT_AVAILABLE,
            result.errorCode,
        )
    }

    @Test
    fun `injectTestError con proveedor delega el kind y devuelve su mapa`() {
        val seenKinds = mutableListOf<String>()
        newHandler(
            testMode = true,
            errorInjectionProvider = { kind ->
                seenKinds += kind
                mapOf(
                    "injected" to true,
                    "kind" to kind,
                    "reason" to null,
                )
            },
        )
        val result = call(
            TestChannelHandler.INJECT_TEST_ERROR,
            mapOf("kind" to "drain_write_error"),
        )
        val map = successMap(result)
        assertEquals(true, map["injected"])
        assertEquals("drain_write_error", map["kind"])
        assertEquals(listOf("drain_write_error"), seenKinds)
    }

    @Test
    fun `injectTestError con kind desconocido responde test_error sin llamar al proveedor`() {
        var providerCalls = 0
        newHandler(
            testMode = true,
            errorInjectionProvider = { kind ->
                providerCalls++
                mapOf("injected" to true, "kind" to kind)
            },
        )
        val result = call(
            TestChannelHandler.INJECT_TEST_ERROR,
            mapOf("kind" to "camara_rota"),
        )
        assertEquals(TestChannelHandler.ERR_TEST_ERROR, result.errorCode)
        assertEquals(0, providerCalls)
    }
}
