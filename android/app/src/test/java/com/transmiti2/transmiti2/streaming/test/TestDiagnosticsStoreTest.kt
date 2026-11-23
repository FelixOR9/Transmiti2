package com.transmiti2.transmiti2.streaming.test

import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Almacén de diagnósticos (diseño base §6 test nº 9; Revisión 2 §3.2–§3.4
 * pruebas 2a–2b, 3a–3c, 4a–4b; Revisión 3 §4.5 prueba 5b-6; Revisión 1
 * §9.1 `processInterruptionIsRecordedOnNextStart`). Harness local con
 * `-ea`, `runBlocking`, directorios temporales (nunca el `filesDir`
 * real) y timeouts inyectados cortos.
 */
class TestDiagnosticsStoreTest {

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

    private lateinit var rootDir: File
    private lateinit var exportDir: File
    private lateinit var clock: FakeTestClock
    private lateinit var captured: MutableList<TestEvent>
    private lateinit var instrumentation: TestInstrumentation
    private lateinit var store: TestDiagnosticsStore

    @Before
    fun setUp() {
        Transmiti2Logger.sink = { _, _ -> }
        val base = File(
            System.getProperty("java.io.tmpdir"),
            "test-diagnostics-test-${System.nanoTime()}",
        )
        rootDir = File(base, "test-diagnostics").apply { mkdirs() }
        exportDir = File(base, "export").apply { mkdirs() }
        clock = FakeTestClock()
        captured = mutableListOf()
        instrumentation = TestInstrumentation(
            enabled = { true },
            clock = clock,
            sink = { captured += it },
        )
        store = TestDiagnosticsStore(rootDir, clock = clock)
    }

    @After
    fun tearDown() {
        rootDir.parentFile?.deleteRecursively()
        Transmiti2Logger.sink = null
    }

    /** Persiste en el almacén todos los eventos capturados hasta ahora. */
    private suspend fun drain() {
        for (event in captured) store.appendEvent(event)
        captured.clear()
    }

    /**
     * Crea una sesión nueva (termina la anterior si la hay), la arranca
     * en el almacén y en la instrumentación, y persiste su cabecera.
     */
    private suspend fun beginAndStart(): TestSession {
        instrumentation.activeSession?.let {
            instrumentation.endSession()
            drain()
        }
        val session = instrumentation.createSession(
            isTestRunIdTaken = { store.hasTestRunId(it) },
        )
        val result = store.beginSession(session)
        assertTrue(result is BeginSessionResult.Started)
        instrumentation.startSession(session)
        drain()
        return session
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private fun snapshotFiles(dir: File): Map<String, String> =
        dir.walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(dir).path to sha256(it) }

    // ------------------------------------------------------------------
    // Persistencia (test nº 9)
    // ------------------------------------------------------------------

    @Test
    fun `logSurvivesStoreReopen`() = runBlocking {
        val session = beginAndStart()
        clock.advance()
        instrumentation.record(TestEventType.RECORDING_STARTED, recordingId = "rec_a")
        clock.advance()
        instrumentation.record(TestEventType.RECORDING_STOPPED, recordingId = "rec_a")
        drain()

        // Nueva instancia del store sobre la misma raíz (nuevo proceso).
        val reopened = TestDiagnosticsStore(rootDir, clock = clock)
        val read = reopened.readSession(session.sessionId)

        assertEquals(0, read.corruptLines)
        assertEquals(
            listOf(
                TestEventType.SESSION_STARTED,
                TestEventType.RECORDING_STARTED,
                TestEventType.RECORDING_STOPPED,
            ),
            read.events.map { it.event },
        )
        assertTrue(read.events.all { it.sessionId == session.sessionId })
    }

