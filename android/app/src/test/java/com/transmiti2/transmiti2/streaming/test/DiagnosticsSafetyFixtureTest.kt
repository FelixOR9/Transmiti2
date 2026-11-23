package com.transmiti2.transmiti2.streaming.test

import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import com.transmiti2.transmiti2.streaming.recording.RecordingFileStore
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Prueba de seguridad (Revisión 2 §3.5 + Revisión 3 §3/§4: pruebas 5a,
 * 5b-1–5b-5, 5c y 2c como alias documentado de 5a para exportación).
 *
 * Fixture con DOS raíces separadas (Revisión 3 §3.2): `dataRoot` (árbol
 * bajo prueba, cuyos hashes se comparan) y `exportRoot` (directorio
 * temporal hermano, vacío al inicio; solo la exportación escribe ahí).
 * El blob `transmiti2.db` nunca se abre como SQLite: solo se compara
 * por bytes (la propiedad bajo prueba es que el almacén no abre la BD).
 */
class DiagnosticsSafetyFixtureTest {

    private class FakeTestClock(
        private var monotonicValue: Long = 1_000_000L,
        private var wallValue: Long = 1_792_000_000_000L,
    ) : TestClock {
        override fun monotonicMs(): Long = monotonicValue
        override fun wallMs(): Long = wallValue
    }

    private lateinit var tempRoot: File
    private lateinit var dataRoot: File
    private lateinit var exportRoot: File
    private lateinit var diagnosticsDir: File
    private lateinit var recordingsDir: File
    private lateinit var clock: FakeTestClock
    private lateinit var captured: MutableList<TestEvent>
    private lateinit var instrumentation: TestInstrumentation
    private lateinit var store: TestDiagnosticsStore
    private lateinit var sessionIds: List<String>

    @Before
    fun setUp() {
        Transmiti2Logger.sink = { _, _ -> }
        tempRoot = File(
            System.getProperty("java.io.tmpdir"),
            "diagnostics-fixture-test-${System.nanoTime()}",
        )
        dataRoot = File(tempRoot, "data").apply { mkdirs() }
        exportRoot = File(tempRoot, "export").apply { mkdirs() }
        diagnosticsDir = File(dataRoot, "files/test-diagnostics").apply { mkdirs() }
        recordingsDir = File(dataRoot, "files/recordings").apply { mkdirs() }
        File(dataRoot, "databases").mkdirs()

        // Grabaciones, segmentos, sidecars y BD con bytes conocidos.
        File(recordingsDir, "rec_20261123_150000_aaaa_seg000.mp4")
            .writeBytes(ByteArray(128) { 0x41 })
        File(recordingsDir, "rec_20261123_150000_aaaa_seg001.mp4")
            .writeBytes(ByteArray(256) { 0x42 })
        File(recordingsDir, "rec_20261123_150000_aaaa_seg002.tmp.mp4")
            .writeBytes(ByteArray(64) { 0x43 })
        File(recordingsDir, "rec_20261123_150000_aaaa.json")
            .writeText("{\"recordingId\": \"rec_20261123_150000_aaaa\"}", Charsets.UTF_8)
        File(recordingsDir, "rec_20261123_151000_bbbb_seg000.mp4")
            .writeBytes(ByteArray(512) { 0x44 })
        File(recordingsDir, "rec_20261123_151000_bbbb.json")
            .writeText("{\"recordingId\": \"rec_20261123_151000_bbbb\"}", Charsets.UTF_8)
        File(dataRoot, "databases/transmiti2.db")
            .writeBytes(ByteArray(1024) { (it % 251).toByte() })

        clock = FakeTestClock()
        captured = mutableListOf()
        instrumentation = TestInstrumentation(
            enabled = { true },
            clock = clock,
            sink = { captured += it },
        )
        store = TestDiagnosticsStore(diagnosticsDir, clock = clock)
        sessionIds = runBlocking {
            val ids = (1..10).map { beginAndStart().sessionId }
            // Sin sesión activa al terminar el setup: el snapshot previo
            // no debe cambiar por un SESSION_ENDED posterior.
            instrumentation.endSession()
            drain()
            // Informes preexistentes en la raíz (deben quedar intactos).
            for (id in ids.take(2)) {
                File(diagnosticsDir, "$id-report.json").writeText(
                    "{\"sessionId\": \"$id\", \"verdict\": \"INCONCLUSIVE\"}",
                    Charsets.UTF_8,
                )
                File(diagnosticsDir, "$id-report.txt").writeText(
                    "VEREDICTO: INCONCLUSIVE",
                    Charsets.UTF_8,
                )
            }
            ids
        }
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
        Transmiti2Logger.sink = null
    }

