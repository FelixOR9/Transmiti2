package com.transmiti2.transmiti2.streaming.test

import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Puerta de compilación (diseño base §6 test nº 12, parte Kotlin;
 * FASE 2, grupo 2-b). El flag se inyecta como parámetro de constructor
 * en los tests; en producción toma el valor de `BuildConfig`.
 *
 * Harness local con `-ea`, directorios temporales (nunca el `filesDir`
 * real) y reloj falso. El `sink` de la instrumentación persiste de
 * forma asíncrona (un `launch` en el scope propio del handler), así
 * que las afirmaciones sobre el contenido del JSONL esperan con un
 * sondeo acotado en vez de asumir que ya se escribió.
 */
class TestModeGateTest {

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

    private lateinit var baseDir: File
    private lateinit var filesDir: File
    private lateinit var exportRoot: File
    private lateinit var clock: FakeTestClock
    private var handler: TestChannelHandler? = null

    @Before
    fun setUp() {
        Transmiti2Logger.sink = { _, _ -> }
        baseDir = File(
            System.getProperty("java.io.tmpdir"),
            "test-mode-gate-${System.nanoTime()}",
        )
        filesDir = File(baseDir, "files").apply { mkdirs() }
        exportRoot = File(baseDir, "export").apply { mkdirs() }
        clock = FakeTestClock()
    }

    @After
    fun tearDown() {
        handler?.close()
        handler = null
        baseDir.deleteRecursively()
        Transmiti2Logger.sink = null
    }