    @Test
    fun `corruptLineIsSkippedNotFatal`() = runBlocking {
        val session = beginAndStart()
        instrumentation.record(TestEventType.RECORDING_STARTED, recordingId = "rec_a")
        drain()
        // Línea corrupta en medio del JSONL + un evento válido después.
        val file = File(rootDir, "${session.sessionId}.jsonl")
        file.appendText("{esto no es json\n", Charsets.UTF_8)
        instrumentation.record(TestEventType.RECORDING_STOPPED, recordingId = "rec_a")
        drain()

        val read = store.readSession(session.sessionId)
        assertEquals(1, read.corruptLines)
        assertEquals(
            listOf(
                TestEventType.SESSION_STARTED,
                TestEventType.RECORDING_STARTED,
                TestEventType.RECORDING_STOPPED,
            ),
            read.events.map { it.event },
        )
    }

    @Test
    fun `processInterruptionIsRecordedOnNextStart`() = runBlocking {
        val first = beginAndStart()
        instrumentation.record(TestEventType.RECORDING_STARTED, recordingId = "rec_a")
        drain()
        // La primera sesión muere sin SESSION_ENDED (sin endSession()).
        instrumentation.activeSession?.let {
            // Se abandona la sesión en memoria sin cerrarla: simula la
            // muerte del proceso. La instrumentación se recrea limpia.
            instrumentation = TestInstrumentation(
                enabled = { true },
                clock = clock,
                sink = { captured += it },
            )
        }
        val second = instrumentation.createSession(
            isTestRunIdTaken = { store.hasTestRunId(it) },
        )
        val result = store.beginSession(second)

        assertTrue(result is BeginSessionResult.Started)
        val started = result as BeginSessionResult.Started
        assertEquals(1, started.interruptedPrevious.size)
        val interrupted = started.interruptedPrevious.single()
        assertEquals(first.sessionId, interrupted.previousSessionId)
        assertEquals("RECORDING_STARTED", interrupted.lastObservedEvent)
        assertFalse(interrupted.recordedInNewSession)
        instrumentation.startSession(second)
        drain()
        val readFirst = store.readSession(first.sessionId)
        val processInterrupted = readFirst.events.singleOrNull {
            it.event == TestEventType.PROCESS_INTERRUPTED
        }
        assertNotNull(processInterrupted)
        assertEquals(TestEventOrigin.INFERRED, processInterrupted!!.origin)
        assertEquals(second.sessionId, processInterrupted.detail["detectedBySessionId"])
        assertEquals("RECORDING_STARTED", processInterrupted.detail["lastObservedEvent"])
    }

    // ------------------------------------------------------------------
    // Exportación (pruebas 2a–2b)
    // ------------------------------------------------------------------

    @Test
    fun `exportSessionProducesValidCompleteJson`() = runBlocking {
        val session = beginAndStart()
        clock.advance()
        instrumentation.record(TestEventType.RECORDING_STARTED, recordingId = "rec_a")
        clock.advance()
        instrumentation.record(TestEventType.RECORDING_STOPPED, recordingId = "rec_a")
        instrumentation.endSession()
        drain()

        val result = store.exportSession(session.sessionId, exportDir)
        assertTrue(result is ExportSessionResult.Exported)
        val exported = result as ExportSessionResult.Exported
        assertEquals(exportDir.canonicalFile, exported.jsonFile.parentFile?.canonicalFile)

        val json = TestJson.parseObject(exported.jsonFile.readText(Charsets.UTF_8))
        assertNotNull(json)
        assertEquals(session.sessionId, json!!.str("sessionId"))
        assertNotNull(json.str("verdict"))
        val criteria = json.arr("criteria")
        assertNotNull(criteria)
        assertEquals(2, criteria!!.items.size)
        val events = json.arr("events")
        assertNotNull(events)
        assertEquals(4, events!!.items.size)
        for (item in events.items) {
            val eventObj = item as TestJson.JsonValue.Obj
            assertNotNull(eventObj.str("testRunId"))
            assertNotNull(eventObj.str("sessionId"))
            assertNotNull(eventObj.str("origin"))
        }
        assertTrue((json.arr("recordings")?.items?.map {
            (it as TestJson.JsonValue.Str).value
        } ?: emptyList()).contains("rec_a"))

        val txt = exported.txtFile.readText(Charsets.UTF_8)
        assertTrue(txt.contains(json.str("verdict")!!))
        assertTrue(txt.contains("C1:"))
        assertTrue(txt.contains("C2:"))
    }

