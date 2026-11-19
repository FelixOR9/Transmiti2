package com.transmiti2.transmiti2.streaming.recording

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.transmiti2.transmiti2.streaming.CameraException
import com.transmiti2.transmiti2.streaming.FpsRange
import com.transmiti2.transmiti2.streaming.PreviewConfig
import com.transmiti2.transmiti2.streaming.PreviewSession
import com.transmiti2.transmiti2.streaming.PreviewSessionListener
import com.transmiti2.transmiti2.streaming.StreamContract
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import com.transmiti2.transmiti2.streaming.VideoSize
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Sesión de grabación (plan 3.1.2 §6): estados, inicio/parada,
 * rotación, escrituras tardías, interrupciones y recuperación.
 * Encoder, muxer, sesión de cámara y verificador son falsos; el
 * codec, la cámara y ExoPlayer reales se prueban en el teléfono.
 */
class RecordingSessionTest {

    private class FakeEncoder(
        override val config: VideoEncoderConfig,
    ) : VideoEncoderPort {
        // Superficie ficticia no nula: la sesión solo la pasa a
        // `recreateSession` (nunca llama a métodos nativos sobre
        // ella). `Surface()` sin argumento NO existe en la API
        // pública 36 (solo `Surface(SurfaceTexture)` y
        // `Surface(SurfaceControl)`); `SurfaceTexture(0)` es el
        // marcador habitual en unitarias JVM puras. Los
        // constructores son no-op tanto en el jar mockable de
        // Gradle (CI) como en los stubs locales de repro3.
        override val surface: Surface? = Surface(SurfaceTexture(0))
        override var outputFormat: MediaFormat? = null
        override var listener: VideoEncoderListener? = null
        var configureCalls = 0
        var releaseCalls = 0
        var stopDrainCalls = 0
        var syncFrameRequests = 0
        var deliverFormatOnStart = true
        var deliverEosOnSignal = true

        override fun configureAndStart() {
            configureCalls++
            if (deliverFormatOnStart) {
                val format = MediaFormat()
                outputFormat = format
                listener?.onOutputFormatChanged(format)
            }
        }

        override fun requestSyncFrame(): Boolean {
            syncFrameRequests++
            return true
        }

        override fun signalEndOfInputStream() {
            if (deliverEosOnSignal) {
                listener?.onEndOfStream()
            }
        }

        override fun stopDrain() {
            stopDrainCalls++
        }

        override fun post(block: () -> Unit) {
            block()
        }

        override fun release() {
            releaseCalls++
        }

        fun deliverSample(ptsUs: Long, keyframe: Boolean) {
            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = 10
                presentationTimeUs = ptsUs
                flags = if (keyframe) {
                    MediaCodec.BUFFER_FLAG_SYNC_FRAME
                } else {
                    0
                }
            }
            listener?.onSample(ByteBuffer.allocate(10), info)
        }

