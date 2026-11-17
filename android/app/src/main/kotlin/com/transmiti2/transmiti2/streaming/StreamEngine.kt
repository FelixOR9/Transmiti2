package com.transmiti2.transmiti2.streaming

/**
 * Motor RTMP (Fase 3.0: solo el modelo de datos y la interfaz).
 *
 * El motor solo recibe un endpoint con la referencia a la clave, nunca su
 * valor: la clave se descifra en Kotlin y se entrega directamente al
 * codificador RTMP en memoria. La implementación RTMP/RTMPS, la
 * codificación H.264/AAC y la grabación MP4 llegan en las fases 3.1 y 3.2.
 */
data class RtmpEndpoint(
    /** URL de ingesta RTMPS, sin la clave dentro. */
    val ingestUrl: String,
    /** Referencia opaca a la clave en el almacén seguro (no su valor). */
    val keyRef: String,
    val destinationId: Long,
)

/** Estados de una sesión de transmisión (para las fases 3.1 en adelante). */
enum class StreamState {
    IDLE,
    PREPARING,
    LIVE,
    RECONNECTING,
    ENDED,
    FAILED,
}

/**
 * Interfaz del motor. En la Fase 3.0 no hay ninguna implementación: las
 * funciones lanzan `UnsupportedOperationException` hasta la Fase 3.1.
 */
interface StreamEngine {
    fun startPreview()
    fun startStream(endpoint: RtmpEndpoint)
    fun startRecording()
    fun stopAll()
}
