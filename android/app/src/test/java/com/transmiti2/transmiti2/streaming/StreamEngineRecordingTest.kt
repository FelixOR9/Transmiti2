package com.transmiti2.transmiti2.streaming

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.Surface
import com.transmiti2.transmiti2.streaming.recording.InterruptedSegmentInfo
import com.transmiti2.transmiti2.streaming.recording.RecordingException
import com.transmiti2.transmiti2.streaming.recording.RecordingFileStore
import com.transmiti2.transmiti2.streaming.recording.RecordingResult
import com.transmiti2.transmiti2.streaming.recording.RecordingSegmentResult
import com.transmiti2.transmiti2.streaming.recording.RecordingSession
import com.transmiti2.transmiti2.streaming.recording.RecordingStartedInfo
import com.transmiti2.transmiti2.streaming.recording.RecordingStatusSnapshot
import com.transmiti2.transmiti2.streaming.recording.SegmentStatus
import com.transmiti2.transmiti2.streaming.recording.SegmentVerification
import com.transmiti2.transmiti2.streaming.recording.SegmentVerifier
import com.transmiti2.transmiti2.streaming.recording.VerificationChecks
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        var recoverResult: List<InterruptedSegmentInfo> = emptyList()
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

        override suspend fun stop(errorCode: String?): RecordingResult {
            stopCalls++
            lastStopErrorCode = errorCode
            fakeState = "idle"
            fakeRecordingId = null
            fakeAudioEnabled = false
            fakeAudioActive = false
            fakeAudioReason = null
            return stopResult
        }

        override suspend fun recoverInterrupted(): List<InterruptedSegmentInfo> {
            return recoverResult
        }
    }

    private class FakeForegroundService : ForegroundServiceControl {
        var startCalls = 0
        var stopCalls = 0
        var startResult = true
        /** Último escenario recibido (3.1.3). */
        var lastScenario: RecordingScenario? = null

        override fun startRecordingService(scenario: RecordingScenario): Boolean {
            startCalls++
            lastScenario = scenario
            return startResult
        }

        override fun stopRecordingService() {
            stopCalls++
        }
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

        fun engine(
            useRecording: Boolean = true,
            useFgs: Boolean = true,
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
            )
        }

        fun eventTypes(): List<String> {
            return events.mapNotNull { it["type"] as? String }
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
    fun `si la sesion falla el FGS se detiene`() = runBlocking {
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
            assertTrue(
                fixture.eventTypes().contains(
                    StreamContract.EVENT_SERVICE_STOPPED,
                ),
            )
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
        }

    @Test
    fun `la desconexion de la camara detiene la grabacion con camera_disconnected`() =
        runBlocking {
            val fixture = Fixture()
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
            assertTrue(
                fixture.eventTypes().contains(
                    StreamContract.EVENT_PREVIEW_ERROR,
                ),
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
    fun `recuperar interrupciones delega en la sesion`() = runBlocking {
        val fixture = Fixture()
        fixture.recording.recoverResult = listOf(
            InterruptedSegmentInfo(
                recordingId = "rec_20261118_120000_ab12",
                index = 0,
                fileName = "rec_20261118_120000_ab12_seg000.tmp.mp4",
                filePath = "/docs/recordings/" +
                    "rec_20261118_120000_ab12_seg000.tmp.mp4",
                status = SegmentStatus.PARTIAL,
                durationMs = 4_400,
                sizeBytes = 1_600_000,
                reason = null,
            ),
        )
        val engine = fixture.engine()

        val recovered = engine.recoverInterruptedRecordings()

        assertEquals(1, recovered.size)
        assertEquals(SegmentStatus.PARTIAL, recovered[0].status)
        val map = engine.recoveredSegmentMap(recovered[0])
        assertEquals("partial", map["status"])
        assertEquals(4_400L, map["durationMs"])
    }
}
