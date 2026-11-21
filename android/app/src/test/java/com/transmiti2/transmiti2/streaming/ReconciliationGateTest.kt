package com.transmiti2.transmiti2.streaming

import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import com.transmiti2.transmiti2.streaming.recording.RecordingFileStore
import com.transmiti2.transmiti2.streaming.recording.RecordingResult
import com.transmiti2.transmiti2.streaming.recording.RecordingSession
import com.transmiti2.transmiti2.streaming.recording.RecordingStartedInfo
import com.transmiti2.transmiti2.streaming.recording.RecordingStatusSnapshot
import com.transmiti2.transmiti2.streaming.recording.ReconciledSegmentOutcome
import com.transmiti2.transmiti2.streaming.recording.ReconciledSegmentRef
import com.transmiti2.transmiti2.streaming.recording.ReconciliationCase
import com.transmiti2.transmiti2.streaming.recording.ReconciliationSummary
import com.transmiti2.transmiti2.streaming.recording.SegmentStatus
import com.transmiti2.transmiti2.streaming.recording.SegmentVerification
import com.transmiti2.transmiti2.streaming.recording.SegmentVerifier
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Puerta de reconciliación (subetapa 0, grupo 0-d2; plan §2.6 y §5):
 * una sola pasada por proceso, espera compartida, `FAILED` con motivo
 * + reintento, payload del canal con los `PENDING_VERIFICATION` y la
 * parte de puerta de I7 (la grabación no espera a la puerta).
 *
 * La contención sesión/verificador y el «grupo nuevo saltado» de I7
 * ya están probados a nivel de sesión en `RecordingSessionTest`
 * (U35/U36/I7, 0-d1); aquí se prueba lo que depende de la puerta:
 * que los esperadores comparten la misma pasada y que el inicio no
 * se bloquea mientras corre.
 */
class ReconciliationGateTest {

    @Before
    fun setUp() {
        Transmiti2Logger.sink = { _, _ -> }
    }

    @After
    fun tearDown() {
        Transmiti2Logger.sink = null
    }

    private val testConfig = PreviewConfig(
        cameraId = "0",
        size = VideoSize(1280, 720),
        fpsRange = FpsRange(30, 30),
        sensorOrientation = 90,
        lensFacing = CameraCharacteristics.LENS_FACING_BACK,
    )

    private class FakeHandle(
        override val textureId: Long = 42L,
    ) : SurfaceProducerHandle {
        override fun setSize(width: Int, height: Int) {}
        override fun surface(): Any? = Object()
        override fun setCallback(callback: SurfaceProducerCallback?) {}
        override fun release() {}
    }

    private class FakePreviewSession(
        private val config: PreviewConfig,
    ) : PreviewSession {
        override var isOpen = false
            private set
        override var listener: PreviewSessionListener? = null

        override suspend fun open(): PreviewConfig {
            isOpen = true
            return config
        }

        override fun close() {
            isOpen = false
        }

        override fun closeSession() {
            isOpen = false
        }

        override suspend fun recreateSession(
            videoSurface: Surface?,
            template: Int,
        ): PreviewConfig {
            isOpen = true
            return config
        }
    }