    private suspend fun drain() {
        for (event in captured) store.appendEvent(event)
        captured.clear()
    }

    private suspend fun beginAndStart(): TestSession {
        instrumentation.activeSession?.let {
            instrumentation.endSession()
            drain()
        }
        val session = instrumentation.createSession(
            isTestRunIdTaken = { store.hasTestRunId(it) },
        )
        val result = store.beginSession(session)
        check(result is BeginSessionResult.Started)
        instrumentation.startSession(session)
        drain()
        return session
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    /** Mapa `rutaRelativa → SHA-256` de todo el árbol bajo [root]. */
    private fun snapshotTree(root: File): Map<String, String> =
        root.walkTopDown()
            // No seguir enlaces simbólicos: un enlace a otro directorio
            // listaría sus archivos otra vez bajo la ruta del enlace.
            .onEnter { dir -> !Files.isSymbolicLink(dir.toPath()) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .associate { it.relativeTo(root).path to sha256(it) }

    private fun diagnosticsPath(name: String) = "files/test-diagnostics/$name"

    // ------------------------------------------------------------------
    // 5a — exportar y eliminar nunca tocan grabaciones ni BD
    // ------------------------------------------------------------------

    @Test
    fun `exportAndDeleteNeverTouchRecordingsOrDatabase`() = runBlocking {
        // 2. Snapshot antes (el fixture ya está construido en setUp).
        val before = snapshotTree(dataRoot)
        // 3. Exportar una sesión.
        val exported = store.exportSession(sessionIds[0], exportRoot)
        assertTrue(exported is ExportSessionResult.Exported)
        // 4. Exportar todas.
        val all = store.exportAll(exportRoot)
        assertEquals(10, all.size)
        assertTrue(all.all { it is ExportSessionResult.Exported })
        // 5. Eliminar una sesión (con confirmación).
        val deleted = store.deleteSession(sessionIds[0], confirmed = true)
        assertTrue(deleted is DeleteSessionResult.Deleted)
        // 5b. Rellenar el hueco liberado (prueba 4b): sin este paso el
        // intento siguiente no llegaría al tope (9 < 10) y no podría
        // bloquearse; el orden literal de Revisión 3 §3.3 no puede
        // producir un intento bloqueado tras borrar una sesión.
        beginAndStart()
        // 6. Intentar un undécimo inicio: bloqueado con test_storage_full.
        val attempted = instrumentation.createSession(
            isTestRunIdTaken = { store.hasTestRunId(it) },
        )
        val blocked = store.beginSession(attempted)
        assertTrue(blocked is BeginSessionResult.Blocked)
        assertEquals("test_storage_full", (blocked as BeginSessionResult.Blocked).reason)
        // 7. Snapshot después.
        val after = snapshotTree(dataRoot)

        // 8a. Fuera de test-diagnostics/: lista y hashes idénticos.
        val beforeOutside = before.filterKeys { !it.startsWith("files/test-diagnostics/") }
        val afterOutside = after.filterKeys { !it.startsWith("files/test-diagnostics/") }
        assertEquals(beforeOutside, afterOutside)
        // 8b. Dentro de test-diagnostics/: solo los cambios esperados.
        val removed = before.keys - after.keys
        val added = after.keys - before.keys
        val changed = before.keys.intersect(after.keys).filter { before[it] != after[it] }
        val deletedId = sessionIds[0]
        assertEquals(
            setOf(
                diagnosticsPath("$deletedId.jsonl"),
                diagnosticsPath("$deletedId-report.json"),
                diagnosticsPath("$deletedId-report.txt"),
            ),
            removed,
        )
        // La sesión de relleno es la única sesión nueva; admin.jsonl y
        // blocked-starts.jsonl aparecen por el borrado y el bloqueo.
        val newSessionFile = added.singleOrNull {
            it.startsWith("files/test-diagnostics/") &&
                it.endsWith(".jsonl") &&
                it != diagnosticsPath("admin.jsonl") &&
                it != diagnosticsPath("blocked-starts.jsonl")
        }
        assertNotNull(newSessionFile)
        assertEquals(
            setOf(
                newSessionFile!!,
                diagnosticsPath("admin.jsonl"),
                diagnosticsPath("blocked-starts.jsonl"),
            ),
            added,
        )
        assertTrue("cambios inesperados dentro de test-diagnostics: $changed", changed.isEmpty())
        // 8c. La exportación realmente ocurrió, fuera del árbol comparado.
        val exportedJson = File(exportRoot, "${sessionIds[0]}-report.json")
        assertTrue(exportedJson.isFile)
        val parsed = TestJson.parseObject(exportedJson.readText(Charsets.UTF_8))
        assertNotNull(parsed)
        assertEquals(sessionIds[0], parsed!!.str("sessionId"))
        assertNotNull(parsed.str("verdict"))
        assertEquals(20, exportRoot.listFiles()!!.size) // 10 json + 10 txt
        // La BD nunca se abrió como SQLite: solo se comparó por bytes
        // (su hash ya quedó cubierto por la comparación de fuera).
        assertEquals(1, store.readBlockedStarts().size)
    }

    // ------------------------------------------------------------------
    // 2c — exportar es de solo lectura (alias de 5a para exportación)
    // ------------------------------------------------------------------

    @Test
    fun `exportIsReadOnly`() = runBlocking {
        val before = snapshotTree(dataRoot)
        store.exportSession(sessionIds[0], exportRoot)
        store.exportAll(exportRoot)
        val after = snapshotTree(dataRoot)

        assertEquals(before, after)
        assertEquals(20, exportRoot.listFiles()!!.size)
        val parsed = TestJson.parseObject(
            File(exportRoot, "${sessionIds[0]}-report.json").readText(Charsets.UTF_8),
        )
        assertNotNull(parsed)
        val events = parsed!!.arr("events")!!
        assertTrue(events.items.isNotEmpty())
        for (item in events.items) {
            val eventObj = item as TestJson.JsonValue.Obj
            assertNotNull(eventObj.str("testRunId"))
            assertNotNull(eventObj.str("sessionId"))
            assertNotNull(eventObj.str("origin"))
        }
    }

    // ------------------------------------------------------------------
    // 5b — contención de rutas (hermanos, traversal, raíz, enlace)
    // ------------------------------------------------------------------

    @Test
    fun `storeRejectsSiblingPrefixDirectory`() = runBlocking {
        val before = snapshotTree(dataRoot)
        try {
            store.resolveInsideRoot("../test-diagnostics-evil/x.json")
            throw AssertionError("se esperaba IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("test_path_outside_root"))
        }
        assertFalse(File(dataRoot, "files/test-diagnostics-evil").exists())
        assertEquals(before, snapshotTree(dataRoot))
    }

    @Test
    fun `storeRejectsSiblingWithNumericSuffix`() = runBlocking {
        val before = snapshotTree(dataRoot)
        try {
            store.resolveInsideRoot("../test-diagnostics2/x.json")
            throw AssertionError("se esperaba IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("test_path_outside_root"))
        }
        assertFalse(File(dataRoot, "files/test-diagnostics2").exists())
        assertEquals(before, snapshotTree(dataRoot))
    }

    @Test
    fun `storeRejectsDotDotTraversal`() = runBlocking {
        val before = snapshotTree(dataRoot)
        for (path in listOf(
            "../recordings/x.json",
            "test-diagnostics/../../recordings/x.json",
        )) {
            try {
                store.resolveInsideRoot(path)
                throw AssertionError("se esperaba IllegalArgumentException para $path")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("test_path_outside_root"))
            }
        }
        assertEquals(before, snapshotTree(dataRoot))
    }

    @Test
    fun `storeAllowsRootAndLegitimateSubdirectory`() {
        assertEquals(diagnosticsDir.canonicalFile, store.resolveInsideRoot(""))
        val nested = store.resolveInsideRoot("sub/file.jsonl")
        nested.parentFile?.mkdirs()
        nested.writeText("{}", Charsets.UTF_8)
        assertTrue(nested.isFile)
        assertEquals(
            File(diagnosticsDir, "plain.jsonl").canonicalFile,
            store.resolveInsideRoot("plain.jsonl"),
        )
    }

    @Test
    fun `storeRejectsSymlinkPointingOutside`() = runBlocking {
        val before = snapshotTree(dataRoot)
        val link = File(diagnosticsDir, "link-out")
        try {
            Files.createSymbolicLink(link.toPath(), recordingsDir.toPath())
        } catch (e: UnsupportedOperationException) {
            Assume.assumeTrue("enlaces simbólicos no soportados en este entorno", false)
            return@runBlocking
        } catch (e: java.io.IOException) {
            Assume.assumeTrue("enlaces simbólicos no soportados en este entorno", false)
            return@runBlocking
        } catch (e: SecurityException) {
            Assume.assumeTrue("enlaces simbólicos no soportados en este entorno", false)
            return@runBlocking
        }
        try {
            store.resolveInsideRoot("link-out/x.json")
            throw AssertionError("se esperaba IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("test_path_outside_root"))
        }
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals(before, snapshotTree(dataRoot))
    }

    // ------------------------------------------------------------------
    // 5c — el inventario de reconciliación no ve test-diagnostics
    // ------------------------------------------------------------------

    @Test
    fun `reconciliationInventoryDoesNotSeeTestDiagnostics`() {
        val fileStore = RecordingFileStore(recordingsDir)
        val finals = fileStore.listFinalSegmentFiles()
        val stagings = fileStore.listStagingFiles()
        val sidecars = fileStore.listSidecarFiles()

        assertEquals(
            listOf(
                "rec_20261123_150000_aaaa_seg000.mp4",
                "rec_20261123_150000_aaaa_seg001.mp4",
                "rec_20261123_151000_bbbb_seg000.mp4",
            ),
            finals.map { it.name },
        )
        assertEquals(
            listOf("rec_20261123_150000_aaaa_seg002.tmp.mp4"),
            stagings.map { it.name },
        )
        assertEquals(
            listOf(
                "rec_20261123_150000_aaaa.json",
                "rec_20261123_151000_bbbb.json",
            ),
            sidecars.map { it.name },
        )
        val allListed = finals + stagings + sidecars
        assertTrue(allListed.none { it.path.contains("test-diagnostics") })
        assertTrue(allListed.none { it.name.endsWith(".jsonl") })
        assertTrue(allListed.none { it.name.contains("-report.") })
        val ids = finals.mapNotNull {
            RecordingFileStore.parseSegmentFileName(it.name)?.first
        }.toSet()
        assertEquals(
            setOf("rec_20261123_150000_aaaa", "rec_20261123_151000_bbbb"),
            ids,
        )
    }
}
