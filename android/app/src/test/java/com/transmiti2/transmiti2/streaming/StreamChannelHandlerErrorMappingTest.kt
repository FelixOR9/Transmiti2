package com.transmiti2.transmiti2.streaming

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del mapeo de errores de `startPreview` (Fase A).
 *
 * Solo se prueba [mapStartPreviewError], la función pura que traduce la
 * excepción al código del contrato: el manejador en sí no se puede
 * instanciar en una prueba unitaria sin refactorizar (su motor es un
 * `lazy` privado construido desde `TextureRegistry`/`CameraManager`),
 * y la sesión real de cámara (`CameraSession`) necesita un
 * `CameraManager` de Android, imposible sin Robolectric o emulador.
 * Por eso la lista vacía → `camera_disabled_by_system` y los caminos
 * de `CameraSession` no tienen prueba unitaria aquí: se verifican por
 * inspección del código y en el S24 Ultra.
 */
class StreamChannelHandlerErrorMappingTest {

    @Test
    fun `timeout es camera_timeout, no cancelacion generica`() = runBlocking {
        // TimeoutCancellationException no tiene ningún constructor
        // público usable directamente en esta versión de
        // kotlinx-coroutines (el sin argumentos no existe y el de
        // (message) es interno, como ya se vio en los runs #28 y #29):
        // la única forma de obtener una instancia real es producirla
        // con withTimeout. El mapeo solo depende del tipo (el mensaje
        // lo fija la producción, "Tiempo de espera agotado al iniciar
        // la cámara (5 s)."), así que la instancia real mantiene el
        // sentido del test sin debilitarlo.
        val timeout: TimeoutCancellationException = try {
            withTimeout(1) { delay(10) }
            error("Se esperaba TimeoutCancellationException")
        } catch (e: TimeoutCancellationException) {
            e
        }
        val mapped = mapStartPreviewError(timeout)
        assertEquals(StreamContract.CAMERA_TIMEOUT, mapped.code)
        assertTrue(mapped.message.contains("5 s"))
        // Los details base usan la etapa del manejador.
        assertEquals("startPreview", mapped.details["stage"])
        assertEquals(
            "TimeoutCancellationException",
            mapped.details["exceptionClass"],
        )
    }

    @Test
    fun `cancelacion sin timeout sigue siendo camera_unavailable`() {
        val mapped = mapStartPreviewError(
            CancellationException("Inicio cancelado"),
        )
        assertEquals(StreamContract.CAMERA_UNAVAILABLE, mapped.code)
        assertTrue(mapped.message.contains("cancelado"))
    }

    @Test
    fun `IllegalArgumentException es camera_config_error`() {
        val mapped = mapStartPreviewError(
            IllegalArgumentException("rango FPS no admitido"),
        )
        assertEquals(StreamContract.CAMERA_CONFIG_ERROR, mapped.code)
        assertTrue(mapped.message.contains("rango FPS no admitido"))
        assertEquals(
            "IllegalArgumentException",
            mapped.details["exceptionClass"],
        )
    }

    @Test
    fun `IllegalStateException es camera_state_error`() {
        val mapped = mapStartPreviewError(
            IllegalStateException("sesion cerrada"),
        )
        assertEquals(StreamContract.CAMERA_STATE_ERROR, mapped.code)
        assertTrue(mapped.message.contains("sesion cerrada"))
        assertEquals(
            "IllegalStateException",
            mapped.details["exceptionClass"],
        )
    }

    @Test
    fun `una excepcion generica sigue siendo camera_unavailable`() {
        val mapped = mapStartPreviewError(RuntimeException("boom"))
        assertEquals(StreamContract.CAMERA_UNAVAILABLE, mapped.code)
        assertEquals("RuntimeException", mapped.details["exceptionClass"])
    }

    @Test
    fun `una excepcion generica conserva su mensaje real`() {
        // Revisión 2: antes el mensaje genérico «No se pudo iniciar
        // la cámara.» sustituía siempre al mensaje real; ahora solo se
        // usa cuando la excepción no trae mensaje.
        val mapped = mapStartPreviewError(
            RuntimeException("servicio de cámara no disponible"),
        )
        assertEquals(StreamContract.CAMERA_UNAVAILABLE, mapped.code)
        assertEquals(
            "servicio de cámara no disponible",
            mapped.message,
        )
        assertEquals("startPreview", mapped.details["stage"])
    }

    @Test
    fun `una excepcion generica sin mensaje usa el generico`() {
        val mapped = mapStartPreviewError(RuntimeException())
        assertEquals(StreamContract.CAMERA_UNAVAILABLE, mapped.code)
        assertEquals("No se pudo iniciar la cámara.", mapped.message)
    }

