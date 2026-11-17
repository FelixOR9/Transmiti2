package com.transmiti2.transmiti2.streaming

import android.hardware.camera2.CameraCharacteristics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pruebas del motor de vista previa con sesiones y productores falsos:
 * no hay Camera2 real aquí (eso se verifica en el S24 Ultra). Lo que se
 * prueba es la orquestación: permiso antes de abrir, idempotencia,
 * liberación de recursos, prevención de sesiones duplicadas, pérdida y
 * recreación de la superficie y traducción de errores.
 */
class StreamEngineImplTest {

    private val testConfig = PreviewConfig(
        cameraId = "0",
        size = VideoSize(1280, 720),
        fpsRange = FpsRange(30, 30),
        sensorOrientation = 90,
        // Cámara trasera: en CameraCharacteristics, LENS_FACING_BACK = 1
        // (0 es LENS_FACING_FRONT). La cámara "0" es la trasera; con 0
        // el motor devuelve "front" y la aserción de lensFacing falla.
        lensFacing = CameraCharacteristics.LENS_FACING_BACK,
    )

    private class FakeHandle(
        override val textureId: Long = 42L,
    ) : SurfaceProducerHandle {
        var size: Pair<Int, Int>? = null
        var released = false
        var lastCallback: SurfaceProducerCallback? = null

        override fun setSize(width: Int, height: Int) {
            size = width to height
        }

        override fun surface(): Any? = Object()

        override fun setCallback(callback: SurfaceProducerCallback?) {
            this.lastCallback = callback
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
        var openError: CameraException? = null
        var openGate: CompletableDeferred<Unit>? = null
        override var isOpen = false
            private set
        override var listener: PreviewSessionListener? = null

        override suspend fun open(): PreviewConfig {
            openCalls++
            openGate?.await()
            openError?.let { throw it }
            isOpen = true
            return config
        }

        override fun close() {
            closeCalls++
            isOpen = false
        }

        fun fireDisconnected() {
            listener?.onDisconnected()
        }

        fun fireError(code: String) {
            listener?.onError(code)
        }
    }

    private inner class Fixture(
        val permissionGranted: Boolean = true,
        val displayRotation: Int = 0,
    ) {
        val handle = FakeHandle()
        var createCalls = 0
        val provider = SurfaceProducerProvider {
            createCalls++
            handle
        }
        var session: FakeSession = FakeSession(testConfig)
        var factoryCalls = 0
        val factory: PreviewSessionFactory = {
            factoryCalls++
            session
        }
        val events = mutableListOf<Map<String, Any?>>()
        val scope = CoroutineScope(Dispatchers.Unconfined)

        fun engine(): StreamEngineImpl {
            return StreamEngineImpl(
                cameraPermissionGranted = { permissionGranted },
                surfaceProvider = provider,
                sessionFactory = factory,
                displayRotationDegrees = { displayRotation },
                scope = scope,
                mainDispatcher = Dispatchers.Unconfined,
                onEvent = { events.add(it) },
            )
        }

        fun eventTypes(): List<String> {
            return events.mapNotNull { it["type"] as? String }
        }
    }

    @Test
    fun `sin permiso no se abre nada y el error es permission_denied`() =
        runBlocking {
            val fixture = Fixture(permissionGranted = false)
            val engine = fixture.engine()

            try {
                engine.startPreview()
                fail("Se esperaba CameraException")
            } catch (e: CameraException) {
                assertEquals(StreamContract.PERMISSION_DENIED, e.code)
            }
            assertEquals(0, fixture.createCalls)
            assertEquals(0, fixture.factoryCalls)
        }

    @Test
    fun `inicio correcto devuelve la informacion real de la vista previa`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()

            val info = engine.startPreview()

            assertEquals(42L, info.textureId)
            assertEquals(1280, info.width)
            assertEquals(720, info.height)
            assertEquals(90, info.sensorOrientation)
            assertEquals(90, info.rotationDegrees)
            assertEquals(30, info.fps)
            assertEquals("back", info.lensFacing)
            assertEquals(1, fixture.createCalls)
            assertEquals(1, fixture.factoryCalls)
            assertTrue(fixture.session.isOpen)
            assertTrue(
                fixture.eventTypes().contains(
                    StreamContract.EVENT_PREVIEW_STARTED,
                ),
            )
        }

    @Test
    fun `la rotacion se calcula con la orientacion del sensor y la pantalla`() =
        runBlocking {
            val fixture = Fixture(displayRotation = 90)
            val engine = fixture.engine()

            val info = engine.startPreview()

            // Sensor 90 - pantalla 90 = 0: en apaisado no hay que rotar.
            assertEquals(0, info.rotationDegrees)
        }

    @Test
    fun `segundo inicio secuencial devuelve lo mismo sin reabrir`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()

            val first = engine.startPreview()
            val second = engine.startPreview()