    private class NeverVerifier : SegmentVerifier {
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
            throw UnsupportedOperationException(
                "El verificador no corre en el test de puerta",
            )
        }
    }

    /**
     * Sesión falsa: la reconciliación es guionizable (bloqueo,
     * fallo una vez, resumen enlatado) y `start()` devuelve un id
     * nuevo sin tocar disco ni codificador.
     */
    private class FakeGateSession(
        scope: CoroutineScope,
    ) : RecordingSession(
        scope = scope,
        fileStore = RecordingFileStore(
            File("/tmp/transmiti2_test_gate"),
        ),
        verifier = NeverVerifier(),
    ) {
        var reconcileCalls = 0

        /** Resumen que devuelve la pasada cuando no se bloquea. */
        var reconcileResult: ReconciliationSummary = emptySummary("run-1")

        /** Si no es nulo, la pasada lo lanza (una sola vez). */
        var failOnce: Exception? = null

        /** La pasada se bloquea aquí hasta que la prueba lo suelta. */
        var blockLatch: CountDownLatch? = null

        /** Se abre cuando la pasada empieza (la prueba espera a esto). */
        val enteredLatch = CountDownLatch(1)

        /** Id que devuelve `start()` (grabación nueva). */
        var nextRecordingId = "rec_new_20261121_000000_xx99"
        var startCalls = 0

        override suspend fun reconcileRecordings(): ReconciliationSummary {
            reconcileCalls++
            enteredLatch.countDown()
            blockLatch?.await(10, TimeUnit.SECONDS)
            failOnce?.let {
                failOnce = null
                throw it
            }
            return reconcileResult
        }

        override fun statusSnapshot(): RecordingStatusSnapshot {
            return RecordingStatusSnapshot(
                state = if (startCalls > 0) "recording" else "idle",
                recordingId = if (startCalls > 0) nextRecordingId else null,
                elapsedMs = 0,
                segmentIndex = 0,
                segmentDurationMs = 0,
                width = 1280,
                height = 720,
                fps = 30,
                errorCode = null,
            )
        }

        override suspend fun start(
            previewSession: PreviewSession,
            previewConfig: PreviewConfig,
            rotationDegrees: Int,
            matchId: String?,
            cameraManager: android.hardware.camera2.CameraManager?,
            audioEnabled: Boolean,
        ): RecordingStartedInfo {
            startCalls++
            return RecordingStartedInfo(
                recordingId = nextRecordingId,
                matchId = matchId,
                width = 1280,
                height = 720,
                fps = 30,
                bitrateBps = 2_500_000,
                profile = 66,
                bitrateMode = 2,
                encoderName = "c2.qti.avc.encoder",
                rotationDegrees = rotationDegrees,
                lowStorageWarning = false,
                cbrSupported = true,
            )
        }

        override suspend fun stop(
            errorCode: String?,
        ): RecordingResult {
            return RecordingResult.empty()
        }

        companion object {
            fun emptySummary(runId: String): ReconciliationSummary {
                return ReconciliationSummary(
                    runId = runId,
                    startedAtMs = 1_000L,
                    finishedAtMs = 1_100L,
                    groupsTotal = 0,
                    groupsReconciled = 0,
                    groupsSkippedActive = emptyList(),
                    groupsFailed = emptyList(),
                    segments = emptyList(),
                    segmentsTrusted = 0,
                    segmentsVerified = 0,
                    segmentsRenamed = 0,
                    segmentsPending = emptyList(),
                    segmentsQuarantined = 0,
                    unknownFiles = emptyList(),
                    missingFiles = emptyList(),
                    orphanWriteTestFilesSwept = 0,
                    reservationTokens = emptyMap(),
                    foreignReleaseRejected = 0,
                    conflicts = 0,
                )
            }
        }
    }

    private class FakeForegroundService : ForegroundServiceControl {
        var startCalls = 0
        override fun startRecordingService(
            scenario: RecordingScenario,
        ): Boolean {
            startCalls++
            return true
        }

        override fun stopRecordingService() {}
    }

    private inner class Fixture(
        val useRecording: Boolean = true,
    ) {
        val scope = CoroutineScope(Dispatchers.Default)
        val session = FakeGateSession(scope)
        val fgs = FakeForegroundService()
        val previewSession = FakePreviewSession(testConfig)
        val provider = SurfaceProducerProvider { FakeHandle() }

        fun engine(): StreamEngineImpl {
            return StreamEngineImpl(
                cameraPermissionGranted = { true },
                microphonePermissionGranted = { true },
                surfaceProvider = provider,
                sessionFactory = { previewSession },
                displayRotationDegrees = { 0 },
                scope = scope,
                mainDispatcher = Dispatchers.Unconfined,
                recordingSessionFactory = { _ ->
                    if (useRecording) session else null
                },
                foregroundService = fgs,
            )
        }
    }

    @Test
    fun `U33 la puerta existe y los esperadores comparten una sola pasada`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()
            assertEquals(
                ReconciliationGateState.NOT_STARTED,
                engine.reconciliationState.value,
            )
            fixture.session.blockLatch = CountDownLatch(1)

            engine.triggerReconciliation()
            assertTrue(
                "La pasada no arrancó",
                fixture.session.enteredLatch.await(5, TimeUnit.SECONDS),
            )
            assertEquals(
                ReconciliationGateState.RUNNING,
                engine.reconciliationState.value,
            )

            // Tres esperadores concurrentes (ancla A + ancla B + canal):
            // todos esperan a la MISMA pasada, no lanzan otras tres.
            val awaiters = (1..3).map {
                async { engine.reconcileRecordings() }
            }
            // La puerta sigue en RUNNING: nadie recibe datos antes
            // de DONE (ningún awaiter completa con el latch cerrado).
            assertFalse(awaiters.any { it.isCompleted })
            fixture.session.blockLatch!!.countDown()
            val summaries = withTimeout(5_000) {
                awaiters.map { it.await() }
            }
            assertEquals(1, fixture.session.reconcileCalls)
            assertTrue(summaries.all { it.runId == "run-1" })
            assertEquals(
                ReconciliationGateState.DONE,
                engine.reconciliationState.value,
            )
            // Una llamada posterior no re-ejecuta: devuelve el mismo
            // resumen sin tocar la sesión.
            val again = withTimeout(5_000) { engine.reconcileRecordings() }
            assertEquals("run-1", again.runId)
            assertEquals(1, fixture.session.reconcileCalls)
        }

    @Test
    fun `U34 puerta en FAILED error con motivo y reintento nunca lista falsa`() =
        runBlocking {
            val fixture = Fixture()
            fixture.session.failOnce =
                IllegalStateException("disco no disponible")
            val engine = fixture.engine()

            try {
                withTimeout(5_000) { engine.reconcileRecordings() }
                fail("Se esperaba ReconciliationFailedException")
            } catch (e: ReconciliationFailedException) {
                assertEquals(
                    StreamContract.RECONCILIATION_FAILED,
                    e.code,
                )
            }
            assertEquals(
                ReconciliationGateState.FAILED,
                engine.reconciliationState.value,
            )
            val stateMap = engine.reconciliationStateMap()
            assertEquals("FAILED", stateMap["state"])
            assertEquals(
                StreamContract.RECONCILIATION_FAILED,
                stateMap["errorCode"],
            )
            assertNull(stateMap["summary"])
            // El payload FAILED lleva segmentos vacíos y motivo: nunca
            // una lista vacía presentada como éxito final.
            val failedMap = engine.reconciliationFailedMap(
                ReconciliationFailure(
                    code = StreamContract.RECONCILIATION_FAILED,
                    message = "disco no disponible",
                ),
            )
            assertEquals("FAILED", failedMap["state"])
            assertEquals(
                emptyList<Any>(),
                failedMap["segments"],
            )

            // Reintento: la segunda llamada re-ejecuta desde cero y
            // converge (el progreso parcial se conserva en disco).
            val summary = withTimeout(5_000) { engine.reconcileRecordings() }
            assertEquals("run-1", summary.runId)
            assertEquals(2, fixture.session.reconcileCalls)
            assertEquals(
                ReconciliationGateState.DONE,
                engine.reconciliationState.value,
            )
        }

    @Test
    fun `puerta en FAILED sin sesion con reconciliation_unavailable`() =
        runBlocking {
            val fixture = Fixture(useRecording = false)
            val engine = fixture.engine()

            try {
                withTimeout(5_000) { engine.reconcileRecordings() }
                fail("Se esperaba ReconciliationFailedException")
            } catch (e: ReconciliationFailedException) {
                assertEquals(
                    StreamContract.RECONCILIATION_UNAVAILABLE,
                    e.code,
                )
            }
            assertEquals(
                ReconciliationGateState.FAILED,
                engine.reconciliationState.value,
            )
        }

    @Test
    fun `el payload del canal expone los PENDING_VERIFICATION y el resumen por grupo`() =
        runBlocking {
            val fixture = Fixture()
            fixture.session.reconcileResult = ReconciliationSummary(
                runId = "run-7",
                startedAtMs = 1_000L,
                finishedAtMs = 1_500L,
                groupsTotal = 2,
                groupsReconciled = 1,
                groupsSkippedActive = listOf("rec_activa"),
                groupsFailed = emptyList(),
                segments = listOf(
                    ReconciledSegmentOutcome(
                        recordingId = "rec_1",
                        index = 0,
                        fileName = "rec_1_seg000.mp4",
                        filePath = "/tmp/transmiti2_test_gate/rec_1_seg000.mp4",
                        status = SegmentStatus.READY,
                        case = ReconciliationCase.C1_TRUSTED_FINAL,
                        durationMs = 3_000,
                        sizeBytes = 900_000,
                        reason = null,
                        verified = false,
                        trusted = true,
                        renamed = false,
                        wasStaging = false,
                    ),
                    ReconciledSegmentOutcome(
                        recordingId = "rec_2",
                        index = 0,
                        fileName = "rec_2_seg000.tmp.mp4",
                        filePath = "/tmp/transmiti2_test_gate/rec_2_seg000.tmp.mp4",
                        status = null,
                        case = ReconciliationCase.C6_PENDING,
                        durationMs = null,
                        sizeBytes = 500_000,
                        reason = "timeout",
                        verified = false,
                        trusted = false,
                        renamed = false,
                        wasStaging = true,
                    ),
                ),
                segmentsTrusted = 1,
                segmentsVerified = 0,
                segmentsRenamed = 0,
                segmentsPending = listOf(
                    ReconciledSegmentRef(
                        recordingId = "rec_2",
                        index = 0,
                        fileName = "rec_2_seg000.tmp.mp4",
                        reason = "timeout",
                    ),
                ),
                segmentsQuarantined = 0,
                unknownFiles = emptyList(),
                missingFiles = emptyList(),
                orphanWriteTestFilesSwept = 1,
                reservationTokens = mapOf("rec_1" to 3L),
                foreignReleaseRejected = 2,
                conflicts = 0,
            )
            val engine = fixture.engine()

            val summary = withTimeout(5_000) { engine.reconcileRecordings() }
            val resultMap = engine.reconciliationResultMap(summary)

            assertEquals("DONE", resultMap["state"])
            @Suppress("UNCHECKED_CAST")
            val segments = resultMap["segments"] as List<Map<String, Any?>>
            assertEquals(2, segments.size)
            // El PENDING_VERIFICATION viaja con status "pending":
            // nunca utilizable ni corrupto, listado para reintento.
            val pending = segments.first { it["recordingId"] == "rec_2" }
            assertEquals("pending", pending["status"])
            assertEquals("C6_PENDING", pending["case"])
            @Suppress("UNCHECKED_CAST")
            val summaryMap = resultMap["summary"] as Map<String, Any?>
            assertEquals("run-7", summaryMap["runId"])
            assertEquals(500L, summaryMap["durationMs"])
            assertEquals(1, summaryMap["recordingsReconciled"])
            assertEquals(1, summaryMap["pending"])
            assertEquals(1, summaryMap["skipped_active"])
            @Suppress("UNCHECKED_CAST")
            val pendingList =
                summaryMap["segmentsPending"] as List<Map<String, Any?>>
            assertEquals(1, pendingList.size)
            assertEquals("rec_2", pendingList[0]["recordingId"])
            @Suppress("UNCHECKED_CAST")
            val tokens =
                summaryMap["reservationTokens"] as Map<String, Long>
            assertEquals(3L, tokens["rec_1"])
            assertEquals(2L, summaryMap["foreignReleaseRejected"])
            assertEquals(1, summaryMap["orphanWriteTestFiles"])
            // El mapa de estado refleja la misma puerta.
            val stateMap = engine.reconciliationStateMap()
            assertEquals("DONE", stateMap["state"])
            assertEquals("run-7", (stateMap["summary"] as Map<*, *>)["runId"])
        }

    @Test
    fun `I7 puerta la grabacion no espera a la puerta y su grupo nuevo no se toca`() =
        runBlocking {
            val fixture = Fixture()
            fixture.session.reconcileResult =
                FakeGateSession.emptySummary("run-9").copy(
                    groupsTotal = 1,
                    groupsReconciled = 1,
                    segments = listOf(
                        ReconciledSegmentOutcome(
                            recordingId = "rec_old",
                            index = 0,
                            fileName = "rec_old_seg000.mp4",
                            filePath = "/tmp/rec_old_seg000.mp4",
                            status = SegmentStatus.READY,
                            case = ReconciliationCase.C1_TRUSTED_FINAL,
                            durationMs = 3_000,
                            sizeBytes = 900_000,
                            reason = null,
                            verified = false,
                            trusted = true,
                            renamed = false,
                            wasStaging = false,
                        ),
                    ),
                    segmentsTrusted = 1,
                )
            fixture.session.blockLatch = CountDownLatch(1)
            val engine = fixture.engine()

            engine.triggerReconciliation()
            assertTrue(
                "La pasada no arrancó",
                fixture.session.enteredLatch.await(5, TimeUnit.SECONDS),
            )
            assertEquals(
                ReconciliationGateState.RUNNING,
                engine.reconciliationState.value,
            )

            // La grabación empieza mientras la puerta corre: no espera
            // a DONE y su `.tmp` nuevo no aparece en ningún resultado.
            engine.startPreview()
            val newId = withTimeout(5_000) {
                engine.startRecording(RecordingOptions())
            }
            assertEquals(fixture.session.nextRecordingId, newId)
            assertEquals(1, fixture.session.startCalls)
            assertEquals(
                "La puerta debe seguir en RUNNING tras el inicio",
                ReconciliationGateState.RUNNING,
                engine.reconciliationState.value,
            )

            fixture.session.blockLatch!!.countDown()
            val summary = withTimeout(5_000) { engine.reconcileRecordings() }
            assertEquals("run-9", summary.runId)
            assertEquals(1, fixture.session.reconcileCalls)
            assertTrue(
                "El grupo nuevo no debe aparecer en el resumen",
                summary.segments.none { it.recordingId == newId },
            )
            assertEquals(
                ReconciliationGateState.DONE,
                engine.reconciliationState.value,
            )
            // La mitad «grupo nuevo saltado por reserva» de I7 está
            // probada a nivel de sesión en RecordingSessionTest (0-d1);
            // aquí se prueba la mitad de puerta: el inicio no espera.
        }
}