    @Test
    fun `exportAllSessionsExportsEachSessionSeparately`() = runBlocking {
        val first = beginAndStart()
        instrumentation.record(TestEventType.RECORDING_STARTED, recordingId = "rec_a")
        drain()
        val second = beginAndStart()
        instrumentation.record(TestEventType.RECORDING_STARTED, recordingId = "rec_b")
        instrumentation.endSession()
        drain()

        val results = store.exportAll(exportDir)
        assertEquals(2, results.size)
        assertTrue(results.all { it is ExportSessionResult.Exported })
        val firstJson = TestJson.parseObject(
            File(exportDir, "${first.sessionId}-report.json").readText(Charsets.UTF_8),
        )!!
        val secondJson = TestJson.parseObject(
            File(exportDir, "${second.sessionId}-report.json").readText(Charsets.UTF_8),
        )!!
        val firstIds = firstJson.arr("events")!!.items.map {
            (it as TestJson.JsonValue.Obj).str("sessionId")
        }
        val secondIds = secondJson.arr("events")!!.items.map {
            (it as TestJson.JsonValue.Obj).str("sessionId")
        }
        assertTrue(firstIds.all { it == first.sessionId })
        assertTrue(secondIds.all { it == second.sessionId })
        assertEquals(4, exportDir.listFiles()!!.size) // 2 json + 2 txt
    }

    // ------------------------------------------------------------------
    // Eliminación explícita (pruebas 3a–3c)
    // ------------------------------------------------------------------

    @Test
    fun `deleteSessionRemovesOnlyThatSession`() = runBlocking {
        val first = beginAndStart()
        val second = beginAndStart()
        instrumentation.record(TestEventType.RECORDING_STARTED, recordingId = "rec_b")
        drain()
        val third = beginAndStart()
        instrumentation.endSession()
        drain()
        // Informes de la sesión a borrar (deben borrarse con ella).
        File(rootDir, "${second.sessionId}-report.json").writeText("{}", Charsets.UTF_8)
        File(rootDir, "${second.sessionId}-report.txt").writeText("txt", Charsets.UTF_8)

        val beforeFirst = sha256(File(rootDir, "${first.sessionId}.jsonl"))
        val beforeThird = sha256(File(rootDir, "${third.sessionId}.jsonl"))
        val result = store.deleteSession(second.sessionId, confirmed = true)

        assertTrue(result is DeleteSessionResult.Deleted)
        assertFalse(File(rootDir, "${second.sessionId}.jsonl").exists())
        assertFalse(File(rootDir, "${second.sessionId}-report.json").exists())
        assertFalse(File(rootDir, "${second.sessionId}-report.txt").exists())
        assertEquals(beforeFirst, sha256(File(rootDir, "${first.sessionId}.jsonl")))
        assertEquals(beforeThird, sha256(File(rootDir, "${third.sessionId}.jsonl")))
        val admin = store.readAdminLines()
        assertEquals(1, admin.size)
        val entry = TestJson.parseObject(admin.single())
        assertEquals("SESSION_DELETED", entry?.str("event"))
        assertEquals(second.sessionId, entry?.str("sessionId"))
    }

    @Test
    fun `deleteWithoutConfirmationDeletesNothing`() = runBlocking {
        val session = beginAndStart()
        instrumentation.endSession()
        drain()
        val before = snapshotFiles(rootDir)

        val result = store.deleteSession(session.sessionId, confirmed = false)

        assertTrue(result is DeleteSessionResult.ConfirmationRequired)
        assertEquals(before, snapshotFiles(rootDir))
        assertTrue(store.readAdminLines().isEmpty())
    }

