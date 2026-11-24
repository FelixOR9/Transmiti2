/// Contrato del canal Flutter <-> Kotlin para la transmisión.
///
/// En la Fase 3.0 el contrato existe y los métodos de video devuelven el
/// error `not_available_in_phase_3_0`: no se simula ningún éxito. La cámara,
/// la vista previa, la transmisión y la grabación llegan en las fases 3.1
/// en adelante.
///
/// En la subetapa 3.1.0 el contrato se amplía sin romper la 3.0: ningún
/// método existente cambia de nombre ni de comportamiento; `requestPermissions`
/// se vuelve real (antes devolvía
/// `permission_request_deferred_to_phase_3_1`) y se añaden `checkPermissions`
/// con estados, `openAppSettings`, `stopPreview` y `stopRecording`. Los
/// métodos de video siguen devolviendo `not_available_in_phase_3_0` hasta
/// su subetapa.
///
/// En la subetapa 3.1.1 `startPreview` y `stopPreview` son reales
/// (Camera2 + `SurfaceProducer`) y `stopAll` detiene también la vista
/// previa. La grabación sigue devolviendo `not_available_in_phase_3_0`.
abstract final class StreamContract {
  /// Canal de métodos Flutter -> Kotlin.
  static const String methodChannel =
      'com.transmiti2.transmiti2/streaming';

  /// Canal de eventos Kotlin -> Flutter (estados de transmisión).
  static const String eventChannel =
      'com.transmiti2.transmiti2/streaming/events';

  // Métodos del canal.
  static const String getStatus = 'getStatus';

  /// Solo lectura: devuelve los estados de cámara, micrófono y
  /// notificaciones (`notRequested`, `granted`, `denied`,
  /// `permanentlyDenied`) sin mostrar ningún diálogo del sistema.
  static const String checkPermissions = 'checkPermissions';

  /// Solicitud real desde la subetapa 3.1.0. Recibe
  /// `{'permissions': ['camera' | 'microphone' | 'notifications']}` y
  /// devuelve el mapa de estados resultante.
  static const String requestPermissions = 'requestPermissions';

  /// Abre los ajustes de la aplicación (para la denegación permanente).
  static const String openAppSettings = 'openAppSettings';

  /// Guarda la clave cifrada de un destino ya creado en SQLite.
  static const String saveStreamKey = 'saveStreamKey';
  static const String deleteStreamKey = 'deleteStreamKey';
  static const String hasStreamKey = 'hasStreamKey';

  /// Funciones de video: existen en el contrato pero no están disponibles
  /// en la Fase 3.0 ni en la subetapa 3.1.0 (devuelven
  /// `not_available_in_phase_3_0`). En la subetapa 3.1.1 `startPreview`
  /// y `stopPreview` son reales; la grabación y la transmisión siguen
  /// sin estarlo.
  static const String startPreview = 'startPreview';
  static const String stopPreview = 'stopPreview';
  static const String startStream = 'startStream';
  static const String startRecording = 'startRecording';
  static const String stopRecording = 'stopRecording';
  static const String stopAll = 'stopAll';

  /// Estado de la grabación y reconciliación de interrupciones
  /// (Fase B, 3.1.2, componente 3; subetapa 0, grupo 0-d2).
  /// `getRecordingStatus` devuelve la instantánea del estado para
  /// re-sincronizar la UI al volver a la pantalla;
  /// `reconcileRecordings` ejecuta (o espera, si ya corre) la única
  /// pasada de reconciliación del proceso y devuelve el resumen por
  /// grupo (renombres, clasificaciones, pendientes
  /// `PENDING_VERIFICATION`, `skipped_active`). El nombre antiguo
  /// `recoverInterruptedRecordings` se eliminó en 0-d2: no hay dos
  /// caminos.
  static const String getRecordingStatus = 'getRecordingStatus';
  static const String reconcileRecordings = 'reconcileRecordings';

  /// Ruta canónica de grabaciones y estado de la puerta de
  /// reconciliación (subetapa 0, 3.1.4). `getRecordingsDir` devuelve
  /// la ruta absoluta `<filesDir>/recordings/` o el error
  /// `recordings_dir_unavailable`; `getReconciliationState` expone
  /// la puerta única por proceso (`NOT_STARTED` → `RUNNING` →
  /// `DONE` | `FAILED`). Espejo en Kotlin (`StreamContract.kt`).
  static const String getRecordingsDir = 'getRecordingsDir';
  static const String getReconciliationState = 'getReconciliationState';

  // Eventos del canal de eventos.
  /// Se emite tras cada solicitud de permisos, una vez por permiso.
  static const String eventPermissionResult = 'permission_result';

  /// Se emite cuando un estado de permiso cambia sin solicitud (por
  /// ejemplo, una revocación detectada al reanudar la app).
  static const String eventPermissionChanged = 'permission_changed';

  /// Se emiten desde la subetapa 3.1.2, cuando el servicio arranca y para
  /// de verdad con una grabación. `service_stopped` lleva
  /// `recordingId` y, desde J3-b (3.1.4), `reason`: `normal_stop`
  /// (parada por `stop()`) o `error_stop` (auto-parada por error: la
  /// sesión completó `stop(errorCode)` y el motor paró el FGS). Un
  /// payload viejo sin `reason` sigue parseando (motivo `null`).
  static const String eventServiceStarted = 'service_started';
  static const String eventServiceStopped = 'service_stopped';

  /// Evento genérico de error del motor.
  static const String eventError = 'error';

  /// Eventos de vista previa (subetapa 3.1.1). `preview_started` lleva
  /// `textureId`, `width`, `height` y `fps`; `preview_error` lleva
  /// `code` y, desde la Fase A, también `message` y los datos de
  /// diagnóstico (`stage`, `cameraCount`, `selectedCameraId`,
  /// `selectedSize`, `selectedFps`, `exceptionClass`) cuando el motor
  /// los tiene (en una desconexión asíncrona solo llega el código);
  /// `preview_surface_lost` no lleva datos (la superficie se perdió
  /// temporalmente y el motor la recupera solo).
  static const String eventPreviewStarted = 'preview_started';
  static const String eventPreviewStopped = 'preview_stopped';
  static const String eventPreviewError = 'preview_error';
  static const String eventPreviewSurfaceLost = 'preview_surface_lost';

