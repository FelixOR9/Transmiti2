package com.transmiti2.transmiti2.streaming.recording

import com.transmiti2.transmiti2.streaming.StreamContract
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pruebas de `getRecordingsDir()` + V1–V4c (subetapa 0, grupo 0-b):
 * U16–U19, U18a–U18c, U54–U61, U58a/U58b. Estilo existente
 * (`runBlocking` + hilos reales; sin `kotlinx-coroutines-test`).
 *
 * El mecanismo del fake es parte de la especificación (revisión 4
 * §3.7): `delay(...)` es suspendible (U58a) y `Thread.sleep(...)`
 * es bloqueante, fiel a una syscall (U58b/U56/U57/U60). Un fake con
 * `delay` en U58b haría pasar la prueba por el camino equivocado.
 *
 * La rama del manejador en sí no se puede instanciar en JVM (su
 * `Context` tiene 104 métodos abstractos y `PermissionManager`
 * necesita `SharedPreferences` reales): lo que se prueba aquí es el
 * flujo extraído ([launchGetRecordingsDir]) que el manejador llama
 * como adaptador fino sin tocar `previewEngine` (U53 queda para
 * 0-e/inspección + F1; ver informe del grupo 0-b).
 */
class RecordingsDirResolverTest {

    /** FS falso que delega en `java.io` y registra hilos y llamadas. */
    private class RecordingFs(
        private val delegate: RecordingsDirFs = JavaIoRecordingsDirFs,
    ) : RecordingsDirFs {
        val calls = mutableListOf<String>()
        val threads = mutableMapOf<String, Thread>()
        var mkdirsOverride: ((File) -> Boolean)? = null
        var createNewFileOverride: (suspend (File) -> Boolean)? = null
        var deleteOverride: ((File) -> Boolean)? = null
        var listFilesOverride: ((File) -> Array<File>?)? = null
        var existsOverride: ((File) -> Boolean)? = null

        private fun record(op: String) {
            synchronized(calls) { calls += op }
            synchronized(threads) { threads[op] = Thread.currentThread() }
        }

        override suspend fun exists(file: File): Boolean {
            record("exists")
            return existsOverride?.invoke(file) ?: delegate.exists(file)
        }

        override suspend fun isDirectory(file: File): Boolean {
            record("isDirectory")
            return delegate.isDirectory(file)
        }

        override suspend fun mkdirs(dir: File): Boolean {
            record("mkdirs")
            return mkdirsOverride?.invoke(dir) ?: delegate.mkdirs(dir)
        }

        override suspend fun listFiles(dir: File): Array<File>? {
            record("listFiles")
            return listFilesOverride?.invoke(dir) ?: delegate.listFiles(dir)
        }

        override suspend fun createNewFile(file: File): Boolean {
            record("createNewFile")
            return createNewFileOverride?.invoke(file)
                ?: delegate.createNewFile(file)
        }

        override suspend fun delete(file: File): Boolean {
            record("delete")
            return deleteOverride?.invoke(file) ?: delegate.delete(file)
        }

        override suspend fun lastModifiedMs(file: File): Long {
            record("lastModifiedMs")
            return delegate.lastModifiedMs(file)
        }

        fun sweepCalls(): Int =
            synchronized(calls) {
                calls.count { it == "listFiles" || it == "delete" }
            }
    }

    /** `RecordingsDirReplySink` falso que registra invocaciones e hilo. */
    private class FakeResult {
        sealed interface Call {
            data class Success(val path: String?) : Call
            data class Error(
                val code: String,
                val message: String?,
                val details: Map<*, *>?,
            ) : Call
        }

        val calls = mutableListOf<Call>()
        val thread = AtomicReference<Thread?>(null)
        val latch = CountDownLatch(1)

        val sink = object : RecordingsDirReplySink {
            override fun success(path: String) {
                synchronized(calls) {
                    calls += Call.Success(path)
                }
                thread.set(Thread.currentThread())
                latch.countDown()
            }

            override fun error(
                code: String,
                message: String?,
                details: Map<String, Any?>?,
            ) {
                synchronized(calls) {
                    calls += Call.Error(code, message, details)
                }
                thread.set(Thread.currentThread())
                latch.countDown()
            }
        }

        fun awaitReply(timeoutMs: Long = 10_000L): Boolean =
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun singleError(): Call.Error {
            assertEquals(1, synchronized(calls) { calls.size })
            return synchronized(calls) { calls.single() } as Call.Error
        }