    @Test
    fun `deletionIsRecordedInAdminLog`() = runBlocking {
        val session = beginAndStart()
        instrumentation.endSession()
        drain()
        val sizeBefore = File(rootDir, "${session.sessionId}.jsonl").length()

        store.deleteSession(session.sessionId, confirmed = true)

        val admin = store.readAdminLines()
        assertEquals(1, admin.size)
        val entry = TestJson.parseObject(admin.single())
        assertNotNull(entry)
        assertEquals("SESSION_DELETED", entry!!.str("event"))
        assertEquals(session.sessionId, entry.str("sessionId"))
        assertEquals(session.testRunId, entry.str("testRunId"))
        assertEquals(sizeBefore, entry.long("sizeBytes"))
        assertNotNull(entry.long("wallMs"))
        assertEquals("observed", entry.str("origin"))
        assertEquals("user", entry.str("deletedBy"))
    }

    // ------------------------------------------------------------------
    // Bloqueo por tope (pruebas 4a–4b)
    // ------------------------------------------------------------------

    @Test
    fun `eleventhStartIsRejectedWithTestStorageFull`() = runBlocking {
        repeat(10) { beginAndStart() }
        val before = snapshotFiles(rootDir)

        val attempted = instrumentation.createSession(
            isTestRunIdTaken = { store.hasTestRunId(it) },
        )
        val result = store.beginSession(attempted)

        assertTrue(result is BeginSessionResult.Blocked)
        assertEquals("test_storage_full", (result as BeginSessionResult.Blocked).reason)
        assertFalse(File(rootDir, "${attempted.sessionId}.jsonl").exists())
        // Las 10 sesiones quedan byte a byte idénticas.
        val afterSessions = snapshotFiles(rootDir)
            .filterKeys { it.endsWith(".jsonl") && it != "blocked-starts.jsonl" }
        assertEquals(before.filterKeys { it.endsWith(".jsonl") }, afterSessions)
        val blocked = store.readBlockedStarts()
        assertEquals(1, blocked.size)
        assertEquals(attempted.testRunId, blocked.single().testRunId)
        assertEquals("test_storage_full", blocked.single().reason)
        assertEquals(10, store.storageState().sessionCount)
        assertTrue(store.storageState().full)
    }

    @Test
    fun `afterExplicitDeleteNewSessionStartsAgain`() = runBlocking {
        val sessions = (1..10).map { beginAndStart() }
        store.deleteSession(sessions.first().sessionId, confirmed = true)

        val session = beginAndStart()

        assertNotNull(session)
        assertEquals(10, store.storageState().sessionCount)
    }

    // ------------------------------------------------------------------
    // Contención de rutas (prueba 5b-6)
    // ------------------------------------------------------------------

    @Test
    fun `containmentRuleUnit`() {
        val canonicalRoot = rootDir.canonicalFile
        // La propia raíz está permitida.
        assertEquals(canonicalRoot, store.resolveInsideRoot(""))
        // Hijo directo y hijo anidado permitidos.
        assertEquals(
            File(canonicalRoot, "abc.jsonl").canonicalFile,
            store.resolveInsideRoot("abc.jsonl"),
        )
        assertEquals(
            File(canonicalRoot, "sub/dir/file.jsonl").canonicalFile,
            store.resolveInsideRoot("sub/dir/file.jsonl"),
        )
        // Hermano de mismo prefijo: rechazado (la regla exige el separador).
        assertThrows(IllegalArgumentException::class.java) {
            store.resolveInsideRoot("../test-diagnostics-evil/x.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.resolveInsideRoot("../test-diagnostics2/x.json")
        }
        // Traversal con `..`: rechazado tras canonizar.
        assertThrows(IllegalArgumentException::class.java) {
            store.resolveInsideRoot("../recordings/x.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.resolveInsideRoot("sub/../../outside.jsonl")
        }
        // Ruta absoluta fuera de la raíz: rechazada antes de resolver.
        assertThrows(IllegalArgumentException::class.java) {
            store.resolveInsideRoot(File(rootDir.parentFile, "x.json").absolutePath)
        }
        // El código de canal viaja en el mensaje.
        try {
            store.resolveInsideRoot("../recordings/x.json")
            throw AssertionError("se esperaba IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("test_path_outside_root"))
        }
    }
}