  /// Eventos de grabación (Fase B, 3.1.2, componente 3).
  ///
  /// `recording_started` lleva `recordingId`, `matchId`, `width`,
  /// `height`, `fps`, `bitrateBps` (ojo: el plan lo llama
  /// `videoBitrateBps`, el cable real es `bitrateBps`), `profile`,
  /// `bitrateMode`, `encoderName`, `rotationDegrees`,
  /// `lowStorageWarning` y `cbrSupported`. `recording_segment` lleva
  /// `recordingId`, `segmentIndex`, `fileName`, `filePath`, `status`
  /// (`ready`/`partial`/`corrupt`), `durationMs`, `sizeBytes` y los
  /// detalles de verificación. `recording_stopped` lleva `recordingId`,
  /// `segments` (la lista de segmentos verificados), `segmentCount`,
  /// `totalDurationMs`, `totalSizeBytes`, `status` (`ready`/`partial`/
  /// `corrupt`/`idle`), `errorCode` y `previewRestoreError` (el plan
  /// lo resume como `filePath`/`durationMs`/`sizeBytes`; el cable real
  /// es este). `recording_error` lleva `code`, `message`, `details` y,
  /// al detener con error, también el resultado (`recordingId`,
  /// `segments`, `status`...).
  static const String eventRecordingStarted = 'recording_started';
  static const String eventRecordingSegment = 'recording_segment';
  static const String eventRecordingStopped = 'recording_stopped';
  static const String eventRecordingError = 'recording_error';

  /// Pérdida de audio en caliente (3.1.3, plan §8.1):
  /// `{recordingId, reason, segmentIndex, message}`. `reason` es uno
  /// de `audio_dead_object`, `audio_invalid_operation`,
  /// `audio_encoder_error`, `audio_silence`, `audio_format_timeout`
  /// o `audio_silence_samples` (Grupo S, S1: silencio digital en las
  /// muestras PCM, distinto de `audio_silence` = lecturas vacías).
  /// Con `audio_silence_samples` el evento lleva además las claves
  /// opcionales `silenceMs` (duración del silencio detectado),
  /// `silenceStartOffsetMs` (offset desde el ancla hasta la primera
  /// muestra silenciosa) y `silencePeak` (pico medido en la ventana
  /// silenciosa, esperado 0). No es un error de grabación: el estado
  /// sigue siendo `recording` y el video continúa.
  static const String eventRecordingAudioLost = 'recording_audio_lost';

  // Códigos de error (nunca llevan la clave ni la URL dentro).
  static const String notAvailableInPhase30 = 'not_available_in_phase_3_0';

  /// Ya no se devuelve desde la subetapa 3.1.0: `requestPermissions` es
  /// real. Se conserva por compatibilidad con la Fase 3.0.
  static const String permissionRequestDeferred =
      'permission_request_deferred_to_phase_3_1';
  static const String invalidRtmpsUrl = 'invalid_rtmps_url';
  static const String emptyKey = 'empty_key';
  static const String secureStoreUnavailable = 'secure_store_unavailable';
  static const String keystoreNotActive = 'keystore_not_active';
  static const String destinationNotFound = 'destination_not_found';

  /// Códigos de error definidos en la subetapa 3.1.0 para las fases
  /// siguientes. En 3.1.0 solo se produce `permission_denied`; los demás
  /// quedan definidos para no cambiar el contrato después.
  static const String cameraUnavailable = 'camera_unavailable';
  static const String cameraInUse = 'camera_in_use';
  static const String cameraDisconnected = 'camera_disconnected';

  /// Códigos de diagnóstico (Fase A, 3.1.1): diferencian fallos que
  /// antes colapsaban en `camera_unavailable`, sin cambiar la lógica
  /// de apertura/cierre. Espejo en Kotlin
  /// (`StreamContract.kt`).
  static const String cameraTimeout = 'camera_timeout';
  static const String cameraConfigError = 'camera_config_error';
  static const String cameraStateError = 'camera_state_error';
  static const String cameraDisabledBySystem = 'camera_disabled_by_system';
  static const String encoderError = 'encoder_error';
  static const String muxerError = 'muxer_error';
  static const String audioUnavailable = 'audio_unavailable';
  static const String storageFull = 'storage_full';
  static const String thermalShutdown = 'thermal_shutdown';
  static const String permissionDenied = 'permission_denied';
  static const String recordingNotFound = 'recording_not_found';

  /// Error de grabación: transición ilegal o duplicada (p. ej. un
  /// segundo `startRecording` con una grabación en curso), o fallo
  /// genérico de grabación sin código más específico.
  static const String recordingStateError = 'recording_state_error';

  /// Errores de la ruta canónica y del espacio (subetapa 0, 3.1.4).
  /// `recordings_dir_unavailable` cubre exclusivamente fallos de
  /// directorio (V1–V4); `storage_check_unavailable`, un `StatFs`
  /// que no pudo medir el espacio. Espejo en Kotlin
  /// (`StreamContract.kt`).
  static const String recordingsDirUnavailable =
      'recordings_dir_unavailable';
  static const String storageCheckUnavailable =
      'storage_check_unavailable';

  /// Códigos de canal (subetapa 0, 3.1.4). Regla (R-E5): existen en
  /// Kotlin solo como espejo; ningún código Kotlin los emite. Los
  /// construye únicamente el wrapper Dart en `AppServices.create()`:
  /// `recordings_channel_unavailable` tras agotar los reintentos del
  /// puente, `recordings_channel_contract_error` ante una respuesta
  /// nula o vacía, y `recordings_channel_timeout` por intento nativo
  /// que supera su `withTimeout`. Espejo en Kotlin
  /// (`StreamContract.kt`).
  static const String recordingsChannelUnavailable =
      'recordings_channel_unavailable';
  static const String recordingsChannelContractError =
      'recordings_channel_contract_error';
  static const String recordingsChannelTimeout =
      'recordings_channel_timeout';

  /// Fallos de la puerta de reconciliación (subetapa 0, grupo 0-d2).
  /// `reconciliation_unavailable`: no hay sesión de grabación con la
  /// que reconciliar (motor sin `context` ni fábrica). Viajan DENTRO
  /// del payload de `reconcileRecordings`/`getReconciliationState`
  /// (`state` `FAILED` + `errorCode`), nunca como
  /// `PlatformException`. Espejo en Kotlin (`StreamContract.kt`).
  static const String reconciliationUnavailable =
      'reconciliation_unavailable';
  static const String reconciliationFailed = 'reconciliation_failed';
}

/// Estado del motor de transmisión que devuelve `getStatus`.
///
/// En la Fase 3.0 `foregroundServiceAvailable` es siempre `false`: el
/// servicio en primer plano existe como esqueleto sin registrar y se activa
/// en la Fase 3.1. En la subetapa 3.1.0 sigue siendo `false`: el servicio
/// ya está registrado en el manifiesto, pero todavía no arranca sesiones
/// reales de grabación (eso llega en 3.1.2).
class StreamStatus {
  const StreamStatus({
    required this.foregroundServiceAvailable,
    required this.cameraGranted,
    required this.microphoneGranted,
    required this.streamingSupported,
  });

  /// `false` hasta la subetapa 3.1.2. La comprobación es de solo lectura:
  /// no muestra ningún diálogo del sistema.
  final bool foregroundServiceAvailable;
  final bool cameraGranted;
  final bool microphoneGranted;

  /// `false` en la Fase 3.0 y en la subetapa 3.1.0: sin vista previa,
  /// transmisión ni grabación.
  final bool streamingSupported;

