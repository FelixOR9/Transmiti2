package com.transmiti2.transmiti2.streaming

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.Surface
import com.transmiti2.transmiti2.streaming.recording.ErrorTrigger
import com.transmiti2.transmiti2.streaming.recording.MergeProgress
import com.transmiti2.transmiti2.streaming.recording.MergeResult
import com.transmiti2.transmiti2.streaming.recording.MergeStage
import com.transmiti2.transmiti2.streaming.recording.RecordingException
import com.transmiti2.transmiti2.streaming.recording.RecordingFileStore
import com.transmiti2.transmiti2.streaming.recording.RecordingResult
import com.transmiti2.transmiti2.streaming.recording.RecordingSegmentResult
import com.transmiti2.transmiti2.streaming.recording.RecordingSession
import com.transmiti2.transmiti2.streaming.recording.RecordingStartedInfo
import com.transmiti2.transmiti2.streaming.recording.RecordingStatusSnapshot
import com.transmiti2.transmiti2.streaming.recording.ReconciledSegmentOutcome
import com.transmiti2.transmiti2.streaming.recording.ReconciliationCase
import com.transmiti2.transmiti2.streaming.recording.ReconciliationSummary
import com.transmiti2.transmiti2.streaming.recording.SegmentStatus
import com.transmiti2.transmiti2.streaming.recording.SegmentVerification
import com.transmiti2.transmiti2.streaming.recording.SegmentVerifier
import com.transmiti2.transmiti2.streaming.recording.VerificationChecks
import com.transmiti2.transmiti2.streaming.test.PausePoint
import com.transmiti2.transmiti2.streaming.test.TestEvent
import com.transmiti2.transmiti2.streaming.test.TestEventOrigin
import com.transmiti2.transmiti2.streaming.test.TestEventResult
import com.transmiti2.transmiti2.streaming.test.TestEventType
import com.transmiti2.transmiti2.streaming.test.TestHooks
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Pruebas de la integración de grabación en el motor (Fase B, 3.1.2,
 * componente 3): la sesión de grabación y el FGS son falsos (la lógica
 * real de la sesión se prueba en `RecordingSessionTest` y el servicio
 * real en el S24 Ultra). Lo que se prueba es la orquestación del motor:
 * vista previa antes de grabar, permiso antes de arrancar, FGS antes de
 * la sesión, rechazo de duplicados sin tocar el FGS, parada de la
 * grabación antes que la vista previa, desconexión de cámara y limpieza
 * de superficie, y re-sincronización del estado. En 3.1.3: escenario
 * del FGS por grabación (`WITH_AUDIO` solo con micrófono concedido y
 * `audioEnabled`) y claves de audio en `recordingStatusMap`.
 */
class StreamEngineRecordingTest {

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
        var released = false
        var lastCallback: SurfaceProducerCallback? = null

        override fun setSize(width: Int, height: Int) {}
        override fun surface(): Any? = Object()
        override fun setCallback(callback: SurfaceProducerCallback?) {
            lastCallback = callback
        }

