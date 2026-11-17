/// Contrato del canal Flutter <-> Kotlin para la transmisión.
///
/// En la Fase 3.0 el contrato existe y los métodos de video devuelven el
/// error `not_available_in_phase_3_0`: no se simula ningún éxito. La cámara,
/// la vista previa, la transmisión y la grabación llegan en las fases 3.1
/// en adelante.
abstract final class StreamContract {
  /// Canal de métodos Flutter -> Kotlin.
  static const String methodChannel =
      'com.transmiti2.transmiti2/streaming';

  /// Canal de eventos Kotlin -> Flutter (estados de transmisión).
  static const String eventChannel =
      'com.transmiti2.transmiti2/streaming/events';

  // Métodos del canal.
  static const String getStatus = 'getStatus';
  static const String checkPermissions = 'checkPermissions';

  /// Reservado para la Fase 3.1. En 3.0 devuelve el error
  /// `permission_request_deferred_to_phase_3_1` y la interfaz nunca lo
  /// llama: en esta fase no se pide cámara ni micrófono al usuario.
  static const String requestPermissions = 'requestPermissions';

  /// Guarda la clave cifrada de un destino ya creado en SQLite.
  static const String saveStreamKey = 'saveStreamKey';
  static const String deleteStreamKey = 'deleteStreamKey';
  static const String hasStreamKey = 'hasStreamKey';

  /// Funciones de video: existen en el contrato pero no están disponibles
  /// en la Fase 3.0.
  static const String startPreview = 'startPreview';
  static const String startStream = 'startStream';
  static const String startRecording = 'startRecording';
  static const String stopAll = 'stopAll';

  // Códigos de error (nunca llevan la clave ni la URL dentro).
  static const String notAvailableInPhase30 = 'not_available_in_phase_3_0';
  static const String permissionRequestDeferred =
      'permission_request_deferred_to_phase_3_1';
  static const String invalidRtmpsUrl = 'invalid_rtmps_url';
  static const String emptyKey = 'empty_key';
  static const String secureStoreUnavailable = 'secure_store_unavailable';
  static const String keystoreNotActive = 'keystore_not_active';
  static const String destinationNotFound = 'destination_not_found';
}

/// Estado del motor de transmisión que devuelve `getStatus`.
///
/// En la Fase 3.0 `foregroundServiceAvailable` es siempre `false`: el
/// servicio en primer plano existe como esqueleto sin registrar y se activa
/// en la Fase 3.1.
class StreamStatus {
  const StreamStatus({
    required this.foregroundServiceAvailable,
    required this.cameraGranted,
    required this.microphoneGranted,
    required this.streamingSupported,
  });

  /// `false` en la Fase 3.0. La comprobación es de solo lectura: no muestra
  /// ningún diálogo del sistema.
  final bool foregroundServiceAvailable;
  final bool cameraGranted;
  final bool microphoneGranted;

  /// `false` en la Fase 3.0: sin vista previa, transmisión ni grabación.
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

/// Dato asociado (AAD) canónico para cifrar la clave de un destino.
///
/// El mismo formato se usa en Kotlin (`StreamAad.streamKeyAad`). Cambiarlo
/// aquí sin cambiarlo allí dejaría todas las claves guardadas ilegibles.
/// El AAD ata cada cifrado a su destino: un cifrado copiado a otro destino
/// no descifra.
String streamKeyAad(int destinationId) =>
    'transmiti2:stream-key:v1:$destinationId';
