import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/streaming/application/permission_controller.dart';
import 'package:transmiti2/features/streaming/application/recording_controller.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';

import '../../support/fake_streaming.dart';

/// Temporizador controlable: no dispara solo; la prueba dispara los
/// ticks con [fire].
class FakeTimer implements Timer {
  FakeTimer(this.callback);

  final void Function(Timer) callback;
  int _ticks = 0;
  bool _active = true;

  @override
  bool get isActive => _active;

  @override
  int get tick => _ticks;

  void fire() {
    _ticks++;
    callback(this);
  }

  @override
  void cancel() {
    _active = false;
  }
}

void main() {
  group('RecordingController', () {
    late FakeStreamingService service;
    late PermissionController permissions;
    late InMemoryRecordingRepository recordings;
    late RecordingController recording;
    late List<FakeTimer> timers;
    late bool previewActive;
    late bool foreground;
    late int now;
    late int notifications;

    Future<void> pumpController(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox());
    }

    setUp(() {
      service = FakeStreamingService();
      service.permissionStates = const PermissionStates(
        camera: PermissionStatus.granted,
        microphone: PermissionStatus.notRequested,
        notifications: PermissionStatus.notRequested,
      );
      permissions = PermissionController(service: service);
      recordings = InMemoryRecordingRepository();
      timers = <FakeTimer>[];
      previewActive = true;
      foreground = true;
      now = 1000000;
      notifications = 0;
      recording = RecordingController(
        service: service,
        permissions: permissions,
        recordings: recordings,
        isPreviewActive: () => previewActive,
        nowMs: () => now,
        isForeground: () => foreground,
        createTimer: (Duration duration, void Function(Timer) callback) {
          final FakeTimer timer = FakeTimer(callback);
          timers.add(timer);
          return timer;
        },
      );
      recording.addListener(() {
        notifications++;
      });
      recording.attach();
    });

    tearDown(() {
      recording.detach();
    });

    Map<String, Object?> startedEvent() {
      return <String, Object?>{
        'type': StreamContract.eventRecordingStarted,
        'recordingId': 'rec_20261118_120000_ab12',
        'matchId': null,
        'width': 1280,
        'height': 720,
        'fps': 30,
        'bitrateBps': 2500000,
        'profile': 66,
        'bitrateMode': 2,
        'encoderName': 'c2.qti.avc.encoder',
        'rotationDegrees': 90,
        'lowStorageWarning': false,
        'cbrSupported': true,
      };
    }

    Map<String, Object?> segmentEvent(int index) {
      return <String, Object?>{
        'type': StreamContract.eventRecordingSegment,
        'recordingId': 'rec_20261118_120000_ab12',
        'segmentIndex': index,
        'fileName': 'rec_20261118_120000_ab12_seg00$index.mp4',
        'filePath':
            '/docs/recordings/rec_20261118_120000_ab12_seg00$index.mp4',
        'status': 'ready',
        'durationMs': 60000,
        'sizeBytes': 18750000,
        'sampleCount': 1800,
        'keyframeCount': 30,
        'firstSampleKeyframe': true,
        'ptsMonotonic': true,
        'profileIdc': 66,
        'fpsMeasured': 29.9,
        'verifiedSampleCount': 1798,
        'ioPhaseMs': 61234,
        'closedCleanly': true,
        'reason': null,
      };
    }

    Map<String, Object?> stoppedEvent() {
      return <String, Object?>{
        'type': StreamContract.eventRecordingStopped,
        'recordingId': 'rec_20261118_120000_ab12',
        'segments': <Map<String, Object?>>[segmentEvent(0)],
        'segmentCount': 1,
        'totalDurationMs': 12000,
        'totalSizeBytes': 3750000,
        'status': 'ready',
        'errorCode': null,
        'previewRestoreError': null,
      };
    }

    /// Evento `recording_merged` (J1-b): fusión completada o
    /// abortada, con las medidas del fusionado (o el motivo).
    Map<String, Object?> mergedEvent({
      String status = 'ready',
      String? reason,
      int? totalDurationMs = 11987,
      int? totalSizeBytes = 3740000,
    }) {
      return <String, Object?>{
        'type': StreamContract.eventRecordingMerged,
        'recordingId': 'rec_20261118_120000_ab12',
        'filePath':
            '/docs/recordings/rec_20261118_120000_ab12_final.mp4',
        'status': status,
        'totalDurationMs': totalDurationMs,
        'totalSizeBytes': totalSizeBytes,
        'segmentCount': 1,
        'skippedSegments': <int>[],
        'audioGapSegments': <int>[],
        'reason': reason,
      };
    }

    /// Evento `recording_started` con audio AAC (3.1.3): el motor
    /// confirma 48 kHz mono y el origen de timestamps.
    Map<String, Object?> startedEventWithAudio() {
      return startedEvent()
        ..['audioEnabled'] = true
        ..['audioSampleRate'] = 48000
        ..['audioChannels'] = 1
        ..['audioEncoderName'] = 'c2.qti.aac.encoder'
        ..['timestampSource'] = 'REALTIME';
    }

    /// Evento `recording_started` en video-only por degradación
    /// nativa (el formato AAC no llegó en 2 s).
    Map<String, Object?> startedEventVideoOnly() {
      return startedEvent()
        ..['audioEnabled'] = false
        ..['audioReason'] = 'audio_format_timeout';
    }

    /// Segmento verificado con pista AAC (3.1.3).
    Map<String, Object?> segmentEventWithAudio(int index) {
      return segmentEvent(index)
        ..['hasAudio'] = true
        ..['audioDurationMs'] = 59800
        ..['audioSampleCount'] = 2800
        ..['audioSampleRate'] = 48000
        ..['audioChannels'] = 1
        ..['avOffsetMs'] = 120
        ..['avDurationDiffMs'] = 200
        ..['audioGapSamples'] = 0;
    }

    /// Pérdida de audio en caliente (3.1.3, D7): el micrófono murió
    /// en el segmento 0; el video continúa.
    Map<String, Object?> audioLostEvent() {
      return <String, Object?>{
        'type': StreamContract.eventRecordingAudioLost,
        'recordingId': 'rec_20261118_120000_ab12',
        'reason': 'audio_dead_object',
        'segmentIndex': 0,
        'message': 'El micrófono dejó de responder.',
      };
    }

    /// Pérdida por silencio digital (Grupo S, S1, T = 5 s, pico 4):
    /// 5 s de ceros exactos desde el offset 12345 ms, pico 0.
    Map<String, Object?> audioLostSilenceEvent() {
      return <String, Object?>{
        'type': StreamContract.eventRecordingAudioLost,
        'recordingId': 'rec_20261118_120000_ab12',
        'reason': 'audio_silence_samples',
        'segmentIndex': 0,
        'message': null,
        'silenceMs': 5000,
        'silenceStartOffsetMs': 12345,
        'silencePeak': 0,
      };
    }

    testWidgets('inicia la grabación con la vista previa activa', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);

      await recording.start();
      await tester.pump();

      expect(recording.state, RecordingState.recording);
      expect(recording.recordingId, 'rec_20261118_120000_ab12');
      expect(service.startRecordingCalls, 1);
      expect(service.lastRecordingOptions?.audioEnabled, isFalse);
      expect(service.lastRecordingOptions?.resolution, '720p');
      expect(service.lastRecordingOptions?.videoBitrateBps, 2500000);
      expect(timers.length, 1);
      expect(timers.first.isActive, isTrue);
      final row = await recordings.byRecordingId(
        'rec_20261118_120000_ab12',
      );
      expect(row, isNotNull);
      expect(row!.status, 'recording');
      expect(row.fileName, 'rec_20261118_120000_ab12_final.mp4');
      expect(row.resolution, '720p');
      expect(row.fps, 30);
    });

    testWidgets('sin vista previa no llama al motor y queda en error', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      previewActive = false;

      await recording.start();
      await tester.pump();

      expect(recording.state, RecordingState.error);
      expect(recording.errorCode, StreamContract.cameraUnavailable);
      expect(
        recording.errorMessage,
        'Inicia la vista previa antes de grabar.',
      );
      expect(service.startRecordingCalls, 0);
    });

    testWidgets('iniciar dos veces no graba dos veces', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);

      await recording.start();
      await recording.start();
      await tester.pump();

      expect(recording.state, RecordingState.recording);
      expect(service.startRecordingCalls, 1);
    });

    testWidgets('stop desde idle es idempotente', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);

      await recording.stop();
      await tester.pump();

      expect(recording.state, RecordingState.idle);
      expect(service.stopRecordingCalls, 0);
    });

    testWidgets('stop detiene y deja la fila en finalizing hasta la fusión', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();

      await recording.stop();
      await tester.pump();

      expect(recording.state, RecordingState.idle);
      expect(service.stopRecordingCalls, 1);
      expect(recording.lastStopped, isNotNull);
      expect(recording.lastStopped!.status, 'ready');
      expect(recording.lastStopped!.totalDurationMs, 12000);
      // J1-b: con segmentos utilizables la fila NO es `ready` al
      // parar (el `_final.mp4` aún no existe): queda `finalizing`
      // con las sumas de los segmentos hasta `recording_merged`.
      final row = await recordings.byRecordingId(
        'rec_20261118_120000_ab12',
      );
      expect(row!.status, 'finalizing');
      expect(row.durationMs, 12000);
      expect(row.sizeBytes, 3750000);
      expect(
        recording.mergePendingRecordingId,
        'rec_20261118_120000_ab12',
      );
      expect(timers.first.isActive, isFalse);
    });

    testWidgets('recording_merged completa la fila a ready con medidas', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();
      await recording.stop();
      await tester.pump();

      service.emitEvent(mergedEvent());
      await tester.pump();

      final row = await recordings.byRecordingId(
        'rec_20261118_120000_ab12',
      );
      expect(row!.status, 'ready');
      expect(row.durationMs, 11987);
      expect(row.sizeBytes, 3740000);
      expect(recording.mergePendingRecordingId, isNull);
      expect(recording.lastMerged, isNotNull);
      expect(recording.lastMerged!.status, 'ready');
      expect(recording.lastMerged!.totalDurationMs, 11987);
      expect(recording.lastMerged!.filePath,
          '/docs/recordings/rec_20261118_120000_ab12_final.mp4');
      expect(recording.diagnosticText, contains('Fusión: ready'));
    });

    testWidgets('stop sin segmentos utilizables marca corrupt directo', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();
      service.stopRecordingResult = const RecordingInfo(
        recordingId: 'rec_20261118_120000_ab12',
        filePath: '',
        durationMs: 0,
        sizeBytes: 0,
        status: 'corrupt',
        segments: <RecordingSegmentInfo>[],
      );

      await recording.stop();
      await tester.pump();

      final row = await recordings.byRecordingId(
        'rec_20261118_120000_ab12',
      );
      expect(row!.status, 'corrupt');
      expect(recording.mergePendingRecordingId, isNull);
    });

    testWidgets('recording_merged con fracaso definitivo marca corrupt '
        'y conserva los segmentos', (WidgetTester tester) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();
      await recording.stop();
      await tester.pump();
      expect(recording.segments.length, 1);

      service.emitEvent(
        mergedEvent(
          status: 'corrupt',
          reason: 'merge_verify_failed',
          totalDurationMs: null,
          totalSizeBytes: null,
        ),
      );
      await tester.pump();

      final row = await recordings.byRecordingId(
        'rec_20261118_120000_ab12',
      );
      expect(row!.status, 'corrupt');
      // Sin fusionado medido, la fila corrupta conserva las sumas
      // de los segmentos; los archivos no se tocan (nada se borra).
      expect(row.durationMs, 12000);
      expect(row.sizeBytes, 3750000);
      expect(recording.segments.length, 1);
      expect(recording.mergePendingRecordingId, isNull);
      expect(recording.lastMerged!.reason, 'merge_verify_failed');
    });

    testWidgets('recording_merged reintentable deja la fila en finalizing',
        (WidgetTester tester) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();
      await recording.stop();
      await tester.pump();

      service.emitEvent(
        mergedEvent(
          status: 'finalizing',
          reason: 'storage_full',
          totalDurationMs: null,
          totalSizeBytes: null,
        ),
      );
      await tester.pump();

      final row = await recordings.byRecordingId(
        'rec_20261118_120000_ab12',
      );
      expect(row!.status, 'finalizing');
      expect(
        recording.mergePendingRecordingId,
        'rec_20261118_120000_ab12',
      );
      expect(recording.lastMergeReason, 'storage_full');
      expect(
        recording.diagnosticText,
        contains('Fusión pendiente por: storage_full'),
      );
    });

    testWidgets('el evento recording_started rellena la información', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();

      service.emitEvent(startedEvent());
      await tester.pump();

      expect(recording.startedInfo, isNotNull);
      expect(recording.startedInfo!.encoderName, 'c2.qti.avc.encoder');
      expect(recording.startedInfo!.profile, 66);
      expect(recording.startedInfo!.bitrateBps, 2500000);
      expect(recording.diagnostics?.encoderName, 'c2.qti.avc.encoder');
      expect(recording.state, RecordingState.recording);
    });

    testWidgets('el evento recording_segment acumula segmentos en orden', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();

      service.emitEvent(segmentEvent(1));
      await tester.pump();
      service.emitEvent(segmentEvent(0));
      await tester.pump();

      expect(recording.segments.length, 2);
      expect(recording.segments[0].index, 0);
      expect(recording.segments[1].index, 1);
      expect(recording.segments[0].status, 'ready');
    });

    testWidgets('la segunda grabación resetea el tiempo al iniciar', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();

      now += 60000;
      expect(recording.elapsedMs, 60000);
      expect(recording.segmentDurationMs, 60000);

      await recording.stop();
      await tester.pump();

      // Al llegar a `idle` el contador ya no corre (paso 2): el
      // diagnóstico en `idle` mostraba el tiempo de la grabación
      // anterior creciendo sin parar.
      expect(recording.elapsedMs, 0);
      expect(recording.segmentDurationMs, 0);

      now += 30000;
      await recording.start();
      await tester.pump();

      // La segunda grabación empieza en 0 aunque la primera duró 60 s
      // y aunque pasaron 30 s más entre ambas.
      expect(recording.elapsedMs, 0);
      expect(recording.segmentDurationMs, 0);
    });

    testWidgets('el segmento actual es el índice nativo 1-based', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();

      // Al iniciar, el segmento en curso es el nativo 0 → «1».
      expect(recording.nativeSegmentIndex, 0);
      expect(recording.currentSegmentDisplay, 1);
      expect(recording.diagnosticText, contains('Segmento actual: 1'));

      // Rotación: se cerró el segmento 0 y el 1 ya graba.
      service.emitEvent(segmentEvent(0));
      await tester.pump();
      expect(recording.nativeSegmentIndex, 1);
      expect(recording.currentSegmentDisplay, 2);
      expect(recording.diagnosticText, contains('Segmento actual: 2'));
      // La duración de Dart y la nativa (PTS) se muestran juntas.
      expect(
        recording.diagnosticText,
        contains('Duración del segmento: 00:00 (nativa: 00:00)'),
      );

      service.emitEvent(segmentEvent(1));
      await tester.pump();
      expect(recording.nativeSegmentIndex, 2);
      expect(recording.currentSegmentDisplay, 3);
      expect(recording.diagnosticText, contains('Segmento actual: 3'));
    });

    testWidgets('el diagnóstico muestra cada segmento con estado y motivo', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();

      // El evento `recording_started` alimenta el diagnóstico
      // (codificador, FPS esperado...): sin él la tarjeta muestra `—`.
      service.emitEvent(startedEvent());
      await tester.pump();

      service.emitEvent(segmentEvent(0));
      await tester.pump();
      final Map<String, Object?> corrupt = segmentEvent(1)
        ..['status'] = 'corrupt'
        ..['durationMs'] = null
        ..['reason'] = 'duracion_no_valida';
      service.emitEvent(corrupt);
      await tester.pump();

      final String? text = recording.diagnosticText;
      expect(text, isNotNull);
      expect(text, contains('FPS esperado: 30'));
      expect(text, contains('Segmentos verificados: 2'));
      expect(
        text,
        contains(
          'seg0: ready 60000ms motivo=— cerradoLimpio=sí fps=29.9 '
          'muestras=1800 verif=1798 fase=61.2s audio=no',
        ),
      );
      expect(
        text,
        contains(
          'seg1: corrupt —ms motivo=duracion_no_valida cerradoLimpio=sí '
          'fps=29.9 muestras=1800 verif=1798 fase=61.2s audio=no',
        ),
      );

      await recording.stop();
      await tester.pump();

      // El resumen de la parada viene del MÉTODO (paso 5): el falso
      // devuelve 1 segmento, aunque Dart vio 2 eventos. La lista del
      // motor es completa y es la que manda.
      expect(
        recording.diagnosticText,
        contains(
          'Última parada: estado=ready duración total=12000ms segmentos=1',
        ),
      );
      // La parada por método trae el contador del falso (0 por
      // defecto, nunca inventado): el método devuelve el mismo mapa
      // que el evento `recording_stopped`.
      expect(
        recording.diagnosticText,
        contains('descartadasEntradaAAC=0'),
      );
    });

    testWidgets('stop por método trae el contador real de descartadas', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();

      // El método devuelve el contador real del motor (el mismo valor
      // que el evento `recording_stopped`): la parada por método no
      // lo pierde.
      service.stopDroppedInputBuffers = 5;
      await recording.stop();
      await tester.pump();

      expect(
        recording.diagnosticText,
        contains('descartadasEntradaAAC=5'),
      );
    });

    testWidgets('stop por método trae la capacidad real del buffer AAC', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();

      // El método devuelve la capacidad real del buffer de entrada
      // AAC (el mismo valor que el evento `recording_stopped`): la
      // parada por método no la pierde (ronda U).
      service.stopInputBufferCapacityBytes = 2048;
      await recording.stop();
      await tester.pump();

      expect(
        recording.diagnosticText,
        contains('capacidadEntradaAAC=2048 bytes'),
      );
    });

    testWidgets('stop por método trae el segundo más silencioso', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();

      // El método devuelve el segundo más silencioso del motor (el
      // mismo valor que el evento `recording_stopped`): la parada
      // por método no lo pierde (Grupo S, S0). Valores distintivos
      // a propósito (ningún defecto coincide con ellos).
      service.stopQuietestSecondPeak = 12;
      service.stopQuietestSecondNonZero = 3;
      await recording.stop();
      await tester.pump();

      expect(
        recording.diagnosticText,
        contains('picoMin1s=12 noCeroMin1s=3'),
      );
    });

    testWidgets('sin segundo completo la línea muestra guiones', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();

      // El falso devuelve `null` por defecto (aún sin un segundo
      // completo): la línea muestra `—`, nunca inventa un 0 que
      // significaría silencio digital (Grupo S, S0).
      await recording.stop();
      await tester.pump();

      expect(
        recording.diagnosticText,
        contains('picoMin1s=— noCeroMin1s=—'),
      );
    });

    testWidgets('stop usa los segmentos del método aunque falte un evento', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();

      // Sin ningún evento `recording_segment`: la lista de Dart está
      // vacía, pero el método devuelve el segmento verificado.
      expect(recording.segments, isEmpty);

      await recording.stop();
      await tester.pump();

      expect(recording.lastStopped, isNotNull);
      expect(recording.lastStopped!.segmentCount, 1);
      expect(recording.segments.length, 1);
      expect(recording.segments[0].index, 0);
      expect(recording.segments[0].durationMs, 12000);
      expect(recording.segments[0].status, 'ready');
    });

    testWidgets('al volver, el snapshot restaura los segmentos', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      // La UI se fue y volvió sin ver los eventos de segmento: el
      // snapshot trae los verificados (paso 6).
      recording.detach();
      service.recordingStatus = RecordingStatusSnapshot(
        state: 'recording',
        recordingId: 'rec_20261118_120000_ab12',
        elapsedMs: 65000,
        segmentIndex: 1,
        segmentDurationMs: 5000,
        width: 1280,
        height: 720,
        fps: 30,
        segments: <RecordingSegmentInfo>[
          RecordingSegmentInfo.fromMap(segmentEvent(0)),
        ],
      );
      recording.attach();
      await tester.pump();

      expect(recording.state, RecordingState.recording);
      expect(recording.segments.length, 1);
      expect(recording.segments[0].index, 0);
      expect(recording.segments[0].durationMs, 60000);
      expect(recording.nativeSegmentIndex, 1);
    });

    testWidgets(
      'la parada externa por evento deja la fila en finalizing hasta la fusión',
      (WidgetTester tester) async {
        await pumpController(tester);
        await recording.start();
        await tester.pump();

        // Parada desde la notificación o por desconexión de cámara:
        // llega por evento, la UI no llamó a stop().
        service.emitEvent(stoppedEvent());
        await tester.pump();

        expect(recording.state, RecordingState.idle);
        expect(service.stopRecordingCalls, 0);
        expect(recording.lastStopped, isNotNull);
        expect(recording.lastStopped!.segmentCount, 1);
        // J1-b: mismo tratamiento que el stop por método (fusión
        // pendiente): la fila queda en `finalizing`, no en `ready`.
        final row = await recordings.byRecordingId(
          'rec_20261118_120000_ab12',
        );
        expect(row!.status, 'finalizing');
        expect(row.durationMs, 12000);
        expect(
          recording.mergePendingRecordingId,
          'rec_20261118_120000_ab12',
        );

        service.emitEvent(mergedEvent());
        await tester.pump();

        final mergedRow = await recordings.byRecordingId(
          'rec_20261118_120000_ab12',
        );
        expect(mergedRow!.status, 'ready');
        expect(mergedRow.durationMs, 11987);
        expect(recording.mergePendingRecordingId, isNull);
      },
    );

    testWidgets('el error de inicio queda en error con código y mensaje', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.startRecordingError = StreamContract.encoderError;
      service.startRecordingErrorMessage = 'Sin codificador H.264.';
      service.startRecordingErrorDetails = <String, Object?>{
        'stage': 'configure',
      };

      await recording.start();
      await tester.pump();

      expect(recording.state, RecordingState.error);
      expect(recording.errorCode, StreamContract.encoderError);
      expect(recording.errorMessage, 'Sin codificador H.264.');
      expect(recording.diagnostics?.lastErrorStage, 'configure');
      // No se crea la fila si el inicio falla.
      final row = await recordings.byRecordingId(
        'rec_20261118_120000_ab12',
      );
      expect(row, isNull);
    });

    testWidgets('el temporizador actualiza el tiempo transcurrido', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();
      expect(recording.elapsedMs, 0);
      final int before = notifications;

      now += 5000;
      timers.first.fire();
      await tester.pump();

      expect(recording.elapsedMs, 5000);
      expect(recording.segmentDurationMs, 5000);
      expect(notifications, greaterThan(before));
    });

    testWidgets('al volver con el motor grabando se re-sincroniza', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      // El motor ya está grabando (la UI se fue y volvió): el estado
      // se consulta al hacer attach.
      recording.detach();
      service.recordingStatus = const RecordingStatusSnapshot(
        state: 'recording',
        recordingId: 'rec_20261118_120000_ab12',
        elapsedMs: 30000,
        segmentIndex: 1,
        segmentDurationMs: 5000,
        width: 1280,
        height: 720,
        fps: 30,
      );
      recording.attach();
      await tester.pump();

      expect(recording.state, RecordingState.recording);
      expect(recording.recordingId, 'rec_20261118_120000_ab12');
      expect(recording.elapsedMs, 30000);
      // El índice y la duración nativos vienen del snapshot (paso 3).
      expect(recording.nativeSegmentIndex, 1);
      expect(recording.nativeSegmentDurationMs, 5000);
      expect(recording.currentSegmentDisplay, 2);
      expect(timers.length, 1);
    });

    testWidgets('la recuperación clasifica los interrumpidos', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      // Una fila interrumpida (la grabación murió con el proceso).
      await recordings.insertRecording(
        recordingId: 'rec_20261118_110000_zz99',
        resolution: '720p',
        fps: 30,
        videoBitrateBps: 2500000,
        nowMs: now,
      );
      recording.detach();
      service.reconciliationResult = const ReconciliationResult(
        state: ReconciliationState.done,
        summary: ReconciliationSummary(
          state: ReconciliationState.done,
          recordingsReconciled: 1,
        ),
        segments: <RecoveredSegmentInfo>[
          RecoveredSegmentInfo(
            recordingId: 'rec_20261118_110000_zz99',
            index: 0,
            fileName: 'rec_20261118_110000_zz99_seg000.tmp.mp4',
            filePath:
                '/docs/recordings/rec_20261118_110000_zz99_seg000.tmp.mp4',
            status: 'partial',
            durationMs: 4400,
            sizeBytes: 1600000,
          ),
        ],
      );
      recording.attach();
      await tester.pump();

      expect(recording.lastRecovery.length, 1);
      // J1-c: la fila interrumpida con segmentos utilizables queda
      // en `finalizing` y reanuda la fusión (no `ready` con las
      // sumas: el `_final.mp4` aún no existe).
      final row = await recordings.byRecordingId(
        'rec_20261118_110000_zz99',
      );
      expect(row!.status, 'finalizing');
      expect(row.durationMs, 4400);
      expect(row.sizeBytes, 1600000);
      expect(
        service.resumeMergeCalls,
        <String>['rec_20261118_110000_zz99'],
      );
      expect(
        recording.mergePendingRecordingId,
        'rec_20261118_110000_zz99',
      );

      service.emitEvent(<String, Object?>{
        'type': StreamContract.eventRecordingMerged,
        'recordingId': 'rec_20261118_110000_zz99',
        'filePath':
            '/docs/recordings/rec_20261118_110000_zz99_final.mp4',
        'status': 'ready',
        'totalDurationMs': 4410,
        'totalSizeBytes': 1600500,
        'segmentCount': 1,
        'skippedSegments': <int>[],
        'audioGapSegments': <int>[],
        'reason': null,
        'alreadyMerged': false,
      });
      await tester.pump();
      final rowMerged = await recordings.byRecordingId(
        'rec_20261118_110000_zz99',
      );
      expect(rowMerged!.status, 'ready');
      expect(rowMerged.durationMs, 4410);
      expect(rowMerged.sizeBytes, 1600500);
    });

    testWidgets('el evento recording_error detiene con error', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();

      service.emitEvent(<String, Object?>{
        'type': StreamContract.eventRecordingError,
        'recordingId': 'rec_20261118_120000_ab12',
        'code': StreamContract.cameraDisconnected,
        'message': 'Cámara desconectada.',
        'details': <String, Object?>{'stage': 'recording'},
      });
      await tester.pump();

      expect(recording.state, RecordingState.error);
      expect(recording.errorCode, StreamContract.cameraDisconnected);
      expect(recording.errorMessage, 'Cámara desconectada.');
    });

    // J3-a (3.1.4): el disparador completo viaja en `details`.

    testWidgets(
      'recording_error con details puebla el disparador y no llama a stopRecording',
      (WidgetTester tester) async {
        await pumpController(tester);
        await recording.start();
        await tester.pump();
        expect(timers.any((FakeTimer t) => t.isActive), isTrue);

        service.emitEvent(<String, Object?>{
          'type': StreamContract.eventRecordingError,
          'recordingId': 'rec_20261118_120000_ab12',
          'code': StreamContract.encoderError,
          'message': 'Error del codificador: codec roto.',
          'details': <String, Object?>{
            'code': StreamContract.encoderError,
            'message': 'Error del codificador: codec roto.',
            'stage': 'encoder',
            'segmentIndex': 0,
            'exceptionClass': 'IllegalStateException',
            'triggerSite': 'onError',
            'timestampMs': 1792000000000,
            'freeBytes': null,
            'closeReason': 'muxer_stop_failed',
          },
        });
        await tester.pump();

        expect(recording.state, RecordingState.error);
        expect(recording.errorCode, StreamContract.encoderError);
        expect(recording.errorMessage, 'Error del codificador: codec roto.');
        expect(recording.diagnostics?.stage, 'encoder');
        expect(recording.diagnostics?.segmentIndex, 0);
        expect(
          recording.diagnostics?.exceptionClass,
          'IllegalStateException',
        );
        expect(recording.diagnostics?.lastErrorStage, 'encoder');
        expect(recording.diagnostics?.trigger, 'onError');
        expect(recording.diagnostics?.closeReason, 'muxer_stop_failed');
        // El temporizador de 1 s se detiene y el handler NO invoca
        // `stopRecording()` (el motor ya paró; re-entrar sería un
        // error y dejaría el FGS vivo igualmente: eso es J3-b).
        // J3-b: el único temporizador activo es el vigilante de
        // `service_stopped` (el primero de la lista es el de 1 s).
        expect(timers.first.isActive, isFalse);
        expect(recording.serviceStoppedWatchdogArmed, isTrue);
        expect(service.stopRecordingCalls, 0);
        expect(recording.diagnosticText, contains('Disparador: onError'));
        expect(
          recording.diagnosticText,
          contains('Cierre del segmento: muxer_stop_failed'),
        );
      },
    );

    testWidgets(
      'el evento recording_error sin details (payload viejo) no rompe',
      (WidgetTester tester) async {
        await pumpController(tester);
        await recording.start();
        await tester.pump();

        service.emitEvent(<String, Object?>{
          'type': StreamContract.eventRecordingError,
          'recordingId': 'rec_20261118_120000_ab12',
          'code': StreamContract.cameraDisconnected,
        });
        await tester.pump();

        expect(recording.state, RecordingState.error);
        expect(recording.errorCode, StreamContract.cameraDisconnected);
        expect(recording.errorMessage, isNull);
        expect(recording.diagnostics?.stage, isNull);
        expect(recording.diagnostics?.segmentIndex, isNull);
        expect(recording.diagnostics?.exceptionClass, isNull);
        expect(recording.diagnostics?.trigger, isNull);
        expect(recording.diagnostics?.closeReason, isNull);
        expect(recording.diagnostics?.freeBytes, isNull);
        // J3-b: el temporizador de 1 s se detiene; el vigilante de
        // `service_stopped` queda armado (ver test anterior).
        expect(timers.first.isActive, isFalse);
        expect(recording.serviceStoppedWatchdogArmed, isTrue);
        expect(service.stopRecordingCalls, 0);
      },
    );

    testWidgets(
      'el evento recording_error con details parcial deja los campos nuevos en null',
      (WidgetTester tester) async {
        await pumpController(tester);
        await recording.start();
        await tester.pump();

        service.emitEvent(<String, Object?>{
          'type': StreamContract.eventRecordingError,
          'recordingId': 'rec_20261118_120000_ab12',
          'code': StreamContract.storageFull,
          'message': 'Sin espacio durante la grabación: parada limpia.',
          'details': <String, Object?>{
            'stage': 'rotate',
            'segmentIndex': 0,
            'freeBytes': 100,
          },
        });
        await tester.pump();

        expect(recording.state, RecordingState.error);
        expect(recording.errorCode, StreamContract.storageFull);
        expect(recording.diagnostics?.stage, 'rotate');
        expect(recording.diagnostics?.segmentIndex, 0);
        expect(recording.diagnostics?.freeBytes, 100);
        expect(recording.diagnostics?.trigger, isNull);
        expect(recording.diagnostics?.closeReason, isNull);
        expect(recording.diagnostics?.exceptionClass, isNull);
        expect(service.stopRecordingCalls, 0);
        expect(recording.diagnosticText, contains('Espacio libre: 100 bytes'));
        expect(recording.diagnosticText, isNot(contains('Disparador:')));
      },
    );

    // J3-b (3.1.4): `service_stopped` y su vigilante.

    testWidgets(
      'service_stopped tras recording_error muestra el motivo y cancela el vigilante',
      (WidgetTester tester) async {
        await pumpController(tester);
        await recording.start();
        await tester.pump();
        service.emitEvent(<String, Object?>{
          'type': StreamContract.eventRecordingError,
          'recordingId': 'rec_20261118_120000_ab12',
          'code': StreamContract.encoderError,
          'message': 'Error del codificador: codec roto.',
          'details': <String, Object?>{
            'code': StreamContract.encoderError,
            'stage': 'encoder',
            'segmentIndex': 0,
            'triggerSite': 'onError',
          },
        });
        await tester.pump();
        expect(recording.serviceStoppedWatchdogArmed, isTrue);

        service.emitEvent(<String, Object?>{
          'type': StreamContract.eventServiceStopped,
          'recordingId': 'rec_20261118_120000_ab12',
          'reason': 'error_stop',
        });
        await tester.pump();

        expect(recording.serviceStoppedReason, 'error_stop');
        expect(recording.serviceStoppedMissing, isFalse);
        expect(recording.serviceStoppedWatchdogArmed, isFalse);
        expect(service.stopRecordingCalls, 0);
        expect(
          recording.diagnosticText,
          contains('Servicio detenido: error_stop'),
        );
        expect(recording.diagnosticText, isNot(contains('ANOMALÍA')));
      },
    );

    testWidgets(
      'service_stopped sin motivo (payload viejo) cancela el vigilante sin romper',
      (WidgetTester tester) async {
        await pumpController(tester);
        await recording.start();
        await tester.pump();
        service.emitEvent(<String, Object?>{
          'type': StreamContract.eventRecordingError,
          'recordingId': 'rec_20261118_120000_ab12',
          'code': StreamContract.encoderError,
        });
        await tester.pump();
        expect(recording.serviceStoppedWatchdogArmed, isTrue);

        service.emitEvent(<String, Object?>{
          'type': StreamContract.eventServiceStopped,
          'recordingId': 'rec_20261118_120000_ab12',
        });
        await tester.pump();

        expect(recording.serviceStoppedReason, isNull);
        expect(recording.serviceStoppedWatchdogArmed, isFalse);
        expect(
          recording.diagnosticText,
          isNot(contains('Servicio detenido:')),
        );
      },
    );

    testWidgets(
      'sin service_stopped en 5 s tras recording_error la tarjeta marca anomalía',
      (WidgetTester tester) async {
        await pumpController(tester);
        await recording.start();
        await tester.pump();
        service.emitEvent(<String, Object?>{
          'type': StreamContract.eventRecordingError,
          'recordingId': 'rec_20261118_120000_ab12',
          'code': StreamContract.encoderError,
          'message': 'Error del codificador: codec roto.',
        });
        await tester.pump();
        expect(recording.serviceStoppedWatchdogArmed, isTrue);

        // El vigilante es el último temporizador creado.
        timers.last.fire();
        await tester.pump();

        expect(recording.serviceStoppedMissing, isTrue);
        expect(
          recording.diagnosticText,
          contains('ANOMALÍA: sin service_stopped 5 s tras el error'),
        );
      },
    );

    testWidgets(
      'con la app en segundo plano el vigilante no marca anomalía',
      (WidgetTester tester) async {
        await pumpController(tester);
        await recording.start();
        await tester.pump();
        service.emitEvent(<String, Object?>{
          'type': StreamContract.eventRecordingError,
          'recordingId': 'rec_20261118_120000_ab12',
          'code': StreamContract.encoderError,
          'message': 'Error del codificador: codec roto.',
        });
        await tester.pump();
        expect(recording.serviceStoppedWatchdogArmed, isTrue);

        // La app pasa a segundo plano: los eventos pueden llegar
        // tarde y el vigilante no debe marcar anomalía.
        foreground = false;
        timers.last.fire();
        await tester.pump();

        expect(recording.serviceStoppedMissing, isFalse);
        expect(recording.diagnosticText, isNot(contains('ANOMALÍA')));
      },
    );

    // Audio AAC (3.1.3, plan §10.2).

    testWidgets(
      'start() con micrófono concedido pasa audioEnabled: true y graba',
      (WidgetTester tester) async {
        await pumpController(tester);
        service.requestOutcomes[PermissionKind.microphone] =
            PermissionStatus.granted;

        await recording.start();
        await tester.pump();

        // Se pidieron micrófono y notificaciones (2 llamadas); el
        // estado del micrófono quedó concedido.
        expect(service.requestPermissionsCalls, 2);
        expect(
          service.permissionStates.microphone,
          PermissionStatus.granted,
        );
        expect(service.lastRecordingOptions?.audioEnabled, isTrue);
        expect(service.startRecordingCalls, 1);
        expect(recording.state, RecordingState.recording);
        expect(recording.audioEnabled, isTrue);
        expect(recording.audioActive, isTrue);
        expect(recording.audioReason, isNull);
      },
    );

    testWidgets(
      'start() con micrófono denegado graba en video-only (D9)',
      (WidgetTester tester) async {
        await pumpController(tester);
        service.requestOutcomes[PermissionKind.microphone] =
            PermissionStatus.denied;

        await recording.start();
        await tester.pump();

        // La denegación NO impide grabar: la grabación arranca igual,
        // solo sin audio.
        expect(service.startRecordingCalls, 1);
        expect(service.lastRecordingOptions?.audioEnabled, isFalse);
        expect(recording.state, RecordingState.recording);
        expect(recording.audioEnabled, isFalse);
        expect(recording.audioActive, isFalse);
        expect(recording.audioReason, StreamContract.permissionDenied);
        expect(
          recording.diagnosticText,
          contains('Audio: no (permission_denied)'),
        );
      },
    );

    testWidgets(
      'start() con micrófono bloqueado no muestra el diálogo y graba',
      (WidgetTester tester) async {
        await pumpController(tester);
        service.permissionStates = const PermissionStates(
          camera: PermissionStatus.granted,
          microphone: PermissionStatus.permanentlyDenied,
          notifications: PermissionStatus.notRequested,
        );
        await permissions.refresh();

        await recording.start();
        await tester.pump();

        // Solo se pidió notificaciones: el micrófono bloqueado no
        // lanza el diálogo del sistema.
        expect(service.requestPermissionsCalls, 1);
        expect(
          service.lastRequestedPermissions,
          <PermissionKind>[PermissionKind.notifications],
        );
        expect(service.lastRecordingOptions?.audioEnabled, isFalse);
        expect(recording.state, RecordingState.recording);
        expect(recording.audioEnabled, isFalse);
      },
    );

    testWidgets('el evento recording_started con audio rellena el estado', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.requestOutcomes[PermissionKind.microphone] =
          PermissionStatus.granted;
      await recording.start();
      await tester.pump();

      service.emitEvent(startedEventWithAudio());
      await tester.pump();

      expect(recording.audioEnabled, isTrue);
      expect(recording.audioActive, isTrue);
      expect(recording.startedInfo?.audioSampleRate, 48000);
      expect(recording.startedInfo?.audioChannels, 1);
      expect(recording.startedInfo?.audioEncoderName, 'c2.qti.aac.encoder');
      expect(recording.startedInfo?.timestampSource, 'REALTIME');
      expect(
        recording.diagnosticText,
        contains('Audio: sí (48 kHz mono)'),
      );
    });

    testWidgets('el evento recording_started video-only muestra el motivo', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.requestOutcomes[PermissionKind.microphone] =
          PermissionStatus.granted;
      await recording.start();
      await tester.pump();

      // El motor degradó a video-only aunque Dart pidió audio.
      service.emitEvent(startedEventVideoOnly());
      await tester.pump();

      expect(recording.audioEnabled, isFalse);
      expect(recording.audioActive, isFalse);
      expect(recording.audioReason, 'audio_format_timeout');
      expect(
        recording.diagnosticText,
        contains('Audio: no (audio_format_timeout)'),
      );
    });

    testWidgets(
      'recording_audio_lost actualiza el estado sin cambiar a error',
      (WidgetTester tester) async {
        await pumpController(tester);
        service.requestOutcomes[PermissionKind.microphone] =
            PermissionStatus.granted;
        await recording.start();
        await tester.pump();
        service.emitEvent(startedEventWithAudio());
        await tester.pump();
        expect(recording.audioActive, isTrue);

        service.emitEvent(audioLostEvent());
        await tester.pump();

        // No es un error de grabación: el estado sigue siendo
        // `recording` y no hay código de error.
        expect(recording.state, RecordingState.recording);
        expect(recording.errorCode, isNull);
        expect(recording.errorMessage, isNull);
        expect(recording.audioEnabled, isTrue);
        expect(recording.audioActive, isFalse);
        expect(recording.audioLostReason, 'audio_dead_object');
        expect(recording.audioLostSegmentIndex, 0);
        expect(
          recording.audioLostMessage,
          'El micrófono dejó de responder.',
        );
        expect(
          recording.diagnosticText,
          contains(
            'Audio perdido en seg 0: audio_dead_object — '
            'El micrófono dejó de responder.',
          ),
        );
        // La grabación sigue: el temporizador no se paró.
        expect(timers.first.isActive, isTrue);
      },
    );

    testWidgets(
      'T-S13: la pérdida por silencio muestra desde cuándo y con qué pico',
      (WidgetTester tester) async {
        await pumpController(tester);
        service.requestOutcomes[PermissionKind.microphone] =
            PermissionStatus.granted;
        await recording.start();
        await tester.pump();
        service.emitEvent(startedEventWithAudio());
        await tester.pump();

        service.emitEvent(audioLostSilenceEvent());
        await tester.pump();

        // No es un error de grabación: el estado sigue siendo
        // `recording` y no hay código de error.
        expect(recording.state, RecordingState.recording);
        expect(recording.audioActive, isFalse);
        expect(recording.audioLostReason, 'audio_silence_samples');
        expect(recording.audioLostSegmentIndex, 0);
        expect(recording.audioLostSilenceMs, 5000);
        expect(recording.audioLostSilenceStartMs, 12345);
        expect(recording.audioLostSilencePeak, 0);
        expect(
          recording.diagnosticText,
          contains(
            'Audio perdido en seg 0: audio_silence_samples — '
            'silencio desde 12345ms (5 s, pico=0)',
          ),
        );
      },
    );

    testWidgets(
      'T-S13 (respaldo): sin las claves de silencio la línea queda como antes',
      (WidgetTester tester) async {
        await pumpController(tester);
        service.requestOutcomes[PermissionKind.microphone] =
            PermissionStatus.granted;
        await recording.start();
        await tester.pump();
        service.emitEvent(startedEventWithAudio());
        await tester.pump();

        // Motivo nuevo sin las claves opcionales: motivo + mensaje,
        // como cualquier otra pérdida.
        service.emitEvent(<String, Object?>{
          'type': StreamContract.eventRecordingAudioLost,
          'recordingId': 'rec_20261118_120000_ab12',
          'reason': 'audio_silence_samples',
          'segmentIndex': 1,
          'message': 'Silencio detectado.',
        });
        await tester.pump();

        expect(recording.audioLostReason, 'audio_silence_samples');
        expect(recording.audioLostSilenceMs, isNull);
        expect(recording.audioLostSilenceStartMs, isNull);
        expect(recording.audioLostSilencePeak, isNull);
        expect(
          recording.diagnosticText,
          contains(
            'Audio perdido en seg 1: audio_silence_samples — '
            'Silencio detectado.',
          ),
        );
      },
    );

    testWidgets(
      'T-S14: la línea de segmento muestra motivoAudio solo con audioReason',
      (WidgetTester tester) async {
        await pumpController(tester);
        service.requestOutcomes[PermissionKind.microphone] =
            PermissionStatus.granted;
        await recording.start();
        await tester.pump();
        service.emitEvent(startedEventWithAudio());
        await tester.pump();

        final Map<String, Object?> withSilence =
            segmentEventWithAudio(0)
              ..['audioReason'] = 'audio_silence_samples';
        service.emitEvent(withSilence);
        await tester.pump();
        service.emitEvent(segmentEvent(1));
        await tester.pump();

        final String? text = recording.diagnosticText;
        expect(text, isNotNull);
        expect(
          text,
          contains(
            'seg0: ready 60000ms motivo=— cerradoLimpio=sí fps=29.9 '
            'muestras=1800 verif=1798 fase=61.2s audio=59800ms av=120ms '
            'inicioAudio=— huecosAudio=0 muestrasAudio=2800 '
            'motivoAudio=audio_silence_samples',
          ),
        );
        // Sin `audioReason`, la línea es idéntica a antes.
        expect(
          text,
          contains(
            'seg1: ready 60000ms motivo=— cerradoLimpio=sí fps=29.9 '
            'muestras=1800 verif=1798 fase=61.2s audio=no '
            'inicioAudio=— huecosAudio=0 muestrasAudio=—',
          ),
        );
        expect(
          'motivoAudio=audio_silence_samples'.allMatches(text!).length,
          1,
        );
      },
    );

    testWidgets(
      'T-S15: Última parada muestra silencioPico tras un disparo',
      (WidgetTester tester) async {
        await pumpController(tester);
        await recording.start();
        await tester.pump();

        // El método devuelve el pico del disparo junto con el
        // segundo más silencioso (Grupo S, S1): la parada por
        // método no lo pierde.
        service.stopSilencePeak = 0;
        service.stopQuietestSecondPeak = 12;
        service.stopQuietestSecondNonZero = 3;
        await recording.stop();
        await tester.pump();

        expect(recording.lastStopped!.silencePeak, 0);
        expect(
          recording.diagnosticText,
          contains('picoMin1s=12 noCeroMin1s=3 silencioPico=0'),
        );
      },
    );

    testWidgets(
      'T-S15 (respaldo): sin disparo Última parada muestra silencioPico=—',
      (WidgetTester tester) async {
        await pumpController(tester);
        await recording.start();
        await tester.pump();

        // El falso devuelve `null` por defecto (sin disparo): la
        // línea muestra `—`, nunca inventa un 0.
        await recording.stop();
        await tester.pump();

        expect(recording.lastStopped!.silencePeak, isNull);
        expect(
          recording.diagnosticText,
          contains('silencioPico=—'),
        );
      },
    );

    testWidgets(
      'la pérdida de audio no se confunde con el estado error',
      (WidgetTester tester) async {
        await pumpController(tester);
        service.requestOutcomes[PermissionKind.microphone] =
            PermissionStatus.granted;
        await recording.start();
        await tester.pump();
        service.emitEvent(startedEventWithAudio());
        await tester.pump();
        service.emitEvent(audioLostEvent());
        await tester.pump();
        expect(recording.state, RecordingState.recording);

        // Un error real después de la pérdida sí detiene con error.
        service.emitEvent(<String, Object?>{
          'type': StreamContract.eventRecordingError,
          'recordingId': 'rec_20261118_120000_ab12',
          'code': StreamContract.cameraDisconnected,
          'message': 'Cámara desconectada.',
          'details': <String, Object?>{'stage': 'recording'},
        });
        await tester.pump();

        expect(recording.state, RecordingState.error);
        expect(recording.errorCode, StreamContract.cameraDisconnected);
        // La pérdida de audio queda registrada aparte del error.
        expect(recording.audioLostReason, 'audio_dead_object');
      },
    );

    testWidgets('la línea de segmento muestra el audio y el offset A/V', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.requestOutcomes[PermissionKind.microphone] =
          PermissionStatus.granted;
      await recording.start();
      await tester.pump();
      service.emitEvent(startedEventWithAudio());
      await tester.pump();

      service.emitEvent(segmentEventWithAudio(0));
      await tester.pump();
      service.emitEvent(segmentEvent(1));
      await tester.pump();

      final String? text = recording.diagnosticText;
      expect(text, isNotNull);
      expect(
        text,
        contains(
          'seg0: ready 60000ms motivo=— cerradoLimpio=sí fps=29.9 '
          'muestras=1800 verif=1798 fase=61.2s audio=59800ms av=120ms',
        ),
      );
      // Segmento video-only (sin claves de audio): `audio=no`.
      expect(
        text,
        contains(
          'seg1: ready 60000ms motivo=— cerradoLimpio=sí fps=29.9 '
          'muestras=1800 verif=1798 fase=61.2s audio=no',
        ),
      );
    });

    testWidgets(
      'el diagnóstico muestra el origen de timestamps y los datos de '
      'audio por segmento',
      (WidgetTester tester) async {
        await pumpController(tester);
        service.requestOutcomes[PermissionKind.microphone] =
            PermissionStatus.granted;
        await recording.start();
        await tester.pump();
        service.emitEvent(startedEventWithAudio());
        await tester.pump();

        // Segmento con audio: primera muestra a 85 ms del origen y
        // 3 muestras descartadas; segmento video-only sin claves.
        final Map<String, Object?> withAudio =
            segmentEventWithAudio(0)
              ..['audioStartOffsetMs'] = 85
              ..['audioGapSamples'] = 3;
        service.emitEvent(withAudio);
        await tester.pump();
        service.emitEvent(segmentEvent(1));
        await tester.pump();

        final String? text = recording.diagnosticText;
        expect(text, isNotNull);
        expect(text, contains('Audio: sí (48 kHz mono)'));
        expect(text, contains('Origen timestamps: REALTIME'));
        expect(
          text,
          contains(
            'seg0: ready 60000ms motivo=— cerradoLimpio=sí fps=29.9 '
            'muestras=1800 verif=1798 fase=61.2s audio=59800ms av=120ms '
            'inicioAudio=85 huecosAudio=3 muestrasAudio=2800',
          ),
        );
        // Sin claves de audio: `audio=no` y el inicio como `—`.
        expect(
          text,
          contains(
            'seg1: ready 60000ms motivo=— cerradoLimpio=sí fps=29.9 '
            'muestras=1800 verif=1798 fase=61.2s audio=no '
            'inicioAudio=— huecosAudio=0 muestrasAudio=—',
          ),
        );
      },
    );

    testWidgets(
      'el diagnóstico muestra las descartadas de entrada AAC al parar',
      (WidgetTester tester) async {
        await pumpController(tester);
        await recording.start();
        await tester.pump();

        // Sin evento `recording_started`: el origen es desconocido.
        expect(recording.diagnosticText, contains('Origen timestamps: —'));

        // Parada externa por evento con el contador nativo y la
        // capacidad del buffer de entrada AAC (ronda U).
        final Map<String, Object?> stopped = stoppedEvent()
          ..['droppedInputBuffers'] = 5
          ..['inputBufferCapacityBytes'] = 2048;
        service.emitEvent(stopped);
        await tester.pump();

        expect(
          recording.diagnosticText,
          contains(
            'Última parada: estado=ready duración total=12000ms '
            'segmentos=1 descartadasEntradaAAC=5 '
            'capacidadEntradaAAC=2048 bytes',
          ),
        );
      },
    );

    testWidgets(
      'el diagnóstico muestra el estado del micrófono y los contadores al parar',
      (WidgetTester tester) async {
        await pumpController(tester);
        await recording.start();
        await tester.pump();

        // Parada externa por evento con los campos de la ronda 3.
        final Map<String, Object?> stopped = stoppedEvent()
          ..['micStateAtStart'] = 3
          ..['micStateAtStop'] = 1
          ..['audioReadCount'] = 0
          ..['audioLastReadOkAgeMs'] = 12300
          ..['audioReaderAlive'] = true
          ..['audioEos'] = true;
        service.emitEvent(stopped);
        await tester.pump();

        expect(
          recording.diagnosticText,
          contains(
            'Última parada: estado=ready duración total=12000ms '
            'segmentos=1 descartadasEntradaAAC=0 '
            'capacidadEntradaAAC=— bytes '
            'estadoMic=RECORDING/STOPPED lecturas=0 ultimoRead=— '
            'ultimaLecturaOk=hace 12.3s lectorVivo=sí audioEos=sí '
            'lectorSinDatos2s=no eosTimeout=no',
          ),
        );
      },
    );

    testWidgets(
      'el diagnóstico muestra — en los campos de la ronda 3 sin claves',
      (WidgetTester tester) async {
        await pumpController(tester);
        await recording.start();
        await tester.pump();

        // Parada externa por evento sin las claves nuevas: nada se inventa.
        service.emitEvent(stoppedEvent());
        await tester.pump();

        expect(
          recording.diagnosticText,
          contains(
            'Última parada: estado=ready duración total=12000ms '
            'segmentos=1 descartadasEntradaAAC=0 '
            'capacidadEntradaAAC=— bytes '
            'estadoMic=—/— lecturas=0 ultimoRead=— '
            'ultimaLecturaOk=— lectorVivo=no audioEos=no '
            'lectorSinDatos2s=no eosTimeout=no',
          ),
        );
      },
    );

    testWidgets('stop por método trae el estado del micrófono y los contadores',
        (WidgetTester tester) async {
      await pumpController(tester);
      await recording.start();
      await tester.pump();

      // El método devuelve los campos de la ronda 3 (mismo mapa que
      // el evento): la parada por método no los pierde (lección de
      // la ronda anterior: `stop()` construye `_lastStopped` desde
      // el `RecordingInfo` del método).
      service.stopMicStateAtStart = 3;
      service.stopMicStateAtStop = 3;
      service.stopAudioReadCount = 42;
      service.stopAudioLastReadCode = 4096;
      service.stopAudioLastReadOkAgeMs = 12300;
      service.stopAudioReaderAlive = true;
      service.stopAudioEos = true;
      // Grupo V: el método trae también el flag del watchdog (V3)
      // y si la espera de EOS agotó los 5 s (V4).
      service.stopAudioReaderNoData2s = true;
      service.stopEosTimedOut = true;
      await recording.stop();
      await tester.pump();

      expect(
        recording.diagnosticText,
        contains(
          'Última parada: estado=ready duración total=12000ms '
          'segmentos=1 descartadasEntradaAAC=0 '
          'capacidadEntradaAAC=— bytes '
          'estadoMic=RECORDING/RECORDING lecturas=42 ultimoRead=4096 '
          'ultimaLecturaOk=hace 12.3s lectorVivo=sí audioEos=sí '
          'lectorSinDatos2s=sí eosTimeout=sí',
        ),
      );
    });

    testWidgets('al volver, el snapshot re-sincroniza el estado de audio', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      recording.detach();
      service.recordingStatus = RecordingStatusSnapshot(
        state: 'recording',
        recordingId: 'rec_20261118_120000_ab12',
        elapsedMs: 30000,
        segmentIndex: 0,
        segmentDurationMs: 5000,
        width: 1280,
        height: 720,
        fps: 30,
        audioEnabled: true,
        audioActive: true,
      );
      recording.attach();
      await tester.pump();

      expect(recording.state, RecordingState.recording);
      expect(recording.audioEnabled, isTrue);
      expect(recording.audioActive, isTrue);
      expect(recording.audioReason, isNull);
    });

    testWidgets(
      'al volver con el audio perdido, el snapshot lo refleja',
      (WidgetTester tester) async {
        await pumpController(tester);
        recording.detach();
        service.recordingStatus = const RecordingStatusSnapshot(
          state: 'recording',
          recordingId: 'rec_20261118_120000_ab12',
          elapsedMs: 45000,
          segmentIndex: 0,
          segmentDurationMs: 45000,
          width: 1280,
          height: 720,
          fps: 30,
          audioEnabled: true,
          audioActive: false,
          audioReason: 'audio_dead_object',
        );
        recording.attach();
        await tester.pump();

        expect(recording.state, RecordingState.recording);
        expect(recording.audioEnabled, isTrue);
        expect(recording.audioActive, isFalse);
        expect(recording.audioLostReason, 'audio_dead_object');
        expect(
          recording.diagnosticText,
          contains('Audio perdido en seg —: audio_dead_object'),
        );
      },
    );

    // Ruta canónica en el diagnóstico (subetapa 0, grupo 0-f): lo que
    // F1/F8 leen en el teléfono. El controlador recibe la ruta, el
    // intento y la duración medida por constructor (desde `AppServices`
    // vía `StreamingController`); sin ellos, no se inventa la línea.
    group('RecordingController: ruta canónica en el diagnóstico', () {
      RecordingController makeController({
        String? recordingsDirPath,
        int? recordingsDirAttempts,
        int? recordingsDirResolutionMs,
      }) {
        final RecordingController controller = RecordingController(
          service: service,
          permissions: permissions,
          recordings: recordings,
          isPreviewActive: () => previewActive,
          nowMs: () => now,
          createTimer: (Duration duration, void Function(Timer) callback) {
            final FakeTimer timer = FakeTimer(callback);
            timers.add(timer);
            return timer;
          },
          recordingsDirPath: recordingsDirPath,
          recordingsDirAttempts: recordingsDirAttempts,
          recordingsDirResolutionMs: recordingsDirResolutionMs,
        );
        controller.attach();
        addTearDown(controller.detach);
        return controller;
      }

      testWidgets('con valores medidos muestra ruta, intento y duración',
          (WidgetTester tester) async {
        await pumpController(tester);
        final RecordingController withPath = makeController(
          recordingsDirPath: '/fake/recordings',
          recordingsDirAttempts: 1,
          recordingsDirResolutionMs: 12,
        );
        service.startRecordingError = StreamContract.storageFull;
        await withPath.start();
        await tester.pump();

        expect(withPath.state, RecordingState.error);
        expect(
          withPath.diagnosticText,
          contains('Carpeta de grabaciones: /fake/recordings'),
        );
        expect(
          withPath.diagnosticText,
          contains('recordingsDirState=ready attempt=1 resolucionMs=12ms'),
        );
      });

      testWidgets('sin valores medidos no inventa la línea de ruta',
          (WidgetTester tester) async {
        await pumpController(tester);
        final RecordingController withoutPath = makeController();
        service.startRecordingError = StreamContract.storageFull;
        await withoutPath.start();
        await tester.pump();

        expect(withoutPath.state, RecordingState.error);
        expect(withoutPath.diagnosticText, isNotNull);
        expect(
          withoutPath.diagnosticText,
          isNot(contains('Carpeta de grabaciones:')),
        );
        expect(
          withoutPath.diagnosticText,
          isNot(contains('recordingsDirState=')),
        );
      });
    });
  });
}
