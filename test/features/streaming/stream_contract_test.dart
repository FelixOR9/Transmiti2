import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';

void main() {
  group('nombres de método del contrato', () {
    test('los métodos de video y permisos son estables', () {
      expect(StreamContract.methodChannel, 'com.transmiti2.transmiti2/streaming');
      expect(
        StreamContract.eventChannel,
        'com.transmiti2.transmiti2/streaming/events',
      );
      expect(StreamContract.getStatus, 'getStatus');
      expect(StreamContract.checkPermissions, 'checkPermissions');
      expect(StreamContract.requestPermissions, 'requestPermissions');
      expect(StreamContract.openAppSettings, 'openAppSettings');
      expect(StreamContract.startPreview, 'startPreview');
      expect(StreamContract.stopPreview, 'stopPreview');
      expect(StreamContract.startStream, 'startStream');
      expect(StreamContract.startRecording, 'startRecording');
      expect(StreamContract.stopRecording, 'stopRecording');
      expect(StreamContract.stopAll, 'stopAll');
    });

    test('los eventos del canal son estables', () {
      expect(StreamContract.eventPermissionResult, 'permission_result');
      expect(StreamContract.eventPermissionChanged, 'permission_changed');
      expect(StreamContract.eventServiceStarted, 'service_started');
      expect(StreamContract.eventServiceStopped, 'service_stopped');
      expect(StreamContract.eventError, 'error');
    });
  });

  group('códigos de error', () {
    test('los 10 códigos definidos en 3.1.0 son distintos', () {
      final List<String> codes = <String>[
        StreamContract.cameraUnavailable,
        StreamContract.cameraInUse,
        StreamContract.cameraDisconnected,
        StreamContract.encoderError,
        StreamContract.muxerError,
        StreamContract.audioUnavailable,
        StreamContract.storageFull,
        StreamContract.thermalShutdown,
        StreamContract.permissionDenied,
        StreamContract.recordingNotFound,
      ];
      expect(codes.toSet().length, codes.length);
      expect(StreamContract.cameraUnavailable, 'camera_unavailable');
      expect(StreamContract.cameraInUse, 'camera_in_use');
      expect(StreamContract.cameraDisconnected, 'camera_disconnected');
      expect(StreamContract.encoderError, 'encoder_error');
      expect(StreamContract.muxerError, 'muxer_error');
      expect(StreamContract.audioUnavailable, 'audio_unavailable');
      expect(StreamContract.storageFull, 'storage_full');
      expect(StreamContract.thermalShutdown, 'thermal_shutdown');
      expect(StreamContract.permissionDenied, 'permission_denied');
      expect(StreamContract.recordingNotFound, 'recording_not_found');
    });

    test('el error de video de la 3.0 se conserva', () {
      expect(
        StreamContract.notAvailableInPhase30,
        'not_available_in_phase_3_0',
      );
      expect(
        StreamContract.permissionRequestDeferred,
        'permission_request_deferred_to_phase_3_1',
      );
    });
  });

  group('RecordingOptions', () {
    test('los valores por defecto son 720p, 30 fps y audio activado', () {
      const RecordingOptions options = RecordingOptions();
      expect(options.matchId, isNull);
      expect(options.resolution, '720p');
      expect(options.fps, 30);
      expect(options.videoBitrateBps, 2500000);
      expect(options.audioEnabled, isTrue);
      final Map<String, Object> map = options.toMap();
      expect(map['resolution'], '720p');
      expect(map['fps'], 30);
      expect(map['videoBitrateBps'], 2500000);
      expect(map['audioEnabled'], isTrue);
      expect(map.containsKey('matchId'), isFalse);
    });

    test('el partido asociado viaja cuando lo hay', () {
      const RecordingOptions options = RecordingOptions(
        matchId: 7,
        audioEnabled: false,
      );
      expect(options.toMap()['matchId'], 7);
      expect(options.toMap()['audioEnabled'], isFalse);
    });
  });

  group('PreviewInfo y RecordingInfo', () {
    test('PreviewInfo se construye desde el mapa', () {
      final PreviewInfo info = PreviewInfo.fromMap(<Object?, Object?>{
        'textureId': 12,
        'width': 1280,
        'height': 720,
      });
      expect(info.textureId, 12);
      expect(info.width, 1280);
      expect(info.height, 720);
    });

    test('RecordingInfo se construye desde el mapa', () {
      final RecordingInfo info = RecordingInfo.fromMap(<Object?, Object?>{
        'recordingId': 'rec-1',
        'filePath': '/tmp/rec.mp4',
        'durationMs': 60000,
        'sizeBytes': 123456,
        'status': 'ready',
      });
      expect(info.recordingId, 'rec-1');
      expect(info.filePath, '/tmp/rec.mp4');
      expect(info.durationMs, 60000);
      expect(info.sizeBytes, 123456);
      expect(info.status, 'ready');
    });
  });
}
