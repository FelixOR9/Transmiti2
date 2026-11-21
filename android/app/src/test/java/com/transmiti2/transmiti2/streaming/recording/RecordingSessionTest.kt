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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        // Hilo propio ("encoder") como el real: TODAS las operaciones
        // del muxer y los callbacks corren en él; las entradas del
        // test (`configureAndStart`, `deliverSample`, `deliverError`)
        // publican en él y esperan (el test sigue siendo síncrono).
        // Sin hilo propio, la corutina del deadline de audio (2 s,
        // hilo del temporizador) abriría el muxer en otro hilo y la
        // guarda del muxer falso fallaría — en producción el `post`
        // salta al hilo del encoder real.
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "FakeEncoder").apply {
                isDaemon = true
                // Ronda U: las excepciones no capturadas de los
                // bloques `post` se registran aquí (sin esto, el
                // executor reemplaza el hilo muerto en silencio y
                // el test no distinguiría el cierre).
                uncaughtExceptionHandler =
                    Thread.UncaughtExceptionHandler { _, e ->
                        synchronized(uncaught) { uncaught += e }
                    }
            }
        }
        /** Excepciones no capturadas del hilo del encoder (ronda U). */
        val uncaught = mutableListOf<Throwable>()
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
                postAndWait { listener?.onOutputFormatChanged(format) }
            }
        }

        override fun requestSyncFrame(): Boolean {
            syncFrameRequests++
            return true
        }

        override fun signalEndOfInputStream() {
            if (deliverEosOnSignal) {
                post { listener?.onEndOfStream() }
            }
        }

        override fun stopDrain() {
            stopDrainCalls++
        }

        override fun post(block: () -> Unit) {
            try {
                executor.execute(block)
            } catch (_: RejectedExecutionException) {
                // Apagado en `tearDown`: se ignora (la sesión ya terminó).
            }
        }

        override fun release() {
            releaseCalls++
        }

        fun shutdown() {
            executor.shutdownNow()
        }

        private fun postAndWait(block: () -> Unit) {
            val done = CountDownLatch(1)
            post {
                try {
                    block()
                } finally {
                    done.countDown()
                }
            }
            check(done.await(5, TimeUnit.SECONDS)) {
                "El hilo del encoder falso no procesó el bloque en 5 s."
            }
        }

        fun deliverSample(ptsUs: Long, keyframe: Boolean) {
            postAndWait {
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
        }

        fun deliverError() {
            postAndWait {
                listener?.onError(IllegalStateException("codec roto"))
            }
        }
    }

    /**
     * Encoder de audio falso (3.1.3): entrega el formato y las
     * muestras de forma síncrona en el hilo del test; la sesión los
     * publica al hilo del encoder de video (falso) como en
     * producción. Sin `AudioRecord` ni `MediaCodec` reales.
     */
    private class FakeAudioEncoder(
        override val config: AudioEncoderConfig,
    ) : AudioEncoderPort {
        override var outputFormat: MediaFormat? = null
        /** Contador de entradas descartadas (instrumentación 3.1.3):
         * el test lo fija para comprobar que `stop()` lo propaga. */
        override var droppedInputBuffers: Int = 0
        /** Capacidad del buffer de entrada AAC (ronda U): el test la
         * fija para comprobar que `stop()` la propaga. */
        override var inputBufferCapacityBytes: Int? = null
        /**
         * Instrumentación ronda 3 (grupo I): estado del micrófono
         * y contadores del lector; el test los fija para comprobar
         * que `stop()` los propaga al `RecordingResult`.
         */
        override var recordingStateAtStart: Int? = null
        override var recordingState: Int = -1
        override var readCount: Int = 0
        override var lastReadCode: Int? = null
        override var lastSuccessfulReadElapsedMs: Long? = null
        override var isReaderAlive: Boolean = false
        /** Flag del watchdog (Grupo V, V3): el test lo fija para
         * comprobar que `stop()` lo propaga al `RecordingResult`. */
        override var readerNoData2s: Boolean = false
        /** Grupo S (S0): segundo más silencioso; el test los fija
         * para comprobar que `stop()` los propaga al
         * `RecordingResult`. */
        override var quietestSecondPeak: Int? = null
        override var quietestSecondNonZero: Int? = null
        override var listener: AudioEncoderListener? = null
        var configureCalls = 0
        var releaseCalls = 0
        var stopDrainCalls = 0
        var eosCalls = 0
        var deliverFormatOnStart = true
        var deliverEosOnSignal = true
        var configureError: Exception? = null

        override fun configureAndStart() {
            configureCalls++
            configureError?.let { throw it }
            if (deliverFormatOnStart) {
                deliverFormat()
            }
        }

        /** Entrega el formato AAC al listener (ronda U: el test lo
         * llama tras el inicio para reproducir el orden real en el
         * que el formato de video llega antes que el AAC). */
        fun deliverFormat() {
            val format = MediaFormat()
            outputFormat = format
            listener?.onAudioFormatChanged(format)
        }

        override fun signalEndOfInputStream() {
            eosCalls++
            if (deliverEosOnSignal) {
                listener?.onAudioEndOfStream()
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

        fun deliverSample(ptsUs: Long) {
            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = 10
                presentationTimeUs = ptsUs
                flags = 0
            }
            listener?.onAudioSample(ByteBuffer.allocate(10), info)
        }

        fun deliverError(
            reason: String = "audio_dead_object",
            extraDetails: Map<String, Any?> = emptyMap(),
        ) {
            listener?.onAudioError(
                RecordingException.audioError(
                    "Micrófono perdido.",
                    mapOf("reason" to reason) + extraDetails,
                ),
            )
        }
    }

    private class FakeSessionMuxer(
        val path: String,
    ) : RecordingMuxer {
        override val name: String = "fake"
        private val guard = MuxerThreadGuard()
        val calls = mutableListOf<String>()
        val writtenPts = mutableListOf<Long>()
        /** PTS escritos por pista (0 = video, 1 = audio, …). */
        val writtenPtsByTrack = mutableMapOf<Int, MutableList<Long>>()
        private var nextTrackIndex = 0
        var writeError: Exception? = null
        var started = false
        var stopped = false
        var released = false

        override fun addTrack(format: MediaFormat): Int {
            guard.check()
            calls += "addTrack"
            val index = nextTrackIndex++
            writtenPtsByTrack[index] = mutableListOf()
            return index
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
            writtenPtsByTrack.getOrPut(trackIndex) { mutableListOf() } +=
                bufferInfo.presentationTimeUs
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
        /** Última expectativa de audio recibida (3.1.3). */
        var lastExpectedAudio: Boolean? = null
        var lastExpectedSampleRate: Int? = null
        var lastExpectedChannels: Int? = null
        /** Retardo artificial por verificación (prueba de serie). */
        var verifyDelayMs: Long = 0L
        /** Concurrencia observada dentro de `verify()`. */
        val currentConcurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        /** Archivos verificados, en orden (0-d1: U36/I7). */
        val verifiedFiles = mutableListOf<String>()
        /** Bloqueo opcional al entrar en `verify()` (0-d1: U36/I7). */
        var blockBeforeVerify: (() -> Unit)? = null

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
            lastClosedCleanly = closedCleanly
            lastExpectedAudio = expectedAudio
            lastExpectedSampleRate = expectedSampleRate
            lastExpectedChannels = expectedChannels
            synchronized(verifiedFiles) { verifiedFiles += file.name }
            blockBeforeVerify?.invoke()
            val current = currentConcurrent.incrementAndGet()
            maxConcurrent.updateAndGet { max -> maxOf(max, current) }
            try {
                if (verifyDelayMs > 0) delay(verifyDelayMs)
                return result
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

    /** Verificación `ready` con pista de audio medida (3.1.3). */
    private fun readyVerificationWithAudio() = readyVerification().copy(
        checks = readyVerification().checks.copy(
            hasAudioTrack = true,
            audioSampleCount = 2800,
            audioDurationMs = 59800L,
            audioSampleRate = 48000,
            audioChannels = 1,
            audioFirstPtsUs = 0L,
            audioLastPtsUs = 59_800_000L,
            audioPtsMonotonic = true,
            audioDecodeOk = true,
            audioDecodedFrames = 2800,
            avOffsetMs = 150L,
            avDurationDiffMs = 200L,
        ),
    )

    private lateinit var dir: File
    private lateinit var fileStore: RecordingFileStore
    private lateinit var fakeEncoder: FakeEncoder
    private lateinit var fakeAudioEncoder: FakeAudioEncoder
    private lateinit var fakePreview: FakePreviewSession
    private lateinit var fakeVerifier: FakeVerifier
    private lateinit var muxers: MutableList<FakeSessionMuxer>
    private lateinit var events: MutableList<Pair<String, Map<String, Any?>>>
    private lateinit var previewConfig: PreviewConfig
    private var freeBytes: Long? = Long.MAX_VALUE
    private var fixedClockMs: Long = 1_792_000_000_000L

    private fun buildSession(
        formatTimeoutMs: Long = 5_000L,
        eosTimeoutMs: Long = 5_000L,
        audioFormatTimeoutMs: Long = 2_000L,
        muxerFactory: ((String) -> RecordingMuxer)? = null,
        registry: ActiveRecordingRegistry = ActiveRecordingRegistry(),
        freeSpaceBytes: (() -> Long?)? = null,
    ): RecordingSession {
        return RecordingSession(
            scope = CoroutineScope(Dispatchers.Unconfined),
            fileStore = fileStore,
            verifier = fakeVerifier,
            emitEvent = { type, payload ->
                synchronized(events) { events += type to payload }
            },
            encoderFactory = { fakeEncoder },
            muxerFactory = muxerFactory ?: { path ->
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
            audioEncoderFactory = { fakeAudioEncoder },
            audioCapabilityCheck = {
                AudioSetup(fakeAudioEncoder.config, "OMX.google.aac.encoder")
            },
            audioFormatTimeoutMs = audioFormatTimeoutMs,
            freeSpaceBytes = freeSpaceBytes ?: { freeBytes },
            clockMs = { fixedClockMs },
            formatTimeoutMs = formatTimeoutMs,
            eosTimeoutMs = eosTimeoutMs,
            registry = registry,
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
        fakeAudioEncoder = FakeAudioEncoder(
            AudioEncoderConfig(
                sampleRate = 48000,
                channels = 1,
                bitrateBps = 128_000,
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
        fakeEncoder.shutdown()
        dir.deleteRecursively()
    }

    private suspend fun startRecording(
        session: RecordingSession,
        audioEnabled: Boolean = false,
    ): RecordingStartedInfo {
        return session.start(
            fakePreview,
            previewConfig,
            90,
            "match-1",
            null,
            audioEnabled,
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
    fun `U20 StatFs que lanza en start da storage_check_unavailable sin encoder`() =
        runBlocking {
            val session = buildSession(
                freeSpaceBytes = {
                    throw IllegalStateException("statfs roto")
                },
            )
            val thrown = assertThrows(RecordingException::class.java) {
                runBlocking { startRecording(session) }
            }
            assertEquals(
                StreamContract.STORAGE_CHECK_UNAVAILABLE,
                thrown.code,
            )
            assertEquals("start", thrown.details["stage"])
            assertEquals("free_space_check", thrown.details["check"])
            assertEquals(
                "IllegalStateException",
                thrown.details["exceptionClass"],
            )
            assertNull(thrown.details["freeBytes"])
            assertEquals(RecordingState.ERROR, session.currentState())
            assertEquals(0, fakeEncoder.configureCalls)
            // Sin encoder, sin segmento, sin fila: la re-validación
            // V1–V4 pasó y el inicio no llegó a crear nada.
            assertTrue(dir.listFiles().isNullOrEmpty())
        }

    @Test
    fun `U20b espacio no medible en rotacion hace parada limpia`() =
        runBlocking {
            // Espacio medible al arrancar y `null` al llegar la
            // rotación: el cambio se hace desde el propio lambda.
            var calls = 0
            val session = buildSession(
                freeSpaceBytes = {
                    calls++
                    if (calls <= 1) 10L * 1024 * 1024 * 1024 else null
                },
            )
            startRecording(session)
            // Muestras cada 1 s; keyframe cada 2 s (como la rotación
            // por keyframe a los 60 s).
            for (second in 0..60) {
                fakeEncoder.deliverSample(
                    second * 1_000_000L,
                    second % 2 == 0,
                )
            }
            awaitState(session, RecordingState.ERROR)
            val result = session.stop()
            assertEquals(
                StreamContract.STORAGE_CHECK_UNAVAILABLE,
                result.errorCode,
            )
            // Segmentos previos intactos: el segmento 0 se cerró y
            // su archivo existe.
            assertEquals(1, result.segments.size)
            assertTrue(File(dir, result.segments[0].fileName).exists())
        }

    @Test
    fun `U20c espacio null nunca es storage_full ni lowStorageWarning`() =
        runBlocking {
            val session = buildSession(freeSpaceBytes = { null })
            val thrown = assertThrows(RecordingException::class.java) {
                runBlocking { startRecording(session) }
            }
            // El camino `null` es `storage_check_unavailable`, nunca
            // `storage_full` (un `null` tratado como 0 caería ahí).
            assertEquals(
                StreamContract.STORAGE_CHECK_UNAVAILABLE,
                thrown.code,
            )
            assertEquals(RecordingState.ERROR, session.currentState())
            // Sin arranque no hay `RecordingStartedInfo`: el aviso
            // `lowStorageWarning` nunca se calculó como `true`.
            assertFalse(
                eventTypes().contains(StreamContract.EVENT_RECORDING_STARTED),
            )
        }

    @Test
    fun `U21 start con directorio invalido se bloquea antes de ensureDir`() =
        runBlocking {
            // El directorio de grabación cuelga de un ARCHIVO: V2 no
            // puede crear `<archivo>/recordings` → `mkdirs_failed`.
            val blocker = File(dir, "bloqueador")
            blocker.writeText("no es un directorio")
            val badStore = RecordingFileStore(File(blocker, "recordings"))
            val session = RecordingSession(
                scope = CoroutineScope(Dispatchers.Unconfined),
                fileStore = badStore,
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
                audioEncoderFactory = { fakeAudioEncoder },
                audioCapabilityCheck = {
                    AudioSetup(fakeAudioEncoder.config, "OMX.google.aac.encoder")
                },
                freeSpaceBytes = { freeBytes },
                clockMs = { fixedClockMs },
            )
            val thrown = assertThrows(RecordingException::class.java) {
                runBlocking { startRecording(session) }
            }
            assertEquals(
                StreamContract.RECORDINGS_DIR_UNAVAILABLE,
                thrown.code,
            )
            assertEquals("mkdirs_failed", thrown.details["stage"])
            assertEquals(RecordingState.ERROR, session.currentState())
            assertEquals(0, fakeEncoder.configureCalls)
            assertEquals(0, muxers.size)
            assertTrue(dir.listFiles()?.none { it.name.endsWith(".mp4") } ?: true)
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
    fun `recuperacion renombra los tmp utilizables y persiste el sidecar`() =
        runBlocking {
            val session = buildSession()
            val staging0 = fileStore.segmentStagingFile("rec_1", 0)
            val staging1 = fileStore.segmentStagingFile("rec_1", 1)
            staging0.writeText("a")
            staging1.writeText("b")
            fakeVerifier.result = partialVerification()
            // 0-d2: la envoltura `recoverInterrupted()` se eliminó;
            // el resumen lleva los segmentos reconciliados.
            val summary = session.reconcileRecordings()
            val found = summary.segments
            assertEquals(2, found.size)
            assertEquals("rec_1", found[0].recordingId)
            assertEquals(0, found[0].index)
            assertEquals(1, found[1].index)
            assertEquals(SegmentStatus.PARTIAL, found[0].status)
            assertEquals(false, fakeVerifier.lastClosedCleanly)
            // 0-d1: la reconciliación repara (renombra) en vez de
            // solo clasificar; nada se borra.
            assertEquals("rec_1_seg000.mp4", found[0].fileName)
            assertEquals("rec_1_seg001.mp4", found[1].fileName)
            assertFalse(staging0.exists())
            assertFalse(staging1.exists())
            assertTrue(fileStore.segmentFile("rec_1", 0).exists())
            assertTrue(fileStore.segmentFile("rec_1", 1).exists())
            assertEquals(2, fakeVerifier.verifyCalls)
            val sidecar = fileStore.readSidecar("rec_1")
            assertTrue(sidecar != null)
            assertEquals(2, sidecar!!.segments.size)
            assertTrue(sidecar.segments.all { it.status == "partial" })
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

    @Test
    fun `las verificaciones de rotacion no corren concurrentes`() =
        runBlocking {
            // Ronda 2: sin serialización, la verificación del segmento 0
            // (300 ms) seguiría corriendo cuando la rotación del
            // segmento 1 lanza la suya. Con el mutex, una a la vez.
            fakeVerifier.verifyDelayMs = 300L
            val session = buildSession()
            startRecording(session)
            // Muestras cada 1 s; keyframe cada 2 s → rotaciones a 60 s
            // y a 120 s (segmentos 0 y 1) y parada con el segmento 2.
            for (second in 0..122) {
                fakeEncoder.deliverSample(
                    second * 1_000_000L,
                    second % 2 == 0,
                )
            }
            val result = session.stop()
            assertEquals(3, result.segments.size)
            assertEquals(3, fakeVerifier.verifyCalls)
            assertEquals(1, fakeVerifier.maxConcurrent.get())
            assertEquals("ready", result.status)
        }

    @Test
    fun `fromSegments suma la duracion de un segmento partial por timeout`() {
        // Ronda 2: un segmento legible cuya verificación no terminó a
        // tiempo es `partial` con su duración medida (no `corrupt` sin
        // duración): el total debe sumarla. Es el caso del teléfono
        // (`rec_20261118_233257_ffb1`): 60 + 60 + 3,8 s, no 3,8 s.
        val segments = listOf(
            RecordingSegmentResult(
                recordingId = "rec_20261118_233257_ffb1",
                index = 0,
                fileName = "rec_20261118_233257_ffb1_seg000.mp4",
                filePath = "/docs/recordings/rec_20261118_233257_ffb1_seg000.mp4",
                status = SegmentStatus.PARTIAL,
                durationMs = 60_000L,
                sizeBytes = 18_750_000L,
                sampleCount = 1800,
                keyframeCount = 30,
                firstSampleKeyframe = true,
                ptsMonotonic = true,
                profileIdc = 66,
                closedCleanly = true,
                reason = "verification_timeout",
            ),
            RecordingSegmentResult(
                recordingId = "rec_20261118_233257_ffb1",
                index = 1,
                fileName = "rec_20261118_233257_ffb1_seg001.mp4",
                filePath = "/docs/recordings/rec_20261118_233257_ffb1_seg001.mp4",
                status = SegmentStatus.PARTIAL,
                durationMs = 60_000L,
                sizeBytes = 18_750_000L,
                sampleCount = 1800,
                keyframeCount = 30,
                firstSampleKeyframe = true,
                ptsMonotonic = true,
                profileIdc = 66,
                closedCleanly = true,
                reason = "verification_timeout",
            ),
            RecordingSegmentResult(
                recordingId = "rec_20261118_233257_ffb1",
                index = 2,
                fileName = "rec_20261118_233257_ffb1_seg002.mp4",
                filePath = "/docs/recordings/rec_20261118_233257_ffb1_seg002.mp4",
                status = SegmentStatus.READY,
                durationMs = 3831L,
                sizeBytes = 1_200_000L,
                sampleCount = 114,
                keyframeCount = 2,
                firstSampleKeyframe = true,
                ptsMonotonic = true,
                profileIdc = 66,
                closedCleanly = true,
                reason = null,
            ),
        )
        val result = RecordingResult.fromSegments(
            recordingId = "rec_20261118_233257_ffb1",
            segments = segments,
            errorCode = null,
            previewRestoreError = null,
        )
        assertEquals(123_831L, result.totalDurationMs)
        assertEquals("partial", result.status)
    }

    @Test
    fun `toMap incluye el recuento verificado y la fase E_S`() {
        // Ronda 4, fix 3: la tarjeta muestra el recuento del
        // verificador y la duración de la fase 1; ambos viajan en el
        // mapa del evento `recording_segment`. Sin estas claves, Dart
        // los dibujaría como `—` aunque el verificador los midiera.
        val segment = RecordingSegmentResult(
            recordingId = "rec_20261119_012113_3210",
            index = 0,
            fileName = "rec_20261119_012113_3210_seg000.mp4",
            filePath = "/docs/rec_20261119_012113_3210_seg000.mp4",
            status = SegmentStatus.PARTIAL,
            durationMs = 60_007L,
            sizeBytes = 18_750_000L,
            sampleCount = 1801,
            keyframeCount = 30,
            firstSampleKeyframe = true,
            ptsMonotonic = true,
            profileIdc = 66,
            fpsMeasured = 30.0,
            verifiedSampleCount = 1801,
            ioPhaseMs = 61_234L,
            closedCleanly = true,
            reason = "verification_timeout",
        )
        val map = segment.toMap()
        assertEquals(1801, map["verifiedSampleCount"])
        assertEquals(61_234L, map["ioPhaseMs"])
        assertEquals(30.0, map["fpsMeasured"])
        assertEquals(1801, map["sampleCount"])
    }

    // --- Audio AAC + sincronización (3.1.3, plan §10.1) ---

    @Test
    fun `arranque con audio abre el muxer con 2 pistas y registra el audio en recording_started`() =
        runBlocking {
            val session = buildSession()
            val info = startRecording(session, audioEnabled = true)
            assertEquals(RecordingState.RECORDING, session.currentState())
            assertTrue(info.audioEnabled)
            assertEquals(48000, info.audioSampleRate)
            assertEquals(1, info.audioChannels)
            assertEquals("OMX.google.aac.encoder", info.audioEncoderName)
            assertEquals(null, info.audioReason)
            assertEquals(1, fakeAudioEncoder.configureCalls)
            // Dos pistas en el muxer del segmento 0.
            assertEquals(2, muxers[0].writtenPtsByTrack.size)
            // El evento `recording_started` lleva los campos de audio.
            val startedPayload = events.first {
                it.first == StreamContract.EVENT_RECORDING_STARTED
            }.second
            assertEquals(true, startedPayload["audioEnabled"])
            assertEquals(48000, startedPayload["audioSampleRate"])
            assertEquals(1, startedPayload["audioChannels"])
            // Video y audio se escriben cada uno en su pista (origen
            // común 0: el primer video se guardó en el buffer hasta
            // que llegó el primer audio).
            fakeEncoder.deliverSample(0L, true)
            fakeAudioEncoder.deliverSample(0L)
            fakeAudioEncoder.deliverSample(21_333L)
            fakeEncoder.deliverSample(33_333L, false)
            assertEquals(
                listOf(0L, 33_333L),
                muxers[0].writtenPtsByTrack[0],
            )
            assertEquals(
                listOf(0L, 21_333L),
                muxers[0].writtenPtsByTrack[1],
            )
            val result = session.stop()
            assertEquals("ready", result.status)
            assertEquals(true, fakeVerifier.lastExpectedAudio)
            assertEquals(48000, fakeVerifier.lastExpectedSampleRate)
            assertEquals(1, fakeVerifier.lastExpectedChannels)
        }

    @Test
    fun `espera de audio que expira degrada a video-only sin perder muestras`() =
        runBlocking {
            fakeAudioEncoder.deliverFormatOnStart = false
            val session = buildSession(audioFormatTimeoutMs = 200L)
            // `start()` espera al segmento: la espera expira a los
            // 200 ms y el segmento se abre video-only.
            val info = startRecording(session, audioEnabled = true)
            assertEquals(RecordingState.RECORDING, session.currentState())
            assertFalse(info.audioEnabled)
            assertEquals("audio_format_timeout", info.audioReason)
            assertEquals(null, info.audioSampleRate)
            // El encoder de audio se liberó (nunca entregó su formato).
            assertEquals(1, fakeAudioEncoder.releaseCalls)
            // Una sola pista en el muxer.
            assertEquals(1, muxers[0].writtenPtsByTrack.size)
            // Las muestras de video llegan y se escriben (ninguna se tira).
            fakeEncoder.deliverSample(0L, true)
            fakeEncoder.deliverSample(33_333L, false)
            fakeEncoder.deliverSample(66_666L, false)
            assertEquals(
                listOf(0L, 33_333L, 66_666L),
                muxers[0].writtenPts,
            )
            val result = session.stop()
            assertEquals("ready", result.status)
            assertEquals(false, fakeVerifier.lastExpectedAudio)
            assertFalse(result.audioEnabled)
        }

    @Test
    fun `rotacion con audio reparte las muestras por la frontera del keyframe`() =
        runBlocking {
            val session = buildSession()
            startRecording(session, audioEnabled = true)
            // Video cada 1 s (keyframe cada 2 s) y audio 0,5 s después.
            for (second in 0..62) {
                fakeEncoder.deliverSample(
                    second * 1_000_000L,
                    second % 2 == 0,
                )
                fakeAudioEncoder.deliverSample(second * 1_000_000L + 500_000L)
            }
            // Vacía la cola del hilo del encoder (la última muestra de
            // audio se publicó sin esperar).
            fakeEncoder.deliverSample(63_000_000L, false)
            assertEquals(2, muxers.size)
            assertEquals(RecordingState.RECORDING, session.currentState())
            // Segmento 0: video 0..59 s y audio 0,5..59,5 s.
            assertEquals(
                (0..59).map { it * 1_000_000L },
                muxers[0].writtenPtsByTrack[0],
            )
            assertEquals(
                (0..59).map { it * 1_000_000L + 500_000L },
                muxers[0].writtenPtsByTrack[1],
            )
            // Segmento 1: keyframe en PTS 0 y audio desde 60,5 s.
            assertEquals(
                listOf(0L, 1_000_000L, 2_000_000L, 3_000_000L),
                muxers[1].writtenPtsByTrack[0],
            )
            assertEquals(
                listOf(500_000L, 1_500_000L, 2_500_000L),
                muxers[1].writtenPtsByTrack[1],
            )
            val result = session.stop()
            assertEquals(2, result.segments.size)
            assertEquals("ready", result.status)
        }

    @Test
    fun `muestra de audio tardia tras la rotacion se descarta y se cuenta`() =
        runBlocking {
            val session = buildSession()
            startRecording(session, audioEnabled = true)
            for (second in 0..62) {
                fakeEncoder.deliverSample(
                    second * 1_000_000L,
                    second % 2 == 0,
                )
                fakeAudioEncoder.deliverSample(second * 1_000_000L + 500_000L)
            }
            // Vacía la cola: la rotación ya ocurrió (segmento 1).
            fakeEncoder.deliverSample(63_000_000L, false)
            val before = muxers[1].writtenPtsByTrack[1]?.size ?: 0
            // Una muestra de audio del segmento 0 que llega tarde.
            fakeAudioEncoder.deliverSample(59_500_000L)
            fakeEncoder.deliverSample(64_000_000L, false)
            assertEquals(before, muxers[1].writtenPtsByTrack[1]?.size)
            val result = session.stop()
            assertEquals(2, result.segments.size)
            assertEquals(1, result.segments[1].audioGapSamples)
        }

    @Test
    fun `parada con audio espera el EOS de ambas pistas y registra el audio verificado`() =
        runBlocking {
            fakeVerifier.result = readyVerificationWithAudio()
            val session = buildSession()
            startRecording(session, audioEnabled = true)
            fakeEncoder.deliverSample(0L, true)
            fakeAudioEncoder.deliverSample(0L)
            fakeAudioEncoder.deliverSample(21_333L)
            fakeEncoder.deliverSample(33_333L, false)
            val result = session.stop()
            assertEquals(1, fakeAudioEncoder.eosCalls)
            assertEquals(1, fakeAudioEncoder.releaseCalls)
            assertEquals("ready", result.status)
            assertEquals(1, result.segments.size)
            val segment = result.segments[0]
            assertTrue(segment.hasAudio)
            assertEquals(59800L, segment.audioDurationMs)
            assertEquals(2800, segment.audioSampleCount)
            assertEquals(48000, segment.audioSampleRate)
            assertEquals(1, segment.audioChannels)
            assertEquals(150L, segment.avOffsetMs)
            assertEquals(200L, segment.avDurationDiffMs)
            assertEquals(null, segment.audioReason)
            assertTrue(result.audioEnabled)
            // El mapa del evento `recording_segment` lleva los campos.
            val segmentPayload = events.first {
                it.first == StreamContract.EVENT_RECORDING_SEGMENT
            }.second
            assertEquals(true, segmentPayload["hasAudio"])
            assertEquals(150L, segmentPayload["avOffsetMs"])
        }

    @Test
    fun `perdida de audio en caliente el video continua y se emite recording_audio_lost`() =
        runBlocking {
            val session = buildSession()
            startRecording(session, audioEnabled = true)
            fakeEncoder.deliverSample(0L, true)
            fakeAudioEncoder.deliverSample(0L)
            fakeAudioEncoder.deliverSample(21_333L)
            fakeEncoder.deliverSample(33_333L, false)
            fakeAudioEncoder.deliverError()
            assertTrue(
                eventTypes().contains(
                    StreamContract.EVENT_RECORDING_AUDIO_LOST,
                ),
            )
            assertEquals(RecordingState.RECORDING, session.currentState())
            // El video continúa tras la pérdida.
            fakeEncoder.deliverSample(66_666L, false)
            fakeEncoder.deliverSample(100_000L, false)
            assertEquals(
                listOf(0L, 33_333L, 66_666L, 100_000L),
                muxers[0].writtenPtsByTrack[0],
            )
            val result = session.stop()
            assertEquals("ready", result.status)
            assertEquals(1, result.segments.size)
            assertEquals("audio_dead_object", result.segments[0].audioReason)
            assertEquals(true, fakeVerifier.lastExpectedAudio)
        }

    @Test
    fun `perdida de audio por error de entrada degrada con motivo audio_input_error`() =
        runBlocking {
            // Ronda U: el motivo nuevo de la guarda del callback AAC
            // viaja como los demás motivos de `onAudioError`.
            val session = buildSession()
            startRecording(session, audioEnabled = true)
            fakeEncoder.deliverSample(0L, true)
            fakeAudioEncoder.deliverSample(0L)
            fakeAudioEncoder.deliverError("audio_input_error")
            assertTrue(
                eventTypes().contains(
                    StreamContract.EVENT_RECORDING_AUDIO_LOST,
                ),
            )
            assertEquals(RecordingState.RECORDING, session.currentState())
            fakeEncoder.deliverSample(33_333L, false)
            val result = session.stop()
            assertEquals("ready", result.status)
            assertEquals("audio_input_error", result.segments[0].audioReason)
        }

    @Test
    fun `T-S11 perdida por silencio emite recording_audio_lost con las tres claves y el video continua`() =
        runBlocking {
            // Grupo S (S1): el motivo nuevo `audio_silence_samples`
            // viaja con `silenceMs`/`silenceStartOffsetMs`/
            // `silencePeak` en el evento y en el resultado de parada
            // (`silencioPico`); el video continúa y el segmento queda
            // `ready` con `audioReason`.
            val session = buildSession()
            startRecording(session, audioEnabled = true)
            fakeEncoder.deliverSample(0L, true)
            fakeAudioEncoder.deliverSample(0L)
            fakeAudioEncoder.deliverSample(21_333L)
            fakeEncoder.deliverSample(33_333L, false)
            fakeAudioEncoder.deliverError(
                reason = "audio_silence_samples",
                extraDetails = mapOf(
                    "silenceMs" to 5_000L,
                    "silenceStartOffsetMs" to 12_345L,
                    "silencePeak" to 0,
                ),
            )
            val payload = events.first {
                it.first == StreamContract.EVENT_RECORDING_AUDIO_LOST
            }.second
            assertEquals("audio_silence_samples", payload["reason"])
            assertEquals(5_000L, payload["silenceMs"])
            assertEquals(12_345L, payload["silenceStartOffsetMs"])
            assertEquals(0, payload["silencePeak"])
            assertEquals(RecordingState.RECORDING, session.currentState())
            // El video continúa tras la pérdida.
            fakeEncoder.deliverSample(66_666L, false)
            fakeEncoder.deliverSample(100_000L, false)
            assertEquals(
                listOf(0L, 33_333L, 66_666L, 100_000L),
                muxers[0].writtenPtsByTrack[0],
            )
            val result = session.stop()
            assertEquals("ready", result.status)
            assertEquals(1, result.segments.size)
            assertEquals(
                "audio_silence_samples",
                result.segments[0].audioReason,
            )
            assertEquals(0, result.silencePeak)
        }

    @Test
    fun `T-S12 tras la perdida por silencio la rotacion recupera el audio en el segmento siguiente`() =
        runBlocking {
            // Grupo S (S1): como la rotación tras la pérdida por
            // `audio_dead_object`, pero con el motivo nuevo: el
            // segmento 0 conserva su pista con las muestras previas
            // a la pérdida y el segmento 1 recupera el audio.
            val session = buildSession()
            startRecording(session, audioEnabled = true)
            fakeEncoder.deliverSample(0L, true)
            fakeAudioEncoder.deliverSample(0L)
            fakeAudioEncoder.deliverSample(21_333L)
            fakeEncoder.deliverSample(1_000_000L, false)
            // Pérdida a los ~2 s: el segmento 0 conserva su pista con
            // las muestras que llegaron; la recreación arranca el
            // encoder nuevo (mismo falso) para el segmento siguiente.
            fakeAudioEncoder.deliverError(
                reason = "audio_silence_samples",
                extraDetails = mapOf(
                    "silenceMs" to 5_000L,
                    "silenceStartOffsetMs" to 2_000L,
                    "silencePeak" to 0,
                ),
            )
            // Las muestras del encoder nuevo a mitad de segmento se
            // descartan (D7: reanudación solo en el siguiente).
            for (second in 2..62) {
                fakeEncoder.deliverSample(
                    second * 1_000_000L,
                    second % 2 == 0,
                )
                fakeAudioEncoder.deliverSample(second * 1_000_000L + 500_000L)
            }
            fakeEncoder.deliverSample(63_000_000L, false)
            assertEquals(2, muxers.size)
            // Segmento 0: solo las muestras de audio previas a la pérdida.
            assertEquals(
                listOf(0L, 21_333L),
                muxers[0].writtenPtsByTrack[1],
            )
            // Segmento 1: recupera la pista con el encoder nuevo.
            assertEquals(
                listOf(500_000L, 1_500_000L, 2_500_000L),
                muxers[1].writtenPtsByTrack[1],
            )
            val result = session.stop()
            assertEquals(2, result.segments.size)
            assertEquals(
                "audio_silence_samples",
                result.segments[0].audioReason,
            )
            assertEquals(null, result.segments[1].audioReason)
            assertEquals(0, result.silencePeak)
        }

    @Test
    fun `apertura de segmento fallida via post no mata el hilo del encoder`() =
        runBlocking {
            // Ronda U (H3): `maybeOpenSegment` re-lanza tras
            // `completeExceptionally`; dentro de un `post` eso mataba
            // el hilo del encoder. La guarda lo convierte en error
            // de inicio sin matar el hilo.
            val session = buildSession(
                audioFormatTimeoutMs = 10_000L,
                muxerFactory = {
                    throw RecordingException.muxerError(
                        "Muxer roto.",
                        mapOf("stage" to "openSegment"),
                    )
                },
            )
            fakeAudioEncoder.deliverFormatOnStart = false
            // `start()` espera el formato AAC; se entrega desde otro
            // hilo cuando la sesión ya asignó el listener, como en
            // producción. Sin `async`: la excepción del `Deferred`
            // reaparecía al cerrar `runBlocking` aunque `await()` la
            // consumiera, y JUnit la reportaba como fallo del test.
            val deliverer = Thread {
                while (fakeAudioEncoder.listener == null) {
                    Thread.sleep(10)
                }
                fakeAudioEncoder.deliverFormat()
            }
            deliverer.start()
            // `assertFailsWith` no acepta un lambda suspend: se
            // captura la excepción con `runCatching`.
            val thrown = runCatching {
                startRecording(session, audioEnabled = true)
            }.exceptionOrNull()
            deliverer.join()
            assertTrue(
                thrown is RecordingException &&
                    thrown.code == StreamContract.MUXER_ERROR,
            )
            assertTrue(
                eventTypes().contains(
                    StreamContract.EVENT_RECORDING_ERROR,
                ),
            )
            assertEquals(RecordingState.ERROR, session.currentState())
            assertTrue(fakeEncoder.uncaught.isEmpty())
        }

    @Test
    fun `stop propaga la capacidad del buffer de entrada AAC`() =
        runBlocking {
            // Ronda U: la capacidad se captura al parar, antes de
            // liberar el encoder (como `droppedInputBuffers`).
            fakeAudioEncoder.inputBufferCapacityBytes = 2048
            val session = buildSession()
            startRecording(session, audioEnabled = true)
            fakeEncoder.deliverSample(0L, true)
            val result = session.stop()
            assertEquals(2048, result.inputBufferCapacityBytes)
            assertEquals(2048, result.toMap()["inputBufferCapacityBytes"])
        }

    @Test
    fun `stop propaga el segundo mas silencioso de la grabacion`() =
        runBlocking {
            // Grupo S (S0): el mínimo por segundo se captura al
            // parar, antes de liberar el encoder (como la capacidad
            // del buffer). Valores distintivos a propósito (ningún
            // defecto coincide con ellos).
            fakeAudioEncoder.quietestSecondPeak = 12
            fakeAudioEncoder.quietestSecondNonZero = 3
            val session = buildSession()
            startRecording(session, audioEnabled = true)
            fakeEncoder.deliverSample(0L, true)
            val result = session.stop()
            assertEquals(12, result.quietestSecondPeak)
            assertEquals(3, result.quietestSecondNonZero)
            assertEquals(12, result.toMap()["quietestSecondPeak"])
            assertEquals(3, result.toMap()["quietestSecondNonZero"])
        }

    @Test
    fun `rotacion tras la perdida recupera el audio en el segmento siguiente`() =
        runBlocking {
            val session = buildSession()
            startRecording(session, audioEnabled = true)
            fakeEncoder.deliverSample(0L, true)
            fakeAudioEncoder.deliverSample(0L)
            fakeAudioEncoder.deliverSample(21_333L)
            fakeEncoder.deliverSample(1_000_000L, false)
            // Pérdida a los ~2 s: el segmento 0 conserva su pista con
            // las muestras que llegaron; la recreación arranca el
            // encoder nuevo (mismo falso) para el segmento siguiente.
            fakeAudioEncoder.deliverError()
            // Las muestras del encoder nuevo a mitad de segmento se
            // descartan (D7: reanudación solo en el siguiente).
            for (second in 2..62) {
                fakeEncoder.deliverSample(
                    second * 1_000_000L,
                    second % 2 == 0,
                )
                fakeAudioEncoder.deliverSample(second * 1_000_000L + 500_000L)
            }
            fakeEncoder.deliverSample(63_000_000L, false)
            assertEquals(2, muxers.size)
            // Segmento 0: solo las muestras de audio previas a la pérdida.
            assertEquals(
                listOf(0L, 21_333L),
                muxers[0].writtenPtsByTrack[1],
            )
            // Segmento 1: recupera la pista con el encoder nuevo.
            assertEquals(
                listOf(500_000L, 1_500_000L, 2_500_000L),
                muxers[1].writtenPtsByTrack[1],
            )
            val result = session.stop()
            assertEquals(2, result.segments.size)
            assertEquals("audio_dead_object", result.segments[0].audioReason)
            assertEquals(null, result.segments[1].audioReason)
        }

    @Test
    fun `video-only sin pedir audio es identico a 3_1_2`() =
        runBlocking {
            val session = buildSession()
            val info = startRecording(session)
            assertFalse(info.audioEnabled)
            assertEquals(null, info.audioReason)
            assertEquals(null, info.audioSampleRate)
            assertEquals(0, fakeAudioEncoder.configureCalls)
            assertEquals(1, muxers[0].writtenPtsByTrack.size)
            val snapshot = session.statusSnapshot()
            assertFalse(snapshot.audioEnabled)
            assertFalse(snapshot.audioActive)
            assertEquals(null, snapshot.audioReason)
            fakeEncoder.deliverSample(0L, true)
            fakeEncoder.deliverSample(33_333L, false)
            val result = session.stop()
            assertEquals("ready", result.status)
            assertEquals(false, fakeVerifier.lastExpectedAudio)
            assertFalse(result.audioEnabled)
        }

    @Test
    fun `audio que falla al arrancar degrada a video-only`() =
        runBlocking {
            // Fallo genérico sin motivo en `details`: el motivo sigue
            // siendo `audio_mic_unavailable` (grupo F, ronda 3).
            fakeAudioEncoder.configureError = RecordingException.audioError(
                "Micrófono no disponible.",
            )
            val session = buildSession()
            val info = startRecording(session, audioEnabled = true)
            assertEquals(RecordingState.RECORDING, session.currentState())
            assertFalse(info.audioEnabled)
            assertEquals("audio_mic_unavailable", info.audioReason)
            assertEquals(1, muxers[0].writtenPtsByTrack.size)
            fakeEncoder.deliverSample(0L, true)
            val result = session.stop()
            assertEquals("ready", result.status)
        }

    @Test
    fun `audio que no arranca degrada a video-only con audio_capture_not_started`() =
        runBlocking {
            // Grupo F (ronda 3): la captura que no arranca trae su
            // motivo en `details["reason"]` y `tryStartAudio` lo
            // propaga (no `audio_mic_unavailable`); la grabación
            // arranca video-only (regla de oro).
            fakeAudioEncoder.configureError = RecordingException.audioError(
                "La captura de audio no arrancó (estado=1).",
                mapOf(
                    "reason" to "audio_capture_not_started",
                    "recordingState" to 1,
                ),
            )
            val session = buildSession()
            val info = startRecording(session, audioEnabled = true)
            assertEquals(RecordingState.RECORDING, session.currentState())
            assertFalse(info.audioEnabled)
            assertEquals("audio_capture_not_started", info.audioReason)
            assertEquals(1, muxers[0].writtenPtsByTrack.size)
            fakeEncoder.deliverSample(0L, true)
            val result = session.stop()
            assertEquals("ready", result.status)
        }

    // --- Instrumentación de audio (3.1.3, ronda de diagnóstico) ---

    @Test
    fun `toMap lleva audioStartOffsetMs con valor y con null`() {
        // Con valor: el offset viaja en el evento `recording_segment`.
        val withOffset = RecordingSegmentResult(
            recordingId = "rec_20261119_151552_e2e0",
            index = 0,
            fileName = "rec_20261119_151552_e2e0_seg000.mp4",
            filePath = "/docs/rec_20261119_151552_e2e0_seg000.mp4",
            status = SegmentStatus.PARTIAL,
            durationMs = 30_625L,
            sizeBytes = 9_500_000L,
            sampleCount = 918,
            keyframeCount = 15,
            firstSampleKeyframe = true,
            ptsMonotonic = true,
            profileIdc = 66,
            closedCleanly = true,
            reason = "sin_pista_audio",
            audioStartOffsetMs = 500L,
        )
        assertEquals(500L, withOffset.toMap()["audioStartOffsetMs"])
        // Sin audio: la clave existe con `null` (nunca se inventa un
        // número); Dart lo dibuja como `—`.
        val withoutAudio = withOffset.copy(audioStartOffsetMs = null)
        val map = withoutAudio.toMap()
        assertTrue(map.containsKey("audioStartOffsetMs"))
        assertEquals(null, map["audioStartOffsetMs"])
    }

    @Test
    fun `verifyAndRecord copia audioStartOffsetMs del segmento cerrado`() =
        runBlocking {
            val session = buildSession()
            startRecording(session, audioEnabled = true)
            // Primer video en 0 y primer audio 500 ms después: el
            // origen común es 0 y el offset es 500 ms.
            fakeEncoder.deliverSample(0L, true)
            fakeAudioEncoder.deliverSample(500_000L)
            fakeEncoder.deliverSample(33_333L, false)
            val result = session.stop()
            assertEquals("ready", result.status)
            assertEquals(1, result.segments.size)
            assertEquals(500L, result.segments[0].audioStartOffsetMs)
            val segmentPayload = events.first {
                it.first == StreamContract.EVENT_RECORDING_SEGMENT
            }.second
            assertEquals(500L, segmentPayload["audioStartOffsetMs"])
        }

    @Test
    fun `verifyAndRecord deja audioStartOffsetMs en null sin audio`() =
        runBlocking {
            val session = buildSession()
            startRecording(session)
            fakeEncoder.deliverSample(0L, true)
            fakeEncoder.deliverSample(33_333L, false)
            val result = session.stop()
            assertEquals("ready", result.status)
            assertEquals(1, result.segments.size)
            assertEquals(null, result.segments[0].audioStartOffsetMs)
        }

    @Test
    fun `fromSegments y toMap llevan droppedInputBuffers con valor y por defecto`() {
        val segment = RecordingSegmentResult(
            recordingId = "rec_20261119_151552_e2e0",
            index = 0,
            fileName = "rec_20261119_151552_e2e0_seg000.mp4",
            filePath = "/docs/rec_20261119_151552_e2e0_seg000.mp4",
            status = SegmentStatus.READY,
            durationMs = 3_000L,
            sizeBytes = 900_000L,
            sampleCount = 90,
            keyframeCount = 2,
            firstSampleKeyframe = true,
            ptsMonotonic = true,
            profileIdc = 66,
            closedCleanly = true,
            reason = null,
        )
        val withDropped = RecordingResult.fromSegments(
            recordingId = "rec_20261119_151552_e2e0",
            segments = listOf(segment),
            errorCode = null,
            previewRestoreError = null,
            droppedInputBuffers = 7,
        )
        assertEquals(7, withDropped.droppedInputBuffers)
        assertEquals(7, withDropped.toMap()["droppedInputBuffers"])
        // Sin pasar el parámetro, el defecto es 0.
        val byDefault = RecordingResult.fromSegments(
            recordingId = "rec_20261119_151552_e2e0",
            segments = listOf(segment),
            errorCode = null,
            previewRestoreError = null,
        )
        assertEquals(0, byDefault.droppedInputBuffers)
        assertEquals(0, byDefault.toMap()["droppedInputBuffers"])
    }

    @Test
    fun `fromSegments y toMap llevan los campos de instrumentacion ronda 3 con valor y por defecto`() {
        val segment = RecordingSegmentResult(
            recordingId = "rec_20261119_151552_e2e0",
            index = 0,
            fileName = "rec_20261119_151552_e2e0_seg000.mp4",
            filePath = "/docs/rec_20261119_151552_e2e0_seg000.mp4",
            status = SegmentStatus.READY,
            durationMs = 3_000L,
            sizeBytes = 900_000L,
            sampleCount = 90,
            keyframeCount = 2,
            firstSampleKeyframe = true,
            ptsMonotonic = true,
            profileIdc = 66,
            closedCleanly = true,
            reason = null,
        )
        val withValues = RecordingResult.fromSegments(
            recordingId = "rec_20261119_151552_e2e0",
            segments = listOf(segment),
            errorCode = null,
            previewRestoreError = null,
            micStateAtStart = 3,
            micStateAtStop = 3,
            audioReadCount = 42,
            audioLastReadCode = 4096,
            audioLastReadOkAgeMs = 12_345L,
            audioReaderAlive = true,
            audioEos = true,
            audioReaderNoData2s = true,
            eosTimedOut = true,
        )
        assertEquals(3, withValues.micStateAtStart)
        assertEquals(3, withValues.micStateAtStop)
        assertEquals(42, withValues.audioReadCount)
        assertEquals(4096, withValues.audioLastReadCode)
        assertEquals(12_345L, withValues.audioLastReadOkAgeMs)
        assertEquals(true, withValues.audioReaderAlive)
        assertEquals(true, withValues.audioEos)
        assertEquals(true, withValues.audioReaderNoData2s)
        assertEquals(true, withValues.eosTimedOut)
        val withMap = withValues.toMap()
        assertEquals(3, withMap["micStateAtStart"])
        assertEquals(3, withMap["micStateAtStop"])
        assertEquals(42, withMap["audioReadCount"])
        assertEquals(4096, withMap["audioLastReadCode"])
        assertEquals(12_345L, withMap["audioLastReadOkAgeMs"])
        assertEquals(true, withMap["audioReaderAlive"])
        assertEquals(true, withMap["audioEos"])
        assertEquals(true, withMap["audioReaderNoData2s"])
        assertEquals(true, withMap["eosTimedOut"])
        // Sin pasar los parámetros, los defectos son null/0/false.
        val byDefault = RecordingResult.fromSegments(
            recordingId = "rec_20261119_151552_e2e0",
            segments = listOf(segment),
            errorCode = null,
            previewRestoreError = null,
        )
        assertEquals(null, byDefault.micStateAtStart)
        assertEquals(null, byDefault.micStateAtStop)
        assertEquals(0, byDefault.audioReadCount)
        assertEquals(null, byDefault.audioLastReadCode)
        assertEquals(null, byDefault.audioLastReadOkAgeMs)
        assertEquals(false, byDefault.audioReaderAlive)
        assertEquals(false, byDefault.audioEos)
        assertEquals(false, byDefault.audioReaderNoData2s)
        assertEquals(false, byDefault.eosTimedOut)
        assertEquals(null, byDefault.toMap()["micStateAtStart"])
        assertEquals(0, byDefault.toMap()["audioReadCount"])
        assertEquals(false, byDefault.toMap()["audioEos"])
        assertEquals(false, byDefault.toMap()["audioReaderNoData2s"])
        assertEquals(false, byDefault.toMap()["eosTimedOut"])
    }

    @Test
    fun `stop propaga el estado del microfono y los contadores del lector`() =
        runBlocking {
            val session = buildSession()
            startRecording(session, audioEnabled = true)
            fakeEncoder.deliverSample(0L, true)
            fakeAudioEncoder.deliverSample(0L)
            fakeEncoder.deliverSample(33_333L, false)
            // Caso H1 (lector bloqueado en `read()`): arranque con
            // estado RECORDING pero ninguna lectura devuelta y el
            // hilo vivo. `lastSuccessfulReadElapsedMs` queda en
            // `null` a propósito: con un valor, `doStop` llamaría a
            // `SystemClock.elapsedRealtime()`, que en JVM (harness
            // y CI) lanza `Stub!`; la resta solo corre en el
            // teléfono. La propagación del valor de la edad queda
            // cubierta por el test de `fromSegments`/`toMap`.
            fakeAudioEncoder.recordingStateAtStart = 3
            fakeAudioEncoder.recordingState = 3
            fakeAudioEncoder.readCount = 0
            fakeAudioEncoder.lastReadCode = null
            fakeAudioEncoder.lastSuccessfulReadElapsedMs = null
            fakeAudioEncoder.isReaderAlive = true
            // Grupo V (V3): el watchdog disparó en este arranque.
            fakeAudioEncoder.readerNoData2s = true
            val result = session.stop()
            assertEquals("ready", result.status)
            assertEquals(3, result.micStateAtStart)
            assertEquals(3, result.micStateAtStop)
            assertEquals(0, result.audioReadCount)
            assertEquals(null, result.audioLastReadCode)
            assertEquals(null, result.audioLastReadOkAgeMs)
            assertEquals(true, result.audioReaderAlive)
            // El falso entrega el EOS al parar: `audioEos` es `true`.
            assertEquals(true, result.audioEos)
            assertEquals(true, result.audioReaderNoData2s)
            // El EOS llegó sin agotar los 5 s: `eosTimedOut` es `false`.
            assertEquals(false, result.eosTimedOut)
            val stoppedPayload = events.first {
                it.first == StreamContract.EVENT_RECORDING_STOPPED
            }.second
            assertEquals(3, stoppedPayload["micStateAtStart"])
            assertEquals(3, stoppedPayload["micStateAtStop"])
            assertEquals(0, stoppedPayload["audioReadCount"])
            assertEquals(null, stoppedPayload["audioLastReadCode"])
            assertEquals(null, stoppedPayload["audioLastReadOkAgeMs"])
            assertEquals(true, stoppedPayload["audioReaderAlive"])
            assertEquals(true, stoppedPayload["audioEos"])
            assertEquals(true, stoppedPayload["audioReaderNoData2s"])
            assertEquals(false, stoppedPayload["eosTimedOut"])
        }

    // --- Subetapa 0, grupo 0-c: registro de coordinación (U39–U42, U49) ---

    private fun failedStartRecordingId(): String {
        val errorEvent = synchronized(events) {
            events.firstOrNull {
                it.first == StreamContract.EVENT_RECORDING_ERROR
            }
        }
        assertNotNull(
            "failStart emite recording_error con el id",
            errorEvent,
        )
        val id = errorEvent!!.second["recordingId"] as? String
        assertNotNull("el evento de error trae recordingId", id)
        return id!!
    }

    @Test
    fun `U39 colision con grupo reservado, se agotan los intentos sin tocar G`() =
        runBlocking {
            val registry = ActiveRecordingRegistry()
            // Reservar TODOS los ids posibles del segundo fijado
            // (sufijo de 16 bits): cualquier id que genere el inicio
            // esta reservado, asi que los 10 intentos del claim
            // fracasan de forma determinista.
            val stampPrefix =
                fileStore.newRecordingId(fixedClockMs).dropLast(5)
            val reservedTokens = mutableMapOf<String, OwnershipToken>()
            for (suffix in 0 until 0x10000) {
                val id = "%s_%04x".format(stampPrefix, suffix)
                val token = registry.tryReserveGroup(id, "reconcile:u39")
                assertNotNull("reserva de $id", token)
                reservedTokens[id] = token!!
            }
            val session = buildSession(registry = registry)
            val thrown = assertThrows(RecordingException::class.java) {
                runBlocking { startRecording(session) }
            }
            assertEquals(
                StreamContract.RECORDING_STATE_ERROR,
                thrown.code,
            )
            assertEquals("id_reservation", thrown.details["stage"])
            assertEquals(RecordingState.ERROR, session.currentState())
            // Sin encoder, sin archivos: el inicio no paso del claim.
            assertEquals(0, fakeEncoder.configureCalls)
            assertTrue(dir.listFiles().isNullOrEmpty())
            // G intacto: todos los tokens siguen iguales.
            val snapshot = registry.snapshot()
            assertEquals(0x10000, snapshot.reservedGroups.size)
            assertTrue(snapshot.activeClaims.isEmpty())
            val sampleId = reservedTokens.keys.first()
            assertEquals(
                reservedTokens[sampleId],
                snapshot.reservedGroups[sampleId]?.token,
            )

            // Recuperacion: con el reloj en el segundo siguiente (sin
            // reservas) el inicio desde ERROR tiene exito con un id
            // nuevo, sin tocar G.
            fixedClockMs += 1_000L
            val info = startRecording(session)
            assertEquals(RecordingState.RECORDING, session.currentState())
            assertTrue(info.recordingId !in reservedTokens)
            assertNull(
                registry.tryReserveGroup(
                    info.recordingId,
                    "reconcile:u39",
                ),
            )
            val result = session.stop()
            assertEquals("ready", result.status)
            val afterStop = registry.snapshot()
            assertEquals(0x10000, afterStop.reservedGroups.size)
            assertTrue(afterStop.activeClaims.isEmpty())
            assertEquals(
                reservedTokens[sampleId],
                afterStop.reservedGroups[sampleId]?.token,
            )
        }

    @Test
    fun `U40 liberacion al parar, reserva fracasa durante stop y tiene exito despues`() =
        runBlocking {
            val registry = ActiveRecordingRegistry()
            val session = buildSession(registry = registry)
            // La verificacion tarda 2 s: `stop()` queda en vuelo
            // mientras el test sondea el claim.
            fakeVerifier.verifyDelayMs = 2_000L
            val info = startRecording(session)
            val id = info.recordingId
            // Claim vivo: la reserva fracasa.
            assertNull(registry.tryReserveGroup(id, "reconcile:run-u40"))
            // Liberacion ajena: NOT_OWNER y no libera.
            val foreignToken = registry.tryClaimRecording("rec_u40_ajeno")!!
            assertEquals(
                ReleaseResult.NOT_OWNER,
                registry.releaseRecording(id, foreignToken),
            )
            assertEquals(
                ReleaseResult.RELEASED,
                registry.releaseRecording("rec_u40_ajeno", foreignToken),
            )
            assertNull(registry.tryReserveGroup(id, "reconcile:run-u40"))

            val stopDeferred = async { session.stop() }
            withTimeout(5_000L) {
                while (fakeVerifier.verifyCalls == 0) delay(10)
            }
            // Durante `stop()` (verificacion en vuelo) el claim sigue
            // vivo: la reserva fracasa y el token ajeno no libera.
            assertNull(
                "reserva durante stop()",
                registry.tryReserveGroup(id, "reconcile:run-u40"),
            )
            assertEquals(
                ReleaseResult.NOT_OWNER,
                registry.releaseRecording(id, foreignToken),
            )
            val result = stopDeferred.await()
            assertEquals("ready", result.status)
            assertEquals(RecordingState.IDLE, session.currentState())
            // Tras parar, el claim esta liberado: la reserva tiene exito.
            val reservation = registry.tryReserveGroup(id, "reconcile:run-u40")
            assertNotNull(reservation)
            assertEquals(
                ReleaseResult.RELEASED,
                registry.releaseGroup(id, reservation!!),
            )
        }

    @Test
    fun `U41 liberacion en failStart, reserva posterior tiene exito`() =
        runBlocking {
            fakeEncoder.deliverFormatOnStart = false
            val registry = ActiveRecordingRegistry()
            val session = buildSession(formatTimeoutMs = 100L, registry = registry)
            val thrown = assertThrows(RecordingException::class.java) {
                runBlocking { startRecording(session) }
            }
            assertEquals(StreamContract.ENCODER_ERROR, thrown.code)
            assertEquals(RecordingState.ERROR, session.currentState())
            val id = failedStartRecordingId()
            // Reserva posterior tiene exito: el claim se libero en
            // `failStart()`.
            val reservation = registry.tryReserveGroup(id, "reconcile:run-u41")
            assertNotNull(reservation)
            assertEquals(
                ReleaseResult.RELEASED,
                registry.releaseGroup(id, reservation!!),
            )
            // Sub-caso de U48(ii) a nivel de sesion: reclamar el id de
            // nuevo y liberar con un token ajeno no toca el claim
            // nuevo. (El token obsoleto exacto de la sesion es privado;
            // el caso obsoleto-del-mismo-id lo cubre U48(ii) a nivel de
            // registro; aqui el token ajeno es de otro id, tambien
            // valido y nunca emitido para este id.)
            val newToken = registry.tryClaimRecording(id)
            assertNotNull(newToken)
            val foreign = registry.tryClaimRecording("rec_u41_ajeno")!!
            assertEquals(
                ReleaseResult.NOT_OWNER,
                registry.releaseRecording(id, foreign),
            )
            assertEquals(
                ReleaseResult.RELEASED,
                registry.releaseRecording("rec_u41_ajeno", foreign),
            )
            assertNull(registry.tryReserveGroup(id, "reconcile:run-u41"))
            assertEquals(
                newToken,
                registry.snapshot().activeClaims[id]?.token,
            )
            assertEquals(
                ReleaseResult.RELEASED,
                registry.releaseRecording(id, newToken!!),
            )
        }

    @Test
    fun `U42 reserva liberada siempre en finally, doble liberacion tolerada`() =
        runBlocking {
            // U42 a nivel de sesion, sobre el registro inyectado en la
            // sesion: el `finally` de la reconciliacion (0-d, todavia
            // sin `reconcileRecordings()`) debe poder llamar a
            // `releaseGroup` incondicionalmente. El `finally` real de
            // la reconciliacion lo cubre 0-d.
            val registry = ActiveRecordingRegistry()
            buildSession(registry = registry)
            val id = "rec_u42"
            val token = registry.tryReserveGroup(id, "reconcile:run-u42")
            assertNotNull(token)
            try {
                assertNull(
                    "grupo reservado: el claim no pasa",
                    registry.tryClaimRecording(id),
                )
            } finally {
                assertEquals(
                    ReleaseResult.RELEASED,
                    registry.releaseGroup(id, token!!),
                )
            }
            // Segundo `finally`: doble liberacion tolerada (NOT_FOUND,
            // no excepcion).
            assertEquals(
                ReleaseResult.NOT_FOUND,
                registry.releaseGroup(id, token),
            )
            // Liberacion con token incorrecto desde un `finally` no libera.
            val second = registry.tryReserveGroup(id, "reconcile:run-u42b")
            assertNotNull(second)
            assertEquals(
                ReleaseResult.NOT_OWNER,
                registry.releaseGroup(id, token),
            )
            assertNull(
                "la reserva sigue viva tras el intento ajeno",
                registry.tryClaimRecording(id),
            )
            assertEquals(
                ReleaseResult.RELEASED,
                registry.releaseGroup(id, second!!),
            )
            val snapshot = registry.snapshot()
            assertTrue(snapshot.activeClaims.isEmpty())
            assertTrue(snapshot.reservedGroups.isEmpty())
        }

    @Test
    fun `U49 liberacion tras failStart, reserva posterior y doble release`() =
        runBlocking {
            fakeEncoder.deliverFormatOnStart = false
            val registry = ActiveRecordingRegistry()
            val session = buildSession(formatTimeoutMs = 100L, registry = registry)
            val thrown = assertThrows(RecordingException::class.java) {
                runBlocking { startRecording(session) }
            }
            assertEquals(StreamContract.ENCODER_ERROR, thrown.code)
            assertEquals(RecordingState.ERROR, session.currentState())
            val id = failedStartRecordingId()
            // Tras el fallo, el claim esta liberado: la reserva tiene exito.
            val reservation = registry.tryReserveGroup(id, "reconcile:run-u49")
            assertNotNull(reservation)
            // Segundo `release` con el mismo token de reserva: la
            // primera liberacion ya lo consumio, la segunda es
            // NOT_FOUND (el token del claim de la sesion es privado y
            // ya se libero en `failStart()`; el de reserva es el
            // observable por el test y tiene la misma semantica).
            assertEquals(
                ReleaseResult.RELEASED,
                registry.releaseGroup(id, reservation!!),
            )
            assertEquals(
                ReleaseResult.NOT_FOUND,
                registry.releaseGroup(id, reservation),
            )
            // ...y no libera una reserva posterior (token obsoleto
            // inofensivo).
            val second = registry.tryReserveGroup(id, "reconcile:run-u49b")
            assertNotNull(second)
            assertEquals(
                ReleaseResult.NOT_OWNER,
                registry.releaseGroup(id, reservation),
            )
            assertNull(
                "la reserva posterior sigue viva",
                registry.tryClaimRecording(id),
            )
            assertEquals(
                ReleaseResult.RELEASED,
                registry.releaseGroup(id, second!!),
            )
            val snapshot = registry.snapshot()
            assertTrue(snapshot.activeClaims.isEmpty())
            assertTrue(snapshot.reservedGroups.isEmpty())
        }

    // --- Subetapa 0, grupo 0-d1: renombre tras verificación (U3/U4),
    // fileName y evidencia en el sidecar (U6), salto por reserva
    // (U35), inicio durante la reconciliación (U36, I7). ---

    @Test
    fun `U3 renombre tras verificacion el sidecar refleja el nombre real`() =
        runBlocking {
            val session = buildSession()
            val info = startRecording(session)
            fakeEncoder.deliverSample(0L, true)
            fakeEncoder.deliverSample(33_333L, false)
            val result = session.stop()
            assertEquals(1, result.segments.size)
            assertEquals(SegmentStatus.READY, result.segments[0].status)
            val final = fileStore.segmentFile(info.recordingId, 0)
            assertTrue(final.exists())
            assertFalse(fileStore.segmentStagingFile(info.recordingId, 0).exists())
            val sidecar = fileStore.readSidecar(info.recordingId)
            assertTrue(sidecar != null)
            assertEquals(1, sidecar!!.segments.size)
            assertEquals("${info.recordingId}_seg000.mp4", sidecar.segments[0].fileName)
            assertEquals("ready", sidecar.segments[0].status)
        }

    @Test
    fun `U4 renombre fallido parcial nunca ready y log especifico`() =
        runBlocking {
            val logs = mutableListOf<String>()
            Transmiti2Logger.sink = { _, message ->
                synchronized(logs) { logs += message }
            }
            val session = buildSession()
            val info = startRecording(session)
            fakeEncoder.deliverSample(0L, true)
            fakeEncoder.deliverSample(33_333L, false)
            // Destino ya ocupado: el renombre no puede sobrescribirlo.
            fileStore.segmentFile(info.recordingId, 0).writeText("ocupado")
            val result = session.stop()
            assertEquals(1, result.segments.size)
            assertEquals(SegmentStatus.PARTIAL, result.segments[0].status)
            assertEquals("partial", result.status)
            assertFalse(result.segments[0].closedCleanly)
            assertTrue(logs.any { it.contains("renombre del segmento 0") })
            assertTrue(fileStore.segmentStagingFile(info.recordingId, 0).exists())
            assertEquals(
                "ocupado",
                fileStore.segmentFile(info.recordingId, 0).readText(),
            )
        }

    @Test
    fun `U6 fileName y evidencia de verificacion en el sidecar tras verifyAndRecord`() =
        runBlocking {
            val session = buildSession()
            val info = startRecording(session)
            fakeEncoder.deliverSample(0L, true)
            fakeEncoder.deliverSample(33_333L, false)
            session.stop()
            val sidecar = fileStore.readSidecar(info.recordingId)
            assertTrue(sidecar != null)
            val entry = sidecar!!.segments[0]
            assertEquals("${info.recordingId}_seg000.mp4", entry.fileName)
            assertEquals("ready", entry.status)
            assertEquals(3000L, entry.durationMs)
            assertEquals(1_792_000_000_000L, entry.verifiedAtMs)
            assertEquals(1, entry.verifierVersion)
            assertNull(entry.verificationReason)
            assertEquals(30.0, entry.fpsMeasured)
        }

    @Test
    fun `U35 reconciliacion salta la grabacion activa por reserva no por id`() =
        runBlocking {
            val registry = ActiveRecordingRegistry()
            val session = buildSession(registry = registry)
            val info = startRecording(session)
            fakeEncoder.deliverSample(0L, true)
            // Otra grabación interrumpida en disco.
            fileStore.segmentStagingFile("rec_9", 0).writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val summary = session.reconcileRecordings()
            assertEquals(listOf(info.recordingId), summary.groupsSkippedActive)
            assertEquals(1, summary.groupsReconciled)
            assertEquals(1, summary.segments.size)
            assertEquals("rec_9", summary.segments[0].recordingId)
            // El grupo activo no se toca: sigue en staging, sin verificar.
            assertTrue(fileStore.segmentStagingFile(info.recordingId, 0).exists())
            assertFalse(fileStore.segmentFile(info.recordingId, 0).exists())
            assertFalse(fakeVerifier.verifiedFiles.any {
                it.startsWith(info.recordingId)
            })
            // El claim sigue vivo en el registro: el salto fue por reserva.
            val snapshot = registry.snapshot()
            assertTrue(snapshot.activeClaims.containsKey(info.recordingId))
            assertTrue(snapshot.reservedGroups.isEmpty())
            // La grabación continúa y para con normalidad.
            fakeEncoder.deliverSample(33_333L, false)
            val result = session.stop()
            assertEquals("ready", result.status)
        }

    @Test
    fun `U36 grabacion que empieza durante la reconciliacion no espera y su tmp no se toca`() =
        runBlocking {
            val registry = ActiveRecordingRegistry()
            val session = buildSession(registry = registry)
            fileStore.segmentStagingFile("rec_old", 0).writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val latch = CountDownLatch(1)
            fakeVerifier.blockBeforeVerify = {
                latch.await(10, TimeUnit.SECONDS)
            }
            val reconcileJob = async(Dispatchers.IO) {
                session.reconcileRecordings()
            }
            // Esperar a que la reconciliación entre en la verificación.
            withTimeout(5_000L) {
                while (fakeVerifier.verifyCalls == 0) delay(10)
            }
            // El inicio con id nuevo completa sin esperar a la reconciliación.
            val info = withTimeout(5_000L) { startRecording(session) }
            assertEquals(RecordingState.RECORDING, session.currentState())
            fakeEncoder.deliverSample(0L, true)
            assertTrue(fileStore.segmentStagingFile(info.recordingId, 0).exists())
            // Soltar la verificación y esperar a la reconciliación.
            latch.countDown()
            val summary = reconcileJob.await()
            assertEquals(1, summary.groupsReconciled)
            // El grupo nuevo nunca se procesó: no está en el resumen (el
            // inventario ya había corrido) ni se verificó su `.tmp`.
            assertTrue(summary.segments.none { it.recordingId == info.recordingId })
            assertFalse(fakeVerifier.verifiedFiles.any {
                it.startsWith(info.recordingId)
            })
            assertTrue(fileStore.segmentStagingFile(info.recordingId, 0).exists())
            assertFalse(fileStore.segmentFile(info.recordingId, 0).exists())
            fakeEncoder.deliverSample(33_333L, false)
            val result = session.stop()
            assertEquals("ready", result.status)
        }

    @Test
    fun `I7 inicio simultaneo controlado la reserva protege el grupo y se libera en finally`() =
        runBlocking {
            val registry = ActiveRecordingRegistry()
            val session = buildSession(registry = registry)
            fileStore.segmentStagingFile("rec_old", 0).writeText("01234567890")
            fakeVerifier.result = readyVerification()
            val latch = CountDownLatch(1)
            fakeVerifier.blockBeforeVerify = {
                latch.await(10, TimeUnit.SECONDS)
            }
            val reconcileJob = async(Dispatchers.IO) {
                session.reconcileRecordings()
            }
            withTimeout(5_000L) {
                while (fakeVerifier.verifyCalls == 0) delay(10)
            }
            // (1) G está en `reservedGroups` con un token mientras el
            // verificador está bloqueado.
            val during = registry.snapshot()
            val reserved = during.reservedGroups["rec_old"]
            assertNotNull(reserved)
            assertTrue(reserved!!.token > 0)
            // (2) Ningún segundo intento de reservar G tiene éxito.
            assertNull(registry.tryReserveGroup("rec_old", "reconcile:otro"))
            assertNull(registry.tryClaimRecording("rec_old"))
            // El inicio con id nuevo completa en ese instante.
            val info = withTimeout(5_000L) { startRecording(session) }
            fakeEncoder.deliverSample(0L, true)
            assertEquals(RecordingState.RECORDING, session.currentState())
            latch.countDown()
            val summary = reconcileJob.await()
            assertEquals(1, summary.groupsReconciled)
            assertTrue(summary.segments.any { it.recordingId == "rec_old" })
            assertTrue(summary.segments.none { it.recordingId == info.recordingId })
            assertEquals(reserved.token, summary.reservationTokens["rec_old"])
            // (3) Tras DONE, el token de G desaparece (liberación en
            // `finally` verificada de extremo a extremo).
            val after = registry.snapshot()
            assertFalse(after.reservedGroups.containsKey("rec_old"))
            assertEquals(info.recordingId, after.activeClaims.keys.single())
            // El `.tmp` nuevo no aparece en ningún resultado.
            assertTrue(fileStore.segmentStagingFile(info.recordingId, 0).exists())
            fakeEncoder.deliverSample(33_333L, false)
            val result = session.stop()
            assertEquals("ready", result.status)
        }

}
