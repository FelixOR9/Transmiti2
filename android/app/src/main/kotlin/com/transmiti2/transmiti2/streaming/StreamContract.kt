package com.transmiti2.transmiti2.streaming

/**
 * Contrato del canal Flutter <-> Kotlin para la transmisión.
 *
 * Espejo de `lib/features/streaming/domain/stream_contract.dart`. En la
 * subetapa 3.1.0 los métodos de video siguen devolviendo el error
 * `not_available_in_phase_3_0`: no se simula ningún éxito. `requestPermissions`
 * se vuelve real y se añaden `checkPermissions` con estados,
 * `openAppSettings`, `stopPreview` y `stopRecording`.
 *
 * Subetapa 3.1.1: `startPreview` y `stopPreview` son reales (Camera2 +
 * `SurfaceProducer`) y `stopAll` detiene también la vista previa. La
 * grabación sigue devolviendo `not_available_in_phase_3_0`.
 */
object StreamContract {
    const val METHOD_CHANNEL = "com.transmiti2.transmiti2/streaming"
    const val EVENT_CHANNEL = "com.transmiti2.transmiti2/streaming/events"

    const val GET_STATUS = "getStatus"
    const val CHECK_PERMISSIONS = "checkPermissions"
    const val REQUEST_PERMISSIONS = "requestPermissions"
    const val OPEN_APP_SETTINGS = "openAppSettings"

    const val SAVE_STREAM_KEY = "saveStreamKey"
    const val DELETE_STREAM_KEY = "deleteStreamKey"
    const val HAS_STREAM_KEY = "hasStreamKey"

    const val START_PREVIEW = "startPreview"
    const val STOP_PREVIEW = "stopPreview"
    const val START_STREAM = "startStream"
    const val START_RECORDING = "startRecording"
    const val STOP_RECORDING = "stopRecording"
    const val STOP_ALL = "stopAll"

    // Eventos del canal de eventos.
    const val EVENT_PERMISSION_RESULT = "permission_result"
    const val EVENT_PERMISSION_CHANGED = "permission_changed"
    const val EVENT_SERVICE_STARTED = "service_started"
    const val EVENT_SERVICE_STOPPED = "service_stopped"
    const val EVENT_ERROR = "error"

    /**
     * Eventos de vista previa (subetapa 3.1.1). `preview_started` lleva
     * `textureId`, `width`, `height` y `fps`; `preview_error` lleva
     * `code`; `preview_surface_lost` no lleva datos (la superficie se
     * perdió temporalmente y el motor la recupera solo).
     */
    const val EVENT_PREVIEW_STARTED = "preview_started"
    const val EVENT_PREVIEW_STOPPED = "preview_stopped"
    const val EVENT_PREVIEW_ERROR = "preview_error"
    const val EVENT_PREVIEW_SURFACE_LOST = "preview_surface_lost"

    // Códigos de error (nunca llevan la clave ni la URL dentro).
    const val NOT_AVAILABLE_IN_PHASE_3_0 = "not_available_in_phase_3_0"

    /**
     * Ya no se devuelve desde la subetapa 3.1.0: `requestPermissions` es
     * real. Se conserva por compatibilidad con la Fase 3.0.
     */
    const val PERMISSION_REQUEST_DEFERRED = "permission_request_deferred_to_phase_3_1"
    const val INVALID_RTMPS_URL = "invalid_rtmps_url"
    const val EMPTY_KEY = "empty_key"
    const val SECURE_STORE_UNAVAILABLE = "secure_store_unavailable"
    const val KEYSTORE_NOT_ACTIVE = "keystore_not_active"
    const val DESTINATION_NOT_FOUND = "destination_not_found"

    /**
     * Códigos de error definidos en la subetapa 3.1.0 para las fases
     * siguientes. En 3.1.0 solo se produce `permission_denied`; los demás
     * quedan definidos para no cambiar el contrato después.
     */
    const val CAMERA_UNAVAILABLE = "camera_unavailable"
    const val CAMERA_IN_USE = "camera_in_use"
    const val CAMERA_DISCONNECTED = "camera_disconnected"
    const val ENCODER_ERROR = "encoder_error"
    const val MUXER_ERROR = "muxer_error"
    const val AUDIO_UNAVAILABLE = "audio_unavailable"
    const val STORAGE_FULL = "storage_full"
    const val THERMAL_SHUTDOWN = "thermal_shutdown"
    const val PERMISSION_DENIED = "permission_denied"
    const val RECORDING_NOT_FOUND = "recording_not_found"
}
