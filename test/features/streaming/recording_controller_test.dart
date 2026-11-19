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
      now = 1000000;
      notifications = 0;
      recording = RecordingController(
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

    testWidgets('stop detiene y actualiza la fila a ready', (
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
      final row = await recordings.byRecordingId(
        'rec_20261118_120000_ab12',
      );
      expect(row!.status, 'ready');
      expect(row.durationMs, 12000);
      expect(row.sizeBytes, 3750000);
      expect(timers.first.isActive, isFalse);
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
      'la parada externa por evento actualiza la fila sin llamar a stop()',
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
        final row = await recordings.byRecordingId(
          'rec_20261118_120000_ab12',
        );
        expect(row!.status, 'ready');
        expect(row.durationMs, 12000);
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
      service.recoveredSegments = <RecoveredSegmentInfo>[
        const RecoveredSegmentInfo(
          recordingId: 'rec_20261118_110000_zz99',
          index: 0,
          fileName: 'rec_20261118_110000_zz99_seg000.tmp.mp4',
          filePath:
              '/docs/recordings/rec_20261118_110000_zz99_seg000.tmp.mp4',
          status: 'partial',
          durationMs: 4400,
          sizeBytes: 1600000,
        ),
      ];
      recording.attach();
      await tester.pump();

      expect(recording.lastRecovery.length, 1);
      final row = await recordings.byRecordingId(
        'rec_20261118_110000_zz99',
      );
      expect(row!.status, 'ready');
      expect(row.durationMs, 4400);
      expect(row.sizeBytes, 1600000);
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
            'inicioAudio=85 huecosAudio=3',
          ),
        );
        // Sin claves de audio: `audio=no` y el inicio como `—`.
        expect(
          text,
          contains(
            'seg1: ready 60000ms motivo=— cerradoLimpio=sí fps=29.9 '
            'muestras=1800 verif=1798 fase=61.2s audio=no '
            'inicioAudio=— huecosAudio=0',
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

        // Parada externa por evento con el contador nativo.
        final Map<String, Object?> stopped = stoppedEvent()
          ..['droppedInputBuffers'] = 5;
        service.emitEvent(stopped);
        await tester.pump();

        expect(
          recording.diagnosticText,
          contains(
            'Última parada: estado=ready duración total=12000ms '
            'segmentos=1 descartadasEntradaAAC=5',
          ),
        );
      },
    );

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
  });
}