    @Test
    fun `la reserva del motor aporta la etapa real`() {
        // Revisión 2: un fallo previo a la sesión (crear el productor)
        // no tiene diagnóstico de sesión; la etapa la da el motor.
        val fallback = mapOf<String, Any?>(
            "stage" to "createProducer",
            "cameraCount" to null,
            "selectedCameraId" to null,
            "selectedSize" to null,
            "selectedFps" to null,
            "exceptionClass" to null,
        )
        val mapped = mapStartPreviewError(
            RuntimeException("no se pudo crear el productor"),
            fallback,
        )
        assertEquals(StreamContract.CAMERA_UNAVAILABLE, mapped.code)
        assertEquals("no se pudo crear el productor", mapped.message)
        assertEquals("createProducer", mapped.details["stage"])
        assertEquals("RuntimeException", mapped.details["exceptionClass"])
        assertNull(mapped.details["cameraCount"])
    }

    @Test
    fun `CameraException sin details usa la etapa de reserva`() {
        val fallback = mapOf<String, Any?>(
            "stage" to "session",
            "cameraCount" to 5,
            "selectedCameraId" to "0",
            "selectedSize" to "1280x720",
            "selectedFps" to 30,
            "exceptionClass" to null,
        )
        val mapped = mapStartPreviewError(
            CameraException(
                StreamContract.CAMERA_IN_USE,
                "La cámara está en uso por otra aplicación.",
            ),
            fallback,
        )
        assertEquals(StreamContract.CAMERA_IN_USE, mapped.code)
        assertEquals("session", mapped.details["stage"])
        assertEquals(5, mapped.details["cameraCount"])
        assertEquals("0", mapped.details["selectedCameraId"])
        assertEquals("CameraException", mapped.details["exceptionClass"])
    }

    @Test
    fun `timeout con reserva usa la etapa de reserva`() {
        val timeout: TimeoutCancellationException = try {
            withTimeout(1) { delay(10) }
            error("Se esperaba TimeoutCancellationException")
        } catch (e: TimeoutCancellationException) {
            e
        }
        val mapped = mapStartPreviewError(
            timeout,
            mapOf<String, Any?>("stage" to "openDevice"),
        )
        assertEquals(StreamContract.CAMERA_TIMEOUT, mapped.code)
        assertEquals("openDevice", mapped.details["stage"])
    }

    @Test
    fun `CameraException conserva su codigo y sus details`() {
        val details = mapOf<String, Any?>(
            "stage" to "enumerate",
            "cameraCount" to 0,
            "selectedCameraId" to null,
            "selectedSize" to null,
            "selectedFps" to null,
            "exceptionClass" to null,
        )
        val mapped = mapStartPreviewError(
            CameraException(
                StreamContract.CAMERA_DISABLED_BY_SYSTEM,
                "No se detectó ninguna cámara.",
                details,
            ),
        )
        assertEquals(StreamContract.CAMERA_DISABLED_BY_SYSTEM, mapped.code)
        assertEquals("No se detectó ninguna cámara.", mapped.message)
        assertEquals("enumerate", mapped.details["stage"])
        assertEquals(0, mapped.details["cameraCount"])
    }

    @Test
    fun `CameraException sin details usa los details base`() {
        val mapped = mapStartPreviewError(
            CameraException(
                StreamContract.CAMERA_IN_USE,
                "La cámara está en uso por otra aplicación.",
            ),
        )
        assertEquals(StreamContract.CAMERA_IN_USE, mapped.code)
        assertEquals("startPreview", mapped.details["stage"])
        assertNull(mapped.details["cameraCount"])
        assertEquals("CameraException", mapped.details["exceptionClass"])
    }

    @Test
    fun `los cuatro codigos nuevos son distintos entre si`() {
        val codes = setOf(
            StreamContract.CAMERA_TIMEOUT,
            StreamContract.CAMERA_CONFIG_ERROR,
            StreamContract.CAMERA_STATE_ERROR,
            StreamContract.CAMERA_DISABLED_BY_SYSTEM,
        )
        assertEquals(4, codes.size)
        assertEquals("camera_timeout", StreamContract.CAMERA_TIMEOUT)
        assertEquals("camera_config_error", StreamContract.CAMERA_CONFIG_ERROR)
        assertEquals("camera_state_error", StreamContract.CAMERA_STATE_ERROR)
        assertEquals(
            "camera_disabled_by_system",
            StreamContract.CAMERA_DISABLED_BY_SYSTEM,
        )
    }
}
