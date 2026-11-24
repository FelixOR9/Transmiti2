package com.transmiti2.transmiti2.streaming.test

import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import com.transmiti2.transmiti2.streaming.FpsRange
import com.transmiti2.transmiti2.streaming.PreviewConfig
import com.transmiti2.transmiti2.streaming.PreviewSession
import com.transmiti2.transmiti2.streaming.PreviewSessionListener
import com.transmiti2.transmiti2.streaming.ReconciliationGateState
import com.transmiti2.transmiti2.streaming.StreamEngineImpl
import com.transmiti2.transmiti2.streaming.SurfaceProducerHandle
import com.transmiti2.transmiti2.streaming.SurfaceProducerCallback
import com.transmiti2.transmiti2.streaming.SurfaceProducerProvider
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import com.transmiti2.transmiti2.streaming.VideoSize
import com.transmiti2.transmiti2.streaming.recording.ActiveRecordingRegistry
import com.transmiti2.transmiti2.streaming.recording.RecordingFileStore
import com.transmiti2.transmiti2.streaming.recording.RecordingSession
import com.transmiti2.transmiti2.streaming.recording.ReconciliationSummary
import com.transmiti2.transmiti2.streaming.recording.SegmentStatus
import com.transmiti2.transmiti2.streaming.recording.SegmentVerification
import com.transmiti2.transmiti2.streaming.recording.SegmentVerifier
import com.transmiti2.transmiti2.streaming.recording.VerificationChecks
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Transiciones de la puerta y cancelación (diseño base §6, test nº 7
 * en su parte de puerta; Revisión 3 §2.2; FASE 2, grupo 2-c). El motor
 * real con una sesión falsa guionizable (bloqueo cancelable, fallo
 * una vez, resumen enlatado) y, para el timeout, con la sesión real.
 *
 * No confundir con el `ReconciliationGateTest` del paquete
 * `streaming` (subetapa 0, grupo 0-d2): aquel prueba que los
 * esperadores comparten una sola pasada y que el inicio no se
 * bloquea; este prueba que las transiciones quedan registradas y que
 * la cancelación deja la puerta en FAILED con `reconciliation_failed`.
 */
class ReconciliationGateTest {

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

    /**
     * Sesión falsa: la reconciliación es guionizable. El bloqueo es
     * una suspensión cancelable (`CompletableDeferred.await()`), no
     * un `CountDownLatch.await()`: la cancelación del scope debe
     * poder interrumpirla (Revisión 3 §2.2).
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

        /** Si no es nulo, la pasada se suspende aquí hasta cancelarla. */
        var blockDeferred: CompletableDeferred<Unit>? = null

        /** Se abre cuando la pasada empieza (la prueba espera a esto). */
        val enteredLatch = CountDownLatch(1)

