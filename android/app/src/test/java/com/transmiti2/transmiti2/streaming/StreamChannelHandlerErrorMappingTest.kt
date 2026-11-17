package com.transmiti2.transmiti2.streaming

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
    fun `timeout es camera_timeout, no cancelacion generica`() {
        val mapped = mapStartPreviewError(
            TimeoutCancellationException("Timed out waiting for 5000 ms"),
        )
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
