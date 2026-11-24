package com.transmiti2.transmiti2.streaming.test

import android.media.MediaFormat
import android.view.Surface
import com.transmiti2.transmiti2.streaming.FpsRange
import com.transmiti2.transmiti2.streaming.PreviewConfig
import com.transmiti2.transmiti2.streaming.PreviewSession
import com.transmiti2.transmiti2.streaming.PreviewSessionListener
import com.transmiti2.transmiti2.streaming.StreamContract
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import com.transmiti2.transmiti2.streaming.VideoSize
import com.transmiti2.transmiti2.streaming.recording.ActiveRecordingRegistry
import com.transmiti2.transmiti2.streaming.recording.EncoderCapabilities
import com.transmiti2.transmiti2.streaming.recording.RecordingException
import com.transmiti2.transmiti2.streaming.recording.RecordingFileStore
import com.transmiti2.transmiti2.streaming.recording.RecordingMuxer
import com.transmiti2.transmiti2.streaming.recording.RecordingSession
import com.transmiti2.transmiti2.streaming.recording.SegmentStatus
import com.transmiti2.transmiti2.streaming.recording.SegmentVerification
import com.transmiti2.transmiti2.streaming.recording.SegmentVerifier
import com.transmiti2.transmiti2.streaming.recording.VerificationChecks
import com.transmiti2.transmiti2.streaming.recording.VideoEncoderConfig
import com.transmiti2.transmiti2.streaming.recording.VideoEncoderListener
import com.transmiti2.transmiti2.streaming.recording.VideoEncoderPort
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Guardas de inicio duplicado y ciclo del claim (diseño base §8, caso
 * incidente F7b 2026-11-23; FASE 2, grupo 2-c). Sesión real con
 * encoder, muxer, sesión de cámara y verificador falsos; sin cámara
 * ni MediaCodec reales.
 *
 * `Surface` y `MediaFormat` se crean con `Unsafe.allocateInstance`
 * (no con sus constructores): el `android.jar` que usa el harness
 * local lanza `Stub!` en los constructores, mientras que el jar
 * mockable de Gradle (CI) no. La sesión nunca llama a métodos sobre
 * estos objetos (solo los pasa: la superficie va a la sesión de
 * cámara falsa y el formato al muxer falso), así que una instancia
 * sin inicializar es suficiente en ambos entornos.
 *
 * NOTA DE FIDELIDAD (fix CI #109): `sun.misc.Unsafe` NO se importa
 * directamente. Gradle en CI compila los tests con el bootclasspath
 * de Android (android.jar), donde esa clase JDK interna no existe,
 * y el import directo rompía la compilación del paso 13 sin ejecutar
 * ninguna prueba (run #109). Por eso se obtiene por reflexión en
 * tiempo de ejecución (`Class.forName` + campo `theUnsafe` + método
 * `allocateInstance`): en tiempo de compilación solo aparece la
 * cadena literal, y en tiempo de ejecución (JVM del host, tanto en
 * el harness local como en los unit tests de Gradle) la clase sí
 * existe.
 */
class RecordingSessionStartGuardTest {

    /** Instancia sin llamar al constructor (ver cabecera: reflexión
     * en tiempo de ejecución porque el import directo de
     * `sun.misc.Unsafe` no compila en CI — run #109). */
    private fun <T> allocateInstance(clazz: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod(
            "allocateInstance",
            Class::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        return allocate.invoke(unsafe, clazz) as T
    }

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

    /** Encoder falso (recortado de `RecordingSessionTest`). */
    private class FakeEncoder(
        override val config: VideoEncoderConfig,
        private val allocate: (Class<Surface>) -> Surface,
        private val allocateFormat: () -> MediaFormat,
    ) : VideoEncoderPort {
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "FakeEncoder").apply { isDaemon = true }
        }
        override val surface: Surface? = allocate(Surface::class.java)
        override var outputFormat: MediaFormat? = null
        override var listener: VideoEncoderListener? = null
        var throwOnConfigure = false
        var deliverEosOnSignal = true

        override fun configureAndStart() {
            if (throwOnConfigure) throw IllegalStateException("encoder roto")
            val format = allocateFormat()
            outputFormat = format
            postAndWait { listener?.onOutputFormatChanged(format) }
        }

        override fun requestSyncFrame(): Boolean = true

        override fun signalEndOfInputStream() {
            if (deliverEosOnSignal) {
                post { listener?.onEndOfStream() }
            }
        }

        override fun stopDrain() {}

        override fun post(block: () -> Unit) {
            try {
                executor.execute(block)
            } catch (_: RejectedExecutionException) {
            }
        }

        override fun release() {}

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
    }

    /** Muxer falso (recortado de `RecordingSessionTest`; sin la guarda
     * de hilo: esa guarda es `internal` del módulo de producción y
     * este test no la necesita — la sesión ya encamina las llamadas
     * al muxer por el hilo del encoder). */
    private class FakeSessionMuxer(
        val path: String,
    ) : RecordingMuxer {
        override val name: String = "fake"
        private var nextTrackIndex = 0
        var started = false

        override fun addTrack(format: MediaFormat): Int {
            return nextTrackIndex++
        }

        override fun start() {
            started = true
            File(path).writeText("mp4")
        }

        override fun writeSampleData(
            trackIndex: Int,
            byteBuffer: ByteBuffer,
            bufferInfo: android.media.MediaCodec.BufferInfo,
        ) {
        }

        override fun stop() {}

        override fun release() {}

        override fun setOrientationHint(degrees: Int) {}
    }

    /** Sesión de cámara falsa (recortada de `RecordingSessionTest`). */
    private class FakePreviewSession(
        private val config: PreviewConfig,
    ) : PreviewSession {
        override var isOpen = true
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

    /** Verificador falso (mínimo de `RecordingReconciliationTest`). */
    private class FakeVerifier(
        var result: SegmentVerification,
    ) : SegmentVerifier {
        override suspend fun verify(
            file: File,
            closedCleanly: Boolean,
            expectedWidth: Int?,
            expectedHeight: Int?,
            expectedFps: Int?,
            expectedAudio: Boolean?,
            expectedSampleRate: Int?,
            expectedChannels: Int?,
        ): SegmentVerification = result
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
        private val allocate: (Class<Surface>) -> Surface,
        private val allocateFormat: () -> MediaFormat,
    ) {
        val clock = FakeTestClock()
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "start-guard-test-${System.nanoTime()}",
        ).apply { mkdirs() }
        val fileStore = RecordingFileStore(dir)
        val fakeEncoder = FakeEncoder(
            VideoEncoderConfig(1280, 720, 30, 2_500_000),
            allocate,
            allocateFormat,
        )
        val fakePreview = FakePreviewSession(
            PreviewConfig(
                cameraId = "0",
                size = VideoSize(1280, 720),
                fpsRange = FpsRange(30, 30),
                sensorOrientation = 90,
                lensFacing = 1,
            ),
        )
        val previewConfig = PreviewConfig(
            cameraId = "0",
            size = VideoSize(1280, 720),
            fpsRange = FpsRange(30, 30),
            sensorOrientation = 90,
            lensFacing = 1,
        )
        val fakeVerifier = FakeVerifier(readyVerification())
        val registry = ActiveRecordingRegistry()
        val instrumentation = TestInstrumentation(
            enabled = { true },
            clock = clock,
        )
        val syncPoints = TestSyncPoints(
            enabled = { true },
            instrumentation = instrumentation,
            clock = clock,
            intentAccess = FakeIntentAccess(),
        )
        val hooks: TestHooks = DefaultTestHooks(instrumentation, syncPoints)
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        /** Se invoca al publicar `recording_error` (para el orden claim/error). */
        var onErrorEvent: (() -> Unit)? = null

        fun session(): RecordingSession = RecordingSession(
            scope = CoroutineScope(Dispatchers.Unconfined),
            fileStore = fileStore,
            verifier = fakeVerifier,
            emitEvent = { type, payload ->
                synchronized(events) { events += type to payload }
                if (type == StreamContract.EVENT_RECORDING_ERROR) {
                    onErrorEvent?.invoke()
                }
            },
            encoderFactory = { fakeEncoder },
            muxerFactory = { path -> FakeSessionMuxer(path) },
            capabilityCheck = { _, _, _, _ ->
                EncoderCapabilities(
                    encoderName = "c2.qti.avc.encoder",
                    profiles = listOf(1),
                    levels = listOf(131072),
                    cbrSupported = true,
                    cameraSupportsSize = true,
                )
            },
            // Audio desactivado en estos tests: si algún camino lo
            // pidiera, que falle alto en vez de tocar AudioRecord.
            audioEncoderFactory = {
                throw AssertionError("audio no esperado en este test")
            },
            audioCapabilityCheck = {
                throw AssertionError("audio no esperado en este test")
            },
            freeSpaceBytes = { Long.MAX_VALUE },
            clockMs = { clock.wallMs() },
            registry = registry,
            testHooks = hooks,
        )

        fun startTestSession(): TestSession {
            val s = runBlocking { instrumentation.createSession() }
            instrumentation.startSession(s)
            return s
        }

        suspend fun start(session: RecordingSession) =
            session.start(
                fakePreview,
                previewConfig,
                0,
                null,
                null,
                false,
            )

        fun advanceClock(ms: Long) {
            clock.wallValue += ms
            clock.monotonicValue += ms
        }
    }

    @Before
    fun setUp() {
        Transmiti2Logger.sink = { _, _ -> }
    }

    @After
    fun tearDown() {
        Transmiti2Logger.sink = null
    }

    @Test(timeout = 15_000)
    fun `secondStartWhileRecordingIsRejected`() = runBlocking {
        val h = Harness(
            allocate = { allocateInstance(it) },
            allocateFormat = { allocateInstance(MediaFormat::class.java) },
        )
        // Sin sesión activa, `record()` es no-op por diseño (§4.A):
        // los eventos del claim solo se registran con sesión.
        h.startTestSession()
        val session = h.session()
        try {
            val info = h.start(session)
            assertEquals("recording", session.statusSnapshot().state)

            // Segunda start() con la sesión en RECORDING: rechazada
            // (sin async + await: el fallo se espera con try/catch
            // directo dentro de runBlocking).
            var thrown: RecordingException? = null
            try {
                h.start(session)
            } catch (e: RecordingException) {
                thrown = e
            }
            assertNotNull("La segunda start() debe rechazarse", thrown)
            assertEquals(
                StreamContract.RECORDING_STATE_ERROR,
                thrown!!.code,
            )
            assertEquals("start", thrown.details["stage"])
            // La sesión sigue grabando y el claim sigue vivo.
            assertEquals("recording", session.statusSnapshot().state)
            assertEquals(info.recordingId, session.statusSnapshot().recordingId)
            assertEquals(1, h.registry.snapshot().activeClaims.size)
            // La solicitud rechazada quedó en el log de pruebas.
            val rejected = h.instrumentation.events().firstOrNull {
                it.event == TestEventType.RECORDING_START_REQUESTED &&
                    it.result == TestEventResult.FAILED
            }
            assertNotNull("Falta RECORDING_START_REQUESTED fallido", rejected)
            assertEquals("recording", rejected!!.detail["state"])
            // Parada normal: el claim se libera por la vía `stop`.
            session.stop(null)
            assertTrue(h.registry.snapshot().activeClaims.isEmpty())
            val released = h.instrumentation.events().firstOrNull {
                it.event == TestEventType.CLAIM_RELEASED
            }
            assertNotNull("Falta CLAIM_RELEASED tras stop()", released)
            assertEquals("stop", released!!.detail["via"])
        } finally {
            h.fakeEncoder.shutdown()
            h.dir.deleteRecursively()
        }
    }

    @Test(timeout = 15_000)
    fun `startAfterErrorReleasesFirstClaim`() = runBlocking {
        val h = Harness(
            allocate = { allocateInstance(it) },
            allocateFormat = { allocateInstance(MediaFormat::class.java) },
        )
        // Sin sesión activa, `record()` es no-op por diseño (§4.A):
        // los eventos del claim solo se registran con sesión.
        h.startTestSession()
        val session = h.session()
        try {
            // El encoder falla DESPUÉS del claim: failStart debe
            // liberar el claim antes de publicar ERROR.
            h.fakeEncoder.throwOnConfigure = true
            var releasedBeforeError = false
            h.onErrorEvent = {
                releasedBeforeError = h.instrumentation.events().any {
                    it.event == TestEventType.CLAIM_RELEASED
                }
            }
            var thrown: RecordingException? = null
            try {
                h.start(session)
            } catch (e: RecordingException) {
                thrown = e
            }
            assertNotNull("La start() con encoder roto debe fallar", thrown)
            assertEquals("error", session.statusSnapshot().state)
            assertEquals(
                StreamContract.ENCODER_ERROR,
                session.statusSnapshot().errorCode,
            )
            assertTrue(
                "failStart debe liberar el claim ANTES de publicar ERROR",
                releasedBeforeError,
            )
            assertTrue(
                "Sin claim vivo tras el fallo",
                h.registry.snapshot().activeClaims.isEmpty(),
            )
            // Orden en el log: CLAIM_GRANTED antes que CLAIM_RELEASED,
            // con la vía `failStart`.
            val events = h.instrumentation.events()
            val granted = events.indexOfFirst {
                it.event == TestEventType.CLAIM_GRANTED
            }
            val released = events.indexOfFirst {
                it.event == TestEventType.CLAIM_RELEASED
            }
            assertTrue("Falta CLAIM_GRANTED", granted >= 0)
            assertTrue(
                "CLAIM_RELEASED debe seguir a CLAIM_GRANTED",
                released > granted,
            )
            assertEquals("failStart", events[released].detail["via"])
            val firstId = events[granted].recordingId
            assertNotNull(firstId)

            // Un nuevo inicio concede un claim nuevo (otro id): el
            // reloj avanza para que `newRecordingId` cambie la parte
            // de fecha/hora del id.
            h.fakeEncoder.throwOnConfigure = false
            h.advanceClock(5_000L)
            val info2 = h.start(session)
            assertNotEquals(
                "El segundo inicio debe usar otro id",
                firstId,
                info2.recordingId,
            )
            assertEquals("recording", session.statusSnapshot().state)
            assertEquals(1, h.registry.snapshot().activeClaims.size)
            assertEquals(
                info2.recordingId,
                h.registry.snapshot().activeClaims.keys.single(),
            )
            session.stop(null)
            assertTrue(h.registry.snapshot().activeClaims.isEmpty())
        } finally {
            h.fakeEncoder.shutdown()
            h.dir.deleteRecursively()
        }
    }
}
