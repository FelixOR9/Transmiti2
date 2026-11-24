package com.transmiti2.transmiti2.streaming.test

import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import com.transmiti2.transmiti2.streaming.recording.ActiveRecordingRegistry
import com.transmiti2.transmiti2.streaming.recording.RecordingFileStore
import com.transmiti2.transmiti2.streaming.recording.RecordingSession
import com.transmiti2.transmiti2.streaming.recording.SegmentStatus
import com.transmiti2.transmiti2.streaming.recording.SegmentVerification
import com.transmiti2.transmiti2.streaming.recording.SegmentVerifier
import com.transmiti2.transmiti2.streaming.recording.VerificationChecks
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Pausa y reanudación de la reconciliación (diseño base §6, tests nº
 * 4–8; FASE 2, grupo 2-c). Sesión real sobre un directorio temporal,
 * verificador falso y ganchos reales: sin cámara ni MediaCodec.
 *
 * Los tests 4 y 5 corren la pasada con la sesión real; el 6 cancela la
 * pasada pausada; el 7 y el 8 prueban la coordinación con el registro
 * (claim activo / claim nuevo durante la pausa). La parte de puerta
 * (que el gate llegue a DONE/FAILED) vive en `ReconciliationGateTest`.
 */
class ReconciliationPauseTest {

    /** Reloj falso (mismo patrón que `PauseConsumptionTest`). */
    private class FakeTestClock(
        var monotonicValue: Long = 100_000L,
        var wallValue: Long = 1_792_000_000_000L,
    ) : TestClock {
        override fun monotonicMs(): Long = monotonicValue
        override fun wallMs(): Long = wallValue
    }

    /** Intención en memoria (mismo patrón que `PauseConsumptionTest`). */
    private class FakeIntentAccess : ArmedPauseFileAccess {
        var content: String? = null
        override fun read(): String? = content
        override fun write(text: String) {
            content = text
        }

        override fun delete(): Boolean {
            content = null
            return true
        }
    }

    /** Verificador falso (mínimo de `RecordingReconciliationTest`). */
    private class FakeVerifier(
        var result: SegmentVerification,
    ) : SegmentVerifier {
        var verifyCalls = 0
        val verifiedFiles = mutableListOf<String>()

        override suspend fun verify(
            file: File,
            closedCleanly: Boolean,
            expectedWidth: Int?,
            expectedHeight: Int?,
            expectedFps: Int?,
            expectedAudio: Boolean?,
            expectedSampleRate: Int?,
            expectedChannels: Int?,
        ): SegmentVerification {
            verifyCalls++
            synchronized(verifiedFiles) { verifiedFiles += file.name }
            return result
        }
    }

    private fun readyVerification() = SegmentVerification(
        status = SegmentStatus.READY,
        checks = VerificationChecks(
            extractorOpens = true,
            hasAvcTrack = true,
            width = 1280,
            height = 720,
            sampleCount = 100,
            keyframeCount = 5,
            firstSampleKeyframe = true,
            ptsMonotonic = true,
            durationMs = 3000L,
            fpsMeasured = 30.0,
            ioPhaseMs = 3000L,
            profileIdc = 66,
            decodeOk = true,
            decodedFrames = 100,
            exoplayerReady = true,
            exoplayerDurationMs = 3000L,
        ),
        reason = null,
    )

    private inner class Harness(
        val pauseTimeoutMs: Long = 500L,
    ) {
        val clock = FakeTestClock()
        val intentAccess = FakeIntentAccess()
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "pause-test-${System.nanoTime()}",
        ).apply { mkdirs() }
        val fileStore = RecordingFileStore(dir)
        val verifier = FakeVerifier(readyVerification())
        val registry = ActiveRecordingRegistry()
        val instrumentation = TestInstrumentation(
            enabled = { true },
            clock = clock,
        )
        val syncPoints = TestSyncPoints(
            enabled = { true },
            instrumentation = instrumentation,
            clock = clock,
            intentAccess = intentAccess,
            pauseTimeoutMs = pauseTimeoutMs,
        )
        val hooks: TestHooks = DefaultTestHooks(instrumentation, syncPoints)
        val scope = CoroutineScope(Dispatchers.Default)

