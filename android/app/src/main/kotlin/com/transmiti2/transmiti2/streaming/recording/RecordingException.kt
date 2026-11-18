package com.transmiti2.transmiti2.streaming.recording

import com.transmiti2.transmiti2.streaming.StreamContract

/**
 * Error de grabación (Fase B, 3.1.2) con código del contrato
 * (`StreamContract`: `encoder_error`, `muxer_error`, `storage_full`,
 * `camera_disconnected`, `recording_state_error`, ...) y detalles de
 * diagnóstico (etapa, segmento actual, archivo), como los errores de
 * vista previa de la Fase A. Viaja a Flutter con `message` técnico y
 * `details`; nunca se inventa un valor.
 */
class RecordingException(
    val code: String,
    message: String,
    val details: Map<String, Any?> = emptyMap(),
) : Exception(message) {

    /** Devuelve una copia con los detalles ampliados (sin tocar el original). */
    fun withDetails(extra: Map<String, Any?>): RecordingException =
        RecordingException(code, message ?: "Error de grabación.", details + extra)

    companion object {
        fun encoderError(
            message: String,
            details: Map<String, Any?> = emptyMap(),
        ): RecordingException =
            RecordingException(StreamContract.ENCODER_ERROR, message, details)

        fun muxerError(
            message: String,
            details: Map<String, Any?> = emptyMap(),
        ): RecordingException =
            RecordingException(StreamContract.MUXER_ERROR, message, details)

        fun storageFull(
            message: String,
            details: Map<String, Any?> = emptyMap(),
        ): RecordingException =
            RecordingException(StreamContract.STORAGE_FULL, message, details)

        fun cameraDisconnected(
            message: String,
            details: Map<String, Any?> = emptyMap(),
        ): RecordingException =
            RecordingException(
                StreamContract.CAMERA_DISCONNECTED,
                message,
                details,
            )

        fun stateError(
            message: String,
            details: Map<String, Any?> = emptyMap(),
        ): RecordingException =
            RecordingException(
                StreamContract.RECORDING_STATE_ERROR,
                message,
                details,
            )
    }
}