  factory StreamStatus.fromMap(Map<Object?, Object?> map) {
    return StreamStatus(
      foregroundServiceAvailable:
          map['foregroundServiceAvailable'] as bool? ?? false,
      cameraGranted: map['cameraGranted'] as bool? ?? false,
      microphoneGranted: map['microphoneGranted'] as bool? ?? false,
      streamingSupported: map['streamingSupported'] as bool? ?? false,
    );
  }

  Map<String, Object> toMap() {
    return <String, Object>{
      'foregroundServiceAvailable': foregroundServiceAvailable,
      'cameraGranted': cameraGranted,
      'microphoneGranted': microphoneGranted,
      'streamingSupported': streamingSupported,
    };
  }
}

/// Información de la vista previa que devuelve `startPreview`
/// (desde la subetapa 3.1.1).
class PreviewInfo {
  const PreviewInfo({
    required this.textureId,
    required this.width,
    required this.height,
    this.sensorOrientation = 90,
    this.rotationDegrees = 90,
    this.fps = 30,
    this.lensFacing = 'back',
  });

  /// Identificador de textura de Flutter para dibujar la preview.
  final int textureId;

  /// Tamaño en orientación del sensor (sin rotar).
  final int width;
  final int height;

  /// Orientación del sensor en grados (normalmente 90 en la trasera).
  final int sensorOrientation;

  /// Rotación en grados que hay que aplicar a la imagen para verla
  /// derecha. La UI la aplica con `RotatedBox`.
  final int rotationDegrees;

  /// FPS del rango elegido (objetivo 30).
  final int fps;

  /// Cámara usada: `back` (la frontal queda diferida en 3.1.1).
  final String lensFacing;

  factory PreviewInfo.fromMap(Map<Object?, Object?> map) {
    return PreviewInfo(
      textureId: (map['textureId'] as num?)?.toInt() ?? -1,
      width: (map['width'] as num?)?.toInt() ?? 0,
      height: (map['height'] as num?)?.toInt() ?? 0,
      sensorOrientation: (map['sensorOrientation'] as num?)?.toInt() ?? 90,
      rotationDegrees: (map['rotationDegrees'] as num?)?.toInt() ?? 90,
      fps: (map['fps'] as num?)?.toInt() ?? 30,
      lensFacing: map['lensFacing'] as String? ?? 'back',
    );
  }
}

/// Opciones de grabación para `startRecording` (desde la subetapa 3.1.2).
class RecordingOptions {
  const RecordingOptions({
    this.matchId,
    this.resolution = '720p',
    this.fps = 30,
    this.videoBitrateBps = 2500000,
    this.audioEnabled = true,
  });

  /// Partido asociado, si lo hay.
  final int? matchId;
  final String resolution;
  final int fps;
  final int videoBitrateBps;
  final bool audioEnabled;

  Map<String, Object> toMap() {
    return <String, Object>{
      if (matchId != null) 'matchId': matchId!,
      'resolution': resolution,
      'fps': fps,
      'videoBitrateBps': videoBitrateBps,
      'audioEnabled': audioEnabled,
    };
  }
}

/// Resultado de `stopRecording` (desde la subetapa 3.1.2).
class RecordingInfo {
  const RecordingInfo({
    required this.recordingId,
    required this.filePath,
    required this.durationMs,
    required this.sizeBytes,
    required this.status,
    this.segments = const <RecordingSegmentInfo>[],
    this.audioEnabled = false,
    this.droppedInputBuffers = 0,
    this.inputBufferCapacityBytes,
    this.micStateAtStart,
    this.micStateAtStop,
    this.audioReadCount = 0,
    this.audioLastReadCode,
    this.audioLastReadOkAgeMs,
    this.audioReaderAlive = false,
    this.audioEos = false,
    this.audioReaderNoData2s = false,
    this.eosTimedOut = false,
    this.quietestSecondPeak,
    this.quietestSecondNonZero,
    this.silencePeak,
  });

  final String recordingId;
  final String filePath;
  final int durationMs;
  final int sizeBytes;

  /// Estado final de la grabación (`ready`, `partial`, ...).
  final String status;

  /// Segmentos verificados (completos): el método `stopRecording`
  /// los devuelve para que la UI no dependa de haber recibido todos
  /// los eventos `recording_segment` (fix duraciones 3.1.2, paso 5).
  final List<RecordingSegmentInfo> segments;

  /// Audio de la grabación (3.1.3, plan §8.1): `true` si al menos un
  /// segmento lleva pista AAC (`RecordingResult.audioEnabled`).
  final bool audioEnabled;

  /// Instrumentación de audio 3.1.3: buffers de entrada AAC
  /// descartados porque la cola del codec estaba llena
  /// (`droppedInputBuffers` nativo). El método `stopRecording`
  /// devuelve el mismo mapa que el evento `recording_stopped`, así
  /// que la parada por método también trae el valor real; ausente → 0.
  final int droppedInputBuffers;

  /// Capacidad del buffer de entrada AAC en bytes (ronda U):
  /// `inputBufferCapacityBytes` nativo, registrado en el primer
  /// drenado. Sirve para decidir a qué tamaño trocear las tramas PCM
  /// si el buffer es menor que la trama (H1-a). Ausente → `null`:
  /// sin audio o sin drenado, nunca se inventa un valor.
  final int? inputBufferCapacityBytes;

  /// Instrumentación ronda 3 (grupo I): estado del micrófono
  /// (`getRecordingState()` nativo: 3 = `RECORDING`, 1 = `STOPPED`)
  /// tras `startRecording()` y al parar; `null` sin encoder o sin
  /// arranque. Los contadores del hilo lector (`audioReadCount`,
  /// `audioLastReadCode`, `audioLastReadOkAgeMs`, `audioReaderAlive`)
  /// y si llegó el EOS de audio (`audioEos`). Ausentes → `null`/`0`/
  /// `false`: nunca se inventan valores.
  final int? micStateAtStart;
  final int? micStateAtStop;
  final int audioReadCount;
  final int? audioLastReadCode;
  final int? audioLastReadOkAgeMs;
  final bool audioReaderAlive;
  final bool audioEos;

  /// Grupo V: `audioReaderNoData2s` (V3) es el flag del watchdog
  /// del lector (sin ninguna lectura devuelta a los 2 s de
  /// arrancar); `eosTimedOut` (V4) es si la espera de EOS (audio y
  /// video, 5 s en total) se agotó sin que llegara el EOS.
  /// Ausentes → `false`: nunca se inventan valores.
  final bool audioReaderNoData2s;
  final bool eosTimedOut;

  /// Grupo S (S0, solo lectura): el segundo más silencioso de la
  /// grabación (pico y muestras no cero de la ventana completa con
  /// menos muestras no cero). Ausentes → `null`: nunca se inventa
  /// un valor (y nunca `0`, porque `0` significaría silencio
  /// digital).
  final int? quietestSecondPeak;
  final int? quietestSecondNonZero;

  /// Grupo S, S1: pico medido en la ventana silenciosa que disparó
  /// el detector, esperado 0; `null` si no hubo disparo.
  final int? silencePeak;

