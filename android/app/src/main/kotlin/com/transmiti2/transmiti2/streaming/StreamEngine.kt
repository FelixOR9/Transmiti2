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

/** Información de la vista previa que devuelve `startPreview` (3.1.1). */
data class PreviewInfo(
    /** Identificador de textura de Flutter para dibujar la preview. */
    val textureId: Long,
    val width: Int,
    val height: Int,
)

/** Opciones de grabación para `startRecording` (3.1.2). */
data class RecordingOptions(
    /** Partido asociado, si lo hay. */
    val matchId: Long? = null,
    val resolution: String = "720p",
    val fps: Int = 30,
    val videoBitrateBps: Int = 2_500_000,
    val audioEnabled: Boolean = true,
)

/** Resultado de `stopRecording` (3.1.2). */
data class RecordingInfo(
    val recordingId: String,
    val filePath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    /** Estado final de la grabación (`ready`, `partial`, ...). */
    val status: String,
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
 * Interfaz del motor. En la Fase 3.0 y en la subetapa 3.1.0 no hay
 * ninguna implementación: las funciones lanzan
 * `UnsupportedOperationException` hasta que llegue la captura (3.1.1) y
 * la grabación (3.1.2). El manejador del canal devuelve
 * `not_available_in_phase_3_0` para todo lo de video.
 */
interface StreamEngine {
    fun startPreview(): PreviewInfo
    fun stopPreview()
    fun startStream(endpoint: RtmpEndpoint)
    fun startRecording(options: RecordingOptions): String
    fun stopRecording(): RecordingInfo
    fun stopAll()
}
