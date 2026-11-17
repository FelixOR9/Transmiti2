package com.transmiti2.transmiti2.streaming

/**
 * Error del motor de cámara, con el código del contrato
 * (`camera_unavailable`, `camera_in_use`, `camera_disconnected`,
 * `permission_denied`). Nunca lleva datos sensibles en el mensaje.
 *
 * `details` (Fase A) lleva los datos de diagnóstico del fallo para
 * Dart (`stage`, `cameraCount`, `selectedCameraId`, `selectedSize`,
 * `selectedFps`, `exceptionClass`); nunca lleva secretos. Vacío cuando
 * el error no pasa por la sesión de cámara.
 */
class CameraException(
    val code: String,
    message: String? = null,
    val details: Map<String, Any?> = emptyMap(),
) : Exception(message) {

    /** Copia con los datos de diagnóstico del fallo. */
    fun withDetails(details: Map<String, Any?>): CameraException {
        return CameraException(code, message, details)
    }
}