  factory RecordingInfo.fromMap(Map<Object?, Object?> map) {
    final List<Object?> rawSegments =
        (map['segments'] as List?)?.cast<Object?>() ?? <Object?>[];
    return RecordingInfo(
      recordingId: map['recordingId'] as String? ?? '',
      filePath: map['filePath'] as String? ?? '',
      durationMs: (map['durationMs'] as num?)?.toInt() ?? 0,
      sizeBytes: (map['sizeBytes'] as num?)?.toInt() ?? 0,
      status: map['status'] as String? ?? '',
      segments: rawSegments
          .whereType<Map<Object?, Object?>>()
          .map(RecordingSegmentInfo.fromMap)
          .toList(),
      audioEnabled: map['audioEnabled'] as bool? ?? false,
      droppedInputBuffers:
          (map['droppedInputBuffers'] as num?)?.toInt() ?? 0,
      inputBufferCapacityBytes:
          (map['inputBufferCapacityBytes'] as num?)?.toInt(),
      micStateAtStart: (map['micStateAtStart'] as num?)?.toInt(),
      micStateAtStop: (map['micStateAtStop'] as num?)?.toInt(),
      audioReadCount: (map['audioReadCount'] as num?)?.toInt() ?? 0,
      audioLastReadCode: (map['audioLastReadCode'] as num?)?.toInt(),
      audioLastReadOkAgeMs:
          (map['audioLastReadOkAgeMs'] as num?)?.toInt(),
      audioReaderAlive: map['audioReaderAlive'] as bool? ?? false,
      audioEos: map['audioEos'] as bool? ?? false,
      audioReaderNoData2s: map['audioReaderNoData2s'] as bool? ?? false,
      eosTimedOut: map['eosTimedOut'] as bool? ?? false,
      quietestSecondPeak: (map['quietestSecondPeak'] as num?)?.toInt(),
      quietestSecondNonZero:
          (map['quietestSecondNonZero'] as num?)?.toInt(),
      silencePeak: (map['silencePeak'] as num?)?.toInt(),
    );
  }
}

/// Información de una grabación recién iniciada (evento
/// `recording_started`, Fase B, 3.1.2).
class RecordingStartedInfo {
  const RecordingStartedInfo({
    required this.recordingId,
    this.matchId,
    required this.width,
    required this.height,
    required this.fps,
    required this.bitrateBps,
    this.profile,
    this.bitrateMode,
    this.encoderName,
    this.rotationDegrees = 90,
    this.lowStorageWarning = false,
    this.cbrSupported = true,
    this.audioEnabled = false,
    this.audioSampleRate,
    this.audioChannels,
    this.audioEncoderName,
    this.audioReason,
    this.timestampSource,
  });

  final String recordingId;
  final String? matchId;
  final int width;
  final int height;
  final int fps;

  /// Bitrate real en bps (el cable lo llama `bitrateBps`).
  final int bitrateBps;
  final int? profile;
  final int? bitrateMode;
  final String? encoderName;
  final int rotationDegrees;
  final bool lowStorageWarning;
  final bool cbrSupported;

  /// Audio de la grabación (3.1.3, plan §8.1): `true` si el motor
  /// arrancó con pista AAC. `false` en modo video-only (permiso
  /// denegado, micrófono no disponible o formato AAC sin llegar).
  /// Las claves ausentes (eventos de versiones anteriores) son
  /// `false`/`null`: nunca se inventan valores.
  final bool audioEnabled;

  /// Frecuencia de muestreo real del AAC (Hz), si hay audio.
  final int? audioSampleRate;

  /// Canales reales del AAC (1 = mono), si hay audio.
  final int? audioChannels;

  /// Nombre del codificador AAC, si hay audio.
  final String? audioEncoderName;

  /// Motivo de video-only cuando se pidió audio (`null` si hay audio
  /// o no se pidió).
  final String? audioReason;

  /// Origen de los timestamps de la cámara (`REALTIME`/`UNKNOWN`),
  /// para la sincronización A/V (plan §4).
  final String? timestampSource;

  factory RecordingStartedInfo.fromMap(Map<Object?, Object?> map) {
    return RecordingStartedInfo(
      recordingId: map['recordingId'] as String? ?? '',
      matchId: map['matchId'] as String?,
      width: (map['width'] as num?)?.toInt() ?? 0,
      height: (map['height'] as num?)?.toInt() ?? 0,
      fps: (map['fps'] as num?)?.toInt() ?? 30,
      bitrateBps: (map['bitrateBps'] as num?)?.toInt() ?? 0,
      profile: (map['profile'] as num?)?.toInt(),
      bitrateMode: (map['bitrateMode'] as num?)?.toInt(),
      encoderName: map['encoderName'] as String?,
      rotationDegrees: (map['rotationDegrees'] as num?)?.toInt() ?? 90,
      lowStorageWarning: map['lowStorageWarning'] as bool? ?? false,
      cbrSupported: map['cbrSupported'] as bool? ?? true,
      audioEnabled: map['audioEnabled'] as bool? ?? false,
      audioSampleRate: (map['audioSampleRate'] as num?)?.toInt(),
      audioChannels: (map['audioChannels'] as num?)?.toInt(),
      audioEncoderName: map['audioEncoderName'] as String?,
      audioReason: map['audioReason'] as String?,
      timestampSource: map['timestampSource'] as String?,
    );
  }
}

/// Segmento verificado de una grabación (evento `recording_segment`,
/// Fase B, 3.1.2).
class RecordingSegmentInfo {
  const RecordingSegmentInfo({
    required this.recordingId,
    required this.index,
    required this.fileName,
    required this.filePath,
    required this.status,
    this.durationMs,
    required this.sizeBytes,
    this.sampleCount,
    this.keyframeCount,
    this.firstSampleKeyframe,
    this.ptsMonotonic,
    this.profileIdc,
    /// FPS medido por el verificador (ronda 3): se muestra en el
    /// diagnóstico para decidir la tolerancia con evidencia.
    this.fpsMeasured,
    /// Muestras leídas por el verificador (ronda 4, fix 3): el
    /// `sampleCount` de arriba es el del drenado y puede diferir;
    /// este es el recuento que vio el verificador.
    this.verifiedSampleCount,
    /// Duración de la fase 1 de verificación en ms (ronda 4, fix 2).
    this.ioPhaseMs,
    this.closedCleanly = false,
    this.reason,
    this.hasAudio = false,
    this.audioDurationMs,
    this.audioSampleCount,
    this.audioSampleRate,
    this.audioChannels,
    this.avOffsetMs,
    this.avDurationDiffMs,
    this.audioReason,
    this.audioGapSamples = 0,
    this.audioStartOffsetMs,
  });

  final String recordingId;
  final int index;
  final String fileName;
  final String filePath;

  /// `ready`, `partial` o `corrupt`.
  final String status;
  final int? durationMs;
  final int sizeBytes;
  final int? sampleCount;
  final int? keyframeCount;
  final bool? firstSampleKeyframe;
  final bool? ptsMonotonic;
  final int? profileIdc;
  final double? fpsMeasured;
  final int? verifiedSampleCount;
  final int? ioPhaseMs;
  final bool closedCleanly;
  final String? reason;

