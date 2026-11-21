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

    /**
     * Estado de la grabación y recuperación de interrupciones (Fase B,
     * 3.1.2, componente 3): `getRecordingStatus` devuelve la
     * instantánea del estado (`RecordingStatusSnapshot`) para
     * re-sincronizar la UI al volver a la pantalla;
     * `recoverInterruptedRecordings` clasifica los `.tmp.mp4` que
     * quedaron de una muerte del proceso y devuelve la lista
     * clasificada (nunca borra ni modifica nada).
     */
    const val GET_RECORDING_STATUS = "getRecordingStatus"
    const val RECOVER_INTERRUPTED_RECORDINGS = "recoverInterruptedRecordings"

    /**
     * Ruta canónica de grabaciones y estado de la puerta de
     * reconciliación (subetapa 0, 3.1.4). `getRecordingsDir` devuelve
     * la ruta absoluta `<filesDir>/recordings/` o el error
     * `recordings_dir_unavailable`; `getReconciliationState` expone
     * la puerta única por proceso (`NOT_STARTED` → `RUNNING` →
     * `DONE` | `FAILED`). Espejo en
     * `lib/features/streaming/domain/stream_contract.dart`.
     */
    const val GET_RECORDINGS_DIR = "getRecordingsDir"
    const val GET_RECONCILIATION_STATE = "getReconciliationState"

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

    /**
     * Eventos de grabación (Fase B, 3.1.2). Espejo en
     * `lib/features/streaming/domain/stream_contract.dart`.
     */
    const val EVENT_RECORDING_STARTED = "recording_started"
    const val EVENT_RECORDING_SEGMENT = "recording_segment"
    const val EVENT_RECORDING_STOPPED = "recording_stopped"
    const val EVENT_RECORDING_ERROR = "recording_error"

    /**
     * Evento de 3.1.3: se emite cuando el audio se pierde en caliente
     * con el video en marcha (plan §2 D7); lleva `recordingId`,
     * `reason`, `segmentIndex` y `message`. No es un error de
     * grabación: el estado sigue siendo `recording`.
     */
    const val EVENT_RECORDING_AUDIO_LOST = "recording_audio_lost"

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

    /**
     * Códigos de diagnóstico (Fase A, 3.1.1): diferencian fallos que
     * antes colapsaban en `camera_unavailable`, sin cambiar la lógica
     * de apertura/cierre. Espejo en
     * `lib/features/streaming/domain/stream_contract.dart`.
     */
    const val CAMERA_TIMEOUT = "camera_timeout"
    const val CAMERA_CONFIG_ERROR = "camera_config_error"
    const val CAMERA_STATE_ERROR = "camera_state_error"
    const val CAMERA_DISABLED_BY_SYSTEM = "camera_disabled_by_system"
    const val ENCODER_ERROR = "encoder_error"
    const val MUXER_ERROR = "muxer_error"
    const val AUDIO_UNAVAILABLE = "audio_unavailable"
    const val STORAGE_FULL = "storage_full"
    const val THERMAL_SHUTDOWN = "thermal_shutdown"
    const val PERMISSION_DENIED = "permission_denied"
    const val RECORDING_NOT_FOUND = "recording_not_found"

    /**
     * Código de grabación (Fase B, 3.1.2): transición ilegal o
     * solicitud duplicada (`startRecording` con una grabación ya
     * activa o en curso de inicio; plan §6.2). Espejo en
     * `lib/features/streaming/domain/stream_contract.dart`.
     */
    const val RECORDING_STATE_ERROR = "recording_state_error"

    /**
     * Errores de la ruta canónica y del espacio (subetapa 0, 3.1.4).
     * `recordings_dir_unavailable` cubre exclusivamente fallos de
     * directorio (V1–V4); `storage_check_unavailable`, un `StatFs`
     * que no pudo medir el espacio. Espejo en
     * `lib/features/streaming/domain/stream_contract.dart`.
     */
    const val RECORDINGS_DIR_UNAVAILABLE = "recordings_dir_unavailable"
    const val STORAGE_CHECK_UNAVAILABLE = "storage_check_unavailable"

    /**
     * Códigos de canal (subetapa 0, 3.1.4). Regla (R-E5): existen
     * en Kotlin solo como espejo; NINGÚN código Kotlin las emite.
     * Las construye únicamente el wrapper Dart en
     * `AppServices.create()`: `recordings_channel_unavailable` tras
     * agotar los reintentos del puente, `recordings_channel_contract_error`
     * ante una respuesta nula o vacía, y `recordings_channel_timeout`
     * por intento nativo que supera su `withTimeout`. Espejo en
     * `lib/features/streaming/domain/stream_contract.dart`.
     */
    const val RECORDINGS_CHANNEL_UNAVAILABLE = "recordings_channel_unavailable"
    const val RECORDINGS_CHANNEL_CONTRACT_ERROR = "recordings_channel_contract_error"
    const val RECORDINGS_CHANNEL_TIMEOUT = "recordings_channel_timeout"
}
