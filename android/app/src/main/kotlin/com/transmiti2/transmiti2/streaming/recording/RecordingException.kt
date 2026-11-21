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

        /**
         * Fallo de directorio (subetapa 0, 3.1.4): V1–V4 al resolver
         * la ruta canónica o al re-validarla en `start()` antes de
         * `ensureDir()`. `stage` es la verificación que falló
         * (`context_null` | `mkdirs_failed` | `not_a_directory` |
         * `not_writable`); con `not_writable`, `reason` es
         * `create_failed` o `write_test_collision` y `exceptionClass`
         * el nombre simple de la excepción, cuando lo hay. Espejo de
         * `storageFull`: mismo patrón `(code, message, details)`.
         */
        fun recordingsDirUnavailable(
            stage: String,
            reason: String? = null,
            exceptionClass: String? = null,
        ): RecordingException {
            val details = mutableMapOf<String, Any?>("stage" to stage)
            if (reason != null) details["reason"] = reason
            if (exceptionClass != null) details["exceptionClass"] = exceptionClass
            return RecordingException(
                StreamContract.RECORDINGS_DIR_UNAVAILABLE,
                "No se pudo preparar la carpeta de grabaciones.",
                details,
            )
        }

        /**
         * Espacio no medible (subetapa 0, 3.1.4): `StatFs` lanzó en el
         * constructor o en `availableBytes`. `stage` es el punto del
         * flujo (`start` | `rotate`), `check` es el chequeo que
         * falló (`free_space_check`) y `freeBytes` queda en `null`
         * (nunca se inventa un valor); en rotación se añade
         * `segmentIndex`. Espejo de `storageFull`: mismo patrón
         * `(code, message, details)`.
         */
        fun storageCheckUnavailable(
            stage: String,
            check: String,
            exceptionClass: String? = null,
            segmentIndex: Int? = null,
        ): RecordingException {
            val details = mutableMapOf<String, Any?>(
                "stage" to stage,
                "check" to check,
                "freeBytes" to null,
            )
            if (exceptionClass != null) details["exceptionClass"] = exceptionClass
            if (segmentIndex != null) details["segmentIndex"] = segmentIndex
            return RecordingException(
                StreamContract.STORAGE_CHECK_UNAVAILABLE,
                "No se pudo comprobar el espacio disponible para grabar.",
                details,
            )
        }

        fun cameraDisconnected(
            message: String,
            details: Map<String, Any?> = emptyMap(),
        ): RecordingException =
            RecordingException(
                StreamContract.CAMERA_DISCONNECTED,
                message,
                details,
            )

        /**
         * Fallo de micrófono/captura de audio (3.1.3): código
         * `audio_unavailable`. Los fallos del codec AAC usan
         * `encoderError` con `stage = "audio"`.
         */
        fun audioError(
            message: String,
            details: Map<String, Any?> = emptyMap(),
        ): RecordingException =
            RecordingException(StreamContract.AUDIO_UNAVAILABLE, message, details)

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
