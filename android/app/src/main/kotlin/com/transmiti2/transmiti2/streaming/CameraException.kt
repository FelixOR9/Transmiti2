package com.transmiti2.transmiti2.streaming

/**
 * Error del motor de cámara, con el código del contrato
 * (`camera_unavailable`, `camera_in_use`, `camera_disconnected`,
 * `permission_denied`). Nunca lleva datos sensibles en el mensaje.
 */
class CameraException(
    val code: String,
    message: String? = null,
) : Exception(message)
