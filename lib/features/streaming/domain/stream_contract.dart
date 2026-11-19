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

  /// Estado de la grabación y recuperación de interrupciones (Fase B,
  /// 3.1.2, componente 3). `getRecordingStatus` devuelve la instantánea
  /// del estado para re-sincronizar la UI al volver a la pantalla;
  /// `recoverInterruptedRecordings` clasifica los `.tmp.mp4` que
  /// quedaron de una muerte del proceso (nunca borra ni modifica nada).
  static const String getRecordingStatus = 'getRecordingStatus';
  static const String recoverInterruptedRecordings =
      'recoverInterruptedRecordings';

  // Eventos del canal de eventos.
  /// Se emite tras cada solicitud de permisos, una vez por permiso.
  static const String eventPermissionResult = 'permission_result';

  /// Se emite cuando un estado de permiso cambia sin solicitud (por
  /// ejemplo, una revocación detectada al reanudar la app).
  static const String eventPermissionChanged = 'permission_changed';

  /// Se emiten desde la subetapa 3.1.2, cuando el servicio arranca y para
  /// de verdad con una grabación.
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
  });

  final String recordingId;
  final List<RecordingSegmentInfo> segments;
  final int totalDurationMs;
  final int totalSizeBytes;

  /// `ready`, `partial`, `corrupt` o `idle`.
  final String status;
  final String? errorCode;
  final String? previewRestoreError;

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

/// Dato asociado (AAD) canónico para cifrar la clave de un destino.
///
/// El mismo formato se usa en Kotlin (`StreamAad.streamKeyAad`). Cambiarlo
/// aquí sin cambiarlo allí dejaría todas las claves guardadas ilegibles.
/// El AAD ata cada cifrado a su destino: un cifrado copiado a otro destino
/// no descifra.
String streamKeyAad(int destinationId) =>
    'transmiti2:stream-key:v1:$destinationId';
