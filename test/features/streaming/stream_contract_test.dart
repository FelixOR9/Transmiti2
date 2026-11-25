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

    test('los 4 códigos de diagnóstico de la Fase A son distintos', () {
      final List<String> codes = <String>[
        StreamContract.cameraTimeout,
        StreamContract.cameraConfigError,
        StreamContract.cameraStateError,
        StreamContract.cameraDisabledBySystem,
      ];
      expect(codes.toSet().length, codes.length);
      expect(StreamContract.cameraTimeout, 'camera_timeout');
      expect(StreamContract.cameraConfigError, 'camera_config_error');
      expect(StreamContract.cameraStateError, 'camera_state_error');
      expect(
        StreamContract.cameraDisabledBySystem,
        'camera_disabled_by_system',
      );
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
        // La lista completa de segmentos (paso 5).
        'segments': <Map<Object?, Object?>>[
          <Object?, Object?>{
            'recordingId': 'rec-1',
            'segmentIndex': 0,
            'fileName': 'rec-1_seg000.mp4',
            'filePath': '/tmp/rec.mp4',
            'status': 'ready',
            'durationMs': 60000,
            'sizeBytes': 123456,
            'closedCleanly': true,
          },
        ],
      });
      expect(info.recordingId, 'rec-1');
      expect(info.filePath, '/tmp/rec.mp4');
      expect(info.durationMs, 60000);
      expect(info.sizeBytes, 123456);
      expect(info.status, 'ready');
      expect(info.segments.length, 1);
      expect(info.segments[0].index, 0);
      expect(info.segments[0].durationMs, 60000);
    });
  });

  group('Modelos de grabación (Fase B, 3.1.2)', () {
    test('RecordingStartedInfo se construye desde el mapa', () {
      final RecordingStartedInfo info =
          RecordingStartedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'width': 1280,
            'height': 720,
            'fps': 30,
            'bitrateBps': 2500000,
            'profile': 66,
            'encoderName': 'c2.qti.avc.encoder',
            'rotationDegrees': 90,
            'cbrSupported': true,
          });
      expect(info.recordingId, 'rec-1');
      expect(info.bitrateBps, 2500000);
      expect(info.encoderName, 'c2.qti.avc.encoder');
      expect(info.rotationDegrees, 90);
    });

    test('RecordingSegmentInfo se construye desde el mapa', () {
      final RecordingSegmentInfo segment =
          RecordingSegmentInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segmentIndex': 2,
            'fileName': 'rec-1_seg002.mp4',
            'filePath': '/docs/rec-1_seg002.mp4',
            'status': 'ready',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'verifiedSampleCount': 1798,
            'ioPhaseMs': 61234,
            'closedCleanly': true,
          });
      expect(segment.index, 2);
      expect(segment.fileName, 'rec-1_seg002.mp4');
      expect(segment.status, 'ready');
      // Ronda 4, fix 3: el recuento del verificador y la duración de
      // la fase 1 llegan por el mismo mapa; sin ellos la tarjeta no
      // puede mostrarlos.
      expect(segment.verifiedSampleCount, 1798);
      expect(segment.ioPhaseMs, 61234);
      expect(segment.closedCleanly, isTrue);
    });

    test('RecordingStoppedInfo se construye desde el mapa', () {
      final RecordingStoppedInfo stopped =
          RecordingStoppedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segments': <Map<Object?, Object?>>[
              <Object?, Object?>{
                'recordingId': 'rec-1',
                'segmentIndex': 0,
                'fileName': 'rec-1_seg000.mp4',
                'filePath': '/docs/rec-1_seg000.mp4',
                'status': 'ready',
                'durationMs': 60000,
                'sizeBytes': 18750000,
              },
            ],
            'segmentCount': 1,
            'totalDurationMs': 60000,
            'totalSizeBytes': 18750000,
            'status': 'ready',
          });
      expect(stopped.segmentCount, 1);
      expect(stopped.totalDurationMs, 60000);
      expect(stopped.filePath, '/docs/rec-1_seg000.mp4');
    });

    test('RecordingMergedInfo se construye desde el mapa', () {
      final RecordingMergedInfo merged =
          RecordingMergedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'filePath': '/docs/rec-1_final.mp4',
            'status': 'ready',
            'totalDurationMs': 11987,
            'totalSizeBytes': 3740000,
            'segmentCount': 2,
            'skippedSegments': <int>[],
            'audioGapSegments': <int>[1],
            'reason': null,
            'alreadyMerged': false,
          });
      expect(merged.recordingId, 'rec-1');
      expect(merged.filePath, '/docs/rec-1_final.mp4');
      expect(merged.status, 'ready');
      expect(merged.totalDurationMs, 11987);
      expect(merged.totalSizeBytes, 3740000);
      expect(merged.segmentCount, 2);
      expect(merged.skippedSegments, isEmpty);
      expect(merged.audioGapSegments, <int>[1]);
      expect(merged.reason, isNull);
      expect(merged.alreadyMerged, isFalse);
    });

    test('RecordingMergedInfo fallido lee el motivo sin medidas', () {
      final RecordingMergedInfo merged =
          RecordingMergedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'status': 'finalizing',
            'segmentCount': 2,
            'skippedSegments': <int>[1],
            'reason': 'storage_full',
          });
      expect(merged.status, 'finalizing');
      expect(merged.filePath, isNull);
      expect(merged.totalDurationMs, isNull);
      expect(merged.totalSizeBytes, isNull);
      expect(merged.skippedSegments, <int>[1]);
      expect(merged.reason, 'storage_full');
    });

    test('RecordingStatusSnapshot se construye desde el mapa', () {
      final RecordingStatusSnapshot snapshot =
          RecordingStatusSnapshot.fromMap(<Object?, Object?>{
            'state': 'recording',
            'recordingId': 'rec-1',
            'elapsedMs': 30000,
            'segmentIndex': 1,
            'segmentDurationMs': 5000,
            'width': 1280,
            'height': 720,
            'fps': 30,
            // Segmentos verificados hasta ahora (paso 6).
            'segments': <Map<Object?, Object?>>[
              <Object?, Object?>{
                'recordingId': 'rec-1',
                'segmentIndex': 0,
                'fileName': 'rec-1_seg000.mp4',
                'filePath': '/docs/rec-1_seg000.mp4',
                'status': 'ready',
                'durationMs': 60000,
                'sizeBytes': 123456,
                'closedCleanly': true,
              },
            ],
          });
      expect(snapshot.state, 'recording');
      expect(snapshot.elapsedMs, 30000);
      expect(snapshot.segmentIndex, 1);
      expect(snapshot.segments.length, 1);
      expect(snapshot.segments[0].index, 0);
      expect(RecordingStatusSnapshot.idle().state, 'idle');
      expect(RecordingStatusSnapshot.idle().segments, isEmpty);
    });

    test('RecoveredSegmentInfo distingue utilizable de corrupto', () {
      const RecoveredSegmentInfo partial = RecoveredSegmentInfo(
        recordingId: 'rec-1',
        index: 0,
        fileName: 'rec-1_seg000.tmp.mp4',
        filePath: '/docs/rec-1_seg000.tmp.mp4',
        status: 'partial',
        durationMs: 4400,
        sizeBytes: 1600000,
      );
      const RecoveredSegmentInfo corrupt = RecoveredSegmentInfo(
        recordingId: 'rec-1',
        index: 1,
        fileName: 'rec-1_seg001.tmp.mp4',
        filePath: '/docs/rec-1_seg001.tmp.mp4',
        status: 'corrupt',
        sizeBytes: 0,
        reason: 'sin_moov',
      );
      expect(partial.usable, isTrue);
      expect(corrupt.usable, isFalse);
      expect(corrupt.reason, 'sin_moov');
    });
  });

  group('Campos de audio (3.1.3, plan §8.1)', () {
    test('el evento de pérdida de audio tiene nombre estable', () {
      expect(
        StreamContract.eventRecordingAudioLost,
        'recording_audio_lost',
      );
    });

    test('RecordingStartedInfo lee los campos de audio', () {
      final RecordingStartedInfo info =
          RecordingStartedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'width': 1280,
            'height': 720,
            'fps': 30,
            'bitrateBps': 2500000,
            'audioEnabled': true,
            'audioSampleRate': 48000,
            'audioChannels': 1,
            'audioEncoderName': 'c2.qti.aac.encoder',
            'timestampSource': 'REALTIME',
          });
      expect(info.audioEnabled, isTrue);
      expect(info.audioSampleRate, 48000);
      expect(info.audioChannels, 1);
      expect(info.audioEncoderName, 'c2.qti.aac.encoder');
      expect(info.audioReason, isNull);
      expect(info.timestampSource, 'REALTIME');
    });

    test('RecordingStartedInfo sin claves de audio no inventa valores', () {
      final RecordingStartedInfo info =
          RecordingStartedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'width': 1280,
            'height': 720,
            'fps': 30,
            'bitrateBps': 2500000,
          });
      expect(info.audioEnabled, isFalse);
      expect(info.audioSampleRate, isNull);
      expect(info.audioChannels, isNull);
      expect(info.audioEncoderName, isNull);
      expect(info.audioReason, isNull);
      expect(info.timestampSource, isNull);
    });

    test('RecordingStartedInfo video-only lee el motivo', () {
      final RecordingStartedInfo info =
          RecordingStartedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'width': 1280,
            'height': 720,
            'fps': 30,
            'bitrateBps': 2500000,
            'audioEnabled': false,
            'audioReason': 'audio_format_timeout',
          });
      expect(info.audioEnabled, isFalse);
      expect(info.audioReason, 'audio_format_timeout');
    });

    test('RecordingSegmentInfo lee los campos de audio', () {
      final RecordingSegmentInfo segment =
          RecordingSegmentInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segmentIndex': 0,
            'fileName': 'rec-1_seg000.mp4',
            'filePath': '/docs/rec-1_seg000.mp4',
            'status': 'ready',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'closedCleanly': true,
            'hasAudio': true,
            'audioDurationMs': 59800,
            'audioSampleCount': 2800,
            'audioSampleRate': 48000,
            'audioChannels': 1,
            'avOffsetMs': 120,
            'avDurationDiffMs': 200,
            'audioGapSamples': 3,
          });
      expect(segment.hasAudio, isTrue);
      expect(segment.audioDurationMs, 59800);
      expect(segment.audioSampleCount, 2800);
      expect(segment.audioSampleRate, 48000);
      expect(segment.audioChannels, 1);
      expect(segment.avOffsetMs, 120);
      expect(segment.avDurationDiffMs, 200);
      expect(segment.audioReason, isNull);
      expect(segment.audioGapSamples, 3);
    });

    test('RecordingSegmentInfo lee audioStartOffsetMs cuando lo hay', () {
      final RecordingSegmentInfo segment =
          RecordingSegmentInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segmentIndex': 0,
            'fileName': 'rec-1_seg000.mp4',
            'filePath': '/docs/rec-1_seg000.mp4',
            'status': 'ready',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'closedCleanly': true,
            'hasAudio': true,
            'audioStartOffsetMs': 85,
            'audioGapSamples': 3,
          });
      expect(segment.audioStartOffsetMs, 85);
      expect(segment.audioGapSamples, 3);
    });

    test('RecordingSegmentInfo sin audioStartOffsetMs no inventa valores', () {
      // Ausente (segmentos de 3.1.2) y `null` explícito (ninguna
      // muestra llegó a la sesión, H1): en ambos casos `null`.
      final RecordingSegmentInfo absent =
          RecordingSegmentInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segmentIndex': 0,
            'fileName': 'rec-1_seg000.mp4',
            'filePath': '/docs/rec-1_seg000.mp4',
            'status': 'ready',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'closedCleanly': true,
          });
      expect(absent.audioStartOffsetMs, isNull);
      final RecordingSegmentInfo explicitNull =
          RecordingSegmentInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segmentIndex': 0,
            'fileName': 'rec-1_seg000.mp4',
            'filePath': '/docs/rec-1_seg000.mp4',
            'status': 'partial',
            'durationMs': 30625,
            'sizeBytes': 18750000,
            'closedCleanly': true,
            'hasAudio': false,
            'audioStartOffsetMs': null,
          });
      expect(explicitNull.audioStartOffsetMs, isNull);
    });

    test('RecordingSegmentInfo sin claves de audio es video-only', () {
      final RecordingSegmentInfo segment =
          RecordingSegmentInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segmentIndex': 0,
            'fileName': 'rec-1_seg000.mp4',
            'filePath': '/docs/rec-1_seg000.mp4',
            'status': 'ready',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'closedCleanly': true,
          });
      expect(segment.hasAudio, isFalse);
      expect(segment.audioDurationMs, isNull);
      expect(segment.audioSampleCount, isNull);
      expect(segment.avOffsetMs, isNull);
      expect(segment.avDurationDiffMs, isNull);
      expect(segment.audioGapSamples, 0);
    });

    test('RecordingStatusSnapshot lee el estado de audio', () {
      final RecordingStatusSnapshot snapshot =
          RecordingStatusSnapshot.fromMap(<Object?, Object?>{
            'state': 'recording',
            'recordingId': 'rec-1',
            'elapsedMs': 30000,
            'segmentIndex': 0,
            'segmentDurationMs': 5000,
            'audioEnabled': true,
            'audioActive': false,
            'audioReason': 'audio_dead_object',
          });
      expect(snapshot.audioEnabled, isTrue);
      expect(snapshot.audioActive, isFalse);
      expect(snapshot.audioReason, 'audio_dead_object');
    });

    test('RecordingStatusSnapshot sin claves de audio no inventa valores', () {
      final RecordingStatusSnapshot snapshot =
          RecordingStatusSnapshot.fromMap(<Object?, Object?>{
            'state': 'recording',
            'recordingId': 'rec-1',
          });
      expect(snapshot.audioEnabled, isFalse);
      expect(snapshot.audioActive, isFalse);
      expect(snapshot.audioReason, isNull);
    });

    test('RecordingStoppedInfo lee droppedInputBuffers cuando lo hay', () {
      final RecordingStoppedInfo stopped =
          RecordingStoppedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segments': <Map<Object?, Object?>>[],
            'totalDurationMs': 60000,
            'totalSizeBytes': 18750000,
            'status': 'ready',
            'audioEnabled': true,
            'droppedInputBuffers': 7,
          });
      expect(stopped.droppedInputBuffers, 7);
    });

    test('RecordingStoppedInfo lee el segundo más silencioso', () {
      // Grupo S (S0): las claves nuevas llegan por el mismo mapa;
      // sin ellas, `null` (nunca se inventa un 0).
      final RecordingStoppedInfo stopped =
          RecordingStoppedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segments': <Map<Object?, Object?>>[],
            'totalDurationMs': 60000,
            'totalSizeBytes': 18750000,
            'status': 'ready',
            'audioEnabled': true,
            'quietestSecondPeak': 12,
            'quietestSecondNonZero': 3,
            'silencePeak': 0,
          });
      expect(stopped.quietestSecondPeak, 12);
      expect(stopped.quietestSecondNonZero, 3);
      // Grupo S (S1): el pico del disparo viaja en el mismo mapa.
      expect(stopped.silencePeak, 0);
      final RecordingStoppedInfo sinSegundo =
          RecordingStoppedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segments': <Map<Object?, Object?>>[],
            'totalDurationMs': 60000,
            'totalSizeBytes': 18750000,
            'status': 'ready',
          });
      expect(sinSegundo.quietestSecondPeak, isNull);
      expect(sinSegundo.quietestSecondNonZero, isNull);
      // Sin disparo, `null` (nunca se inventa un 0).
      expect(sinSegundo.silencePeak, isNull);
    });

    test('RecordingInfo lee el segundo más silencioso', () {
      final RecordingInfo info =
          RecordingInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'filePath': '/docs/recordings/rec-1_seg000.mp4',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'status': 'ready',
            'audioEnabled': true,
            'quietestSecondPeak': 12,
            'quietestSecondNonZero': 3,
            'silencePeak': 0,
          });
      expect(info.quietestSecondPeak, 12);
      expect(info.quietestSecondNonZero, 3);
      // Grupo S (S1): el pico del disparo viaja en el mismo mapa;
      // sin él, `null`.
      expect(info.silencePeak, 0);
      final RecordingInfo sinDisparo =
          RecordingInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'filePath': '/docs/recordings/rec-1_seg000.mp4',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'status': 'ready',
          });
      expect(sinDisparo.silencePeak, isNull);
    });

    test('RecordingInfo lee droppedInputBuffers cuando lo hay', () {
      final RecordingInfo info =
          RecordingInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'filePath': '/docs/recordings/rec-1_seg000.mp4',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'status': 'ready',
            'audioEnabled': true,
            'droppedInputBuffers': 7,
          });
      expect(info.droppedInputBuffers, 7);
    });

    test('RecordingInfo sin droppedInputBuffers es 0', () {
      final RecordingInfo info =
          RecordingInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'filePath': '/docs/recordings/rec-1_seg000.mp4',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'status': 'ready',
          });
      expect(info.droppedInputBuffers, 0);
    });

    test('RecordingStoppedInfo sin droppedInputBuffers es 0', () {
      final RecordingStoppedInfo stopped =
          RecordingStoppedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segments': <Map<Object?, Object?>>[],
            'totalDurationMs': 60000,
            'totalSizeBytes': 18750000,
            'status': 'ready',
          });
      expect(stopped.droppedInputBuffers, 0);
    });

    test('RecordingStoppedInfo lee inputBufferCapacityBytes cuando lo hay',
        () {
      final RecordingStoppedInfo stopped =
          RecordingStoppedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segments': <Map<Object?, Object?>>[],
            'totalDurationMs': 60000,
            'totalSizeBytes': 18750000,
            'status': 'ready',
            'audioEnabled': true,
            'inputBufferCapacityBytes': 2048,
          });
      expect(stopped.inputBufferCapacityBytes, 2048);
    });

    test('RecordingInfo lee inputBufferCapacityBytes cuando lo hay', () {
      final RecordingInfo info =
          RecordingInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'filePath': '/docs/recordings/rec-1_seg000.mp4',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'status': 'ready',
            'audioEnabled': true,
            'inputBufferCapacityBytes': 2048,
          });
      expect(info.inputBufferCapacityBytes, 2048);
    });

    test('RecordingInfo sin inputBufferCapacityBytes es null', () {
      final RecordingInfo info =
          RecordingInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'filePath': '/docs/recordings/rec-1_seg000.mp4',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'status': 'ready',
          });
      expect(info.inputBufferCapacityBytes, isNull);
    });

    test('RecordingStoppedInfo sin inputBufferCapacityBytes es null', () {
      final RecordingStoppedInfo stopped =
          RecordingStoppedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segments': <Map<Object?, Object?>>[],
            'totalDurationMs': 60000,
            'totalSizeBytes': 18750000,
            'status': 'ready',
          });
      expect(stopped.inputBufferCapacityBytes, isNull);
    });

    test('RecordingInfo lee audioReaderNoData2s y eosTimedOut (grupo V)',
        () {
      final RecordingInfo info =
          RecordingInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'filePath': '/docs/recordings/rec-1_seg000.mp4',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'status': 'ready',
            'audioReaderNoData2s': true,
            'eosTimedOut': true,
          });
      expect(info.audioReaderNoData2s, isTrue);
      expect(info.eosTimedOut, isTrue);
    });

    test('RecordingInfo sin audioReaderNoData2s ni eosTimedOut es false',
        () {
      final RecordingInfo info =
          RecordingInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'filePath': '/docs/recordings/rec-1_seg000.mp4',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'status': 'ready',
          });
      expect(info.audioReaderNoData2s, isFalse);
      expect(info.eosTimedOut, isFalse);
    });

    test(
        'RecordingStoppedInfo lee audioReaderNoData2s y eosTimedOut (grupo V)',
        () {
      final RecordingStoppedInfo stopped =
          RecordingStoppedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segments': <Map<Object?, Object?>>[],
            'totalDurationMs': 60000,
            'totalSizeBytes': 18750000,
            'status': 'ready',
            'audioReaderNoData2s': true,
            'eosTimedOut': true,
          });
      expect(stopped.audioReaderNoData2s, isTrue);
      expect(stopped.eosTimedOut, isTrue);
    });

    test(
        'RecordingStoppedInfo sin audioReaderNoData2s ni eosTimedOut es false',
        () {
      final RecordingStoppedInfo stopped =
          RecordingStoppedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segments': <Map<Object?, Object?>>[],
            'totalDurationMs': 60000,
            'totalSizeBytes': 18750000,
            'status': 'ready',
          });
      expect(stopped.audioReaderNoData2s, isFalse);
      expect(stopped.eosTimedOut, isFalse);
    });

    test('RecordingStoppedInfo lee los campos de instrumentación ronda 3',
        () {
      final RecordingStoppedInfo stopped =
          RecordingStoppedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segments': <Map<Object?, Object?>>[],
            'totalDurationMs': 60000,
            'totalSizeBytes': 18750000,
            'status': 'ready',
            'micStateAtStart': 3,
            'micStateAtStop': 1,
            'audioReadCount': 42,
            'audioLastReadCode': 4096,
            'audioLastReadOkAgeMs': 12300,
            'audioReaderAlive': true,
            'audioEos': true,
          });
      expect(stopped.micStateAtStart, 3);
      expect(stopped.micStateAtStop, 1);
      expect(stopped.audioReadCount, 42);
      expect(stopped.audioLastReadCode, 4096);
      expect(stopped.audioLastReadOkAgeMs, 12300);
      expect(stopped.audioReaderAlive, isTrue);
      expect(stopped.audioEos, isTrue);
    });

    test(
        'RecordingStoppedInfo sin claves de instrumentación ronda 3 no inventa valores',
        () {
      final RecordingStoppedInfo stopped =
          RecordingStoppedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segments': <Map<Object?, Object?>>[],
            'totalDurationMs': 60000,
            'totalSizeBytes': 18750000,
            'status': 'ready',
          });
      expect(stopped.micStateAtStart, isNull);
      expect(stopped.micStateAtStop, isNull);
      expect(stopped.audioReadCount, 0);
      expect(stopped.audioLastReadCode, isNull);
      expect(stopped.audioLastReadOkAgeMs, isNull);
      expect(stopped.audioReaderAlive, isFalse);
      expect(stopped.audioEos, isFalse);
    });

    test('RecordingInfo lee los campos de instrumentación ronda 3', () {
      final RecordingInfo info =
          RecordingInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'filePath': '/docs/recordings/rec-1_seg000.mp4',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'status': 'ready',
            'micStateAtStart': 3,
            'micStateAtStop': 3,
            'audioReadCount': 42,
            'audioLastReadCode': 4096,
            'audioLastReadOkAgeMs': 12300,
            'audioReaderAlive': true,
            'audioEos': true,
          });
      expect(info.micStateAtStart, 3);
      expect(info.micStateAtStop, 3);
      expect(info.audioReadCount, 42);
      expect(info.audioLastReadCode, 4096);
      expect(info.audioLastReadOkAgeMs, 12300);
      expect(info.audioReaderAlive, isTrue);
      expect(info.audioEos, isTrue);
    });

    test(
        'RecordingInfo sin claves de instrumentación ronda 3 no inventa valores',
        () {
      final RecordingInfo info =
          RecordingInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'filePath': '/docs/recordings/rec-1_seg000.mp4',
            'durationMs': 60000,
            'sizeBytes': 18750000,
            'status': 'ready',
          });
      expect(info.micStateAtStart, isNull);
      expect(info.micStateAtStop, isNull);
      expect(info.audioReadCount, 0);
      expect(info.audioLastReadCode, isNull);
      expect(info.audioLastReadOkAgeMs, isNull);
      expect(info.audioReaderAlive, isFalse);
      expect(info.audioEos, isFalse);
    });

    test('RecordingStoppedInfo lee audioEnabled a nivel de grabación', () {
      final RecordingStoppedInfo stopped =
          RecordingStoppedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segments': <Map<Object?, Object?>>[],
            'totalDurationMs': 60000,
            'totalSizeBytes': 18750000,
            'status': 'ready',
            'audioEnabled': true,
          });
      expect(stopped.audioEnabled, isTrue);
      final RecordingStoppedInfo withoutAudio =
          RecordingStoppedInfo.fromMap(<Object?, Object?>{
            'recordingId': 'rec-1',
            'segments': <Map<Object?, Object?>>[],
            'totalDurationMs': 60000,
            'totalSizeBytes': 18750000,
            'status': 'ready',
          });
      expect(withoutAudio.audioEnabled, isFalse);
    });
  });
}
