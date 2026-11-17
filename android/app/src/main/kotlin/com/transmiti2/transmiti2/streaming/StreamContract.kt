package com.transmiti2.transmiti2.streaming

/**
 * Contrato del canal Flutter <-> Kotlin para la transmisión.
 *
 * Espejo de `lib/features/streaming/domain/stream_contract.dart`. En la
 * Fase 3.0 los métodos de video devuelven el error
 * `not_available_in_phase_3_0`: no se simula ningún éxito.
 */
object StreamContract {
    const val METHOD_CHANNEL = "com.transmiti2.transmiti2/streaming"
    const val EVENT_CHANNEL = "com.transmiti2.transmiti2/streaming/events"

    const val GET_STATUS = "getStatus"
    const val CHECK_PERMISSIONS = "checkPermissions"

    /** Reservado para la Fase 3.1. En 3.0 devuelve `permission_request_deferred_to_phase_3_1`. */
    const val REQUEST_PERMISSIONS = "requestPermissions"

    const val SAVE_STREAM_KEY = "saveStreamKey"
    const val DELETE_STREAM_KEY = "deleteStreamKey"
    const val HAS_STREAM_KEY = "hasStreamKey"

    const val START_PREVIEW = "startPreview"
    const val START_STREAM = "startStream"
    const val START_RECORDING = "startRecording"
    const val STOP_ALL = "stopAll"

    // Códigos de error (nunca llevan la clave ni la URL dentro).
    const val NOT_AVAILABLE_IN_PHASE_3_0 = "not_available_in_phase_3_0"
    const val PERMISSION_REQUEST_DEFERRED = "permission_request_deferred_to_phase_3_1"
    const val INVALID_RTMPS_URL = "invalid_rtmps_url"
    const val EMPTY_KEY = "empty_key"
    const val SECURE_STORE_UNAVAILABLE = "secure_store_unavailable"
    const val KEYSTORE_NOT_ACTIVE = "keystore_not_active"
    const val DESTINATION_NOT_FOUND = "destination_not_found"
}