        fun session(): RecordingSession = RecordingSession(
            scope = scope,
            fileStore = fileStore,
            verifier = verifier,
            clockMs = { clock.wallMs() },
            registry = registry,
            testHooks = hooks,
        )

        fun startTestSession(): TestSession {
            val s = runBlocking { instrumentation.createSession() }
            instrumentation.startSession(s)
            return s
        }

        fun arm(point: PausePoint) {
            runBlocking { syncPoints.arm(point) }
        }

        /** Escribe segmentos finales (sin sidecar: la pasada verifica, C4). */
        fun writeGroup(id: String, vararg indices: Int) {
            for (i in indices) {
                File(dir, fileStore.segmentFileName(id, i))
                    .writeText("seg-$id-$i")
            }
        }

        /** Contenido de todos los archivos de un grupo, por nombre. */
        fun groupBytes(id: String): Map<String, ByteArray> =
            dir.listFiles()
                ?.filter { it.name.startsWith(id) }
                ?.sortedBy { it.name }
                ?.associate { it.name to it.readBytes() }
                ?: emptyMap()

        fun events() = instrumentation.events()
    }

    @Before
    fun setUp() {
        Transmiti2Logger.sink = { _, _ -> }
    }

    @After
    fun tearDown() {
        Transmiti2Logger.sink = null
    }

    private suspend fun awaitPaused(h: Harness, point: PausePoint) {
        withTimeout(5_000) {
            while (!h.syncPoints.isPaused(point)) delay(10)
        }
    }

    @Test(timeout = 15_000)
    fun `pauseAtP1ThenResumeCompletes`() = runBlocking {
        val h = Harness()
        h.writeGroup("rec_20261123_150000_aaaa", 0, 1)
        h.writeGroup("rec_20261123_151000_bbbb", 0)
        h.startTestSession()
        h.arm(PausePoint.P1)
        val session = h.session()
        val job = async { session.reconcileRecordings() }
        awaitPaused(h, PausePoint.P1)

        // Durante la pausa: ningún grupo procesado aún.
        assertEquals(0, h.verifier.verifyCalls)
        assertTrue(
            h.events().none { it.event == TestEventType.GROUP_RESERVED },
        )
        // El coordinationMutex está libre: un claim nuevo se concede
        // de inmediato (si la pausa lo retuviera, esto suspendería
        // hasta el timeout). El verificationMutex no se puede tomar
        // directamente (privado): su libertad se establece por
        // construcción (P1 está fuera de `reconcileSegment`, el único
        // lugar que lo toma) y por la evidencia de que ninguna
        // verificación está en curso (`verifyCalls == 0`).
        val probeId = "rec_20261123_152000_probe"
        val probeToken = withTimeout(1_000) {
            h.registry.tryClaimRecording(probeId)
        }
        assertNotNull("El claim durante la pausa debe concederse", probeToken)
        h.registry.releaseRecording(probeId, probeToken!!)

        assertTrue(h.syncPoints.resume(PausePoint.P1))
        val summary = job.await()
        assertEquals("run-1", summary.runId)
        assertEquals(2, summary.groupsReconciled)
        assertTrue(summary.groupsSkippedActive.isEmpty())

        val events = h.events()
        val entered = events.indexOfFirst { it.event == TestEventType.PAUSE_ENTERED }
        val released = events.indexOfFirst { it.event == TestEventType.PAUSE_RELEASED }
        val finished = events.indexOfFirst {
            it.event == TestEventType.RECONCILIATION_FINISHED
        }
        assertTrue("Falta PAUSE_ENTERED", entered >= 0)
        assertTrue("PAUSE_RELEASED debe seguir a PAUSE_ENTERED", released > entered)
        assertTrue(
            "RECONCILIATION_FINISHED debe seguir a PAUSE_RELEASED",
            finished > released,
        )
        assertEquals("run-1", events[finished].detail["runId"])
    }

    @Test(timeout = 15_000)
    fun `pauseWithoutResumeTimesOutAndCompletes`() = runBlocking {
        val h = Harness(pauseTimeoutMs = 500L)
        h.writeGroup("rec_20261123_150000_aaaa", 0)
        h.writeGroup("rec_20261123_151000_bbbb", 0)
        h.startTestSession()
        h.arm(PausePoint.P1)
        val session = h.session()
        val job = async { session.reconcileRecordings() }
        awaitPaused(h, PausePoint.P1)
        // No se reanuda: el timeout libera solo (la puerta que llega
        // a DONE se verifica a nivel de motor en `ReconciliationGateTest`).
        val summary = withTimeout(10_000) { job.await() }
        assertEquals(2, summary.groupsReconciled)
        val events = h.events()
        assertTrue(
            "Falta PAUSE_TIMEOUT",
            events.any { it.event == TestEventType.PAUSE_TIMEOUT },
        )
        assertTrue(
            "Falta RECONCILIATION_FINISHED tras el timeout",
            events.any { it.event == TestEventType.RECONCILIATION_FINISHED },
        )
    }

    @Test(timeout = 15_000)
    fun `exceptionDuringPausedRunReleasesReservation`() = runBlocking {
        val h = Harness(pauseTimeoutMs = 10_000L)
        h.writeGroup("rec_20261123_150000_aaaa", 0)
        h.writeGroup("rec_20261123_151000_bbbb", 0)
        h.startTestSession()
        h.arm(PausePoint.P1)
        val session = h.session()
        val job = async { session.reconcileRecordings() }
        awaitPaused(h, PausePoint.P1)
        job.cancel()
        try {
            job.await()
            fail("Se esperaba CancellationException")
        } catch (_: CancellationException) {
            // Esperado: la cancelación propaga, no se traga.
        }
        // Sin reservas huérfanas: en P1 no había ninguna tomada y la
        // cancelación no dejó ninguna.
        val snapshot = h.registry.snapshot()
        assertTrue(snapshot.reservedGroups.isEmpty())
        assertTrue(snapshot.activeClaims.isEmpty())
        // La cancelación estando suspendida queda registrada.
        val cancelled = h.events().firstOrNull {
            it.event == TestEventType.PAUSE_CANCELLED
        }
        assertNotNull("Falta PAUSE_CANCELLED", cancelled)
        assertEquals(true, cancelled!!.wasSuspended)
        // Una reconciliación posterior puede correr (misma sesión,
        // nuevo runId, sin pausa armada).
        val summary2 = session.reconcileRecordings()
        assertEquals("run-2", summary2.runId)
        assertEquals(2, summary2.groupsReconciled)
    }

    @Test(timeout = 15_000)
    fun `activeClaimGroupIsSkippedAndUntouched`() = runBlocking {
        val h = Harness()
        val activeId = "rec_20261123_150000_aaaa"
        val otherId = "rec_20261123_151000_bbbb"
        h.writeGroup(activeId, 0, 1)
        h.writeGroup(otherId, 0)
        val beforeActive = h.groupBytes(activeId).mapValues { it.value.toList() }
        h.startTestSession()
        // Claim activo sobre el grupo A (simula una grabación en curso).
        val token = h.registry.tryClaimRecording(activeId)
        assertNotNull("El claim del grupo activo debe concederse", token)

        val summary = h.session().reconcileRecordings()
        assertTrue(summary.groupsSkippedActive.contains(activeId))
        assertEquals(1, summary.groupsReconciled)
        assertFalse(summary.segments.any { it.recordingId == activeId })

        val events = h.events()
        assertTrue(
            "Falta GROUP_SKIPPED_ACTIVE para el grupo activo",
            events.any {
                it.event == TestEventType.GROUP_SKIPPED_ACTIVE &&
                    it.detail["groupId"] == activeId
            },
        )
        assertTrue(
            "El grupo activo no debe reservarse",
            events.none {
                it.event == TestEventType.GROUP_RESERVED &&
                    it.detail["groupId"] == activeId
            },
        )
        // Guarda: si algún día la sesión emite eventos de segmento,
        // este test caza la verificación/renombre del grupo activo.
        // Hoy la sesión no los emite (solo grupo + resumen), así que
        // la protección real la demuestran las tres afirmaciones
        // siguientes: archivos intactos, claim vivo y sin segmentos.
        assertTrue(
            events.none {
                it.event == TestEventType.SEGMENT_VERIFIED &&
                    it.recordingId == activeId
            },
        )
        assertTrue(
            events.none {
                it.event == TestEventType.SEGMENT_RENAMED &&
                    it.recordingId == activeId
            },
        )
        assertEquals(
            "Los archivos del grupo activo no deben cambiar",
            beforeActive,
            h.groupBytes(activeId).mapValues { it.value.toList() },
        )
        assertTrue(
            "La reconciliación no debe tocar el claim activo",
            h.registry.snapshot().activeClaims.containsKey(activeId),
        )
        h.registry.releaseRecording(activeId, token!!)
        assertTrue(h.registry.snapshot().activeClaims.isEmpty())
    }

    @Test(timeout = 15_000)
    fun `newClaimDuringPausedRunIsGrantedAndGroupUntouched`() = runBlocking {
        val h = Harness()
        h.writeGroup("rec_20261123_150000_aaaa", 0)
        h.writeGroup("rec_20261123_151000_bbbb", 0)
        h.startTestSession()
        h.arm(PausePoint.P1)
        val session = h.session()
        val job = async { session.reconcileRecordings() }
        awaitPaused(h, PausePoint.P1)

        // Grabación nueva durante la pausa: el claim se concede (el
        // coordinationMutex está libre; P1 no retiene ninguna
        // reserva). Simula el `start()` de F7b sin cámara.
        val newId = "rec_20261123_153000_cccc"
        val token = withTimeout(1_000) { h.registry.tryClaimRecording(newId) }
        assertNotNull(
            "El claim de la grabación nueva debe concederse durante la pausa",
            token,
        )
        assertTrue(h.syncPoints.isPaused(PausePoint.P1))

        h.syncPoints.resume(PausePoint.P1)
        val summary = job.await()
        // El id nuevo no estaba en el inventario: no aparece en la
        // pasada (ni procesado ni saltado).
        assertFalse(summary.segments.any { it.recordingId == newId })
        assertFalse(summary.groupsSkippedActive.contains(newId))
        assertEquals(0, summary.conflicts)
        // Al liberar el claim (simula la parada), no quedan reservas
        // huérfanas ni liberaciones ajenas rechazadas.
        h.registry.releaseRecording(newId, token!!)
        val snapshot = h.registry.snapshot()
        assertTrue(snapshot.activeClaims.isEmpty())
        assertTrue(snapshot.reservedGroups.isEmpty())
        assertEquals(0L, snapshot.foreignReleaseRejected)
        // Y el grupo nuevo nunca se verificó ni renombró en la pasada.
        assertTrue(
            h.events().none {
                (it.event == TestEventType.SEGMENT_VERIFIED ||
                    it.event == TestEventType.SEGMENT_RENAMED) &&
                    it.recordingId == newId
            },
        )
        assertNull(
            h.events().firstOrNull {
                it.event == TestEventType.GROUP_RESERVED &&
                    it.detail["groupId"] == newId
            },
        )
    }
}
