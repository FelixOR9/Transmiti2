import 'package:flutter/services.dart';
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

  /// Solo lectura: comprueba si cámara y micrófono están concedidos sin
  /// mostrar ningún diálogo del sistema.
  Future<({bool camera, bool microphone})> checkPermissions();

  Future<void> saveStreamKey({
    required int destinationId,
    required String key,
  });
  Future<void> deleteStreamKey({required int destinationId});
  Future<bool> hasStreamKey({required int destinationId});

  /// Funciones de video. En la Fase 3.0 el lado nativo devuelve el error
  /// `not_available_in_phase_3_0`: no se simula ningún éxito.
  Future<void> startPreview();
  Future<void> startStream();
  Future<void> startRecording();
  Future<void> stopAll();
}

class MethodChannelStreamingService implements StreamingService {
  MethodChannelStreamingService({
    MethodChannel? channel,
  }) : _channel = channel ?? const MethodChannel(StreamContract.methodChannel);

  final MethodChannel _channel;

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
  Future<({bool camera, bool microphone})> checkPermissions() async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(StreamContract.checkPermissions);
      return (
        camera: result?['cameraGranted'] as bool? ?? false,
        microphone: result?['microphoneGranted'] as bool? ?? false,
      );
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

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
  Future<void> startPreview() => _invokeVideo(StreamContract.startPreview);

  @override
  Future<void> startStream() => _invokeVideo(StreamContract.startStream);

  @override
  Future<void> startRecording() =>
      _invokeVideo(StreamContract.startRecording);

  @override
  Future<void> stopAll() => _invokeVideo(StreamContract.stopAll);
}
