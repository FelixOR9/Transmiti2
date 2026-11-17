import 'package:flutter/services.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';

/// Error del motor de transmisión. El código nunca lleva la clave ni la
/// URL dentro: solo el código, para que pueda mostrarse o registrarse.
class StreamingException implements Exception {
  const StreamingException(this.code, [this.message]);

  final String code;
  final String? message;

  @override
  String toString() => 'StreamingException($code)';
}

/// Lado nativo (Kotlin) del streaming, detrás de una interfaz para poder
/// sustituirlo en las pruebas.
///
/// La clave solo se pasa como argumento de `saveStreamKey` y nunca se guarda
/// en ningún campo de Dart. Limitación conocida: los `String` de Dart son
/// inmutables y no se pueden borrar de memoria con garantías; por eso la
/// clave solo existe durante esa llamada y el campo de la pantalla se limpia
/// en cuanto termina.
abstract class StreamingService {
  Future<StreamStatus> getStatus();

  /// Solo lectura: devuelve los estados de cámara, micrófono y
  /// notificaciones sin mostrar ningún diálogo del sistema. Los estados
  /// se vuelven a consultar al regresar a la app; nunca se cachean.
  Future<PermissionStates> checkPermissions();

  /// Pide los permisos indicados con el diálogo del sistema y devuelve
  /// los estados resultantes. En la subetapa 3.1.0 la UI solo pide la
  /// cámara; micrófono y notificaciones quedan construidos para 3.1.2
  /// y 3.1.3.
  Future<PermissionStates> requestPermissions({
    required List<PermissionKind> permissions,
  });

  /// Abre los ajustes de la aplicación (denegación permanente).
  Future<void> openAppSettings();

  /// Eventos del lado nativo (`permission_result`, `permission_changed`,
  /// `service_started`, `service_stopped`, `error`). En la subetapa 3.1.0
  /// se emite `permission_result` tras cada solicitud; los estados que
  /// usa la UI llegan por los resultados de los métodos.
  Stream<Map<String, Object?>> get events;

  Future<void> saveStreamKey({
    required int destinationId,
    required String key,
  });
  Future<void> deleteStreamKey({required int destinationId});
  Future<bool> hasStreamKey({required int destinationId});

  /// Funciones de video. En la subetapa 3.1.0 el lado nativo devuelve el
  /// error `not_available_in_phase_3_0`: no se simula ningún éxito. La
  /// vista previa llega en 3.1.1 y la grabación en 3.1.2.
  Future<PreviewInfo> startPreview();
  Future<void> stopPreview();
  Future<void> startStream();
  Future<String> startRecording({
    RecordingOptions options = const RecordingOptions(),
  });
  Future<RecordingInfo> stopRecording();
  Future<void> stopAll();
}

class MethodChannelStreamingService implements StreamingService {
  MethodChannelStreamingService({
    MethodChannel? channel,
    EventChannel? eventChannel,
  }) : _channel = channel ?? const MethodChannel(StreamContract.methodChannel),
       _events =
           (eventChannel ??
                   const EventChannel(StreamContract.eventChannel))
               .receiveBroadcastStream()
               .map(
                 (dynamic event) =>
                     Map<String, Object?>.from(event as Map),
               );

  final MethodChannel _channel;
  final Stream<Map<String, Object?>> _events;

  StreamingException _fromPlatform(PlatformException e) {
    return StreamingException(e.code, e.message);
  }

  @override
  Future<StreamStatus> getStatus() async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(StreamContract.getStatus);
      if (result == null) {
        return const StreamStatus(
          foregroundServiceAvailable: false,
          cameraGranted: false,
          microphoneGranted: false,
          streamingSupported: false,
        );
      }
      return StreamStatus.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<PermissionStates> checkPermissions() async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(StreamContract.checkPermissions);
      if (result == null) {
        return const PermissionStates.allNotRequested();
      }
      return PermissionStates.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<PermissionStates> requestPermissions({
    required List<PermissionKind> permissions,
  }) async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(
            StreamContract.requestPermissions,
            <String, Object>{
              'permissions':
                  permissions.map((p) => p.wireName).toList(),
            },
          );
      if (result == null) {
        return const PermissionStates.allNotRequested();
      }
      return PermissionStates.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<void> openAppSettings() async {
    try {
      await _channel.invokeMethod<void>(StreamContract.openAppSettings);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Stream<Map<String, Object?>> get events => _events;

  @override
  Future<void> saveStreamKey({
    required int destinationId,
    required String key,
  }) async {
    try {
      await _channel.invokeMethod<void>(StreamContract.saveStreamKey, <String,
          Object>{
        'destinationId': destinationId,
        'key': key,
      });
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<void> deleteStreamKey({required int destinationId}) async {
    try {
      await _channel.invokeMethod<void>(StreamContract.deleteStreamKey, <
          String, Object>{
        'destinationId': destinationId,
      });
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<bool> hasStreamKey({required int destinationId}) async {
    try {
      final bool? result = await _channel.invokeMethod<bool>(
        StreamContract.hasStreamKey,
        <String, Object>{'destinationId': destinationId},
      );
      return result ?? false;
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  Future<void> _invokeVideo(String method) async {
    try {
      await _channel.invokeMethod<void>(method);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<PreviewInfo> startPreview() async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(StreamContract.startPreview);
      if (result == null) {
        throw const StreamingException(
          StreamContract.notAvailableInPhase30,
        );
      }
      return PreviewInfo.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<void> stopPreview() => _invokeVideo(StreamContract.stopPreview);

  @override
  Future<void> startStream() => _invokeVideo(StreamContract.startStream);

  @override
  Future<String> startRecording({
    RecordingOptions options = const RecordingOptions(),
  }) async {
    try {
      final String? result = await _channel.invokeMethod<String>(
        StreamContract.startRecording,
        options.toMap(),
      );
      if (result == null) {
        throw const StreamingException(
          StreamContract.notAvailableInPhase30,
        );
      }
      return result;
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<RecordingInfo> stopRecording() async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(StreamContract.stopRecording);
      if (result == null) {
        throw const StreamingException(
          StreamContract.notAvailableInPhase30,
        );
      }
      return RecordingInfo.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<void> stopAll() => _invokeVideo(StreamContract.stopAll);
}
