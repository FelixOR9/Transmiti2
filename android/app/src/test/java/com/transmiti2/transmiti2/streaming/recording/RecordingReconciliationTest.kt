package com.transmiti2.transmiti2.streaming.recording

import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reconciliación, núcleo 0-d1 (subetapa 0, grupo 0-d1; plan §3 y §5):
 * casos C1–C6, confianza E1–E6, `PENDING_VERIFICATION`, idempotencia
 * e integración I1–I5. Solo disco + verificador falso: no arranca
 * ninguna grabación (el flujo en vivo —U3/U4/U6/U35/U36/I7— vive en
 * `RecordingSessionTest`, que tiene los fakes de encoder).
 */
class RecordingReconciliationTest {

    private class FakeVerifier(
        var result: SegmentVerification,
    ) : SegmentVerifier {
        var verifyCalls = 0
        val verifiedFiles = mutableListOf<String>()
        var throwOnVerify: Exception? = null
        var verifyDelayMs: Long = 0L
        /** Resultado por archivo (si no es nulo, manda sobre `result`). */
        var resultByFile: ((File) -> SegmentVerification)? = null
        val currentConcurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

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
            val current = currentConcurrent.incrementAndGet()
            maxConcurrent.updateAndGet { max -> maxOf(max, current) }
            try {
                if (verifyDelayMs > 0) delay(verifyDelayMs)
                throwOnVerify?.let { throw it }
                return resultByFile?.invoke(file) ?: result
            } finally {
                currentConcurrent.decrementAndGet()
            }
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

    private fun partialVerification() = readyVerification().copy(
        status = SegmentStatus.PARTIAL,
        reason = null,
    )

    private fun corruptVerification() = readyVerification().copy(
        status = SegmentStatus.CORRUPT,
        reason = "ilegible",
    )

    private lateinit var dir: File
    private lateinit var fileStore: RecordingFileStore
    private lateinit var fakeVerifier: FakeVerifier
    private lateinit var registry: ActiveRecordingRegistry
    private var fixedClockMs: Long = 1_792_000_000_000L

    private fun buildSession(): RecordingSession {
        return RecordingSession(
            scope = CoroutineScope(Dispatchers.Unconfined),
            fileStore = fileStore,
            verifier = fakeVerifier,
            clockMs = { fixedClockMs },
            registry = registry,
        )
    }

    /** Escribe un sidecar con una entrada por segmento dado. */
    private fun writeSidecar(
        id: String,
        segments: List<SegmentMetadata>,
    ) {
        fileStore.writeSidecar(
            RecordingMetadata(
                recordingId = id,
                matchId = "match-1",
                width = 1280,
                height = 720,
                fps = 30,
                bitrateBps = 2_500_000,
                rotationDegrees = 0,
                encoderName = "c2.qti.avc.encoder",
                profile = 1,
                bitrateMode = 2,
                segments = segments,
            ),
        )
    }

    private fun segmentEntry(
        id: String,
        index: Int,
        staging: Boolean,
        status: String? = null,
        durationMs: Long? = null,
        sizeBytes: Long? = null,
        verifiedAtMs: Long? = null,
        verifierVersion: Int? = null,
        verificationReason: String? = null,
        fpsMeasured: Double? = null,
        audioExpected: Boolean = false,
        hasAudio: Boolean = false,
        audioSampleCount: Int? = null,
    ): SegmentMetadata {
        val name = if (staging) {
            fileStore.segmentStagingFileName(id, index)
        } else {
            fileStore.segmentFileName(id, index)
        }
        return SegmentMetadata(
            index = index,
            fileName = name,
            firstEncoderPtsUs = null,
            startWallMs = 1_792_000_000_000L,
            status = status,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            audioExpected = audioExpected,
            hasAudio = hasAudio,
            audioSampleCount = audioSampleCount,
            verifiedAtMs = verifiedAtMs,
            verifierVersion = verifierVersion,
            verificationReason = verificationReason,
            fpsMeasured = fpsMeasured,
        )
    }

    @Before
    fun setUp() {
        Transmiti2Logger.sink = { _, _ -> }
        dir = File(
            System.getProperty("java.io.tmpdir"),
            "reconciliation-test-${System.nanoTime()}",
        )
        dir.mkdirs()
        fileStore = RecordingFileStore(dir)
        fakeVerifier = FakeVerifier(readyVerification())
        registry = ActiveRecordingRegistry()
        fixedClockMs = 1_792_000_000_000L
    }

    @After
    fun tearDown() {
        Transmiti2Logger.sink = null
        dir.deleteRecursively()
    }

    @Test
    fun `U7 tmp utilizable se renombra a mp4 y su sidecar se actualiza`() =
        runBlocking {
            val session = buildSession()
            val staging = fileStore.segmentStagingFile("rec_1", 0)
            staging.writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val summary = session.reconcileRecordings()
            assertEquals(1, summary.segmentsRenamed)
            assertEquals(1, summary.segmentsVerified)
            val final = fileStore.segmentFile("rec_1", 0)
            assertTrue(final.exists())
            assertFalse(staging.exists())
            val sidecar = fileStore.readSidecar("rec_1")
            assertNotNull(sidecar)
            assertEquals(1, sidecar!!.segments.size)
            assertEquals("rec_1_seg000.mp4", sidecar.segments[0].fileName)
            assertEquals("partial", sidecar.segments[0].status)
            assertEquals(1, sidecar.segments[0].verifierVersion)
            assertNotNull(sidecar.segments[0].verifiedAtMs)
            assertEquals(30.0, sidecar.segments[0].fpsMeasured)
        }

    @Test
    fun `U8 tmp corrupto se deja como tmp marcado corrupt sin borrarlo`() =
        runBlocking {
            val session = buildSession()
            val staging = fileStore.segmentStagingFile("rec_1", 0)
            staging.writeText("01234567890")
            fakeVerifier.result = corruptVerification()
            val summary = session.reconcileRecordings()
            assertEquals(0, summary.segmentsRenamed)
            assertEquals(1, summary.segments.size)
            assertEquals(SegmentStatus.CORRUPT, summary.segments[0].status)
            assertEquals(ReconciliationCase.C4_NO_SIDECAR, summary.segments[0].case)
            assertTrue(staging.exists())
            assertFalse(fileStore.segmentFile("rec_1", 0).exists())
            val sidecar = fileStore.readSidecar("rec_1")
            assertEquals("corrupt", sidecar!!.segments[0].status)
        }

    @Test
    fun `U9 mp4 cerrado sin registro se verifica y se anade su registro`() =
        runBlocking {
            val session = buildSession()
            val final = fileStore.segmentFile("rec_1", 0)
            final.writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val summary = session.reconcileRecordings()
            assertEquals(1, summary.segmentsVerified)
            assertEquals(0, summary.segmentsRenamed)
            assertEquals(SegmentStatus.READY, summary.segments[0].status)
            assertEquals(ReconciliationCase.C4_NO_SIDECAR, summary.segments[0].case)
            val sidecar = fileStore.readSidecar("rec_1")
            assertNotNull(sidecar)
            assertEquals(1, sidecar!!.segments.size)
            assertEquals("rec_1_seg000.mp4", sidecar.segments[0].fileName)
            assertEquals("ready", sidecar.segments[0].status)
            assertEquals(1, sidecar.segments[0].verifierVersion)
            assertNotNull(sidecar.segments[0].verifiedAtMs)
        }

    @Test
    fun `U12 reconciliacion idempotente dos ejecuciones mismo estado final`() =
        runBlocking {
            val session = buildSession()
            fileStore.segmentStagingFile("rec_1", 0).writeText("01234567890")
            fileStore.segmentFile("rec_1", 1).writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val first = session.reconcileRecordings()
            assertEquals(2, first.segmentsVerified)
            val filesAfterFirst = dir.listFiles()!!.map { it.name }.sorted()
            val sidecarAfterFirst = fileStore.sidecarFile("rec_1").readText()
            val second = session.reconcileRecordings()
            assertEquals(0, second.segmentsVerified)
            assertEquals(2, second.segmentsTrusted)
            assertEquals(filesAfterFirst, dir.listFiles()!!.map { it.name }.sorted())
            assertEquals(sidecarAfterFirst, fileStore.sidecarFile("rec_1").readText())
            assertEquals(2, fakeVerifier.verifyCalls)
        }

    @Test
    fun `U13 grabacion activa se salta y los demas grupos se reconcilian`() =
        runBlocking {
            val session = buildSession()
            registry.tryClaimRecording("rec_1")
            fileStore.segmentStagingFile("rec_1", 0).writeText("a")
            fileStore.segmentStagingFile("rec_2", 0).writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val summary = session.reconcileRecordings()
            assertEquals(listOf("rec_1"), summary.groupsSkippedActive)
            assertEquals(1, summary.groupsReconciled)
            assertEquals(1, summary.segments.size)
            assertEquals("rec_2", summary.segments[0].recordingId)
            // El grupo activo no se toca: sigue en staging, sin verificar.
            assertTrue(fileStore.segmentStagingFile("rec_1", 0).exists())
            assertFalse(fileStore.segmentFile("rec_1", 0).exists())
            assertEquals(1, fakeVerifier.verifyCalls)
            // El nombre registrado es el de la verificación (staging:
            // el renombre ocurre después de verificar).
            assertEquals(listOf("rec_2_seg000.tmp.mp4"), fakeVerifier.verifiedFiles)
            assertNull(fileStore.readSidecar("rec_1"))
        }

    @Test
    fun `U25 E1-E6 completo no ejecuta el verificador y clasifica por sidecar`() =
        runBlocking {
            val session = buildSession()
            val final = fileStore.segmentFile("rec_1", 0)
            final.writeText("01234567890")
            writeSidecar(
                "rec_1",
                listOf(
                    segmentEntry(
                        "rec_1", 0, staging = false,
                        status = "ready",
                        durationMs = 3000L,
                        sizeBytes = 11L,
                        verifiedAtMs = 1_792_000_001_000L,
                        verifierVersion = 1,
                        fpsMeasured = 30.0,
                    ),
                ),
            )
            val summary = session.reconcileRecordings()
            assertEquals(0, fakeVerifier.verifyCalls)
            assertEquals(1, summary.segmentsTrusted)
            assertEquals(0, summary.segmentsVerified)
            assertEquals(SegmentStatus.READY, summary.segments[0].status)
            assertEquals(ReconciliationCase.C1_TRUSTED_FINAL, summary.segments[0].case)
            assertTrue(summary.segments[0].trusted)
        }

    @Test
    fun `U26 tamano coincidente por si solo no basta se verifica`() =
        runBlocking {
            val session = buildSession()
            val final = fileStore.segmentFile("rec_1", 0)
            final.writeText("01234567890")
            writeSidecar(
                "rec_1",
                listOf(
                    segmentEntry(
                        "rec_1", 0, staging = false,
                        status = null,
                        durationMs = 3000L,
                        sizeBytes = 11L,
                        verifiedAtMs = 1_792_000_001_000L,
                        verifierVersion = 1,
                    ),
                ),
            )
            val summary = session.reconcileRecordings()
            assertEquals(1, fakeVerifier.verifyCalls)
            assertEquals(1, summary.segmentsVerified)
            assertEquals(SegmentStatus.READY, summary.segments[0].status)
        }

    @Test
    fun `U27 version del verificador anterior fuerza re-verificacion`() =
        runBlocking {
            val session = buildSession()
            val final = fileStore.segmentFile("rec_1", 0)
            final.writeText("01234567890")
            writeSidecar(
                "rec_1",
                listOf(
                    segmentEntry(
                        "rec_1", 0, staging = false,
                        status = "ready",
                        durationMs = 3000L,
                        sizeBytes = 11L,
                        verifiedAtMs = 1_792_000_001_000L,
                        verifierVersion = 0,
                    ),
                ),
            )
            val summary = session.reconcileRecordings()
            assertEquals(1, fakeVerifier.verifyCalls)
            assertEquals(ReconciliationCase.C5_CONTRADICTORY, summary.segments[0].case)
            val sidecar = fileStore.readSidecar("rec_1")
            assertEquals(1, sidecar!!.segments[0].verifierVersion)
        }

    @Test
    fun `U28 fileName staging contra mp4 real se verifica y corrige el sidecar`() =
        runBlocking {
            val session = buildSession()
            val final = fileStore.segmentFile("rec_1", 0)
            final.writeText("01234567890")
            writeSidecar(
                "rec_1",
                listOf(
                    segmentEntry(
                        "rec_1", 0, staging = true,
                        status = "ready",
                        durationMs = 3000L,
                        sizeBytes = 11L,
                        verifiedAtMs = 1_792_000_001_000L,
                        verifierVersion = 1,
                    ),
                ),
            )
            val summary = session.reconcileRecordings()
            assertEquals(1, fakeVerifier.verifyCalls)
            assertEquals(ReconciliationCase.C5_CONTRADICTORY, summary.segments[0].case)
            val sidecar = fileStore.readSidecar("rec_1")
            assertEquals("rec_1_seg000.mp4", sidecar!!.segments[0].fileName)
        }

    @Test
    fun `U29 sidecar ausente se verifica y se crea el registro con E5-E6`() =
        runBlocking {
            val session = buildSession()
            val staging = fileStore.segmentStagingFile("rec_1", 0)
            staging.writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val summary = session.reconcileRecordings()
            assertEquals(1, fakeVerifier.verifyCalls)
            assertEquals(ReconciliationCase.C4_NO_SIDECAR, summary.segments[0].case)
            val sidecar = fileStore.readSidecar("rec_1")
            assertNotNull(sidecar)
            assertEquals(1, sidecar!!.segments.size)
            assertNotNull(sidecar.segments[0].verifiedAtMs)
            assertEquals(1, sidecar.segments[0].verifierVersion)
            assertEquals("rec_1_seg000.mp4", sidecar.segments[0].fileName)
            assertTrue(fileStore.segmentFile("rec_1", 0).exists())
        }

    @Test
    fun `U30 sidecar contradictorio en tamano se verifica y sobrescribe lo medido`() =
        runBlocking {
            val session = buildSession()
            val final = fileStore.segmentFile("rec_1", 0)
            final.writeText("01234567890")
            writeSidecar(
                "rec_1",
                listOf(
                    segmentEntry(
                        "rec_1", 0, staging = false,
                        status = "ready",
                        durationMs = 3000L,
                        sizeBytes = 999L,
                        verifiedAtMs = 1_792_000_001_000L,
                        verifierVersion = 1,
                    ),
                ),
            )
            val summary = session.reconcileRecordings()
            assertEquals(1, fakeVerifier.verifyCalls)
            assertEquals(ReconciliationCase.C5_CONTRADICTORY, summary.segments[0].case)
            val sidecar = fileStore.readSidecar("rec_1")
            assertEquals(11L, sidecar!!.segments[0].sizeBytes)
        }

    @Test
    fun `U31 verificacion imposible queda PENDING_VERIFICATION sin borrar ni listar como usable`() =
        runBlocking {
            val session = buildSession()
            val staging = fileStore.segmentStagingFile("rec_1", 0)
            staging.writeText("01234567890")
            fakeVerifier.throwOnVerify = RuntimeException("decodificador no disponible")
            val summary = session.reconcileRecordings()
            assertEquals(1, summary.segmentsPending.size)
            assertEquals("rec_1", summary.segmentsPending[0].recordingId)
            assertEquals(0, summary.segmentsPending[0].index)
            assertTrue(summary.segments[0].pending)
            assertNull(summary.segments[0].status)
            assertFalse(summary.segments[0].usable)
            assertEquals(ReconciliationCase.C6_PENDING, summary.segments[0].case)
            // Nunca se elimina, nunca es ready, excluido de lo utilizable.
            assertTrue(staging.exists())
            assertFalse(fileStore.segmentFile("rec_1", 0).exists())
            assertEquals(0, summary.segmentsRenamed)
            // 0-d2: los PENDING_VERIFICATION ya no se filtran a una
            // lista aparte: viajan en el resumen (`segmentsPending`)
            // y en el payload del canal con `status = "pending"`.
            assertTrue(summary.segments.none { it.usable })
            // Reintento: la siguiente pasada vuelve a intentarlo.
            fakeVerifier.throwOnVerify = null
            fakeVerifier.result = readyVerification()
            val retry = session.reconcileRecordings()
            assertEquals(0, retry.segmentsPending.size)
            assertEquals(SegmentStatus.PARTIAL, retry.segments[0].status)
            assertTrue(fileStore.segmentFile("rec_1", 0).exists())
        }

    @Test
    fun `U32 la reconciliacion persiste la verificacion y la segunda no re-verifica`() =
        runBlocking {
            val session = buildSession()
            val staging = fileStore.segmentStagingFile("rec_1", 0)
            staging.writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val first = session.reconcileRecordings()
            assertEquals(1, first.segmentsVerified)
            val sidecar = fileStore.readSidecar("rec_1")
            assertNotNull(sidecar)
            val entry = sidecar!!.segments[0]
            assertEquals("partial", entry.status)
            assertEquals(3000L, entry.durationMs)
            assertEquals(11L, entry.sizeBytes)
            assertNotNull(entry.verifiedAtMs)
            assertEquals(1, entry.verifierVersion)
            val second = session.reconcileRecordings()
            assertEquals(0, second.segmentsVerified)
            assertEquals(1, second.segmentsTrusted)
            assertEquals(ReconciliationCase.C1_TRUSTED_FINAL, second.segments[0].case)
            assertEquals(1, fakeVerifier.verifyCalls)
        }

    @Test
    fun `U37 contencion del verificador dos verificaciones no corren en paralelo`() =
        runBlocking {
            val session = buildSession()
            fileStore.segmentStagingFile("rec_1", 0).writeText("01234567890")
            fileStore.segmentStagingFile("rec_1", 1).writeText("01234567890")
            fakeVerifier.result = readyVerification()
            fakeVerifier.verifyDelayMs = 100L
            val summary = session.reconcileRecordings()
            assertEquals(2, fakeVerifier.verifyCalls)
            assertEquals(2, summary.segmentsVerified)
            assertEquals(1, fakeVerifier.maxConcurrent.get())
        }

    @Test
    fun `I1 muerte durante la grabacion reabrir renombra los utilizables`() =
        runBlocking {
            val session = buildSession()
            fileStore.segmentStagingFile("rec_1", 0).writeText("01234567890")
            fileStore.segmentStagingFile("rec_1", 1).writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val summary = session.reconcileRecordings()
            assertEquals(2, summary.segmentsRenamed)
            assertTrue(fileStore.segmentFile("rec_1", 0).exists())
            assertTrue(fileStore.segmentFile("rec_1", 1).exists())
            val sidecar = fileStore.readSidecar("rec_1")
            assertNotNull(sidecar)
            assertEquals(2, sidecar!!.segments.size)
            assertTrue(sidecar.segments.all { it.verifiedAtMs != null })
            assertTrue(sidecar.segments.all { it.verifierVersion == 1 })
        }

    @Test
    fun `I2 reinicio a mitad de la reconciliacion re-ejecuta y converge sin duplicados ni perdidas`() =
        runBlocking {
            val session = buildSession()
            fileStore.segmentStagingFile("rec_1", 0).writeText("01234567890")
            fileStore.segmentStagingFile("rec_1", 1).writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val first = session.reconcileRecordings()
            assertEquals(2, first.segmentsRenamed)
            val second = session.reconcileRecordings()
            assertEquals(0, second.segmentsVerified)
            assertEquals(2, second.segmentsTrusted)
            assertEquals(0, second.segmentsRenamed)
            assertEquals(0, second.segmentsQuarantined)
            assertEquals(2, fakeVerifier.verifyCalls)
            assertTrue(fileStore.segmentFile("rec_1", 0).exists())
            assertTrue(fileStore.segmentFile("rec_1", 1).exists())
            assertEquals(0, dir.listFiles()!!.count {
                it.name.contains(".quarantine-")
            })
        }

    @Test
    fun `I3 muerte entre segmentos los mp4 cerrados se clasifican`() =
        runBlocking {
            val session = buildSession()
            fileStore.segmentFile("rec_1", 0).writeText("01234567890")
            fileStore.segmentStagingFile("rec_1", 1).writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val summary = session.reconcileRecordings()
            assertEquals(2, summary.segments.size)
            val seg0 = summary.segments.first { it.index == 0 }
            val seg1 = summary.segments.first { it.index == 1 }
            assertEquals(SegmentStatus.READY, seg0.status)
            assertEquals(SegmentStatus.PARTIAL, seg1.status)
            assertTrue(seg0.usable)
            assertTrue(seg1.usable)
            assertTrue(fileStore.segmentFile("rec_1", 1).exists())
        }

    @Test
    fun `I4 segunda grabacion intacta la primera no se toca`() =
        runBlocking {
            val session = buildSession()
            val final1 = fileStore.segmentFile("rec_1", 0)
            final1.writeText("01234567890")
            writeSidecar(
                "rec_1",
                listOf(
                    segmentEntry(
                        "rec_1", 0, staging = false,
                        status = "ready",
                        durationMs = 3000L,
                        sizeBytes = 11L,
                        verifiedAtMs = 1_792_000_001_000L,
                        verifierVersion = 1,
                        fpsMeasured = 30.0,
                    ),
                ),
            )
            val sidecarBefore = fileStore.sidecarFile("rec_1").readText()
            fileStore.segmentStagingFile("rec_2", 0).writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val summary = session.reconcileRecordings()
            assertEquals(2, summary.groupsReconciled)
            assertEquals(1, fakeVerifier.verifyCalls)
            // El nombre registrado es el de la verificación (staging:
            // el renombre ocurre después de verificar).
            assertEquals(listOf("rec_2_seg000.tmp.mp4"), fakeVerifier.verifiedFiles)
            assertEquals(sidecarBefore, fileStore.sidecarFile("rec_1").readText())
            assertTrue(final1.exists())
            assertEquals("01234567890", final1.readText())
        }

    @Test
    fun `I5 solo se verifica lo que el sidecar no certifica`() =
        runBlocking {
            val session = buildSession()
            val final = fileStore.segmentFile("rec_1", 0)
            final.writeText("01234567890")
            writeSidecar(
                "rec_1",
                listOf(
                    segmentEntry(
                        "rec_1", 0, staging = false,
                        status = "ready",
                        durationMs = 3000L,
                        sizeBytes = 11L,
                        verifiedAtMs = 1_792_000_001_000L,
                        verifierVersion = 1,
                    ),
                ),
            )
            fileStore.segmentStagingFile("rec_1", 1).writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val summary = session.reconcileRecordings()
            assertEquals(1, fakeVerifier.verifyCalls)
            assertEquals(1, summary.segmentsTrusted)
            assertEquals(1, summary.segmentsVerified)
            assertEquals(listOf("rec_1_seg001.tmp.mp4"), fakeVerifier.verifiedFiles)
        }

    @Test
    fun `reconciliacion con duplicado conserva el verificado y cuarentena el otro`() =
        runBlocking {
            val session = buildSession()
            // El `.mp4` ya existe y es corrupto; el `.tmp` del mismo
            // índice es utilizable: el staging gana, el final va a
            // cuarentena y el `outcome` del final queda obsoleto.
            val final = fileStore.segmentFile("rec_1", 0)
            final.writeText("viejo")
            val staging = fileStore.segmentStagingFile("rec_1", 0)
            staging.writeText("01234567890")
            fakeVerifier.resultByFile = { file ->
                if (file.name.endsWith(".tmp.mp4")) {
                    readyVerification()
                } else {
                    corruptVerification()
                }
            }
            val summary = session.reconcileRecordings()
            assertEquals(1, summary.conflicts)
            assertEquals(1, summary.segmentsQuarantined)
            assertEquals(1, summary.segments.size)
            assertEquals(SegmentStatus.PARTIAL, summary.segments[0].status)
            assertEquals("rec_1_seg000.mp4", summary.segments[0].fileName)
            assertTrue(summary.segments[0].renamed)
            assertEquals("01234567890", final.readText())
            assertFalse(staging.exists())
            val quarantined = dir.listFiles()!!
                .filter { it.name.contains(".quarantine-") }
            assertEquals(1, quarantined.size)
            assertEquals("viejo", quarantined[0].readText())
        }

    @Test
    fun `reconciliacion ignora write-test y lista lo desconocido sin tocarlo`() =
        runBlocking {
            val session = buildSession()
            val writeTest = File(dir, ".write-test-aaa.tmp")
            writeTest.writeText("")
            writeTest.setLastModified(1_792_000_000_000L - 61_000L)
            val unknown = File(dir, "rec_1_final.mp4")
            unknown.writeText("fusion")
            fileStore.segmentStagingFile("rec_1", 0).writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val summary = session.reconcileRecordings()
            assertEquals(1, summary.orphanWriteTestFilesSwept)
            assertFalse(writeTest.exists())
            assertEquals(listOf("rec_1_final.mp4"), summary.unknownFiles)
            assertTrue(unknown.exists())
            assertEquals("fusion", unknown.readText())
            assertEquals(0, fakeVerifier.verifiedFiles.count {
                it.startsWith(".write-test-")
            })
        }

    @Test
    fun `J1-c tmp de fusion huerfano y final existente van a desconocidos sin tocarse`() =
        runBlocking {
            val session = buildSession()
            // Segmentos utilizables del grupo: la reconciliación los
            // certifica aunque exista un final (la fusión es un paso
            // posterior invocado por el controller, J1-c; la
            // reconciliación en sí no fusiona).
            fileStore.segmentFile("rec_1", 0).writeText("01234567890")
            val final = fileStore.finalFile("rec_1")
            final.writeText("fusionado")
            val mergeTmp = fileStore.finalStagingFile("rec_1")
            mergeTmp.writeText("fusion-parcial")
            fakeVerifier.result = readyVerification()
            val summary = session.reconcileRecordings()
            // Los dos archivos de fusión van al cubo desconocido,
            // sin tocarse: ni se verifican, ni se renombran, ni se
            // borran (el tmp huérfano lo sobrescribe la fusión de
            // reanudación; el final lo verifica el fusionador).
            assertEquals(
                listOf("rec_1_final.mp4", "rec_1_final.tmp.mp4"),
                summary.unknownFiles,
            )
            assertTrue(final.exists())
            assertEquals("fusionado", final.readText())
            assertTrue(mergeTmp.exists())
            assertEquals("fusion-parcial", mergeTmp.readText())
            assertFalse(fakeVerifier.verifiedFiles.any {
                it.contains("_final")
            })
            // El segmento sí se certificó: la presencia del final no
            // bloquea la reconciliación del grupo.
            assertEquals(1, summary.segments.size)
            assertEquals(SegmentStatus.READY, summary.segments[0].status)
            assertEquals(
                "rec_1_seg000.mp4",
                summary.segments[0].fileName,
            )
        }
}
