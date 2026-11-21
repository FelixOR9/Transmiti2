package com.transmiti2.transmiti2.streaming

import com.transmiti2.transmiti2.streaming.recording.RecordingSegmentResult

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
    /** Tamaño en orientación del sensor (sin rotar). */
    val width: Int,
    val height: Int,
    /** Orientación del sensor en grados (normalmente 90 en la trasera). */
    val sensorOrientation: Int = 90,
    /**
     * Rotación en grados que hay que aplicar a la imagen para verla
     * derecha (`(sensorOrientation - rotación de pantalla + 360) % 360`,
     * cámara trasera). Dart la aplica con `RotatedBox`.
     */
    val rotationDegrees: Int = 90,
    /** FPS del rango elegido (máximo del rango; objetivo 30). */
    val fps: Int = 30,
    /** Cámara usada: `back` (la frontal queda diferida en 3.1.1). */
    val lensFacing: String = "back",
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
    /** Segmentos verificados (completos): el método `stopRecording`
     * los devuelve para que la UI no dependa de haber recibido todos
     * los eventos `recording_segment` (fix duraciones 3.1.2, paso 5). */
    val segments: List<RecordingSegmentResult> = emptyList(),
    /**
     * Instrumentación de audio 3.1.3 (grupos I/U y Grupo V): los
     * mismos 9 campos de `RecordingResult` (buffers descartados,
     * capacidad del buffer AAC, estado del micrófono, contadores
     * del lector y EOS de audio) más los 2 del Grupo V
     * (`audioReaderNoData2s`: el watchdog disparó a los 2 s sin
     * lecturas; `eosTimedOut`: la espera de EOS agotó los 5 s).
     * Sin ellos, el resultado del método sobrescribía en Dart los
     * valores reales del evento `recording_stopped` con los
     * defectos (diagnóstico ronda 3, §2): el método debe llevar
     * exactamente los mismos campos que el evento.
     */
    val droppedInputBuffers: Int = 0,
    val inputBufferCapacityBytes: Int? = null,
    val micStateAtStart: Int? = null,
    val micStateAtStop: Int? = null,
    val audioReadCount: Int = 0,
    val audioLastReadCode: Int? = null,
    val audioLastReadOkAgeMs: Long? = null,
    val audioReaderAlive: Boolean = false,
    val audioEos: Boolean = false,
    val audioReaderNoData2s: Boolean = false,
    val eosTimedOut: Boolean = false,
    /**
     * Grupo S (S0, solo lectura): los mismos 2 campos de
     * `RecordingResult` (el segundo más silencioso). Sin ellos, el
     * resultado del método sobrescribiría en Dart los valores reales
     * del evento `recording_stopped` con los defectos (diagnóstico
     * ronda 3, §2): el método debe llevar exactamente los mismos
     * campos que el evento.
     */
    val quietestSecondPeak: Int? = null,
    val quietestSecondNonZero: Int? = null,
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
 *
 * Subetapa 3.1.1: `startPreview` y `stopPreview` son reales (suspenden
 * porque abrir la cámara es asíncrono) y `stopAll` detiene la vista
 * previa. La grabación sigue sin implementarse: `startStream`,
 * `startRecording` y `stopRecording` lanzan
 * `UnsupportedOperationException` y el manejador sigue devolviendo
 * `not_available_in_phase_3_0` para ellas.
 *
 * Fase B, 3.1.2: `startRecording` y `stopRecording` son reales
 * (suspenden: el inicio espera el formato del encoder y la parada
 * espera la verificación del último segmento). `startStream` sigue
 * lanzando `UnsupportedOperationException` (Fase 3.2).
 */
interface StreamEngine {
    suspend fun startPreview(): PreviewInfo
    suspend fun stopPreview()
    fun startStream(endpoint: RtmpEndpoint)
    suspend fun startRecording(options: RecordingOptions): String
    suspend fun stopRecording(): RecordingInfo
    suspend fun stopAll()
}