  /// Audio del segmento (3.1.3, plan §8.1). `hasAudio` es `false` en
  /// modo video-only y en los segmentos de 3.1.2 (sin claves de
  /// audio): nunca se inventa audio donde no lo hay.
  final bool hasAudio;

  /// Duración real de la pista AAC (ms) y muestras AAC leídas.
  final int? audioDurationMs;
  final int? audioSampleCount;

  /// Formato real de la pista AAC (Hz y canales).
  final int? audioSampleRate;
  final int? audioChannels;

  /// Desplazamiento A/V medido (primer PTS de audio − primer PTS de
  /// video, ms) y diferencia de duraciones (video − audio, ms).
  final int? avOffsetMs;
  final int? avDurationDiffMs;

  /// Motivo de audio del segmento (p. ej. `audio_format_timeout`).
  final String? audioReason;

  /// Muestras de audio descartadas por cola llena (aviso §5.2).
  final int audioGapSamples;

  /// Desplazamiento (ms) de la primera muestra de audio respecto al
  /// origen común del segmento (instrumentación de audio 3.1.3): `null`
  /// cuando ninguna muestra llegó a la sesión (H1) o cuando el motor
  /// no lo envía (segmentos de 3.1.2). Nunca se inventa.
  final int? audioStartOffsetMs;

  factory RecordingSegmentInfo.fromMap(Map<Object?, Object?> map) {
    return RecordingSegmentInfo(
      recordingId: map['recordingId'] as String? ?? '',
      index: (map['segmentIndex'] as num?)?.toInt() ?? 0,
      fileName: map['fileName'] as String? ?? '',
      filePath: map['filePath'] as String? ?? '',
      status: map['status'] as String? ?? '',
      durationMs: (map['durationMs'] as num?)?.toInt(),
      sizeBytes: (map['sizeBytes'] as num?)?.toInt() ?? 0,
      sampleCount: (map['sampleCount'] as num?)?.toInt(),
      keyframeCount: (map['keyframeCount'] as num?)?.toInt(),
      firstSampleKeyframe: map['firstSampleKeyframe'] as bool?,
      ptsMonotonic: map['ptsMonotonic'] as bool?,
      profileIdc: (map['profileIdc'] as num?)?.toInt(),
      fpsMeasured: (map['fpsMeasured'] as num?)?.toDouble(),
      verifiedSampleCount: (map['verifiedSampleCount'] as num?)?.toInt(),
      ioPhaseMs: (map['ioPhaseMs'] as num?)?.toInt(),
      closedCleanly: map['closedCleanly'] as bool? ?? false,
      reason: map['reason'] as String?,
      hasAudio: map['hasAudio'] as bool? ?? false,
      audioDurationMs: (map['audioDurationMs'] as num?)?.toInt(),
      audioSampleCount: (map['audioSampleCount'] as num?)?.toInt(),
      audioSampleRate: (map['audioSampleRate'] as num?)?.toInt(),
      audioChannels: (map['audioChannels'] as num?)?.toInt(),
      avOffsetMs: (map['avOffsetMs'] as num?)?.toInt(),
      avDurationDiffMs: (map['avDurationDiffMs'] as num?)?.toInt(),
      audioReason: map['audioReason'] as String?,
      audioGapSamples: (map['audioGapSamples'] as num?)?.toInt() ?? 0,
      audioStartOffsetMs: (map['audioStartOffsetMs'] as num?)?.toInt(),
    );
  }
}

/// Resultado de una grabación detenida (evento `recording_stopped` o
/// `recording_error` al detener con error, Fase B, 3.1.2).
class RecordingStoppedInfo {
  const RecordingStoppedInfo({
    required this.recordingId,
    required this.segments,
    required this.totalDurationMs,
    required this.totalSizeBytes,
    required this.status,
    this.errorCode,
    this.previewRestoreError,
    this.audioEnabled = false,
    this.droppedInputBuffers = 0,
    this.inputBufferCapacityBytes,
    this.micStateAtStart,
    this.micStateAtStop,
    this.audioReadCount = 0,
    this.audioLastReadCode,
    this.audioLastReadOkAgeMs,
    this.audioReaderAlive = false,
    this.audioEos = false,
    this.audioReaderNoData2s = false,
    this.eosTimedOut = false,
    this.quietestSecondPeak,
    this.quietestSecondNonZero,
    this.silencePeak,
  });

  final String recordingId;
  final List<RecordingSegmentInfo> segments;
  final int totalDurationMs;
  final int totalSizeBytes;

  /// `ready`, `partial`, `corrupt` o `idle`.
  final String status;
  final String? errorCode;
  final String? previewRestoreError;

  /// Audio de la grabación (3.1.3, plan §8.1): `true` si al menos un
  /// segmento lleva pista AAC.
  final bool audioEnabled;

  /// Entradas PCM descartadas por el encoder AAC al parar
  /// (instrumentación de audio 3.1.3: `dequeueInputBuffer` sin búfer
  /// libre). Ausente → 0.
  final int droppedInputBuffers;

  /// Capacidad del buffer de entrada AAC en bytes (ronda U): mismo
  /// significado que en `RecordingInfo`. Ausente → `null`: nunca se
  /// inventa un valor.
  final int? inputBufferCapacityBytes;

  /// Instrumentación ronda 3 (grupo I): mismo significado que en
  /// `RecordingInfo` (estado del micrófono al arrancar y al parar,
  /// contadores del lector y EOS de audio). Ausentes → `null`/`0`/
  /// `false`: nunca se inventan valores.
  final int? micStateAtStart;
  final int? micStateAtStop;
  final int audioReadCount;
  final int? audioLastReadCode;
  final int? audioLastReadOkAgeMs;
  final bool audioReaderAlive;
  final bool audioEos;

  /// Grupo V: mismo significado que en `RecordingInfo`
  /// (`audioReaderNoData2s`: el watchdog disparó a los 2 s sin
  /// lecturas; `eosTimedOut`: la espera de EOS agotó los 5 s).
  /// Ausentes → `false`: nunca se inventan valores.
  final bool audioReaderNoData2s;
  final bool eosTimedOut;

  /// Grupo S (S0, solo lectura): mismo significado que en
  /// `RecordingInfo` (el segundo más silencioso). Ausentes →
  /// `null`: nunca se inventa un valor.
  final int? quietestSecondPeak;
  final int? quietestSecondNonZero;

  /// Grupo S, S1: pico medido en la ventana silenciosa que disparó
  /// el detector, esperado 0; `null` si no hubo disparo.
  final int? silencePeak;

  int get segmentCount => segments.length;

  /// Ruta del primer segmento (la ruta final fusionada llega en 3.1.4).
  String get filePath => segments.isEmpty ? '' : segments.first.filePath;