        fun singleSuccess(): Call.Success {
            assertEquals(1, synchronized(calls) { calls.size })
            return synchronized(calls) { calls.single() } as Call.Success
        }
    }

    private lateinit var baseDir: File
    private lateinit var mainExecutor: java.util.concurrent.ExecutorService
    private lateinit var mainDispatcher: CoroutineDispatcher

    @Before
    fun setUp() {
        // `Transmiti2Logger` sin receptor llamaría a `android.util.Log`,
        // que lanza en las unitarias JVM puras.
        Transmiti2Logger.sink = { _, _ -> }
        baseDir = File(
            System.getProperty("java.io.tmpdir"),
            "recordings-dir-test-${System.nanoTime()}",
        )
        baseDir.mkdirs()
        mainExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "TestMain")
        }
        mainDispatcher = mainExecutor.asCoroutineDispatcher()
    }

    @After
    fun tearDown() {
        Transmiti2Logger.sink = null
        mainExecutor.shutdownNow()
        baseDir.deleteRecursively()
    }

    private fun recordingsDir(): File = File(baseDir, "recordings")

    private fun runFlow(
        scope: CoroutineScope,
        filesDir: File?,
        attempt: Int = 1,
        fs: RecordingsDirFs = JavaIoRecordingsDirFs,
        nowMs: () -> Long = System::currentTimeMillis,
        newUuid: () -> String = {
            java.util.UUID.randomUUID().toString()
        },
        timeoutMs: Long = GET_RECORDINGS_DIR_TIMEOUT_MS,
    ): FakeResult {
        val fake = FakeResult()
        val job = launchGetRecordingsDir(
            scope = scope,
            mainDispatcher = mainDispatcher,
            filesDir = filesDir,
            attempt = attempt,
            replySink = fake.sink,
            fs = fs,
            nowMs = nowMs,
            newUuid = newUuid,
            timeoutMs = timeoutMs,
        )
        runBlocking { job.join() }
        assertTrue("la respuesta no llegó", fake.awaitReply())
        return fake
    }

    // --- U16–U19 ---

    @Test
    fun `U16 camino feliz devuelve la ruta absoluta y el directorio existe`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            try {
                val fake = runFlow(scope, baseDir)
                val success = fake.singleSuccess()
                assertEquals(recordingsDir().absolutePath, success.path)
                assertTrue(recordingsDir().exists())
                assertTrue(recordingsDir().isDirectory)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `U17 mkdirs falla devuelve mkdirs_failed sin ruta y sin barrido`() =
        runBlocking {
            val fs = RecordingFs().apply {
                mkdirsOverride = { false }
            }
            val outcome = resolveRecordingsDir(baseDir, fs)
            val failed = outcome as RecordingsDirOutcome.Failed
            assertEquals("mkdirs_failed", failed.stage)
            assertNull(failed.reason)
            // Adición rev. 4: con V2 fallido, V4a no se ejecuta.
            assertEquals(0, fs.sweepCalls())
            assertFalse(recordingsDir().exists())
        }

    @Test
    fun `U17 mkdirs que lanza tambien es mkdirs_failed sin barrido`() =
        runBlocking {
            val fs = RecordingFs().apply {
                mkdirsOverride = { throw SecurityException("denegado") }
            }
            val outcome = resolveRecordingsDir(baseDir, fs)
            val failed = outcome as RecordingsDirOutcome.Failed
            assertEquals("mkdirs_failed", failed.stage)
            assertEquals("SecurityException", failed.exceptionClass)
            assertEquals(0, fs.sweepCalls())
        }

    @Test
    fun `U18 directorio no escribible devuelve not_writable sin ruta`() =
        runBlocking {
            recordingsDir().mkdirs()
            val fs = RecordingFs().apply {
                createNewFileOverride = {
                    throw java.io.IOException("Permission denied")
                }
            }
            val outcome = resolveRecordingsDir(baseDir, fs)
            val failed = outcome as RecordingsDirOutcome.Failed
            assertEquals("not_writable", failed.stage)
            assertEquals("create_failed", failed.reason)
            assertEquals("IOException", failed.exceptionClass)
        }

    @Test
    fun `U18 tres colisiones de nombre devuelven write_test_collision`() =
        runBlocking {
            recordingsDir().mkdirs()
            val fs = RecordingFs().apply {
                // `createNewFile()` devuelve `false` = el nombre ya
                // existe: 3 UUID seguidos colisionan.
                createNewFileOverride = { false }
            }
            val outcome = resolveRecordingsDir(baseDir, fs)
            val failed = outcome as RecordingsDirOutcome.Failed
            assertEquals("not_writable", failed.stage)
            assertEquals("write_test_collision", failed.reason)
        }

    @Test
    fun `U18a dos V4 concurrentes usan nombres distintos y ambas superan V4`() =
        runBlocking {
            recordingsDir().mkdirs()
            val createdNames = mutableListOf<String>()
            val fs = RecordingFs().apply {
                createNewFileOverride = { file ->
                    synchronized(createdNames) { createdNames += file.name }
                    JavaIoRecordingsDirFs.createNewFile(file)
                }
            }
            val uuidCounter = AtomicInteger(0)
            val uuids = { "uuid-${uuidCounter.incrementAndGet()}" }
            val first = async(Dispatchers.Default) {
                resolveRecordingsDir(baseDir, fs, newUuid = uuids)
            }
            val second = async(Dispatchers.Default) {
                resolveRecordingsDir(baseDir, fs, newUuid = uuids)
            }
            val outcomes = listOf(first.await(), second.await())
            assertTrue(outcomes.all { it is RecordingsDirOutcome.Ok })
            assertEquals(2, createdNames.size)
            assertNotEquals(createdNames[0], createdNames[1])
            assertTrue(createdNames.all { it.startsWith(".write-test-") })
        }

    @Test
    fun `U18b delete forzado a fallar no bloquea y marca writeTestDeleteFailed`() =
        runBlocking {
            recordingsDir().mkdirs()
            val fs = RecordingFs().apply {
                deleteOverride = { false }
            }
            val outcome = resolveRecordingsDir(baseDir, fs)
            val ok = outcome as RecordingsDirOutcome.Ok
            assertTrue(ok.writeTestDeleteFailed)
            assertNotNull(ok.writeTestFile)
            // El archivo quedó en su sitio: lo recoge el barrido
            // del siguiente intento.
            assertTrue(File(recordingsDir(), ok.writeTestFile!!).exists())
        }

    @Test
    fun `U18c barrido elimina solo el write-test antiguo`() = runBlocking {
        val dir = recordingsDir()
        dir.mkdirs()
        val old = File(dir, ".write-test-viejo.tmp").apply {
            writeText("")
            setLastModified(System.currentTimeMillis() - 61_000L)
        }
        val recent = File(dir, ".write-test-reciente.tmp").apply {
            writeText("")
        }
        val video = File(dir, "rec_20261121_120000_ab12_seg000.mp4").apply {
            writeText("mp4")
        }
        val sidecar = File(dir, "rec_20261121_120000_ab12.json").apply {
            writeText("{}")
        }
        val outcome = resolveRecordingsDir(baseDir)
        val ok = outcome as RecordingsDirOutcome.Ok
        assertEquals(1, ok.orphanWriteTestFiles)
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(video.exists())
        assertTrue(sidecar.exists())
    }

    @Test
    fun `U19 context nulo devuelve context_null sin barrido`() =
        runBlocking {
            val fs = RecordingFs()
            val outcome = resolveRecordingsDir(null, fs)
            val failed = outcome as RecordingsDirOutcome.Failed
            assertEquals("context_null", failed.stage)
            // Adición rev. 4: con V1 fallido, V4a no se ejecuta.
            assertEquals(0, fs.sweepCalls())
            assertTrue(fs.calls.isEmpty())
        }

    // --- U54–U57 ---

    @Test
    fun `U54 disco fuera del hilo principal`() = runBlocking {
        val testThread = Thread.currentThread()
        val fs = RecordingFs()
        val scope = CoroutineScope(SupervisorJob())
        try {
            runFlow(scope, baseDir, fs = fs)
            val diskOps = listOf(
                "mkdirs",
                "createNewFile",
                "delete",
                "listFiles",
            )
            assertTrue(fs.calls.contains("mkdirs"))
            // Orden (rev. 4 §2.5): el barrido corre después de mkdirs().
            val calls = synchronized(fs.calls) { fs.calls.toList() }
            assertTrue(calls.indexOf("mkdirs") < calls.indexOf("listFiles"))
            for (op in diskOps) {
                val thread = fs.threads[op] ?: continue
                assertNotEquals(
                    "operación $op en el hilo del test",
                    testThread,
                    thread,
                )
                assertNotEquals(
                    "operación $op en el hilo main",
                    "main",
                    thread.name,
                )
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `U55 respuesta en el hilo principal`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val fake = runFlow(scope, baseDir)
            fake.singleSuccess()
            val replyThread = fake.thread.get()
            assertNotNull(replyThread)
            assertTrue(
                "respuesta en ${replyThread!!.name}, no en TestMain",
                replyThread.name.startsWith("TestMain"),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `U56 respuesta exactamente una vez con FS bloqueante`() {
        // Fake con Thread.sleep (bloqueante, fiel a una syscall):
        // bloquea 3 s en createNewFile y luego completa. La
        // invocación llega cuando el bloqueo termina, no a los 2 s,
        // y eso es correcto (revisión 4 §3.9).
        val fs = RecordingFs().apply {
            createNewFileOverride = { file ->
                Thread.sleep(3_000L)
                JavaIoRecordingsDirFs.createNewFile(file)
            }
        }
        val startedAt = System.currentTimeMillis()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val fake = runFlow(scope, baseDir, fs = fs)
            val elapsed = System.currentTimeMillis() - startedAt
            val error = fake.singleError()
            assertEquals(
                StreamContract.RECORDINGS_CHANNEL_TIMEOUT,
                error.code,
            )
            assertEquals("getRecordingsDir", error.details?.get("stage"))
            assertTrue("respuesta a los ${elapsed}ms, antes del bloqueo", elapsed >= 2_500L)
            assertTrue("respuesta a los ${elapsed}ms, demasiado tarde", elapsed < 5_000L)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `U57 destruccion antes de responder no invoca el Result`() {
        // Fake con Thread.sleep; la cancelación del scope se emite
        // durante el bloqueo (antes del timeout nativo de 2 s).
        val fs = RecordingFs().apply {
            createNewFileOverride = { file ->
                Thread.sleep(1_000L)
                JavaIoRecordingsDirFs.createNewFile(file)
            }
        }
        val scope = CoroutineScope(SupervisorJob())
        val fake = FakeResult()
        val job = launchGetRecordingsDir(
            scope = scope,
            mainDispatcher = mainDispatcher,
            filesDir = baseDir,
            attempt = 1,
            replySink = fake.sink,
            fs = fs,
        )
        runBlocking {
            delay(100L)
            scope.cancel()
            job.join()
        }
        // 0 invocaciones al Result tras la cancelación, gracias a
        // la guarda scope.isActive en replyOnce (revisión 4 §3.9).
        assertTrue(fake.calls.isEmpty())
        // Ninguna excepción no controlada: el Job termina normal.
        runBlocking { job.join() }
    }

    // --- U58a / U58b (revisión 4 §3.7; sustituyen a la U58 original) ---

    @Test
    fun `U58a timeout nativo sobre espera suspendible responde a los 2 s`() {
        // Fake con delay(5 s) (suspendible, no bloqueante) entre V2
        // y V4b; withTimeout(2 s) real.
        val fs = RecordingFs().apply {
            createNewFileOverride = { _ ->
                delay(5_000L)
                true
            }
        }
        val scope = CoroutineScope(SupervisorJob())
        try {
            val startedAt = System.currentTimeMillis()
            val fake = runFlow(scope, baseDir, fs = fs, attempt = 3)
            val elapsed = System.currentTimeMillis() - startedAt
            val error = fake.singleError()
            assertEquals(
                StreamContract.RECORDINGS_CHANNEL_TIMEOUT,
                error.code,
            )
            assertEquals("getRecordingsDir", error.details?.get("stage"))
            assertEquals(3, error.details?.get("attempt"))
            assertEquals(
                "TimeoutCancellationException",
                error.details?.get("exceptionClass"),
            )
            assertTrue(
                "respuesta a los ${elapsed}ms, fuera de 2 s ± margen",
                elapsed in 1_500L..3_000L,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `U58b timeout nativo sobre syscall bloqueante responde al terminar el bloqueo`() {
        // Fake con Thread.sleep(5 s) (bloqueante, fiel a una
        // syscall) y luego éxito; withTimeout(2 s) real. NO se exige
        // respuesta a los 2 s: el error llega solo cuando el bloqueo
        // termina (~5 s), y eso es el comportamiento correcto.
        val fs = RecordingFs().apply {
            createNewFileOverride = { file ->
                Thread.sleep(5_000L)
                JavaIoRecordingsDirFs.createNewFile(file)
            }
        }
        val scope = CoroutineScope(SupervisorJob())
        try {
            val startedAt = System.currentTimeMillis()
            val fake = runFlow(scope, baseDir, fs = fs, attempt = 1)
            val elapsed = System.currentTimeMillis() - startedAt
            val error = fake.singleError()
            assertEquals(
                StreamContract.RECORDINGS_CHANNEL_TIMEOUT,
                error.code,
            )
            assertEquals("getRecordingsDir", error.details?.get("stage"))
            assertTrue(
                "respuesta a los ${elapsed}ms, no al terminar el bloqueo",
                elapsed in 4_500L..6_500L,
            )
            // La corrutina termina y el hilo IO se libera: una
            // operación posterior en IO completa con normalidad.
            val second = runBlocking {
                resolveRecordingsDir(baseDir)
            }
            assertTrue(second is RecordingsDirOutcome.Ok)
            // Ningún éxito falso: no se registró ruta.
            assertTrue(fake.calls.none { it is FakeResult.Call.Success })
        } finally {
            scope.cancel()
        }
    }

    // --- U60 / U61 ---

    @Test
    fun `U60 timeout sin huerfano permanente, el segundo intento barre`() {
        // Primer intento: crea el .write-test y bloquea con
        // Thread.sleep en el delete de V4c hasta el timeout; el
        // archivo queda huérfano (V4c no pudo borrarlo).
        val sleptOnce = AtomicBoolean(false)
        val fs = RecordingFs().apply {
            deleteOverride = { _ ->
                if (sleptOnce.compareAndSet(false, true)) {
                    Thread.sleep(5_000L)
                }
                false
            }
        }
        val scope = CoroutineScope(SupervisorJob())
        try {
            val first = runFlow(scope, baseDir, fs = fs)
            assertEquals(
                StreamContract.RECORDINGS_CHANNEL_TIMEOUT,
                first.singleError().code,
            )
            val orphans = recordingsDir().listFiles()
                ?.filter { it.name.startsWith(".write-test-") }
                ?: emptyList()
            assertEquals(1, orphans.size)
            // Segundo intento con el reloj adelantado > 60 s: barre
            // el huérfano y completa V4.
            val advancedClock = {
                System.currentTimeMillis() + 61_000L
            }
            val second = runBlocking {
                resolveRecordingsDir(baseDir, nowMs = advancedClock)
            }
            val ok = second as RecordingsDirOutcome.Ok
            assertEquals(1, ok.orphanWriteTestFiles)
            assertTrue(
                recordingsDir().listFiles()
                    ?.none { it.name.startsWith(".write-test-") } == true,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `U61 barrido en IO y selectivo, despues de mkdirs e isDirectory`() {
        val dir = recordingsDir()
        dir.mkdirs()
        val old = File(dir, ".write-test-viejo.tmp").apply {
            writeText("")
            setLastModified(System.currentTimeMillis() - 61_000L)
        }
        val recent = File(dir, ".write-test-reciente.tmp").apply {
            writeText("")
        }
        val video = File(dir, "rec_20261121_120000_ab12_seg000.mp4").apply {
            writeText("mp4")
        }
        val sidecar = File(dir, "rec_20261121_120000_ab12.json").apply {
            writeText("{}")
        }
        val testThread = Thread.currentThread()
        val fs = RecordingFs()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val fake = runFlow(scope, baseDir, fs = fs)
            fake.singleSuccess()
            assertFalse(old.exists())
            assertTrue(recent.exists())
            assertTrue(video.exists())
            assertTrue(sidecar.exists())
            // El barrido corrió en IO, nunca en el hilo del test.
            val sweepThread = fs.threads["listFiles"]
            assertNotNull(sweepThread)
            assertNotEquals(testThread, sweepThread)
            assertNotEquals("main", sweepThread!!.name)
            // Aserción opcional de orden (rev. 4 §2.5): el barrido se
            // invocó después de que isDirectory pasara (el directorio
            // ya existía en este test, así que mkdirs() no corre; el
            // orden mkdirs() → barrido se fija en U54, con directorio
            // nuevo).
            val calls = synchronized(fs.calls) { fs.calls.toList() }
            assertTrue(
                calls.indexOf("isDirectory") < calls.indexOf("listFiles"),
            )
        } finally {
            scope.cancel()
        }
    }
}
