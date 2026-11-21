package com.transmiti2.transmiti2.streaming

import com.transmiti2.transmiti2.streaming.recording.RecordingSegmentResult
import com.transmiti2.transmiti2.streaming.recording.SegmentStatus
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
    fun `timeout con reserva usa la etapa de reserva`() = runBlocking {
        // withTimeout es suspendida: como el primer test de timeout,
        // este también corre dentro de runBlocking.
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

    @Test
    fun `recordingInfoMap lleva los 13 campos de audio al metodo`() {
        // Grupo V (V1, camino real) + Grupo S (S0): el mapa del
        // método debe llevar los mismos 13 campos de
        // instrumentación de audio que el evento `recording_stopped`.
        // Es la función que los soltó en las rondas 3/U (diagnóstico
        // ronda 3, §2): si un campo vuelve a faltar aquí, este test
        // rompe antes que el teléfono. Los valores son distintivos
        // a propósito (ningún defecto coincide con ellos).
        val info = RecordingInfo(
            recordingId = "rec_20261120_135244_c8a2",
            filePath = "/docs/recordings/rec_20261120_135244_c8a2_seg000.mp4",
            durationMs = 76_898,
            sizeBytes = 9_999_999,
            status = "partial",
            segments = listOf(
                RecordingSegmentResult(
                    recordingId = "rec_20261120_135244_c8a2",
                    index = 0,
                    fileName = "rec_20261120_135244_c8a2_seg000.mp4",
                    filePath = "/docs/recordings/" +
                        "rec_20261120_135244_c8a2_seg000.mp4",
                    status = SegmentStatus.PARTIAL,
                    durationMs = 60_053,
                    sizeBytes = 7_000_000,
                    sampleCount = 1797,
                    keyframeCount = 30,
                    firstSampleKeyframe = true,
                    ptsMonotonic = true,
                    profileIdc = 66,
                    closedCleanly = true,
                    reason = "sin_pista_audio",
                ),
            ),
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
        val map = recordingInfoMap(info)
        assertEquals("rec_20261120_135244_c8a2", map["recordingId"])
        assertEquals("partial", map["status"])
        assertEquals(7, map["droppedInputBuffers"])
        assertEquals(2048, map["inputBufferCapacityBytes"])
        assertEquals(3, map["micStateAtStart"])
        assertEquals(1, map["micStateAtStop"])
        assertEquals(42, map["audioReadCount"])
        assertEquals(4096, map["audioLastReadCode"])
        assertEquals(12_345L, map["audioLastReadOkAgeMs"])
        assertEquals(true, map["audioReaderAlive"])
        assertEquals(true, map["audioEos"])
        assertEquals(true, map["audioReaderNoData2s"])
        assertEquals(true, map["eosTimedOut"])
        assertEquals(12, map["quietestSecondPeak"])
        assertEquals(3, map["quietestSecondNonZero"])
    }

    @Test
    fun `recordingInfoMap sin audio deja los contadores en defecto sin inventar`() {
        // Sin campos de audio, el mapa lleva los defectos
        // (null/0/false): Dart los muestra como `—`, nunca inventa.
        val info = RecordingInfo(
            recordingId = "",
            filePath = "",
            durationMs = 0,
            sizeBytes = 0,
            status = "idle",
        )
        val map = recordingInfoMap(info)
        assertEquals(0, map["droppedInputBuffers"])
        assertNull(map["inputBufferCapacityBytes"])
        assertNull(map["micStateAtStart"])
        assertNull(map["micStateAtStop"])
        assertEquals(0, map["audioReadCount"])
        assertNull(map["audioLastReadCode"])
        assertNull(map["audioLastReadOkAgeMs"])
        assertEquals(false, map["audioReaderAlive"])
        assertEquals(false, map["audioEos"])
        assertEquals(false, map["audioReaderNoData2s"])
        assertEquals(false, map["eosTimedOut"])
        assertNull(map["quietestSecondPeak"])
        assertNull(map["quietestSecondNonZero"])
    }
}