  factory RecordingStoppedInfo.fromMap(Map<Object?, Object?> map) {
    final List<Object?> rawSegments =
        (map['segments'] as List?)?.cast<Object?>() ?? <Object?>[];
    return RecordingStoppedInfo(
      recordingId: map['recordingId'] as String? ?? '',
      segments: rawSegments
          .whereType<Map<Object?, Object?>>()
          .map(RecordingSegmentInfo.fromMap)
          .toList(),
      totalDurationMs: (map['totalDurationMs'] as num?)?.toInt() ?? 0,
      totalSizeBytes: (map['totalSizeBytes'] as num?)?.toInt() ?? 0,
      status: map['status'] as String? ?? '',
      errorCode: map['errorCode'] as String?,
      previewRestoreError: map['previewRestoreError'] as String?,
      audioEnabled: map['audioEnabled'] as bool? ?? false,
      droppedInputBuffers: (map['droppedInputBuffers'] as num?)?.toInt() ?? 0,
      inputBufferCapacityBytes:
          (map['inputBufferCapacityBytes'] as num?)?.toInt(),
      micStateAtStart: (map['micStateAtStart'] as num?)?.toInt(),
      micStateAtStop: (map['micStateAtStop'] as num?)?.toInt(),
      audioReadCount: (map['audioReadCount'] as num?)?.toInt() ?? 0,
      audioLastReadCode: (map['audioLastReadCode'] as num?)?.toInt(),
      audioLastReadOkAgeMs:
          (map['audioLastReadOkAgeMs'] as num?)?.toInt(),
      audioReaderAlive: map['audioReaderAlive'] as bool? ?? false,
      audioEos: map['audioEos'] as bool? ?? false,
      audioReaderNoData2s: map['audioReaderNoData2s'] as bool? ?? false,
      eosTimedOut: map['eosTimedOut'] as bool? ?? false,
      quietestSecondPeak: (map['quietestSecondPeak'] as num?)?.toInt(),
      quietestSecondNonZero:
          (map['quietestSecondNonZero'] as num?)?.toInt(),
      silencePeak: (map['silencePeak'] as num?)?.toInt(),
    );
  }
}

/// Instantánea del estado de grabación para `getRecordingStatus`
/// (Fase B, 3.1.2).
class RecordingStatusSnapshot {
  const RecordingStatusSnapshot({
    required this.state,
    this.recordingId,
    this.elapsedMs = 0,
    this.segmentIndex = 0,
    this.segmentDurationMs = 0,
    this.width,
    this.height,
    this.fps,
    this.errorCode,
    this.segments = const <RecordingSegmentInfo>[],
    this.audioEnabled = false,
    this.audioActive = false,
    this.audioReason,
  });

  /// `idle`, `starting`, `recording`, `rotating_segment`, `stopping`
  /// o `error`.
  final String state;
  final String? recordingId;
  final int elapsedMs;
  final int segmentIndex;
  final int segmentDurationMs;
  final int? width;
  final int? height;
  final int? fps;
  final String? errorCode;

  /// Segmentos verificados hasta ahora (paso 6): la UI los recupera
  /// al re-sincronizar sin haber visto los eventos.
  final List<RecordingSegmentInfo> segments;

  /// Audio de la grabación (3.1.3, plan §8.1): `audioEnabled` es lo
  /// que se pidió al arrancar, `audioActive` es si el audio se está
  /// capturando ahora mismo (`false` tras una pérdida en caliente) y
  /// `audioReason` es el motivo de video-only o de la pérdida.
  /// Claves ausentes → `false`/`null`: nunca se inventan valores.
  final bool audioEnabled;
  final bool audioActive;
  final String? audioReason;

  factory RecordingStatusSnapshot.fromMap(Map<Object?, Object?> map) {
    final List<Object?> rawSegments =
        (map['segments'] as List?)?.cast<Object?>() ?? <Object?>[];
    return RecordingStatusSnapshot(
      state: map['state'] as String? ?? 'idle',
      recordingId: map['recordingId'] as String?,
      elapsedMs: (map['elapsedMs'] as num?)?.toInt() ?? 0,
      segmentIndex: (map['segmentIndex'] as num?)?.toInt() ?? 0,
      segmentDurationMs: (map['segmentDurationMs'] as num?)?.toInt() ?? 0,
      width: (map['width'] as num?)?.toInt(),
      height: (map['height'] as num?)?.toInt(),
      fps: (map['fps'] as num?)?.toInt(),
      errorCode: map['errorCode'] as String?,
      segments: rawSegments
          .whereType<Map<Object?, Object?>>()
          .map(RecordingSegmentInfo.fromMap)
          .toList(),
      audioEnabled: map['audioEnabled'] as bool? ?? false,
      audioActive: map['audioActive'] as bool? ?? false,
      audioReason: map['audioReason'] as String?,
    );
  }

  factory RecordingStatusSnapshot.idle() =>
      const RecordingStatusSnapshot(state: 'idle');
}

/// Segmento interrumpido (`.tmp.mp4`) clasificado en la recuperación
/// (Fase B, 3.1.2).
class RecoveredSegmentInfo {
  const RecoveredSegmentInfo({
    required this.recordingId,
    required this.index,
    required this.fileName,
    required this.filePath,
    required this.status,
    this.durationMs,
    required this.sizeBytes,
    this.reason,
  });

  final String recordingId;
  final int index;
  final String fileName;
  final String filePath;

  /// `ready`, `partial` o `corrupt`.
  final String status;
  final int? durationMs;
  final int sizeBytes;
  final String? reason;

  /// Utilizable para reproducir (`ready` o `partial`).
  bool get usable => status == 'ready' || status == 'partial';

  factory RecoveredSegmentInfo.fromMap(Map<Object?, Object?> map) {
    return RecoveredSegmentInfo(
      recordingId: map['recordingId'] as String? ?? '',
      index: (map['segmentIndex'] as num?)?.toInt() ?? 0,
      fileName: map['fileName'] as String? ?? '',
      filePath: map['filePath'] as String? ?? '',
      status: map['status'] as String? ?? '',
      durationMs: (map['durationMs'] as num?)?.toInt(),
      sizeBytes: (map['sizeBytes'] as num?)?.toInt() ?? 0,
      reason: map['reason'] as String?,
    );
  }
}

/// Estado de la puerta única de reconciliación por proceso
/// (subetapa 0, 3.1.4): `NOT_STARTED` → `RUNNING` → `DONE` | `FAILED`.
/// La puerta garantiza una ejecución por proceso y que la biblioteca
/// espere a `DONE`; la seguridad frente a la carrera con una
/// grabación activa la da el registro con tokens, no la puerta.
enum ReconciliationState {
  notStarted,
  running,
  done,
  failed;

  static ReconciliationState fromWireName(String? value) {
    return switch (value) {
      'RUNNING' => ReconciliationState.running,
      'DONE' => ReconciliationState.done,
      'FAILED' => ReconciliationState.failed,
      _ => ReconciliationState.notStarted,
    };
  }

  String get wireName => switch (this) {
    ReconciliationState.notStarted => 'NOT_STARTED',
    ReconciliationState.running => 'RUNNING',
    ReconciliationState.done => 'DONE',
    ReconciliationState.failed => 'FAILED',
  };
}