    private fun newHandler(testMode: Boolean): TestChannelHandler =
        TestChannelHandler(
            filesDir = filesDir,
            testMode = testMode,
            clock = clock,
            exportRoot = exportRoot,
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

    /** Espera acotada a que el JSONL de la sesión contenga `marker`. */
    private fun waitForSessionLine(sessionId: String, marker: String, timeoutMs: Long = 2_000L) {
        val file = File(File(filesDir, "test-diagnostics"), "$sessionId.jsonl")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile && file.readLines(Charsets.UTF_8).any { it.contains(marker) }) return
            Thread.sleep(20)
        }
        throw AssertionError("El JSONL de $sessionId no contiene $marker tras ${timeoutMs}ms")
    }

    private fun sessionFile(sessionId: String): File =
        File(File(filesDir, "test-diagnostics"), "$sessionId.jsonl")

    private fun startSession(adoptTestRunId: String? = null): Map<String, Any?> {
        val args = if (adoptTestRunId == null) null
        else mapOf<String, Any?>("adoptTestRunId" to adoptTestRunId)
        val started = successMap(call(TestChannelHandler.START_TEST_SESSION, args))
        val sessionId = started["sessionId"] as String
        waitForSessionLine(sessionId, "SESSION_STARTED")
        return started
    }

    @Test
    fun `channelReturnsUnavailableWhenFlagIsFalse`() {
        newHandler(testMode = false)
        val calls = listOf(
            TestChannelHandler.START_TEST_RUN to null,
            TestChannelHandler.START_TEST_SESSION to
                mapOf<String, Any?>("adoptTestRunId" to "run_20261123_150000_ab12"),
            TestChannelHandler.END_TEST_SESSION to
                mapOf<String, Any?>("verdict" to "PASSED"),
            TestChannelHandler.GET_TEST_EVENTS to
                mapOf<String, Any?>("sessionId" to "sesion-1"),
            TestChannelHandler.GET_TEST_RUNS to null,
            TestChannelHandler.GET_TEST_SESSIONS to null,
            TestChannelHandler.GET_STORAGE_STATE to null,
            TestChannelHandler.DELETE_TEST_SESSION to
                mapOf<String, Any?>("sessionId" to "sesion-1", "confirmed" to true),
            TestChannelHandler.EXPORT_TEST_REPORT to
                mapOf<String, Any?>("sessionId" to "sesion-1"),
            TestChannelHandler.EXPORT_TEST_SESSIONS to null,
            TestChannelHandler.ARM_RECONCILIATION_PAUSE to
                mapOf<String, Any?>("point" to "P1"),
            TestChannelHandler.RESUME_RECONCILIATION to
                mapOf<String, Any?>("point" to "P1"),
            TestChannelHandler.GET_PAUSE_STATE to
                mapOf<String, Any?>("point" to "P1"),
            TestChannelHandler.GET_REGISTRY_SNAPSHOT to null,
            TestChannelHandler.SAMPLE_ACTIVE_STAGING_SIZE to null,
        )
        for ((method, args) in calls) {
            val result = call(method, args)
            assertEquals(
                "El método $method debe responder test_mode_unavailable con el flag en false",
                TestChannelHandler.ERR_TEST_MODE_UNAVAILABLE,
                result.errorCode,
            )
            assertEquals(1, result.calls)
        }
        // Con el flag en false no se toca disco: ni siquiera se crea la raíz.
        assertFalse(File(filesDir, "test-diagnostics").exists())
    }

    @Test
    fun `getTestModeReflectsFlag`() {
        newHandler(testMode = false)
        val off = call(TestChannelHandler.GET_TEST_MODE)
        assertNull(off.errorCode)
        assertEquals(false, off.successValue)

        handler?.close()
        newHandler(testMode = true)
        val on = call(TestChannelHandler.GET_TEST_MODE)
        assertNull(on.errorCode)
        assertEquals(true, on.successValue)
    }

    @Test
    fun `startTestSessionWithFlagTrueStartsSessionAndPersistsHeader`() {
        newHandler(testMode = true)
        val started = startSession()
        val sessionId = started["sessionId"] as String
        val testRunId = started["testRunId"] as String
        assertTrue(testRunId.startsWith("run_"))
        assertTrue(sessionFile(sessionId).isFile)

        val ended = successMap(call(TestChannelHandler.END_TEST_SESSION))
        assertEquals(sessionId, ended["sessionId"])
        assertEquals(testRunId, ended["testRunId"])
        assertEquals(true, ended["ended"])
        waitForSessionLine(sessionId, "SESSION_ENDED")

        // Sin sesión activa, terminar de nuevo responde null.
        val again = call(TestChannelHandler.END_TEST_SESSION)
        assertNull(again.errorCode)
        assertNull(again.successValue)
    }

    @Test
    fun `startTestRunReturnsNewRunId`() {
        newHandler(testMode = true)
        val first = successMap(call(TestChannelHandler.START_TEST_RUN))
        val second = successMap(call(TestChannelHandler.START_TEST_RUN))
        val firstId = first["testRunId"] as String
        val secondId = second["testRunId"] as String
        assertTrue(firstId.startsWith("run_"))
        assertTrue(secondId.startsWith("run_"))
        // Mismo segundo de reloj falso: el sufijo aleatorio los distingue.
        assertTrue(firstId != secondId)
    }

    @Test
    fun `startTestSessionWithAdoptedRunIdContinuesCampaign`() {
        newHandler(testMode = true)
        val run = successMap(call(TestChannelHandler.START_TEST_RUN))
        val testRunId = run["testRunId"] as String
        val started = startSession(adoptTestRunId = testRunId)
        assertEquals(testRunId, started["testRunId"])

        // La lista de campañas se devuelve como valor directo, no como mapa.
        val runsResult = call(TestChannelHandler.GET_TEST_RUNS)
        assertNull(runsResult.errorCode)
        @Suppress("UNCHECKED_CAST")
        val runs = runsResult.successValue as List<Map<String, Any?>>
        assertEquals(1, runs.size)
        assertEquals(testRunId, runs[0]["testRunId"])
        assertEquals(1, runs[0]["sessionCount"])
    }

    @Test
    fun `startTestSessionTwiceReturnsSessionAlreadyActive`() {
        newHandler(testMode = true)
        startSession()
        val second = call(TestChannelHandler.START_TEST_SESSION)
        assertEquals(TestChannelHandler.ERR_TEST_ERROR, second.errorCode)
        @Suppress("UNCHECKED_CAST")
        val details = second.errorDetails as Map<String, Any?>
        assertEquals("session_already_active", details["reason"])
        // La segunda sesión no deja un `.jsonl` huérfano: sigue habiendo una sola.
        val sessions = File(filesDir, "test-diagnostics")
            .listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        assertEquals(1, sessions.size)
    }

    @Test
    fun `getStorageStateReflectsSessions`() {
        newHandler(testMode = true)
        val empty = successMap(call(TestChannelHandler.GET_STORAGE_STATE))
        assertEquals(0, empty["sessionCount"])
        assertEquals(TestDiagnosticsStore.MAX_SESSIONS, empty["limit"])
        assertEquals(false, empty["full"])

        startSession()
        val one = successMap(call(TestChannelHandler.GET_STORAGE_STATE))
        assertEquals(1, one["sessionCount"])
        assertEquals(false, one["full"])
    }

    @Test
    fun `deleteTestSessionWithoutConfirmationReturnsConfirmationRequired`() {
        newHandler(testMode = true)
        val started = startSession()
        val sessionId = started["sessionId"] as String
        call(TestChannelHandler.END_TEST_SESSION)

        val unconfirmed = call(
            TestChannelHandler.DELETE_TEST_SESSION,
            mapOf<String, Any?>("sessionId" to sessionId),
        )
        assertEquals(
            TestChannelHandler.ERR_CONFIRMATION_REQUIRED,
            unconfirmed.errorCode,
        )
        assertTrue("Sin confirmación no se borra nada", sessionFile(sessionId).isFile)

        val deleted = successMap(
            call(
                TestChannelHandler.DELETE_TEST_SESSION,
                mapOf<String, Any?>("sessionId" to sessionId, "confirmed" to true),
            ),
        )
        assertEquals(sessionId, deleted["sessionId"])
        assertEquals(true, deleted["deleted"])
        assertFalse(sessionFile(sessionId).exists())
    }

    @Test
    fun `armWithoutActiveSessionReturnsNoActiveSession`() {
        newHandler(testMode = true)
        val result = call(
            TestChannelHandler.ARM_RECONCILIATION_PAUSE,
            mapOf<String, Any?>("point" to "P1"),
        )
        assertEquals(
            TestChannelHandler.ERR_TEST_NO_ACTIVE_SESSION,
            result.errorCode,
        )
    }

    @Test
    fun `registrySnapshotAndStagingSizeWithoutProviderReturnNotAvailable`() {
        newHandler(testMode = true)
        val snapshot = call(TestChannelHandler.GET_REGISTRY_SNAPSHOT)
        assertEquals(TestChannelHandler.ERR_TEST_NOT_AVAILABLE, snapshot.errorCode)
        @Suppress("UNCHECKED_CAST")
        val snapshotDetails = snapshot.errorDetails as Map<String, Any?>
        assertEquals("registry_provider_not_wired", snapshotDetails["reason"])

        val staging = call(TestChannelHandler.SAMPLE_ACTIVE_STAGING_SIZE)
        assertEquals(TestChannelHandler.ERR_TEST_NOT_AVAILABLE, staging.errorCode)
        @Suppress("UNCHECKED_CAST")
        val stagingDetails = staging.errorDetails as Map<String, Any?>
        assertEquals("staging_size_provider_not_wired", stagingDetails["reason"])
    }

    @Test
    fun `getPauseStateReturnsAllThreeWithoutPoint`() {
        newHandler(testMode = true)
        val result = call(TestChannelHandler.GET_PAUSE_STATE)
        assertNull(result.errorCode)
        @Suppress("UNCHECKED_CAST")
        val states = result.successValue as List<Map<String, Any?>>
        assertEquals(listOf("P0", "P1", "P2"), states.map { it["point"] })
        assertTrue(states.all { it["state"] == "NONE" })
    }

    @Test
    fun `exportTestReportReturnsPathsAndUnknownSessionIsNotFound`() {
        newHandler(testMode = true)
        val started = startSession()
        val sessionId = started["sessionId"] as String
        val exported = successMap(
            call(
                TestChannelHandler.EXPORT_TEST_REPORT,
                mapOf<String, Any?>("sessionId" to sessionId),
            ),
        )
        assertEquals(sessionId, exported["sessionId"])
        assertTrue(File(exported["jsonPath"] as String).isFile)
        assertTrue(File(exported["txtPath"] as String).isFile)

        val missing = call(
            TestChannelHandler.EXPORT_TEST_REPORT,
            mapOf<String, Any?>("sessionId" to "sesion-inexistente"),
        )
        assertEquals(TestChannelHandler.ERR_SESSION_NOT_FOUND, missing.errorCode)
    }
}