            assertEquals(first, second)
            assertEquals(1, fixture.factoryCalls)
            assertEquals(1, fixture.session.openCalls)
        }

    @Test
    fun `dos inicios simultaneos comparten el mismo trabajo`() =
        runBlocking {
            val fixture = Fixture()
            val gate = CompletableDeferred<Unit>()
            fixture.session.openGate = gate
            val engine = fixture.engine()

            val first = async { engine.startPreview() }
            val second = async { engine.startPreview() }
            gate.complete(Unit)

            assertEquals(first.await(), second.await())
            assertEquals(1, fixture.factoryCalls)
            assertEquals(1, fixture.session.openCalls)
        }

    @Test
    fun `detener libera sesion y productor y es idempotente`() = runBlocking {
        val fixture = Fixture()
        val engine = fixture.engine()

        engine.startPreview()
        engine.stopPreview()

        assertEquals(1, fixture.session.closeCalls)
        assertTrue(fixture.handle.released)
        assertFalse(fixture.session.isOpen)
        assertTrue(
            fixture.eventTypes().contains(
                StreamContract.EVENT_PREVIEW_STOPPED,
            ),
        )

        engine.stopPreview()
        assertEquals(1, fixture.session.closeCalls)
        assertEquals(1, fixture.createCalls)
    }

    @Test
    fun `stopAll detiene la vista previa`() = runBlocking {
        val fixture = Fixture()
        val engine = fixture.engine()

        engine.startPreview()
        engine.stopAll()

        assertEquals(1, fixture.session.closeCalls)
        assertTrue(fixture.handle.released)
    }

    @Test
    fun `error al abrir se traduce y deja el motor en IDLE`() = runBlocking {
        val fixture = Fixture()
        fixture.session.openError = CameraException(
            StreamContract.CAMERA_IN_USE,
            "En uso.",
        )
        val engine = fixture.engine()

        try {
            engine.startPreview()
            fail("Se esperaba CameraException")
        } catch (e: CameraException) {
            assertEquals(StreamContract.CAMERA_IN_USE, e.code)
        }
        assertTrue(fixture.handle.released)
        assertFalse(fixture.session.isOpen)

        // Tras el error se puede volver a intentar.
        fixture.session.openError = null
        val info = engine.startPreview()
        assertEquals(42L, info.textureId)
        assertEquals(2, fixture.factoryCalls)
    }

    @Test
    fun `desconexion de camara libera todo y emite preview_error`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()

            engine.startPreview()
            fixture.session.fireDisconnected()

            assertEquals(1, fixture.session.closeCalls)
            assertTrue(fixture.handle.released)
            assertFalse(fixture.session.isOpen)
            val errors = fixture.events.filter {
                it["type"] == StreamContract.EVENT_PREVIEW_ERROR
            }
            assertEquals(1, errors.size)
            assertEquals(
                StreamContract.CAMERA_DISCONNECTED,
                errors.first()["code"],
            )

            // Tras la desconexión se puede volver a abrir.
            val info = engine.startPreview()
            assertEquals(42L, info.textureId)
        }

    @Test
    fun `perdida de superficie cierra la sesion sin liberar el productor`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()

            engine.startPreview()
            fixture.handle.lastCallback?.onSurfaceCleanup()

            assertEquals(1, fixture.session.closeCalls)
            assertFalse(fixture.handle.released)
            assertTrue(
                fixture.eventTypes().contains(
                    StreamContract.EVENT_PREVIEW_SURFACE_LOST,
                ),
            )
            assertFalse(fixture.session.isOpen)
        }

    @Test
    fun `superficie disponible tras la perdida reabre la sesion`() =
        runBlocking {
            val fixture = Fixture()
            val engine = fixture.engine()

            engine.startPreview()
            fixture.handle.lastCallback?.onSurfaceCleanup()
            fixture.handle.lastCallback?.onSurfaceAvailable()

            assertEquals(2, fixture.factoryCalls)
            assertEquals(2, fixture.session.openCalls)
            assertTrue(fixture.session.isOpen)
            assertFalse(fixture.handle.released)
            assertEquals(
                2,
                fixture.eventTypes().count {
                    it == StreamContract.EVENT_PREVIEW_STARTED
                },
            )

            // La vista previa sigue funcionando y se puede detener.
            engine.stopPreview()
            assertEquals(2, fixture.session.closeCalls)
            assertTrue(fixture.handle.released)
        }

    @Test
    fun `superficie disponible sin perdida no reabre nada`() = runBlocking {
        val fixture = Fixture()
        val engine = fixture.engine()

        engine.startPreview()
        fixture.handle.lastCallback?.onSurfaceAvailable()

        assertEquals(1, fixture.factoryCalls)
        assertEquals(1, fixture.session.openCalls)
    }

    @Test
    fun `la grabacion sigue sin estar disponible en 3_1_1`() {
        val fixture = Fixture()
        val engine = fixture.engine()

        try {
            engine.startRecording(RecordingOptions())
            fail("Se esperaba UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // Esperado.
        }
        try {
            engine.stopRecording()
            fail("Se esperaba UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // Esperado.
        }
    }
}