/// Segmento pendiente de verificación (caso C6 de la reconciliación):
/// no verificable (timeout, excepción, decodificador no disponible).
/// Nunca `ready`, nunca se elimina, excluido de lo utilizable, listado
/// para reintento. Es la forma Dart de `ReconciledSegmentRef` (Kotlin).
class PendingSegmentRef {
  const PendingSegmentRef({
    required this.recordingId,
    required this.segmentIndex,
    required this.fileName,
    this.reason,
  });

  final String recordingId;
  final int segmentIndex;
  final String fileName;
  final String? reason;

  factory PendingSegmentRef.fromMap(Map<Object?, Object?> map) {
    return PendingSegmentRef(
      recordingId: map['recordingId'] as String? ?? '',
      segmentIndex: (map['segmentIndex'] as num?)?.toInt() ?? 0,
      fileName: map['fileName'] as String? ?? '',
      reason: map['reason'] as String?,
    );
  }

  Map<String, Object?> toMap() {
    return <String, Object?>{
      'recordingId': recordingId,
      'segmentIndex': segmentIndex,
      'fileName': fileName,
      'reason': reason,
    };
  }
}

/// Resumen de una reconciliación (subetapa 0, 3.1.4), tal como lo
/// devuelve `getReconciliationState` al completarse la puerta y
/// `reconcileRecordings` dentro de su resultado.
///
/// Los contadores nunca se inventan: ausentes en el mapa → 0. `pending`
/// cuenta los segmentos `PENDING_VERIFICATION` (no verificables: nunca
/// `ready`, nunca se eliminan); `skippedActive` cuenta los grupos
/// saltados porque su grabación estaba activa o reservada.
/// `orphansReconstructed` lo rellena Dart al reconciliar las filas
/// (Kotlin no conoce las filas: en el mapa nativo la clave no viaja y
/// aquí es 0 hasta que el controlador la fusiona con `copyWith`).
///
/// Los tokens viajan como mapa grupo → token (`reservationTokens`):
/// no hay un único token de reserva (uno por grupo reservado) y el
/// token del claim del grabador es privado de la sesión, así que los
/// campos únicos `claimToken`/`reservationToken` de 0-a se eliminaron
/// en 0-d2.
class ReconciliationSummary {
  const ReconciliationSummary({
    this.state = ReconciliationState.notStarted,
    this.runId,
    this.startedAtMs,
    this.finishedAtMs,
    this.durationMs = 0,
    this.groupsTotal = 0,
    this.recordingsReconciled = 0,
    this.segmentsRenamed = 0,
    this.segmentsClassified = 0,
    this.segmentsTrusted = 0,
    this.segmentsVerified = 0,
    this.orphansReconstructed = 0,
    this.duplicatesQuarantined = 0,
    this.pending = 0,
    this.segmentsPending = const <PendingSegmentRef>[],
    this.skippedActive = 0,
    this.groupsSkippedActive = const <String>[],
    this.groupsFailed = const <String>[],
    this.reservationTokens = const <String, int>{},
    this.foreignReleaseRejected = 0,
    this.orphanWriteTestFiles = 0,
    this.conflicts = 0,
  });

  final ReconciliationState state;

  /// Identificador de la pasada (`run-N`).
  final String? runId;

  /// Inicio/fin de la pasada (reloj de pared, ms).
  final int? startedAtMs;
  final int? finishedAtMs;

  /// Duración medida de la pasada (ms).
  final int durationMs;

  /// Grupos en el inventario.
  final int groupsTotal;

  /// Grabaciones reconciliadas.
  final int recordingsReconciled;

  /// Segmentos renombrados (`.tmp` → `.mp4` con guarda).
  final int segmentsRenamed;

  /// Segmentos clasificados (por `status` del sidecar o verificados).
  final int segmentsClassified;

  /// Segmentos en los que se confió sin verificar (E1–E6).
  final int segmentsTrusted;

  /// Segmentos verificados en esta pasada (C3–C5).
  final int segmentsVerified;

  /// Huérfanos reconstruidos (archivo sin fila: fila insertada).
  final int orphansReconstructed;

  /// Duplicados en cuarentena (destino ocupado: nunca sobrescritos).
  final int duplicatesQuarantined;

  /// Segmentos pendientes de verificación (`PENDING_VERIFICATION`).
  final int pending;

  /// Detalle de los pendientes (para reintento).
  final List<PendingSegmentRef> segmentsPending;

  /// Grupos saltados por grabación activa o reserva (`skipped_active`).
  final int skippedActive;

  /// Identificadores de los grupos saltados.
  final List<String> groupsSkippedActive;

  /// Grupos que fallaron con excepción (no tumbaron la pasada).
  final List<String> groupsFailed;

  /// Grupo → token de reserva que lo procesó.
  final Map<String, int> reservationTokens;

  /// Liberaciones ajenas rechazadas (`NOT_OWNER`).
  final int foreignReleaseRejected;

  /// Archivos huérfanos `.write-test-*.tmp` barridos (V4a).
  final int orphanWriteTestFiles;

  /// Conflictos de destino duplicado encontrados.
  final int conflicts;

  factory ReconciliationSummary.fromMap(Map<Object?, Object?> map) {
    final List<Object?> rawPending =
        (map['segmentsPending'] as List?)?.cast<Object?>() ?? <Object?>[];
    final Map<Object?, Object?>? rawTokens =
        map['reservationTokens'] as Map<Object?, Object?>?;
    return ReconciliationSummary(
      state: ReconciliationState.fromWireName(map['state'] as String?),
      runId: map['runId'] as String?,
      startedAtMs: (map['startedAtMs'] as num?)?.toInt(),
      finishedAtMs: (map['finishedAtMs'] as num?)?.toInt(),
      durationMs: (map['durationMs'] as num?)?.toInt() ?? 0,
      groupsTotal: (map['groupsTotal'] as num?)?.toInt() ?? 0,
      recordingsReconciled:
          (map['recordingsReconciled'] as num?)?.toInt() ?? 0,
      segmentsRenamed: (map['segmentsRenamed'] as num?)?.toInt() ?? 0,
      segmentsClassified:
          (map['segmentsClassified'] as num?)?.toInt() ?? 0,
      segmentsTrusted: (map['segmentsTrusted'] as num?)?.toInt() ?? 0,
      segmentsVerified: (map['segmentsVerified'] as num?)?.toInt() ?? 0,
      orphansReconstructed:
          (map['orphansReconstructed'] as num?)?.toInt() ?? 0,
      duplicatesQuarantined:
          (map['duplicatesQuarantined'] as num?)?.toInt() ?? 0,
      pending: (map['pending'] as num?)?.toInt() ?? 0,
      segmentsPending: rawPending
          .whereType<Map<Object?, Object?>>()
          .map(PendingSegmentRef.fromMap)
          .toList(),
      skippedActive: (map['skipped_active'] as num?)?.toInt() ?? 0,
      groupsSkippedActive: _stringList(map['groupsSkippedActive']),
      groupsFailed: _stringList(map['groupsFailed']),
      reservationTokens: rawTokens == null
          ? const <String, int>{}
          : <String, int>{
              for (final MapEntry<Object?, Object?> e in rawTokens.entries)
                e.key.toString(): (e.value as num).toInt(),
            },
      foreignReleaseRejected:
          (map['foreignReleaseRejected'] as num?)?.toInt() ?? 0,
      orphanWriteTestFiles:
          (map['orphanWriteTestFiles'] as num?)?.toInt() ?? 0,
      conflicts: (map['conflicts'] as num?)?.toInt() ?? 0,
    );
  }

