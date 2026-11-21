import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/streaming/application/permission_controller.dart';
import 'package:transmiti2/features/streaming/application/recording_controller.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/data/recording_repository.dart';
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

/// Reconciliación de filas (subetapa 0, grupo 0-d2; plan §5):
/// U10 (fila ausente → se reconstruye), U11 (fila sin archivo →
/// marcada, sin inventar), U14 (fallo de `insertRecording` → marcador
/// y reconstrucción en la siguiente pasada), U33 (la puerta existe y
/// las operaciones la esperan), U34 (puerta en `FAILED`: error con
/// motivo + reintento), I6 (biblioteca antes de `DONE`) e I1–I5
/// (partes que tocan la fila Dart).
///
/// La parte de tiempo acotado de I5 (verificar solo lo que el sidecar
/// no certifica) es Kotlin y ya está cubierta en 0-d1 (U25/U32); aquí
/// se prueba su reflejo en filas: la segunda pasada no re-inserta.
void main() {
  group('RecordingController reconciliación', () {
    late FakeStreamingService service;
    late PermissionController permissions;
    late InMemoryRecordingRepository recordings;
    late RecordingController recording;
    late List<FakeTimer> timers;
    late bool previewActive;
    late int now;

    Future<void> pumpController(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox());
    }

    RecordingController buildController() {
      return RecordingController(
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
      recording = buildController();
      recording.attach();
    });

    tearDown(() {
      recording.detach();
    });

    ReconciliationResult doneResult({
      List<RecoveredSegmentInfo> segments = const <RecoveredSegmentInfo>[],
      List<String> groupsSkippedActive = const <String>[],
      ReconciliationSummary? summary,
    }) {
      return ReconciliationResult(
        state: ReconciliationState.done,
        summary:
            summary ??
            const ReconciliationSummary(state: ReconciliationState.done),
        segments: segments,
        groupsSkippedActive: groupsSkippedActive,
      );
    }

    RecoveredSegmentInfo segment({
      required String recordingId,
      required int index,
      required String status,
      int? durationMs,
      required int sizeBytes,
    }) {
      return RecoveredSegmentInfo(
        recordingId: recordingId,
        index: index,
        fileName: '${recordingId}_seg${index.toString().padLeft(3, '0')}'
            '${status == 'pending' ? '.tmp.mp4' : '.mp4'}',
        filePath: '/docs/recordings/${recordingId}_seg'
            '${index.toString().padLeft(3, '0')}'
            '${status == 'pending' ? '.tmp.mp4' : '.mp4'}',
        status: status,
        durationMs: durationMs,
        sizeBytes: sizeBytes,
      );
    }

    testWidgets('U10 fila ausente se reconstruye desde sidecar + archivos',
        (WidgetTester tester) async {
      await pumpController(tester);
      recording.detach();
      service.reconciliationResult = doneResult(
        segments: <RecoveredSegmentInfo>[
          segment(
            recordingId: 'rec_20261118_110000_zz99',
            index: 0,
            status: 'ready',
            durationMs: 3000,
            sizeBytes: 900000,
          ),
          segment(
            recordingId: 'rec_20261118_110000_zz99',
            index: 1,
            status: 'partial',
            durationMs: 2000,
            sizeBytes: 600000,
          ),
          // Pendiente (C6): no suma duración ni cambia el estado.
          segment(
            recordingId: 'rec_20261118_110000_zz99',
            index: 2,
            status: 'pending',
            sizeBytes: 100000,
          ),
        ],
        summary: const ReconciliationSummary(
          state: ReconciliationState.done,
          runId: 'run-1',
          recordingsReconciled: 1,
          pending: 1,
        ),
      );
      recording.attach();
      await tester.pump();

      final row = await recordings.byRecordingId(
        'rec_20261118_110000_zz99',
      );
      expect(row, isNotNull);
      // `partial` colapsa a `ready` en v2; duración/tamaño sumados
      // solo de los utilizables.
      expect(row!.status, 'ready');
      expect(row.durationMs, 5000);
      expect(row.sizeBytes, 1500000);
      expect(recording.orphansReconstructed, 1);
      expect(recording.lastReconciliation?.orphansReconstructed, 1);
      expect(recording.lastReconciliation?.pending, 1);
      expect(recording.lastReconciliation?.runId, 'run-1');
      expect(recording.lastRecovery.length, 2);
      expect(recording.pendingReconciliationIds, isEmpty);
    });

    testWidgets('U11 fila sin archivo queda marcada, sin inventar '
        'segmentos ni borrar la fila', (WidgetTester tester) async {
      await pumpController(tester);
      await recordings.insertRecording(
        recordingId: 'rec_20261118_110000_zz99',
        resolution: '720p',
        fps: 30,
        videoBitrateBps: 2500000,
        nowMs: now,
      );
      recording.detach();
      // La pasada no trae ningún grupo: la fila no tiene archivos.
      service.reconciliationResult = doneResult();
      recording.attach();
      await tester.pump();

      final row = await recordings.byRecordingId(
        'rec_20261118_110000_zz99',
      );
      expect(row, isNotNull);
      expect(row!.status, 'recording');
      expect(row.durationMs, isNull);
      expect(recording.pendingReconciliationIds,
          contains('rec_20261118_110000_zz99'));
      expect(recording.lastRecovery, isEmpty);
      expect(recordings.rows.length, 1);
    });

    testWidgets('U14 insertRecording fallido: la grabación sigue, '
        'queda el marcador y la siguiente reconciliación reconstruye '
        'la fila', (WidgetTester tester) async {
      await pumpController(tester);
      recordings.failInsertRecording = true;

      await recording.start();
      await tester.pump();

      // La grabación sigue aunque la fila no se creara.
      expect(recording.state, RecordingState.recording);
      expect(
        recording.pendingReconciliationIds,
        contains('rec_20261118_120000_ab12'),
      );
      expect(
        await recordings.byRecordingId('rec_20261118_120000_ab12'),
        isNull,
      );

      recordings.failInsertRecording = false;
      recording.detach();
      service.reconciliationResult = doneResult(
        segments: <RecoveredSegmentInfo>[
          segment(
            recordingId: 'rec_20261118_120000_ab12',
            index: 0,
            status: 'ready',
            durationMs: 12000,
            sizeBytes: 3750000,
          ),
        ],
      );
      recording.attach();
      await tester.pump();

      final row = await recordings.byRecordingId(
        'rec_20261118_120000_ab12',
      );
      expect(row, isNotNull);
      expect(row!.status, 'ready');
      expect(row.durationMs, 12000);
      expect(row.sizeBytes, 3750000);
      expect(recording.pendingReconciliationIds, isEmpty);
      expect(recording.orphansReconstructed, 1);
    });

    testWidgets('U33 con la puerta en RUNNING no se devuelve ningún '
        'dato hasta DONE', (WidgetTester tester) async {
      await pumpController(tester);
      recording.detach();
      await recordings.insertRecording(
        recordingId: 'rec_20261118_110000_zz99',
        resolution: '720p',
        fps: 30,
        videoBitrateBps: 2500000,
        nowMs: now,
      );
      // Controlador nuevo (sin pasada previa): la puerta en
      // `RUNNING` es lo único que ve hasta que completa.
      final Completer<ReconciliationResult> gate =
          Completer<ReconciliationResult>();
      service.reconcileRecordingsOverride = () => gate.future;
      final RecordingController fresh = buildController();
      fresh.attach();
      await tester.pump();

      expect(fresh.reconciliationState, ReconciliationState.running);
      expect(fresh.lastReconciliation, isNull);
      expect(fresh.lastRecovery, isEmpty);
      final rowBefore = await recordings.byRecordingId(
        'rec_20261118_110000_zz99',
      );
      expect(rowBefore!.status, 'recording');

      gate.complete(doneResult(
        segments: <RecoveredSegmentInfo>[
          segment(
            recordingId: 'rec_20261118_110000_zz99',
            index: 0,
            status: 'partial',
            durationMs: 4400,
            sizeBytes: 1600000,
          ),
        ],
      ));
      await tester.pump();
      await tester.pump();

      expect(fresh.reconciliationState, ReconciliationState.done);
      final rowAfter = await recordings.byRecordingId(
        'rec_20261118_110000_zz99',
      );
      expect(rowAfter!.status, 'ready');
      expect(rowAfter.durationMs, 4400);
      fresh.detach();
    });

    testWidgets('U34 puerta en FAILED: error con motivo + reintento, '
        'nunca lista vacía como final', (WidgetTester tester) async {
      await pumpController(tester);
      await recordings.insertRecording(
        recordingId: 'rec_20261118_110000_zz99',
        resolution: '720p',
        fps: 30,
        videoBitrateBps: 2500000,
        nowMs: now,
      );
      recording.detach();
      service.reconciliationError = const StreamingException(
        StreamContract.reconciliationFailed,
        'La reconciliación falló.',
      );
      recording.attach();
      await tester.pump();

      expect(recording.reconciliationState, ReconciliationState.failed);
      expect(
        recording.reconciliationError,
        StreamContract.reconciliationFailed,
      );
      expect(recording.lastRecovery, isEmpty);
      // La fila sigue en `recording` y marcada: no se presenta
      // «no hay grabaciones» como resultado final.
      final row = await recordings.byRecordingId(
        'rec_20261118_110000_zz99',
      );
      expect(row!.status, 'recording');
      expect(recording.pendingReconciliationIds,
          contains('rec_20261118_110000_zz99'));

      // Reintento al volver a abrir: la segunda pasada converge.
      recording.detach();
      service.reconciliationError = null;
      service.reconciliationResult = doneResult(
        segments: <RecoveredSegmentInfo>[
          segment(
            recordingId: 'rec_20261118_110000_zz99',
            index: 0,
            status: 'ready',
            durationMs: 4400,
            sizeBytes: 1600000,
          ),
        ],
      );
      recording.attach();
      await tester.pump();

      expect(recording.reconciliationState, ReconciliationState.done);
      expect(recording.reconciliationError, isNull);
      final rowAfter = await recordings.byRecordingId(
        'rec_20261118_110000_zz99',
      );
      expect(rowAfter!.status, 'ready');
      expect(recording.pendingReconciliationIds, isEmpty);
    });

    testWidgets('I6 biblioteca abierta antes de DONE: «Preparando '
        'biblioteca…» y los datos aparecen al completarse',
        (WidgetTester tester) async {
      await pumpController(tester);
      recording.detach();

      // Controlador nuevo (sin pasada previa) con la puerta en
      // `RUNNING`: no hay resumen ni datos hasta que completa.
      final Completer<ReconciliationResult> gate =
          Completer<ReconciliationResult>();
      service.reconcileRecordingsOverride = () => gate.future;
      final RecordingController fresh = buildController();
      fresh.attach();
      await tester.pump();
      expect(fresh.reconciliationState, ReconciliationState.running);
      expect(fresh.lastReconciliation, isNull);
      expect(fresh.lastRecovery, isEmpty);

      // Con una parada previa el diagnóstico existe en `idle` y
      // muestra el «preparando…» mientras la puerta corre.
      await fresh.start();
      await tester.pump();
      await fresh.stop();
      await tester.pump();
      expect(
        fresh.diagnosticText,
        contains('Reconciliación: preparando…'),
      );

      gate.complete(doneResult(
        segments: <RecoveredSegmentInfo>[
          segment(
            recordingId: 'rec_20261118_110000_zz99',
            index: 0,
            status: 'ready',
            durationMs: 4400,
            sizeBytes: 1600000,
          ),
        ],
        summary: const ReconciliationSummary(
          state: ReconciliationState.done,
          runId: 'run-3',
          recordingsReconciled: 1,
        ),
      ));
      await tester.pump();
      await tester.pump();

      expect(fresh.reconciliationState, ReconciliationState.done);
      expect(
        fresh.diagnosticText,
        contains('Reconciliación: done run=run-3'),
      );
      fresh.detach();
    });

    testWidgets('I1 muerte durante la grabación: reabrir renombra '
        'los utilizables y la fila queda correcta',
        (WidgetTester tester) async {
      await pumpController(tester);
      await recordings.insertRecording(
        recordingId: 'rec_a',
        resolution: '720p',
        fps: 30,
        videoBitrateBps: 2500000,
        nowMs: now,
      );
      await recordings.insertRecording(
        recordingId: 'rec_b',
        resolution: '720p',
        fps: 30,
        videoBitrateBps: 2500000,
        nowMs: now,
      );
      recording.detach();
      service.reconciliationResult = doneResult(
        segments: <RecoveredSegmentInfo>[
          segment(
            recordingId: 'rec_a',
            index: 0,
            status: 'ready',
            durationMs: 3000,
            sizeBytes: 900000,
          ),
          segment(
            recordingId: 'rec_a',
            index: 1,
            status: 'partial',
            durationMs: 2000,
            sizeBytes: 600000,
          ),
          segment(
            recordingId: 'rec_b',
            index: 0,
            status: 'corrupt',
            durationMs: 1000,
            sizeBytes: 400000,
          ),
        ],
      );
      recording.attach();
      await tester.pump();

      final rowA = await recordings.byRecordingId('rec_a');
      expect(rowA!.status, 'ready');
      expect(rowA.durationMs, 5000);
      expect(rowA.sizeBytes, 1500000);
      final rowB = await recordings.byRecordingId('rec_b');
      expect(rowB!.status, 'corrupt');
      // `corrupt` no suma duración/tamaño (ningún usable).
      expect(rowB.durationMs, 0);
      expect(rowB.sizeBytes, 0);
    });

    testWidgets('I2/I5 reinicio a mitad de la reconciliación: la '
        'segunda pasada converge sin duplicados ni pérdidas',
        (WidgetTester tester) async {
      await pumpController(tester);
      await recordings.insertRecording(
        recordingId: 'rec_a',
        resolution: '720p',
        fps: 30,
        videoBitrateBps: 2500000,
        nowMs: now,
      );
      service.reconciliationResult = doneResult(
        segments: <RecoveredSegmentInfo>[
          segment(
            recordingId: 'rec_a',
            index: 0,
            status: 'ready',
            durationMs: 3000,
            sizeBytes: 900000,
          ),
        ],
      );
      recording.detach();
      recording.attach();
      await tester.pump();
      final first = await recordings.byRecordingId('rec_a');
      expect(first!.status, 'ready');
      expect(recordings.rows.length, 1);
      expect(recording.orphansReconstructed, 0);

      // Segunda pasada con el mismo payload: mismo estado final,
      // sin duplicar la fila ni re-insertar (huérfanos 0).
      recording.detach();
      recording.attach();
      await tester.pump();
      final second = await recordings.byRecordingId('rec_a');
      expect(second!.status, 'ready');
      expect(second.durationMs, 3000);
      expect(second.sizeBytes, 900000);
      expect(recordings.rows.length, 1);
      expect(recording.orphansReconstructed, 0);
    });

    testWidgets('I3 muerte entre segmentos: los .mp4 cerrados se '
        'clasifican y la fila no queda en recording',
        (WidgetTester tester) async {
      await pumpController(tester);
      await recordings.insertRecording(
        recordingId: 'rec_a',
        resolution: '720p',
        fps: 30,
        videoBitrateBps: 2500000,
        nowMs: now,
      );
      recording.detach();
      service.reconciliationResult = doneResult(
        segments: <RecoveredSegmentInfo>[
          segment(
            recordingId: 'rec_a',
            index: 0,
            status: 'ready',
            durationMs: 60000,
            sizeBytes: 18750000,
          ),
          segment(
            recordingId: 'rec_a',
            index: 1,
            status: 'ready',
            durationMs: 60000,
            sizeBytes: 18750000,
          ),
        ],
      );
      recording.attach();
      await tester.pump();

      final row = await recordings.byRecordingId('rec_a');
      expect(row!.status, 'ready');
      expect(row.durationMs, 120000);
      expect(row.sizeBytes, 37500000);
    });

    testWidgets('I4 segunda grabación intacta: la fila ready no se '
        'toca', (WidgetTester tester) async {
      await pumpController(tester);
      final RecordingRow inserted = await recordings.insertRecording(
        recordingId: 'rec_a',
        resolution: '720p',
        fps: 30,
        videoBitrateBps: 2500000,
        nowMs: now,
      );
      await recordings.updateOnStop(
        recordingId: 'rec_a',
        status: 'ready',
        durationMs: 9999,
        sizeBytes: 111,
        nowMs: now,
      );
      expect(inserted.id, isNotNull);
      recording.detach();
      service.reconciliationResult = doneResult(
        segments: <RecoveredSegmentInfo>[
          segment(
            recordingId: 'rec_a',
            index: 0,
            status: 'ready',
            durationMs: 3000,
            sizeBytes: 900000,
          ),
        ],
      );
      recording.attach();
      await tester.pump();

      final row = await recordings.byRecordingId('rec_a');
      expect(row!.status, 'ready');
      expect(row.durationMs, 9999);
      expect(row.sizeBytes, 111);
      expect(recordings.rows.length, 1);
    });
  });
}