        override fun release() {
            released = true
        }
    }

    private class FakeSession(
        private val config: PreviewConfig,
    ) : PreviewSession {
        var openCalls = 0
        var closeCalls = 0
        override var isOpen = false
            private set
        override var listener: PreviewSessionListener? = null

        override suspend fun open(): PreviewConfig {
            openCalls++
            isOpen = true
            return config
        }

        override fun close() {
            closeCalls++
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

        fun fireDisconnected() {
            listener?.onDisconnected()
        }

        fun fireError(code: String) {
            listener?.onError(code)
        }
    }

    /** Verificador falso (la sesión falsa nunca lo usa de verdad). */
    private class FakeVerifier : SegmentVerifier {
        /** Última expectativa de audio recibida (3.1.3). */
        var lastExpectedAudio: Boolean? = null
        var lastExpectedSampleRate: Int? = null
        var lastExpectedChannels: Int? = null

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
            lastExpectedAudio = expectedAudio
            lastExpectedSampleRate = expectedSampleRate
            lastExpectedChannels = expectedChannels
            return SegmentVerification(
                SegmentStatus.READY,
                VerificationChecks(
                    extractorOpens = true,
                    hasAvcTrack = true,
                    width = 1280,
                    height = 720,
                    sampleCount = 360,
                    keyframeCount = 6,
                    firstSampleKeyframe = true,
                    ptsMonotonic = true,
                    durationMs = 12_000,
                    fpsMeasured = 30.0,
                    ioPhaseMs = 12_000L,
                    profileIdc = 66,
                    decodeOk = true,
                    decodedFrames = 360,
                    exoplayerReady = true,
                    exoplayerDurationMs = 12_000,
                ),
                null,
            )
        }
    }

    /**
     * Sesión de grabación falsa: registra las llamadas y devuelve
     * resultados enlatados. El estado lo fija la prueba (`fakeState`).
     */
    private class FakeRecordingSession(
        scope: CoroutineScope,
    ) : RecordingSession(
        scope = scope,
        fileStore = RecordingFileStore(File("/tmp/transmiti2_test_rec")),
        verifier = FakeVerifier(),
    ) {
        var fakeState = "idle"
        var fakeRecordingId: String? = null
        var startCalls = 0
        var stopCalls = 0
        var lastStopErrorCode: String? = null
        var lastStartConfig: PreviewConfig? = null
        var lastStartRotation: Int? = null
        var lastStartMatchId: String? = null
        /** Último `audioEnabled` con el que se llamó a `start` (3.1.3). */
        var lastStartAudioEnabled: Boolean? = null
        /** Audio del snapshot (3.1.3): espejo de la sesión real
         * (`audioEnabled = audioExpected`, `audioActive` con muxer). */
        var fakeAudioEnabled = false
        var fakeAudioActive = false
        var fakeAudioReason: String? = null
        var startError: RecordingException? = null
        var stopResult: RecordingResult = RecordingResult.empty()
        /** Resumen que devuelve `reconcileRecordings()` (0-d2). */
        var reconcileResult: ReconciliationSummary = ReconciliationSummary(
            runId = "run-0",
            startedAtMs = 0L,
            finishedAtMs = 0L,
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
        var reconcileCalls = 0
        var startedInfo = RecordingStartedInfo(
            recordingId = "rec_20261118_120000_ab12",
            matchId = null,
            width = 1280,
            height = 720,
            fps = 30,
            bitrateBps = 2_500_000,
            profile = 66,
            bitrateMode = 2,
            encoderName = "c2.qti.avc.encoder",
            rotationDegrees = 90,
            lowStorageWarning = false,
            cbrSupported = true,
        )

        override fun statusSnapshot(): RecordingStatusSnapshot {
            return RecordingStatusSnapshot(
                state = fakeState,
                recordingId = fakeRecordingId,
                elapsedMs = if (fakeState == "recording") 12_000 else 0,
                segmentIndex = 0,
                segmentDurationMs = 12_000,
                width = 1280,
                height = 720,
                fps = 30,
                errorCode = null,
                audioEnabled = fakeAudioEnabled,
                audioActive = fakeAudioActive,
                audioReason = fakeAudioReason,
            )
        }

        override suspend fun start(
            previewSession: PreviewSession,
            previewConfig: PreviewConfig,
            rotationDegrees: Int,
            matchId: String?,
            cameraManager: CameraManager?,
            audioEnabled: Boolean,
        ): RecordingStartedInfo {
            startCalls++
            lastStartConfig = previewConfig
            lastStartRotation = rotationDegrees
            lastStartMatchId = matchId
            lastStartAudioEnabled = audioEnabled
            startError?.let { throw it }
            fakeState = "recording"
            fakeRecordingId = startedInfo.recordingId
            fakeAudioEnabled = audioEnabled
            fakeAudioActive = audioEnabled
            fakeAudioReason = null
            return startedInfo
        }

        // J3-a (3.1.4): `stop()` ganó el parámetro `errorTrigger`
        // (disparador completo de la auto-parada); el falso lo acepta
        // y lo ignora, como antes ignoraba el código en este camino.
        // J3-b: si es true, `stop(errorCode)` invoca el handler de
        // error-stop del motor (como la sesión real al completar
        // `doStop` con error); por defecto no se invoca, como antes.
        var invokeErrorStopHandler = false

        override suspend fun stop(
            errorCode: String?,
            errorTrigger: ErrorTrigger?,
        ): RecordingResult {
            stopCalls++
            lastStopErrorCode = errorCode
            fakeState = "idle"
            fakeRecordingId = null
            fakeAudioEnabled = false
            fakeAudioActive = false
            fakeAudioReason = null
            if (invokeErrorStopHandler && errorCode != null &&
                stopResult.recordingId.isNotEmpty()
            ) {
                onSessionErrorStop?.invoke(
                    errorCode,
                    errorTrigger,
                    stopResult.recordingId,
                )
            }
            return stopResult
        }

        override suspend fun reconcileRecordings(): ReconciliationSummary {
            reconcileCalls++
            return reconcileResult
        }

        /** `kind` recibidos por `injectTestError` (J3-c). */
        var injectedKinds = mutableListOf<String>()

        /** Resultado enlatado de `injectTestError` (J3-c). */
        var injectResult = true

        override fun injectTestError(kind: String): Boolean {
            injectedKinds += kind
            return injectResult
        }
    }

    private class FakeForegroundService : ForegroundServiceControl {
        var startCalls = 0
        var stopCalls = 0
        var startResult = true
        /** Último escenario recibido (3.1.3). */
        var lastScenario: RecordingScenario? = null
        /** Textos de progreso de fusión recibidos (J1-b). */
        val mergeProgressTexts = mutableListOf<String>()

        override fun startRecordingService(scenario: RecordingScenario): Boolean {
            startCalls++
            lastScenario = scenario
            return startResult
        }

        override fun stopRecordingService() {
            stopCalls++
        }

        override fun updateMergeProgress(progressText: String) {
            mergeProgressTexts += progressText
        }
    }

    /** Ganchos de pruebas que capturan los eventos registrados (J3-c). */
    private class CapturingTestHooks : TestHooks {
        data class Recorded(
            val event: TestEventType,
            val recordingId: String?,
            val detail: Map<String, Any?>,
        )

        val records = mutableListOf<Recorded>()

        override fun record(
            event: TestEventType,
            operation: String,
            recordingId: String?,
            result: TestEventResult,
            errorCode: String?,
            durationMs: Long?,
            detail: Map<String, Any?>,
            origin: TestEventOrigin,
            wasSuspended: Boolean?,
        ): TestEvent? {
            synchronized(records) {
                records += Recorded(event, recordingId, detail)
            }
            return null
        }

        override suspend fun awaitPauseIfArmed(point: PausePoint, runId: String?): Boolean =
            false

        override fun resumePause(point: PausePoint): Boolean = false
        override val sessionActive: Boolean = false

        fun ofType(event: TestEventType): List<Recorded> =
            synchronized(records) { records.filter { it.event == event } }
    }

    private inner class Fixture(
        permissionGranted: Boolean = true,
        val displayRotation: Int = 0,
    ) {
        /** Mutable: la prueba puede revocar el permiso tras abrir la vista previa. */
        var permissionGranted = permissionGranted
        /** Mutable: micrófono concedido (3.1.3), espejo del de cámara. */
        var microphoneGranted = true
        val handle = FakeHandle()
        val provider = SurfaceProducerProvider { handle }
        var session: FakeSession = FakeSession(testConfig)
        val factory: PreviewSessionFactory = { session }
        val events = mutableListOf<Map<String, Any?>>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val recording = FakeRecordingSession(scope)
        val fgs = FakeForegroundService()
        /** Ganchos capturadores (J3-c): por defecto los del fixture. */
        val hooks = CapturingTestHooks()
        /** Ejecutor de fusión enlatado (J1-b); `null` = sin fusión. */
        var mergeRunner: (
            suspend (
                String,
                ((MergeProgress) -> Unit)?,
            ) -> MergeResult
        )? = null

        fun engine(
            useRecording: Boolean = true,
            useFgs: Boolean = true,
            testHooks: TestHooks = hooks,
        ): StreamEngineImpl {
            return StreamEngineImpl(
                cameraPermissionGranted = { permissionGranted },
                microphonePermissionGranted = { microphoneGranted },
                surfaceProvider = provider,
                sessionFactory = factory,
                displayRotationDegrees = { displayRotation },
                scope = scope,
                mainDispatcher = Dispatchers.Unconfined,
                onEvent = { events.add(it) },
                recordingSessionFactory = { emit ->
                    if (useRecording) recording else null
                },
                foregroundService = if (useFgs) fgs else null,
                testHooks = testHooks,
                mergeRunner = mergeRunner,
            )
        }

        fun eventTypes(): List<String> {
            return events.mapNotNull { it["type"] as? String }
        }

        /** Eventos `service_stopped` recibidos, en orden (J3-b). */
        fun serviceStoppedEvents(): List<Map<String, Any?>> {
            return events.filter {
                it["type"] == StreamContract.EVENT_SERVICE_STOPPED
            }
        }

        /** Resultado de parada con un segmento `ready` de 12 s. */
        fun readyStopResult(): RecordingResult {
            return RecordingResult.fromSegments(
                recordingId = "rec_20261118_120000_ab12",
                segments = listOf(
                    RecordingSegmentResult(
                        recordingId = "rec_20261118_120000_ab12",
                        index = 0,
                        fileName = "rec_20261118_120000_ab12_seg000.mp4",
                        filePath = "/docs/recordings/" +
                            "rec_20261118_120000_ab12_seg000.mp4",
                        status = SegmentStatus.READY,
                        durationMs = 12_000,
                        sizeBytes = 3_750_000,
                        sampleCount = 360,
                        keyframeCount = 6,
                        firstSampleKeyframe = true,
                        ptsMonotonic = true,
                        profileIdc = 66,
                        closedCleanly = true,
                        reason = null,
                    ),
                ),
                errorCode = null,
                previewRestoreError = null,
            )
        }
    }

    @Test
    fun `startRecording sin vista previa devuelve camera_unavailable y no toca el FGS`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()

            try {
                engine.startRecording(RecordingOptions())
                fail("Se esperaba CameraException")
            } catch (e: CameraException) {
                assertEquals(StreamContract.CAMERA_UNAVAILABLE, e.code)
                assertEquals(
                    "Inicia la vista previa antes de grabar.",
                    e.message,
                )
            }
            assertEquals(0, fixture.fgs.startCalls)
            assertEquals(0, fixture.recording.startCalls)
        }

    @Test
    fun `startRecording sin permiso devuelve permission_denied y no toca el FGS`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()
            engine.startPreview()
            // El permiso se revoca DESPUÉS de abrir la vista previa
            // (es el caso real: la cámara ya está abierta y el usuario
            // revoca el permiso en ajustes).
            fixture.permissionGranted = false

            try {
                engine.startRecording(RecordingOptions())
                fail("Se esperaba CameraException")
            } catch (e: CameraException) {
                assertEquals(StreamContract.PERMISSION_DENIED, e.code)
            }
            assertEquals(0, fixture.fgs.startCalls)
            assertEquals(0, fixture.recording.startCalls)
        }

    @Test
    fun `startRecording con vista previa arranca el FGS y la sesion con la misma configuracion`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()
            val preview = engine.startPreview()
            assertEquals(1280, preview.width)

            val id = engine.startRecording(
                RecordingOptions(matchId = 7L),
            )

            assertEquals("rec_20261118_120000_ab12", id)
            assertEquals(1, fixture.fgs.startCalls)
            assertEquals(0, fixture.fgs.stopCalls)
            assertEquals(1, fixture.recording.startCalls)
            assertEquals(testConfig, fixture.recording.lastStartConfig)
            assertEquals(90, fixture.recording.lastStartRotation)
            assertEquals("7", fixture.recording.lastStartMatchId)
            assertTrue(
                fixture.eventTypes().contains(
                    StreamContract.EVENT_SERVICE_STARTED,
                ),
            )
        }

    @Test
    fun `startRecording con microfono y audioEnabled usa WITH_AUDIO y pasa audio a la sesion`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()
            engine.startPreview()

            val id = engine.startRecording(
                RecordingOptions(audioEnabled = true),
            )

            assertEquals("rec_20261118_120000_ab12", id)
            assertEquals(1, fixture.fgs.startCalls)
            assertEquals(RecordingScenario.WITH_AUDIO, fixture.fgs.lastScenario)
            assertEquals(1, fixture.recording.startCalls)
            assertEquals(true, fixture.recording.lastStartAudioEnabled)
        }

    @Test
    fun `startRecording sin microfono usa WITHOUT_AUDIO y la sesion arranca video-only`() =
        runBlocking {
            val fixture = Fixture()
            // El micrófono se revoca DESPUÉS de abrir la vista previa
            // (mismo caso real que el permiso de cámara).
            fixture.microphoneGranted = false
            val engine = fixture.engine()
            engine.startPreview()

            val id = engine.startRecording(
                RecordingOptions(audioEnabled = true),
            )

            // La grabación arranca igualmente (D9): el audio nunca
            // hace fallar el inicio; solo cambia el escenario y el
            // audio efectivo que recibe la sesión.
            assertEquals("rec_20261118_120000_ab12", id)
            assertEquals(1, fixture.fgs.startCalls)
            assertEquals(
                RecordingScenario.WITHOUT_AUDIO,
                fixture.fgs.lastScenario,
            )
            assertEquals(1, fixture.recording.startCalls)
            assertEquals(false, fixture.recording.lastStartAudioEnabled)
        }

    @Test
    fun `startRecording con audioEnabled false usa WITHOUT_AUDIO aunque haya microfono`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()
            engine.startPreview()

            engine.startRecording(RecordingOptions(audioEnabled = false))

            assertEquals(1, fixture.fgs.startCalls)
            assertEquals(
                RecordingScenario.WITHOUT_AUDIO,
                fixture.fgs.lastScenario,
            )
            assertEquals(false, fixture.recording.lastStartAudioEnabled)
        }

    @Test
    fun `si el FGS no arranca no se graba nada`() = runBlocking {
        val fixture = Fixture()
        fixture.fgs.startResult = false
        val engine = fixture.engine()
        engine.startPreview()

        try {
            engine.startRecording(RecordingOptions())
            fail("Se esperaba CameraException")
        } catch (e: CameraException) {
            assertEquals(StreamContract.PERMISSION_DENIED, e.code)
        }
        assertEquals(0, fixture.recording.startCalls)
        assertEquals(0, fixture.fgs.stopCalls)
    }

    @Test
    fun `si la sesion falla el FGS se detiene sin evento service_stopped`() =
        runBlocking {
        val fixture = Fixture()
        fixture.recording.startError = RecordingException.encoderError(
            "Sin codificador H.264.",
            mapOf("stage" to "configure"),
        )
        val engine = fixture.engine()
        engine.startPreview()

        try {
            engine.startRecording(RecordingOptions())
            fail("Se esperaba RecordingException")
        } catch (e: RecordingException) {
            assertEquals(StreamContract.ENCODER_ERROR, e.code)
        }
        assertEquals(1, fixture.fgs.startCalls)
        assertEquals(1, fixture.fgs.stopCalls)
        // J3-b: sin evento `service_stopped` (la grabación nunca
        // arrancó y Dart nunca vio `service_started`).
        assertFalse(
            fixture.eventTypes().contains(
                StreamContract.EVENT_SERVICE_STOPPED,
            ),
        )
    }

    @Test
    fun `una segunda grabacion se rechaza sin tocar el FGS`() = runBlocking {
        val fixture = Fixture()
        val engine = fixture.engine()
        engine.startPreview()
        engine.startRecording(RecordingOptions())
        assertEquals(1, fixture.fgs.startCalls)

        try {
            engine.startRecording(RecordingOptions())
            fail("Se esperaba RecordingException")
        } catch (e: RecordingException) {
            assertEquals(StreamContract.RECORDING_STATE_ERROR, e.code)
        }
        // La grabación activa sigue con su FGS: ni se vuelve a
        // arrancar ni se detiene por el intento duplicado, y el
        // escenario no se re-decide.
        assertEquals(1, fixture.fgs.startCalls)
        assertEquals(0, fixture.fgs.stopCalls)
        assertEquals(
            RecordingScenario.WITH_AUDIO,
            fixture.fgs.lastScenario,
        )
        assertEquals(1, fixture.recording.startCalls)
    }

    @Test
    fun `stopRecording detiene la sesion y el FGS y devuelve el resultado`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.stopResult = fixture.readyStopResult()
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            val info = engine.stopRecording()

            assertEquals("rec_20261118_120000_ab12", info.recordingId)
            assertEquals(
                "/docs/recordings/rec_20261118_120000_ab12_seg000.mp4",
                info.filePath,
            )
            assertEquals(12_000, info.durationMs)
            assertEquals(3_750_000, info.sizeBytes)
            assertEquals("ready", info.status)
            // El método devuelve la lista completa de segmentos
            // (paso 5): la UI no depende de los eventos recibidos.
            assertEquals(1, info.segments.size)
            assertEquals(0, info.segments.first().index)
            assertEquals(12_000L, info.segments.first().durationMs)
            assertEquals(
                SegmentStatus.READY,
                info.segments.first().status,
            )
            assertEquals(1, fixture.recording.stopCalls)
            assertEquals(1, fixture.fgs.stopCalls)
            // J3-b: un solo `service_stopped`, con motivo normal_stop.
            val stopped = fixture.serviceStoppedEvents()
            assertEquals(1, stopped.size)
            assertEquals(
                "rec_20261118_120000_ab12",
                stopped[0]["recordingId"],
            )
            assertEquals("normal_stop", stopped[0]["reason"])
        }

    @Test
    fun `stopRecording lleva los contadores de audio al resultado del metodo`() =
        runBlocking {
            // Grupo V (V1, camino real): el resultado del método
            // debe llevar los mismos 11 campos de instrumentación
            // de audio que el evento `recording_stopped`. Si el
            // motor soltara uno, Dart sobrescribiría el valor real
            // del evento con el defecto (diagnóstico ronda 3, §2):
            // este test rompe antes que el teléfono.
            val fixture = Fixture()
            fixture.recording.stopResult =
                RecordingResult.fromSegments(
                    recordingId = "rec_20261118_120000_ab12",
                    segments = listOf(
                        RecordingSegmentResult(
                            recordingId = "rec_20261118_120000_ab12",
                            index = 0,
                            fileName = "rec_20261118_120000_ab12_seg000.mp4",
                            filePath = "/docs/recordings/" +
                                "rec_20261118_120000_ab12_seg000.mp4",
                            status = SegmentStatus.READY,
                            durationMs = 12_000,
                            sizeBytes = 3_750_000,
                            sampleCount = 360,
                            keyframeCount = 6,
                            firstSampleKeyframe = true,
                            ptsMonotonic = true,
                            profileIdc = 66,
                            closedCleanly = true,
                            reason = null,
                        ),
                    ),
                    errorCode = null,
                    previewRestoreError = null,
                    droppedInputBuffers = 7,
                    inputBufferCapacityBytes = 2048,
                    micStateAtStart = 3,
                    micStateAtStop = 1,
                    audioReadCount = 42,
                    audioLastReadCode = 4096,
                    audioLastReadOkAgeMs = 12_345L,
                    audioReaderAlive = true,
                    audioEos = true,
                    audioReaderNoData2s = true,
                    eosTimedOut = true,
                    quietestSecondPeak = 12,
                    quietestSecondNonZero = 3,
                    silencePeak = 2,
                )
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            val info = engine.stopRecording()

            assertEquals(7, info.droppedInputBuffers)
            assertEquals(2048, info.inputBufferCapacityBytes)
            assertEquals(3, info.micStateAtStart)
            assertEquals(1, info.micStateAtStop)
            assertEquals(42, info.audioReadCount)
            assertEquals(4096, info.audioLastReadCode)
            assertEquals(12_345L, info.audioLastReadOkAgeMs)
            assertEquals(true, info.audioReaderAlive)
            assertEquals(true, info.audioEos)
            assertEquals(true, info.audioReaderNoData2s)
            assertEquals(true, info.eosTimedOut)
            assertEquals(12, info.quietestSecondPeak)
            assertEquals(3, info.quietestSecondNonZero)
            assertEquals(2, info.silencePeak)
        }

    @Test
    fun `stopRecording desde IDLE es idempotente`() = runBlocking {
        val fixture = Fixture()
        val engine = fixture.engine()

        val info = engine.stopRecording()

        assertEquals("", info.recordingId)
        assertEquals("idle", info.status)
        assertEquals(0, info.segments.size)
        assertEquals(1, fixture.recording.stopCalls)
    }

    @Test
    fun `stopPreview detiene primero la grabacion y despues la vista previa`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.stopResult = fixture.readyStopResult()
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            engine.stopPreview()

            assertEquals(1, fixture.recording.stopCalls)
            assertEquals(1, fixture.session.closeCalls)
            assertEquals(1, fixture.fgs.stopCalls)
            assertFalse(fixture.session.isOpen)
            // J3-b: este camino no emite `service_stopped`
            // (comportamiento anterior conservado).
            assertFalse(
                fixture.eventTypes().contains(
                    StreamContract.EVENT_SERVICE_STOPPED,
                ),
            )
        }

    @Test
    fun `la desconexion de la camara detiene la grabacion con camera_disconnected`() =
        runBlocking {
            val fixture = Fixture()
            // J3-b: la sesión real avisa al motor al completar el
            // `stop(errorCode)`; el falso lo reproduce con el flag.
            fixture.recording.invokeErrorStopHandler = true
            fixture.recording.stopResult = fixture.readyStopResult()
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            fixture.session.fireDisconnected()

            assertEquals(1, fixture.recording.stopCalls)
            assertEquals(
                StreamContract.CAMERA_DISCONNECTED,
                fixture.recording.lastStopErrorCode,
            )
            assertEquals(1, fixture.fgs.stopCalls)
            // J3-b: el aviso de error-stop ya emitió
            // `service_stopped` (error_stop); el `finally` de
            // `stopRecordingIfActive` no lo duplica.
            val stopped = fixture.serviceStoppedEvents()
            assertEquals(1, stopped.size)
            assertEquals("error_stop", stopped[0]["reason"])
            assertTrue(
                fixture.eventTypes().contains(
                    StreamContract.EVENT_PREVIEW_ERROR,
                ),
            )
        }

    // J3-b (3.1.4): parada del FGS por error-stop.

    @Test
    fun `el error-stop de la sesion detiene el FGS una sola vez y emite service_stopped con motivo error_stop`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.invokeErrorStopHandler = true
            fixture.recording.stopResult = fixture.readyStopResult()
                .copy(errorCode = StreamContract.ENCODER_ERROR)
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            // La sesión completó un `stop(errorCode)` (en producción
            // lo dispara `scheduleErrorStop` al final de `doStop`).
            fixture.recording.stop(StreamContract.ENCODER_ERROR)

            assertEquals(1, fixture.fgs.stopCalls)
            val stopped = fixture.serviceStoppedEvents()
            assertEquals(1, stopped.size)
            assertEquals(
                "rec_20261118_120000_ab12",
                stopped[0]["recordingId"],
            )
            assertEquals("error_stop", stopped[0]["reason"])
        }

    @Test
    fun `doble error-stop no duplica la parada del FGS`() = runBlocking {
        val fixture = Fixture()
        fixture.recording.invokeErrorStopHandler = true
        fixture.recording.stopResult = fixture.readyStopResult()
            .copy(errorCode = StreamContract.ENCODER_ERROR)
        val engine = fixture.engine()
        engine.startPreview()
        engine.startRecording(RecordingOptions())

        // Dos avisos de error-stop (en producción el segundo
        // `stop()` es perdedor y ni siquiera avisa: aquí se fuerza
        // el aviso dos veces para probar la guarda del motor).
        fixture.recording.stop(StreamContract.ENCODER_ERROR)
        fixture.recording.stop(StreamContract.ENCODER_ERROR)

        assertEquals(1, fixture.fgs.stopCalls)
        assertEquals(1, fixture.serviceStoppedEvents().size)
    }

    @Test
    fun `stopRecording tras un error-stop no emite service_stopped duplicado`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.invokeErrorStopHandler = true
            fixture.recording.stopResult = fixture.readyStopResult()
                .copy(errorCode = StreamContract.ENCODER_ERROR)
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            // Gana el error-stop; la parada normal llega después.
            fixture.recording.stop(StreamContract.ENCODER_ERROR)
            engine.stopRecording()

            assertEquals(2, fixture.recording.stopCalls)
            assertEquals(1, fixture.fgs.stopCalls)
            val stopped = fixture.serviceStoppedEvents()
            assertEquals(1, stopped.size)
            assertEquals("error_stop", stopped[0]["reason"])
        }

    @Test
    fun `un error-stop tardio de una grabacion anterior no detiene el FGS de la grabacion nueva`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.invokeErrorStopHandler = true
            fixture.recording.stopResult = fixture.readyStopResult()
                .copy(errorCode = StreamContract.ENCODER_ERROR)
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            fixture.recording.stop(StreamContract.ENCODER_ERROR)
            assertEquals(1, fixture.fgs.stopCalls)

            // Segunda grabación con id nuevo: la guarda se resetea
            // en su `startRecording`.
            fixture.recording.startedInfo =
                fixture.recording.startedInfo.copy(
                    recordingId = "rec_20261118_120001_cd34",
                )
            val secondId = engine.startRecording(RecordingOptions())
            assertEquals("rec_20261118_120001_cd34", secondId)
            assertEquals(2, fixture.fgs.startCalls)

            // Aviso tardío de la grabación anterior (A): debe
            // ignorarse; el FGS de B sigue vivo.
            fixture.recording.onSessionErrorStop?.invoke(
                StreamContract.ENCODER_ERROR,
                null,
                "rec_20261118_120000_ab12",
            )

            assertEquals(1, fixture.fgs.stopCalls)
            assertEquals(1, fixture.serviceStoppedEvents().size)
        }

    // J3-c (3.1.4): instrumentación `FGS_STOPPED` y hook de
    // inyección de error (plan §1.3 paso 6).

    @Test
    fun `J3-c el error-stop emite FGS_STOPPED con origen error_stop una sola vez`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.invokeErrorStopHandler = true
            fixture.recording.stopResult = fixture.readyStopResult()
                .copy(errorCode = StreamContract.ENCODER_ERROR)
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            fixture.recording.stop(StreamContract.ENCODER_ERROR)

            assertEquals(1, fixture.fgs.stopCalls)
            val stopped = fixture.hooks.ofType(TestEventType.FGS_STOPPED)
            assertEquals(1, stopped.size)
            assertEquals("error_stop", stopped[0].detail["origin"])
            assertEquals("error_stop", stopped[0].detail["reason"])
            assertEquals(
                "rec_20261118_120000_ab12",
                stopped[0].recordingId,
            )
            // El `service_stopped` de siempre sigue saliendo igual.
            val serviceStopped = fixture.serviceStoppedEvents()
            assertEquals(1, serviceStopped.size)
            assertEquals("error_stop", serviceStopped[0]["reason"])
        }

    @Test
    fun `J3-c la parada normal emite FGS_STOPPED con origen normal_stop`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.stopResult = fixture.readyStopResult()
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            engine.stopRecording()

            assertEquals(1, fixture.fgs.stopCalls)
            val stopped = fixture.hooks.ofType(TestEventType.FGS_STOPPED)
            assertEquals(1, stopped.size)
            assertEquals("normal_stop", stopped[0].detail["origin"])
            assertEquals("normal_stop", stopped[0].detail["reason"])
            assertEquals(
                "rec_20261118_120000_ab12",
                stopped[0].recordingId,
            )
        }

    @Test
    fun `J3-c la parada desde la notificacion emite FGS_STOPPED con origen notification`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.stopResult = fixture.readyStopResult()
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            engine.stopRecordingFromNotification()

            assertEquals(1, fixture.fgs.stopCalls)
            val stopped = fixture.hooks.ofType(TestEventType.FGS_STOPPED)
            assertEquals(1, stopped.size)
            assertEquals("notification", stopped[0].detail["origin"])
            // El `service_stopped` de siempre sigue con
            // `reason=normal_stop`: la notificación es una parada
            // normal; solo la instrumentación distingue el origen.
            val serviceStopped = fixture.serviceStoppedEvents()
            assertEquals(1, serviceStopped.size)
            assertEquals("normal_stop", serviceStopped[0]["reason"])
        }

    @Test
    fun `J3-c la desconexion de camara sin aviso emite FGS_STOPPED camera_error`() =
        runBlocking {
            val fixture = Fixture()
            // Sin aviso de error-stop de la sesión (la parada con
            // error la cierra `stopRecordingIfActive`): es el caso
            // en que la sesión ya estaba en ERROR con otro código.
            fixture.recording.invokeErrorStopHandler = false
            fixture.recording.stopResult = fixture.readyStopResult()
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            fixture.session.fireDisconnected()

            assertEquals(1, fixture.recording.stopCalls)
            assertEquals(1, fixture.fgs.stopCalls)
            val stopped = fixture.hooks.ofType(TestEventType.FGS_STOPPED)
            assertEquals(1, stopped.size)
            assertEquals("camera_error", stopped[0].detail["origin"])
            assertFalse(stopped[0].detail.containsKey("reason"))
            // Sin `service_stopped`: la red de seguridad conserva su
            // comportamiento anterior.
            assertTrue(fixture.serviceStoppedEvents().isEmpty())
        }

    @Test
    fun `J3-c el inicio fallido emite FGS_STOPPED con origen start_failed y sin recordingId`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.startError = RecordingException.encoderError(
                "Sin codificador H.264.",
                mapOf("stage" to "configure"),
            )
            val engine = fixture.engine()
            engine.startPreview()

            try {
                engine.startRecording(RecordingOptions())
                fail("Se esperaba RecordingException")
            } catch (e: RecordingException) {
                assertEquals(StreamContract.ENCODER_ERROR, e.code)
            }
            assertEquals(1, fixture.fgs.stopCalls)
            val stopped = fixture.hooks.ofType(TestEventType.FGS_STOPPED)
            assertEquals(1, stopped.size)
            assertEquals("start_failed", stopped[0].detail["origin"])
            assertNull(stopped[0].recordingId)
            assertFalse(
                fixture.eventTypes().contains(
                    StreamContract.EVENT_SERVICE_STOPPED,
                ),
            )
        }

    @Test
    fun `J3-c doble error-stop emite un solo FGS_STOPPED`() = runBlocking {
        val fixture = Fixture()
        fixture.recording.invokeErrorStopHandler = true
        fixture.recording.stopResult = fixture.readyStopResult()
            .copy(errorCode = StreamContract.ENCODER_ERROR)
        val engine = fixture.engine()
        engine.startPreview()
        engine.startRecording(RecordingOptions())

        fixture.recording.stop(StreamContract.ENCODER_ERROR)
        fixture.recording.stop(StreamContract.ENCODER_ERROR)

        assertEquals(1, fixture.fgs.stopCalls)
        assertEquals(
            1,
            fixture.hooks.ofType(TestEventType.FGS_STOPPED).size,
        )
    }

    @Test
    fun `J3-c testInjectError delega en la sesion y responde el mapa`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()
            // Sin motor materializado: no hay nada que inyectar y no
            // se materializa la sesión por la consulta.
            val beforeStart = engine.testInjectError("encoder_error")
            assertEquals(false, beforeStart["injected"])
            assertEquals("engine_not_started", beforeStart["reason"])
            assertTrue(fixture.recording.injectedKinds.isEmpty())

            engine.startPreview()
            engine.startRecording(RecordingOptions())
            val injected = engine.testInjectError("encoder_error")
            assertEquals(true, injected["injected"])
            assertEquals("encoder_error", injected["kind"])
            assertNull(injected["reason"])
            assertEquals(listOf("encoder_error"), fixture.recording.injectedKinds)

            fixture.recording.injectResult = false
            val notInjected = engine.testInjectError("drain_write_error")
            assertEquals(false, notInjected["injected"])
            assertEquals("not_injected", notInjected["reason"])
            assertEquals(
                listOf("encoder_error", "drain_write_error"),
                fixture.recording.injectedKinds,
            )
        }

    @Test
    fun `la limpieza de superficie detiene la grabacion y deja recuperar la vista previa`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.stopResult = fixture.readyStopResult()
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            fixture.handle.lastCallback?.onSurfaceCleanup()

            assertEquals(1, fixture.recording.stopCalls)
            assertEquals(1, fixture.fgs.stopCalls)
            assertTrue(
                fixture.eventTypes().contains(
                    StreamContract.EVENT_PREVIEW_SURFACE_LOST,
                ),
            )
        }

    @Test
    fun `getRecordingStatus devuelve la instantanea de la sesion`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            val snapshot = engine.recordingStatus()

            assertEquals("recording", snapshot.state)
            assertEquals("rec_20261118_120000_ab12", snapshot.recordingId)
            assertEquals(12_000, snapshot.elapsedMs)
            assertEquals(1280, snapshot.width)
            assertEquals(720, snapshot.height)
            assertEquals(30, snapshot.fps)
            assertEquals(0, snapshot.segmentIndex)

            val map = engine.recordingStatusMap()
            assertEquals("recording", map["state"])
            assertEquals("rec_20261118_120000_ab12", map["recordingId"])
        }

    @Test
    fun `recordingStatusMap incluye las claves de audio del snapshot`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions(audioEnabled = true))

            val map = engine.recordingStatusMap()

            assertEquals("recording", map["state"])
            assertEquals(true, map["audioEnabled"])
            assertEquals(true, map["audioActive"])
            assertEquals(null, map["audioReason"])
        }

    @Test
    fun `sin sesion getRecordingStatus es idle`() {
        val fixture = Fixture()
        val engine = fixture.engine(useRecording = false, useFgs = false)

        val snapshot = engine.recordingStatus()

        assertEquals("idle", snapshot.state)
        assertEquals(null, snapshot.recordingId)
        assertFalse(engine.foregroundServiceAvailable())
    }

    @Test
    fun `foregroundServiceAvailable es true con sesion y control`() {
        val fixture = Fixture()
        val engine = fixture.engine()

        assertTrue(engine.foregroundServiceAvailable())
    }

    @Test
    fun `foregroundServiceAvailable es false sin control del servicio`() {
        val fixture = Fixture()
        val engine = fixture.engine(useFgs = false)

        assertFalse(engine.foregroundServiceAvailable())
    }

    @Test
    fun `reconciliar interrupciones delega en la sesion y expone el pendiente por el canal`() =
        runBlocking {
        val fixture = Fixture()
        fixture.recording.reconcileResult = ReconciliationSummary(
            runId = "run-1",
            startedAtMs = 1_000L,
            finishedAtMs = 1_100L,
            groupsTotal = 1,
            groupsReconciled = 1,
            groupsSkippedActive = emptyList(),
            groupsFailed = emptyList(),
            segments = listOf(
                ReconciledSegmentOutcome(
                    recordingId = "rec_20261118_120000_ab12",
                    index = 0,
                    fileName = "rec_20261118_120000_ab12_seg000.mp4",
                    filePath = "/docs/recordings/" +
                        "rec_20261118_120000_ab12_seg000.mp4",
                    status = SegmentStatus.PARTIAL,
                    case = ReconciliationCase.C2_TRUSTED_STAGING,
                    durationMs = 4_400,
                    sizeBytes = 1_600_000,
                    reason = null,
                    verified = false,
                    trusted = true,
                    renamed = true,
                    wasStaging = true,
                ),
            ),
            segmentsTrusted = 1,
            segmentsVerified = 0,
            segmentsRenamed = 1,
            segmentsPending = emptyList(),
            segmentsQuarantined = 0,
            unknownFiles = emptyList(),
            missingFiles = emptyList(),
            orphanWriteTestFilesSwept = 0,
            reservationTokens = mapOf(
                "rec_20261118_120000_ab12" to 1L,
            ),
            foreignReleaseRejected = 0,
            conflicts = 0,
        )
        val engine = fixture.engine()

        val summary = engine.reconcileRecordings()

        assertEquals(1, fixture.recording.reconcileCalls)
        assertEquals(1, summary.segments.size)
        assertEquals(SegmentStatus.PARTIAL, summary.segments[0].status)
        val map = engine.reconciledSegmentMap(summary.segments[0])
        assertEquals("partial", map["status"])
        assertEquals(4_400L, map["durationMs"])
        // El resumen por grupo viaja en el payload del canal.
        val resultMap = engine.reconciliationResultMap(summary)
        assertEquals("DONE", resultMap["state"])
    }

    // --- J1-b (3.1.4): orquestación stop → fusión asíncrona --------

    /** Eventos `recording_merged` recibidos, en orden. */
    private fun Fixture.mergedEvents(): List<Map<String, Any?>> {
        return events.filter {
            it["type"] == StreamContract.EVENT_RECORDING_MERGED
        }
    }

    private fun successfulMergeResult(): MergeResult = MergeResult(
        success = true,
        reason = null,
        detailReason = null,
        recordingId = "rec_20261118_120000_ab12",
        finalFile = File(
            "/docs/recordings/rec_20261118_120000_ab12_final.mp4",
        ),
        status = SegmentStatus.READY,
        segmentCount = 1,
        segmentIndices = listOf(0),
        audioGapSegments = emptyList(),
        partialSegments = emptyList(),
        affectedIndices = emptyList(),
        videoSampleCount = 360,
        audioSampleCount = 2800,
        totalVideoDurationMs = 12_000,
        totalAudioDurationMs = 11_987,
        mergedDurationMs = 11_987,
        mergedSizeBytes = 3_740_000,
        mergedAvOffsetMs = 103,
        mergedAvDurationDiffMs = 13,
        avOffsetDriftMsBySegment = mapOf(0 to 0L),
        alreadyMerged = false,
        duplicateResolution = null,
    )

    private fun failedMergeResult(reason: String): MergeResult = MergeResult(
        success = false,
        reason = reason,
        detailReason = null,
        recordingId = "rec_20261118_120000_ab12",
        finalFile = null,
        status = null,
        segmentCount = 0,
        segmentIndices = emptyList(),
        audioGapSegments = emptyList(),
        partialSegments = emptyList(),
        affectedIndices = listOf(0),
        videoSampleCount = 0,
        audioSampleCount = 0,
        totalVideoDurationMs = null,
        totalAudioDurationMs = null,
        mergedDurationMs = null,
        mergedSizeBytes = null,
        mergedAvOffsetMs = null,
        mergedAvDurationDiffMs = null,
        avOffsetDriftMsBySegment = emptyMap(),
        alreadyMerged = false,
        duplicateResolution = null,
    )

    @Test
    fun `stopRecording con fusion asincrona mantiene el FGS vivo con progreso y lo detiene al terminar`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.stopResult = fixture.readyStopResult()
            val gate = CompletableDeferred<MergeResult>()
            var runnerCalls = 0
            fixture.mergeRunner = { _, onProgress ->
                runnerCalls++
                onProgress?.invoke(
                    MergeProgress(MergeStage.VALIDATING, 0, 0),
                )
                onProgress?.invoke(
                    MergeProgress(MergeStage.COPYING, 0, 1),
                )
                gate.await()
            }
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            // El stop es rápido: vuelve sin esperar a la fusión.
            val info = engine.stopRecording()
            assertEquals("ready", info.status)
            assertEquals(1, runnerCalls)
            // FGS vivo durante la fusión: sin parada ni
            // `service_stopped`, con progreso en la notificación.
            assertEquals(0, fixture.fgs.stopCalls)
            assertEquals(0, fixture.serviceStoppedEvents().size)
            assertEquals(0, fixture.mergedEvents().size)
            assertTrue(fixture.fgs.mergeProgressTexts.isNotEmpty())
            assertTrue(
                fixture.fgs.mergeProgressTexts.any {
                    it.contains("segmento 0 de 1")
                },
            )

            // La fusión termina: evento con las medidas y parada del
            // FGS (una sola vez, con motivo normal_stop).
            gate.complete(successfulMergeResult())
            val merged = fixture.mergedEvents()
            assertEquals(1, merged.size)
            assertEquals(
                "rec_20261118_120000_ab12",
                merged[0]["recordingId"],
            )
            assertEquals(
                "/docs/recordings/rec_20261118_120000_ab12_final.mp4",
                merged[0]["filePath"],
            )
            assertEquals("ready", merged[0]["status"])
            assertEquals(11_987, merged[0]["totalDurationMs"])
            assertEquals(3_740_000, merged[0]["totalSizeBytes"])
            assertEquals(1, merged[0]["segmentCount"])
            assertEquals(1, fixture.fgs.stopCalls)
            val stopped = fixture.serviceStoppedEvents()
            assertEquals(1, stopped.size)
            assertEquals("normal_stop", stopped[0]["reason"])
        }

    @Test
    fun `stopRecording sin segmentos utilizables no fusiona y detiene el FGS como hoy`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.stopResult = RecordingResult.fromSegments(
                recordingId = "rec_20261118_120000_ab12",
                segments = listOf(
                    RecordingSegmentResult(
                        recordingId = "rec_20261118_120000_ab12",
                        index = 0,
                        fileName = "rec_20261118_120000_ab12_seg000.mp4",
                        filePath = "/docs/recordings/" +
                            "rec_20261118_120000_ab12_seg000.mp4",
                        status = SegmentStatus.CORRUPT,
                        durationMs = null,
                        sizeBytes = 0,
                        sampleCount = 0,
                        keyframeCount = 0,
                        firstSampleKeyframe = false,
                        ptsMonotonic = false,
                        profileIdc = null,
                        closedCleanly = false,
                        reason = "sin_muestras",
                    ),
                ),
                errorCode = null,
                previewRestoreError = null,
            )
            var runnerCalls = 0
            fixture.mergeRunner = { _, _ ->
                runnerCalls++
                successfulMergeResult()
            }
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            val info = engine.stopRecording()

            assertEquals("corrupt", info.status)
            assertEquals(0, runnerCalls)
            assertEquals(0, fixture.mergedEvents().size)
            assertEquals(1, fixture.fgs.stopCalls)
        }

    @Test
    fun `stopRecording con fusion fallida definitiva emite recording_merged corrupt y detiene el FGS`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.stopResult = fixture.readyStopResult()
            fixture.mergeRunner = { _, _ ->
                failedMergeResult("merge_verify_failed")
            }
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            engine.stopRecording()

            val merged = fixture.mergedEvents()
            assertEquals(1, merged.size)
            assertEquals("corrupt", merged[0]["status"])
            assertEquals("merge_verify_failed", merged[0]["reason"])
            assertNull(merged[0]["filePath"])
            assertEquals(listOf(0), merged[0]["skippedSegments"])
            assertEquals(1, fixture.fgs.stopCalls)
            assertEquals(1, fixture.serviceStoppedEvents().size)
        }

    @Test
    fun `stopRecording con fusion reintentable emite recording_merged finalizing y detiene el FGS`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.stopResult = fixture.readyStopResult()
            fixture.mergeRunner = { _, _ ->
                failedMergeResult("storage_full")
            }
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            engine.stopRecording()

            val merged = fixture.mergedEvents()
            assertEquals(1, merged.size)
            assertEquals("finalizing", merged[0]["status"])
            assertEquals("storage_full", merged[0]["reason"])
            assertEquals(1, fixture.fgs.stopCalls)
        }

    @Test
    fun `stopRecording dos veces no dispara la fusion dos veces`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.stopResult = fixture.readyStopResult()
            val gate = CompletableDeferred<MergeResult>()
            var runnerCalls = 0
            fixture.mergeRunner = { _, _ ->
                runnerCalls++
                gate.await()
            }
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            engine.stopRecording()
            // Segundo stop: la sesión devuelve el mismo resultado
            // (cacheado) pero la fusión no se re-dispara.
            engine.stopRecording()

            assertEquals(1, runnerCalls)
            assertEquals(0, fixture.fgs.stopCalls)
            assertEquals(0, fixture.mergedEvents().size)

            gate.complete(successfulMergeResult())
            assertEquals(1, fixture.mergedEvents().size)
            assertEquals(1, fixture.fgs.stopCalls)
            assertEquals(1, fixture.serviceStoppedEvents().size)
        }

    @Test
    fun `stopPreview con fusion pendiente mantiene el FGS vivo hasta recording_merged`() =
        runBlocking {
            val fixture = Fixture()
            fixture.recording.stopResult = fixture.readyStopResult()
            val gate = CompletableDeferred<MergeResult>()
            fixture.mergeRunner = { _, _ -> gate.await() }
            val engine = fixture.engine()
            engine.startPreview()
            engine.startRecording(RecordingOptions())

            engine.stopPreview()

            // Parada externa: la fusión arrancó y el FGS sigue vivo.
            assertEquals(0, fixture.fgs.stopCalls)
            assertEquals(0, fixture.mergedEvents().size)

            gate.complete(successfulMergeResult())
            assertEquals(1, fixture.mergedEvents().size)
            assertEquals("ready", fixture.mergedEvents()[0]["status"])
            assertEquals(1, fixture.fgs.stopCalls)
            // Este camino no emite `service_stopped` (como la parada
            // directa de `stopRecordingIfActive`): solo se detiene
            // el servicio.
            assertEquals(0, fixture.serviceStoppedEvents().size)
        }
}