  Map<String, Object> toMap() {
    return <String, Object>{
      'state': state.wireName,
      if (runId != null) 'runId': runId!,
      if (startedAtMs != null) 'startedAtMs': startedAtMs!,
      if (finishedAtMs != null) 'finishedAtMs': finishedAtMs!,
      'durationMs': durationMs,
      'groupsTotal': groupsTotal,
      'recordingsReconciled': recordingsReconciled,
      'segmentsRenamed': segmentsRenamed,
      'segmentsClassified': segmentsClassified,
      'segmentsTrusted': segmentsTrusted,
      'segmentsVerified': segmentsVerified,
      'orphansReconstructed': orphansReconstructed,
      'duplicatesQuarantined': duplicatesQuarantined,
      'pending': pending,
      'segmentsPending':
          segmentsPending.map((s) => s.toMap()).toList(),
      'skipped_active': skippedActive,
      'groupsSkippedActive': groupsSkippedActive,
      'groupsFailed': groupsFailed,
      'reservationTokens': reservationTokens,
      'foreignReleaseRejected': foreignReleaseRejected,
      'orphanWriteTestFiles': orphanWriteTestFiles,
      'conflicts': conflicts,
    };
  }

  /// Fusión de lo que solo Dart conoce: el estado observado por el
  /// canal y los huérfanos reconstruidos al reconciliar las filas.
  ReconciliationSummary copyWith({
    ReconciliationState? state,
    int? orphansReconstructed,
  }) {
    return ReconciliationSummary(
      state: state ?? this.state,
      runId: runId,
      startedAtMs: startedAtMs,
      finishedAtMs: finishedAtMs,
      durationMs: durationMs,
      groupsTotal: groupsTotal,
      recordingsReconciled: recordingsReconciled,
      segmentsRenamed: segmentsRenamed,
      segmentsClassified: segmentsClassified,
      segmentsTrusted: segmentsTrusted,
      segmentsVerified: segmentsVerified,
      orphansReconstructed:
          orphansReconstructed ?? this.orphansReconstructed,
      duplicatesQuarantined: duplicatesQuarantined,
      pending: pending,
      segmentsPending: segmentsPending,
      skippedActive: skippedActive,
      groupsSkippedActive: groupsSkippedActive,
      groupsFailed: groupsFailed,
      reservationTokens: reservationTokens,
      foreignReleaseRejected: foreignReleaseRejected,
      orphanWriteTestFiles: orphanWriteTestFiles,
      conflicts: conflicts,
    );
  }
}

/// Resultado completo de `reconcileRecordings` por el canal (0-d2;
/// forma fijada en ambos lados a la vez, plan §6 A4). `segments` lleva
/// TODOS los segmentos del inventario (certificados o `pending`);
/// `pending` es el subconjunto `PENDING_VERIFICATION` (el mismo que
/// `summary.segmentsPending`). En `FAILED`, `segments` está vacío y
/// `errorCode`/`errorMessage` llevan el motivo: nunca una lista vacía
/// como éxito final (U34).
class ReconciliationResult {
  const ReconciliationResult({
    this.state = ReconciliationState.notStarted,
    this.summary = const ReconciliationSummary(),
    this.segments = const <RecoveredSegmentInfo>[],
    this.groupsSkippedActive = const <String>[],
    this.groupsFailed = const <String>[],
    this.unknownFiles = const <String>[],
    this.missingFiles = const <String>[],
    this.errorCode,
    this.errorMessage,
  });

  final ReconciliationState state;
  final ReconciliationSummary summary;

  /// Todos los segmentos del inventario, en orden por grabación/índice.
  final List<RecoveredSegmentInfo> segments;

  /// Grupos saltados por grabación activa o reserva.
  final List<String> groupsSkippedActive;

  /// Grupos que fallaron con excepción.
  final List<String> groupsFailed;

  /// Archivos sin `recordingId` reconocible (se listan, nunca se tocan).
  final List<String> unknownFiles;

  /// Entradas del sidecar sin archivo en disco (se listan, nunca se borran).
  final List<String> missingFiles;

  /// Motivo en `FAILED` (`null` en `DONE`).
  final String? errorCode;
  final String? errorMessage;

  /// Segmentos pendientes de verificación (`status == 'pending'`).
  List<RecoveredSegmentInfo> get pendingSegments =>
      segments.where((s) => s.status == 'pending').toList();

  /// Segmentos utilizables (`ready` o `partial`).
  List<RecoveredSegmentInfo> get usableSegments =>
      segments.where((s) => s.usable).toList();

  factory ReconciliationResult.fromMap(Map<Object?, Object?> map) {
    final ReconciliationState state =
        ReconciliationState.fromWireName(map['state'] as String?);
    final Map<Object?, Object?>? rawSummary =
        map['summary'] as Map<Object?, Object?>?;
    final List<Object?> rawSegments =
        (map['segments'] as List?)?.cast<Object?>() ?? <Object?>[];
    return ReconciliationResult(
      state: state,
      summary: rawSummary == null
          ? ReconciliationSummary(state: state)
          : ReconciliationSummary.fromMap(rawSummary),
      segments: rawSegments
          .whereType<Map<Object?, Object?>>()
          .map(RecoveredSegmentInfo.fromMap)
          .toList(),
      groupsSkippedActive: _stringList(map['groupsSkippedActive']),
      groupsFailed: _stringList(map['groupsFailed']),
      unknownFiles: _stringList(map['unknownFiles']),
      missingFiles: _stringList(map['missingFiles']),
      errorCode: map['errorCode'] as String?,
      errorMessage: map['errorMessage'] as String?,
    );
  }
}

List<String> _stringList(Object? value) {
  final List<Object?>? raw = (value as List?)?.cast<Object?>();
  if (raw == null) {
    return const <String>[];
  }
  return raw.whereType<String>().toList();
}

/// Dato asociado (AAD) canónico para cifrar la clave de un destino.
///
/// El mismo formato se usa en Kotlin (`StreamAad.streamKeyAad`). Cambiarlo
/// aquí sin cambiarlo allí dejaría todas las claves guardadas ilegibles.
/// El AAD ata cada cifrado a su destino: un cifrado copiado a otro destino
/// no descifra.
String streamKeyAad(int destinationId) =>
    'transmiti2:stream-key:v1:$destinationId';
