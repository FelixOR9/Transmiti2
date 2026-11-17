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
  /// `code`; `preview_surface_lost` no lleva datos (la superficie se
  /// perdió temporalmente y el motor la recupera solo).
  static const String eventPreviewStarted = 'preview_started';
  static const String eventPreviewStopped = 'preview_stopped';
  static const String eventPreviewError = 'preview_error';
  static const String eventPreviewSurfaceLost = 'preview_surface_lost';

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
  static const String encoderError = 'encoder_error';
  static const String muxerError = 'muxer_error';
  static const String audioUnavailable = 'audio_unavailable';
  static const String storageFull = 'storage_full';
  static const String thermalShutdown = 'thermal_shutdown';
  static const String permissionDenied = 'permission_denied';
  static const String recordingNotFound = 'recording_not_found';
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
  });

  final String recordingId;
  final String filePath;
  final int durationMs;
  final int sizeBytes;

  /// Estado final de la grabación (`ready`, `partial`, ...).
  final String status;

  factory RecordingInfo.fromMap(Map<Object?, Object?> map) {
    return RecordingInfo(
      recordingId: map['recordingId'] as String? ?? '',
      filePath: map['filePath'] as String? ?? '',
      durationMs: (map['durationMs'] as num?)?.toInt() ?? 0,
      sizeBytes: (map['sizeBytes'] as num?)?.toInt() ?? 0,
      status: map['status'] as String? ?? '',
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