        override suspend fun reconcileRecordings(): ReconciliationSummary {
            reconcileCalls++
            enteredLatch.countDown()
            blockDeferred?.await()
            failOnce?.let {
                failOnce = null
                throw it
            }
            return reconcileResult
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

    private val testConfig = PreviewConfig(
        cameraId = "0",
        size = VideoSize(1280, 720),
        fpsRange = FpsRange(30, 30),
        sensorOrientation = 90,
        lensFacing = CameraCharacteristics.LENS_FACING_BACK,
    )

    private inner class Fixture(
        val pauseTimeoutMs: Long = 500L,
        val useRealSession: Boolean = false,
    ) {
        val clock = FakeTestClock()
        val intentAccess = FakeIntentAccess()
        val scope = CoroutineScope(Dispatchers.Default)
        val fakeSession = FakeGateSession(scope)
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "gate-test-${System.nanoTime()}",
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

        /** Sesión real (solo para el test de timeout con pausa real). */
        val realSession: RecordingSession = RecordingSession(
            scope = scope,
            fileStore = fileStore,
            verifier = verifier,
            clockMs = { clock.wallMs() },
            registry = registry,
            testHooks = hooks,
        )

        fun engine(): StreamEngineImpl {
            return StreamEngineImpl(
                cameraPermissionGranted = { true },
                microphonePermissionGranted = { true },
                surfaceProvider = SurfaceProducerProvider { FakeHandle() },
                sessionFactory = { FakePreviewSession(testConfig) },
                displayRotationDegrees = { 0 },
                scope = scope,
                mainDispatcher = Dispatchers.Unconfined,
                recordingSessionFactory = { _ ->
                    if (useRealSession) realSession else fakeSession
                },
                testHooks = hooks,
            )
        }

        fun startTestSession(): TestSession {
            val s = runBlocking { instrumentation.createSession() }
            instrumentation.startSession(s)
            return s
        }

        fun arm(point: PausePoint) {
            runBlocking { syncPoints.arm(point) }
        }

        fun writeGroup(id: String, vararg indices: Int) {
            for (i in indices) {
                File(dir, fileStore.segmentFileName(id, i))
                    .writeText("seg-$id-$i")
            }
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

    private suspend fun awaitGateState(
        engine: StreamEngineImpl,
        state: ReconciliationGateState,
        timeoutMs: Long = 5_000L,
    ) {
        withTimeout(timeoutMs) {
            while (engine.reconciliationState.value != state) delay(10)
        }
    }

    @Test(timeout = 15_000)
    fun `gateTransitionsAreRecordedInOrder`() = runBlocking {
        val f = Fixture()
        // Sin sesión activa, `record()` es no-op por diseño (§4.A).
        f.startTestSession()
        val engine = f.engine()
        engine.triggerReconciliation()
        awaitGateState(engine, ReconciliationGateState.DONE)

        assertEquals(ReconciliationGateState.DONE, engine.reconciliationState.value)
        val events = f.instrumentation.events()
        val started = events.indexOfFirst {
            it.event == TestEventType.RECONCILIATION_STARTED
        }
        val finished = events.indexOfFirst {
            it.event == TestEventType.RECONCILIATION_FINISHED
        }
        assertTrue("Falta RECONCILIATION_STARTED", started >= 0)
        assertTrue(
            "RECONCILIATION_FINISHED debe seguir a RECONCILIATION_STARTED",
            finished > started,
        )
        assertEquals("NOT_STARTED", events[started].detail["from"])
        assertEquals("RUNNING", events[started].detail["to"])
        assertEquals("gate", events[started].detail["source"])
        assertEquals(TestEventResult.OK, events[finished].result)
        assertEquals("run-1", events[finished].detail["runId"])
        assertEquals("DONE", events[finished].detail["gateState"])
    }

    @Test(timeout = 15_000)
    fun `cancellationLeavesGateFailedWithReason`() = runBlocking {
        val f = Fixture()
        // Sin sesión activa, `record()` es no-op por diseño (§4.A).
        f.startTestSession()
        val engine = f.engine()
        f.fakeSession.blockDeferred = CompletableDeferred()
        engine.triggerReconciliation()
        assertTrue(
            "La pasada no arrancó",
            f.fakeSession.enteredLatch.await(5, TimeUnit.SECONDS),
        )
        assertEquals(ReconciliationGateState.RUNNING, engine.reconciliationState.value)

        // Cancelar el scope del motor mientras la pasada está
        // suspendida: la cancelación debe dejar la puerta en FAILED
        // (Revisión 3 §2.2), nunca atascada en RUNNING.
        f.scope.cancel()
        awaitGateState(engine, ReconciliationGateState.FAILED)

        assertEquals(
            "reconciliation_failed",
            engine.reconciliationStateMap()["errorCode"],
        )
        val events = f.instrumentation.events()
        val finished = events.filter {
            it.event == TestEventType.RECONCILIATION_FINISHED
        }
        assertTrue(
            "Falta RECONCILIATION_FINISHED con FAILED",
            finished.any {
                it.result == TestEventResult.FAILED &&
                    it.errorCode == "reconciliation_failed"
            },
        )
        assertTrue(
            "Ningún FINISHED debe ser OK tras la cancelación",
            finished.none { it.result == TestEventResult.OK },
        )
        val started = events.filter {
            it.event == TestEventType.RECONCILIATION_STARTED
        }
        assertEquals(1, started.size)
        assertEquals("NOT_STARTED", started[0].detail["from"])
    }

    @Test(timeout = 15_000)
    fun `retryAfterFailureIsRecorded`() = runBlocking {
        val f = Fixture()
        // Sin sesión activa, `record()` es no-op por diseño (§4.A).
        f.startTestSession()
        f.fakeSession.failOnce = RuntimeException("fallo inducido")
        val engine = f.engine()
        engine.triggerReconciliation()
        awaitGateState(engine, ReconciliationGateState.FAILED)
        assertEquals(
            "reconciliation_failed",
            engine.reconciliationStateMap()["errorCode"],
        )

        // Reintento desde FAILED: la puerta vuelve a RUNNING y llega
        // a DONE; ambas transiciones quedan registradas.
        engine.triggerReconciliation()
        awaitGateState(engine, ReconciliationGateState.DONE)

        val events = f.instrumentation.events()
        val started = events.filter {
            it.event == TestEventType.RECONCILIATION_STARTED
        }
        assertEquals(2, started.size)
        assertEquals("NOT_STARTED", started[0].detail["from"])
        assertEquals("FAILED", started[1].detail["from"])
        val finished = events.filter {
            it.event == TestEventType.RECONCILIATION_FINISHED
        }
        assertEquals(2, finished.size)
        assertEquals(TestEventResult.FAILED, finished[0].result)
        assertEquals(TestEventResult.OK, finished[1].result)
    }

    @Test(timeout = 15_000)
    fun `timeoutReleasesGateToDone`() = runBlocking {
        val f = Fixture(pauseTimeoutMs = 500L, useRealSession = true)
        f.writeGroup("rec_20261123_150000_aaaa", 0)
        f.writeGroup("rec_20261123_151000_bbbb", 0)
        f.startTestSession()
        f.arm(PausePoint.P1)
        val engine = f.engine()
        engine.triggerReconciliation()
        // La pasada se detiene en P1 con la puerta en RUNNING...
        withTimeout(5_000) {
            while (!f.syncPoints.isPaused(PausePoint.P1)) delay(10)
        }
        assertEquals(ReconciliationGateState.RUNNING, engine.reconciliationState.value)
        // ...y el timeout la libera sola hasta DONE, sin reanudar.
        awaitGateState(engine, ReconciliationGateState.DONE, timeoutMs = 10_000L)
        val events = f.instrumentation.events()
        assertTrue(
            "Falta PAUSE_TIMEOUT",
            events.any { it.event == TestEventType.PAUSE_TIMEOUT },
        )
        val gateFinished = events.firstOrNull {
            it.event == TestEventType.RECONCILIATION_FINISHED &&
                it.detail["source"] == "gate"
        }
        assertNotNull("Falta el FINISHED de la puerta", gateFinished)
        assertEquals("DONE", gateFinished!!.detail["gateState"])
        assertEquals("run-1", gateFinished.detail["runId"])
    }
}