        fun deliverError() {
            listener?.onError(IllegalStateException("codec roto"))
        }
    }

    private class FakeSessionMuxer(
        val path: String,
    ) : RecordingMuxer {
        override val name: String = "fake"
        private val guard = MuxerThreadGuard()
        val calls = mutableListOf<String>()
        val writtenPts = mutableListOf<Long>()
        var writeError: Exception? = null
        var started = false
        var stopped = false
        var released = false

        override fun addTrack(format: MediaFormat): Int {
            guard.check()
            calls += "addTrack"
            return 0
        }

        override fun start() {
            guard.check()
            started = true
            calls += "start"
            // El muxer real crea el archivo al arrancar; el falso
            // escribe un contenido mínimo para que el renombrado del
            // staging al nombre final funcione como en producción.
            File(path).writeText("mp4")
        }

        override fun writeSampleData(
            trackIndex: Int,
            byteBuffer: ByteBuffer,
            bufferInfo: MediaCodec.BufferInfo,
        ) {
            guard.check()
            writeError?.let { throw it }
            writtenPts += bufferInfo.presentationTimeUs
            calls += "write"
        }

        override fun stop() {
            guard.check()
            stopped = true
            calls += "stop"
        }

        override fun release() {
            guard.check()
            released = true
            calls += "release"
        }

        override fun setOrientationHint(degrees: Int) {
            guard.check()
            calls += "orientation"
        }
    }

    private class FakePreviewSession(
        private val config: PreviewConfig,
    ) : PreviewSession {
        var openCalls = 0
        var closeCalls = 0
        var closeSessionCalls = 0
        var recreateCalls = 0
        var lastVideoSurface: Surface? = null
        var lastTemplate: Int? = null
        var recreateError: CameraException? = null
        override var isOpen = true
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
            closeSessionCalls++
            isOpen = false
        }

        override suspend fun recreateSession(
            videoSurface: Surface?,
            template: Int,
        ): PreviewConfig {
            recreateCalls++
            recreateError?.let { throw it }
            lastVideoSurface = videoSurface
            lastTemplate = template
            isOpen = true
            return config
        }
    }

    private class FakeVerifier(
        var result: SegmentVerification,
    ) : SegmentVerifier {
        var verifyCalls = 0
        var lastClosedCleanly: Boolean? = null

        override suspend fun verify(
            file: File,
            closedCleanly: Boolean,
            expectedWidth: Int?,
            expectedHeight: Int?,
            expectedFps: Int?,
        ): SegmentVerification {
            verifyCalls++
            lastClosedCleanly = closedCleanly
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

    private lateinit var dir: File
    private lateinit var fileStore: RecordingFileStore
    private lateinit var fakeEncoder: FakeEncoder
    private lateinit var fakePreview: FakePreviewSession
    private lateinit var fakeVerifier: FakeVerifier
    private lateinit var muxers: MutableList<FakeSessionMuxer>
    private lateinit var events: MutableList<Pair<String, Map<String, Any?>>>
    private lateinit var previewConfig: PreviewConfig
    private var freeBytes: Long = Long.MAX_VALUE
    private var fixedClockMs: Long = 1_792_000_000_000L

    private fun buildSession(
        formatTimeoutMs: Long = 5_000L,
        eosTimeoutMs: Long = 5_000L,
    ): RecordingSession {
        return RecordingSession(
            scope = CoroutineScope(Dispatchers.Unconfined),
            fileStore = fileStore,
            verifier = fakeVerifier,
            emitEvent = { type, payload ->
                synchronized(events) { events += type to payload }
            },
            encoderFactory = { fakeEncoder },
            muxerFactory = { path ->
                FakeSessionMuxer(path).also { muxers += it }
            },
            capabilityCheck = { _, _, _, _ ->
                EncoderCapabilities(
                    encoderName = "c2.qti.avc.encoder",
                    profiles = listOf(1),
                    levels = listOf(131072),
                    cbrSupported = true,
                    cameraSupportsSize = true,
                )
            },
            freeSpaceBytes = { freeBytes },
            clockMs = { fixedClockMs },
            formatTimeoutMs = formatTimeoutMs,
            eosTimeoutMs = eosTimeoutMs,
        )
    }

    @Before
    fun setUp() {
        // `Transmiti2Logger` sin receptor llamaría a `android.util.Log`,
        // que lanza en las unitarias JVM puras.
        Transmiti2Logger.sink = { _, _ -> }
        dir = File(
            System.getProperty("java.io.tmpdir"),
            "recording-session-test-${System.nanoTime()}",
        )
        dir.mkdirs()
        fileStore = RecordingFileStore(dir)
        fakeEncoder = FakeEncoder(
            VideoEncoderConfig(
                width = 1280,
                height = 720,
                fps = 30,
                bitrateBps = 2_500_000,
            ),
        )
        previewConfig = PreviewConfig(
            cameraId = "0",
            size = VideoSize(1280, 720),
            fpsRange = FpsRange(30, 30),
            sensorOrientation = 90,
            lensFacing = 1,
        )
        fakePreview = FakePreviewSession(previewConfig)
        fakeVerifier = FakeVerifier(readyVerification())
        muxers = mutableListOf()
        events = mutableListOf()
        freeBytes = Long.MAX_VALUE
        fixedClockMs = 1_792_000_000_000L
    }

    @After
    fun tearDown() {
        Transmiti2Logger.sink = null
        dir.deleteRecursively()
    }

    private suspend fun startRecording(
        session: RecordingSession,
    ): RecordingStartedInfo {
        return session.start(
            fakePreview,
            previewConfig,
            90,
            "match-1",
            null,
        )
    }

    private suspend fun awaitState(
        session: RecordingSession,
        expected: RecordingState,
    ) {
        withTimeout(2_000L) {
            while (session.currentState() != expected) {
                delay(10)
            }
        }
    }

    private fun eventTypes(): List<String> =
        synchronized(events) { events.map { it.first } }

    @Test
    fun `start crea encoder, recrea la sesion y emite recording_started`() =
        runBlocking {
            val session = buildSession()
            val info = startRecording(session)
            assertEquals(RecordingState.RECORDING, session.currentState())
            assertTrue(info.recordingId.startsWith("rec_"))
            assertEquals(1280, info.width)
            assertEquals(720, info.height)
            assertEquals(30, info.fps)
            assertEquals("c2.qti.avc.encoder", info.encoderName)
            assertEquals(1, fakeEncoder.configureCalls)
            assertEquals(1, fakePreview.recreateCalls)
            assertTrue(fakePreview.lastVideoSurface != null)
            assertEquals(
                android.hardware.camera2.CameraDevice.TEMPLATE_RECORD,
                fakePreview.lastTemplate,
            )
            assertEquals(1, muxers.size)
            assertTrue(muxers[0].started)
            assertEquals(
                listOf(StreamContract.EVENT_RECORDING_STARTED),
                eventTypes(),
            )
        }

    @Test
    fun `start duplicado es rechazado con recording_state_error`() =
        runBlocking {
            val session = buildSession()
            startRecording(session)
            val thrown = assertThrows(RecordingException::class.java) {
                runBlocking { startRecording(session) }
            }
            assertEquals(
                StreamContract.RECORDING_STATE_ERROR,
                thrown.code,
            )
            assertEquals(RecordingState.RECORDING, session.currentState())
        }

    @Test
    fun `stop desde IDLE es idempotente`() = runBlocking {
        val session = buildSession()
        val result = session.stop()
        assertEquals("idle", result.status)
        assertTrue(result.segments.isEmpty())
        assertEquals(RecordingState.IDLE, session.currentState())
    }

    @Test
    fun `stop normal finaliza el segmento como ready y restaura la vista previa`() =
        runBlocking {
            val session = buildSession()
            val info = startRecording(session)
            fakeEncoder.deliverSample(0L, true)
            fakeEncoder.deliverSample(33_333L, false)
            fakeEncoder.deliverSample(66_666L, false)
            val result = session.stop()
            assertEquals(RecordingState.IDLE, session.currentState())
            assertEquals("ready", result.status)
            assertEquals(1, result.segments.size)
            assertEquals(info.recordingId, result.recordingId)
            assertEquals(SegmentStatus.READY, result.segments[0].status)
            assertTrue(result.segments[0].closedCleanly)
            assertEquals(listOf(0L, 33_333L, 66_666L), muxers[0].writtenPts)
            assertTrue(muxers[0].stopped)
            assertTrue(muxers[0].released)
            // El staging se renombró al nombre final.
            assertTrue(File(dir, result.segments[0].fileName).exists())
            assertFalse(
                result.segments[0].fileName.endsWith(".tmp.mp4"),
            )
            // La vista previa vuelve a solo vista previa.
            assertEquals(2, fakePreview.recreateCalls)
            assertEquals(null, fakePreview.lastVideoSurface)
            assertEquals(
                android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW,
                fakePreview.lastTemplate,
            )
            assertEquals(
                listOf(
                    StreamContract.EVENT_RECORDING_STARTED,
                    StreamContract.EVENT_RECORDING_SEGMENT,
                    StreamContract.EVENT_RECORDING_STOPPED,
                ),
                eventTypes(),
            )
        }

    @Test
    fun `rotacion por keyframe a los 60 s abre un segmento nuevo en PTS 0`() =
        runBlocking {
            val session = buildSession()
            startRecording(session)
            // Muestras cada 1 s; keyframe cada 2 s.
            for (second in 0..62) {
                fakeEncoder.deliverSample(
                    second * 1_000_000L,
                    second % 2 == 0,
                )
            }
            // La muestra del segundo 60 (keyframe) disparó la rotación.
            assertEquals(2, muxers.size)
            assertTrue(fakeEncoder.syncFrameRequests >= 1)
            assertEquals(RecordingState.RECORDING, session.currentState())
            // El segmento nuevo empieza en PTS 0 con el keyframe y
            // sigue con las muestras de los segundos 61 y 62.
            assertEquals(
                listOf(0L, 1_000_000L, 2_000_000L),
                muxers[1].writtenPts,
            )
            // El segmento 0 se verificó como cerrado correctamente.
            assertEquals(true, fakeVerifier.lastClosedCleanly)
            val result = session.stop()
            assertEquals(2, result.segments.size)
            assertEquals(0, result.segments[0].index)
            assertEquals(1, result.segments[1].index)
            assertEquals("ready", result.status)
        }

    @Test
    fun `sin espacio no se empieza`() = runBlocking {
        freeBytes = 100L * 1024 * 1024
        val session = buildSession()
        val thrown = assertThrows(RecordingException::class.java) {
            runBlocking { startRecording(session) }
        }
        assertEquals(StreamContract.STORAGE_FULL, thrown.code)
        assertEquals(RecordingState.ERROR, session.currentState())
        assertEquals(0, fakeEncoder.configureCalls)
    }

    @Test
    fun `sin formato del encoder en 5 s el inicio falla con encoder_error`() =
        runBlocking {
            fakeEncoder.deliverFormatOnStart = false
            val session = buildSession(formatTimeoutMs = 100L)
            val thrown = assertThrows(RecordingException::class.java) {
                runBlocking { startRecording(session) }
            }
            assertEquals(StreamContract.ENCODER_ERROR, thrown.code)
            assertEquals(RecordingState.ERROR, session.currentState())
            assertEquals(1, fakeEncoder.releaseCalls)
        }

    @Test
    fun `error del encoder detiene con encoder_error y conserva el segmento`() =
        runBlocking {
            val session = buildSession()
            startRecording(session)
            fakeEncoder.deliverSample(0L, true)
            fakeEncoder.deliverSample(33_333L, false)
            fakeEncoder.deliverError()
            awaitState(session, RecordingState.ERROR)
            val result = session.stop()
            assertEquals("ready", result.status)
            assertEquals(1, result.segments.size)
            assertEquals(
                StreamContract.ENCODER_ERROR,
                result.errorCode,
            )
            assertTrue(
                eventTypes().contains(StreamContract.EVENT_RECORDING_ERROR),
            )
        }

    @Test
    fun `error del muxer al escribir detiene con muxer_error`() =
        runBlocking {
            val session = buildSession()
            startRecording(session)
            fakeEncoder.deliverSample(0L, true)
            muxers[0].writeError = IllegalStateException(
                "Muxer has been released!",
            )
            fakeEncoder.deliverSample(33_333L, false)
            awaitState(session, RecordingState.ERROR)
            val result = session.stop()
            assertEquals(
                StreamContract.MUXER_ERROR,
                result.errorCode,
            )
            assertTrue(
                eventTypes().contains(StreamContract.EVENT_RECORDING_ERROR),
            )
        }

    @Test
    fun `muestra tardia tras stopDrain no escribe en el muxer cerrado`() =
        runBlocking {
            fakeEncoder.deliverEosOnSignal = false
            val session = buildSession(eosTimeoutMs = 100L)
            startRecording(session)
            fakeEncoder.deliverSample(0L, true)
            fakeEncoder.deliverSample(33_333L, false)
            val result = session.stop()
            // EOS no llegó: se detuvo el drenado y se cerró el muxer.
            assertEquals(1, fakeEncoder.stopDrainCalls)
            assertTrue(muxers[0].released)
            val writesAfterStop = muxers[0].writtenPts.size
            // Muestra tardía: se ignora, no se registra como error.
            fakeEncoder.deliverSample(66_666L, false)
            assertEquals(writesAfterStop, muxers[0].writtenPts.size)
            assertEquals(RecordingState.IDLE, session.currentState())
            assertEquals(1, result.segments.size)
        }

    @Test
    fun `recuperacion clasifica los tmp como partial sin borrarlos`() =
        runBlocking {
            val session = buildSession()
            val staging0 = fileStore.segmentStagingFile("rec_1", 0)
            val staging1 = fileStore.segmentStagingFile("rec_1", 1)
            staging0.writeText("a")
            staging1.writeText("b")
            fakeVerifier.result = partialVerification()
            val found = session.recoverInterrupted()
            assertEquals(2, found.size)
            assertEquals("rec_1", found[0].recordingId)
            assertEquals(0, found[0].index)
            assertEquals(1, found[1].index)
            assertEquals(SegmentStatus.PARTIAL, found[0].status)
            assertEquals(false, fakeVerifier.lastClosedCleanly)
            // Los originales se conservan.
            assertTrue(staging0.exists())
            assertTrue(staging1.exists())
            assertEquals(2, fakeVerifier.verifyCalls)
        }

    @Test
    fun `stop desde ERROR devuelve el resultado ya calculado`() =
        runBlocking {
            val session = buildSession()
            startRecording(session)
            fakeEncoder.deliverSample(0L, true)
            fakeEncoder.deliverError()
            awaitState(session, RecordingState.ERROR)
            val first = session.stop()
            val second = session.stop()
            assertEquals(first.recordingId, second.recordingId)
            assertEquals(first.segments.size, second.segments.size)
        }

    @Test
    fun `el estado se puede consultar durante la grabacion`() =
        runBlocking {
            val session = buildSession()
            startRecording(session)
            fakeEncoder.deliverSample(0L, true)
            fakeEncoder.deliverSample(33_333L, false)
            val snapshot = session.statusSnapshot()
            assertEquals("recording", snapshot.state)
            assertEquals(0, snapshot.segmentIndex)
            assertEquals(33L, snapshot.segmentDurationMs)
            assertEquals(1280, snapshot.width)
            assertEquals(720, snapshot.height)
            assertEquals(30, snapshot.fps)
            // Sin segmentos cerrados, la lista es vacía (paso 6).
            assertEquals(0, snapshot.segments.size)
        }

    @Test
    fun `fromSegments suma las duraciones medidas aunque un segmento sea corrupt`() {
        // Fix duraciones 3.1.2, paso 4: el total no debe ser 0 por un
        // segmento `corrupt` por otro motivo (p. ej. decode) si su
        // duración se midió; el nulo solo aporta 0.
        val segments = listOf(
            RecordingSegmentResult(
                recordingId = "rec_20261118_120000_ab12",
                index = 0,
                fileName = "rec_20261118_120000_ab12_seg000.mp4",
                filePath = "/docs/recordings/rec_20261118_120000_ab12_seg000.mp4",
                status = SegmentStatus.READY,
                durationMs = 60_000L,
                sizeBytes = 18_750_000L,
                sampleCount = 1800,
                keyframeCount = 30,
                firstSampleKeyframe = true,
                ptsMonotonic = true,
                profileIdc = 66,
                closedCleanly = true,
                reason = null,
            ),
            RecordingSegmentResult(
                recordingId = "rec_20261118_120000_ab12",
                index = 1,
                fileName = "rec_20261118_120000_ab12_seg001.mp4",
                filePath = "/docs/recordings/rec_20261118_120000_ab12_seg001.mp4",
                status = SegmentStatus.CORRUPT,
                durationMs = 5_000L,
                sizeBytes = 1_500_000L,
                sampleCount = 150,
                keyframeCount = 3,
                firstSampleKeyframe = true,
                ptsMonotonic = true,
                profileIdc = 66,
                closedCleanly = true,
                reason = "decode_incompleto",
            ),
            RecordingSegmentResult(
                recordingId = "rec_20261118_120000_ab12",
                index = 2,
                fileName = "rec_20261118_120000_ab12_seg002.mp4",
                filePath = "/docs/recordings/rec_20261118_120000_ab12_seg002.mp4",
                status = SegmentStatus.CORRUPT,
                durationMs = null,
                sizeBytes = 0L,
                sampleCount = 0,
                keyframeCount = 0,
                firstSampleKeyframe = false,
                ptsMonotonic = true,
                profileIdc = null,
                closedCleanly = false,
                reason = "sin_muestras",
            ),
        )
        val result = RecordingResult.fromSegments(
            recordingId = "rec_20261118_120000_ab12",
            segments = segments,
            errorCode = null,
            previewRestoreError = null,
        )
        assertEquals(65_000L, result.totalDurationMs)
        assertEquals("partial", result.status)
    }
}
