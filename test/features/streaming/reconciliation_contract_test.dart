import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:transmiti2/core/db/app_database.dart';
import 'package:transmiti2/features/streaming/data/recording_repository.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';

/// Contrato de reconciliación (subetapa 0, grupo 0-d2; plan §5):
/// la ruta canónica es la misma en Dart y Kotlin (U1) y el payload
/// del canal tiene la forma fijada en ambos lados a la vez (plan
/// §6 A4): resumen por grupo, segmentos con `pending` y tokens por
/// grupo.
void main() {
  sqfliteFfiInit();

  group('U1 ruta canónica', () {
    late Directory dir;
    late String dbPath;
    Database? db;

    setUp(() async {
      dir = await Directory.systemTemp.createTemp('transmiti2_u1_');
      dbPath = p.join(dir.path, 'test.db');
    });

    tearDown(() async {
      await db?.close();
      db = null;
      await dir.delete(recursive: true);
    });

    test('Dart y Kotlin usan la misma carpeta y la misma convención '
        'de nombres', () async {
      // La mitad fuerte de U1: el repositorio construye `file_path`
      // uniendo la ruta canónica inyectada (`<filesDir>/recordings/`,
      // resuelta por el nativo) con `<id>_final.mp4`, la misma
      // convención que `RecordingFileStore.finalFileName` en Kotlin.
      // Sin fallback: la ruta viene del canal, no de `path_provider`.
      final Database opened = await AppDatabase.open(
        path: dbPath,
        factory: databaseFactoryFfi,
        targetVersion: AppDatabase.version,
      );
      db = opened;
      final SqliteRecordingRepository repo = SqliteRecordingRepository(
        opened,
        recordingsDirPath: '/filesDir/recordings',
      );

      final RecordingRow row = await repo.insertRecording(
        recordingId: 'rec_20261118_120000_ab12',
        resolution: '720p',
        fps: 30,
        videoBitrateBps: 2500000,
        nowMs: 1000,
      );
      expect(
        row.filePath,
        '/filesDir/recordings/rec_20261118_120000_ab12_final.mp4',
      );
      final RecordingRow reconciled = await repo.insertReconciledRow(
        recordingId: 'rec_20261118_110000_zz99',
        status: 'ready',
        durationMs: 5000,
        sizeBytes: 1500000,
        nowMs: 2000,
      );
      expect(
        reconciled.filePath,
        '/filesDir/recordings/rec_20261118_110000_zz99_final.mp4',
      );
    });

    test('los nombres de método del canal son los que Kotlin expone',
        () {
      // La mitad documental de U1: los nombres del cable se fijan
      // aquí como literales. Si un lado renombra, este test rompe y
      // el renombre debe reflejarse en el otro lado (el espejo vive
      // en `StreamContract.kt`).
      expect(StreamContract.reconcileRecordings, 'reconcileRecordings');
      expect(
        StreamContract.getReconciliationState,
        'getReconciliationState',
      );
      expect(StreamContract.getRecordingsDir, 'getRecordingsDir');
    });
  });

  group('Payload de reconciliación', () {
    test('ReconciliationSummary hace round-trip con pendientes y '
        'tokens por grupo', () {
      const ReconciliationSummary summary = ReconciliationSummary(
        state: ReconciliationState.done,
        runId: 'run-7',
        startedAtMs: 1000,
        finishedAtMs: 1500,
        durationMs: 500,
        groupsTotal: 2,
        recordingsReconciled: 1,
        segmentsRenamed: 1,
        segmentsClassified: 2,
        segmentsTrusted: 1,
        segmentsVerified: 1,
        orphansReconstructed: 1,
        duplicatesQuarantined: 1,
        pending: 1,
        segmentsPending: <PendingSegmentRef>[
          PendingSegmentRef(
            recordingId: 'rec_2',
            segmentIndex: 0,
            fileName: 'rec_2_seg000.tmp.mp4',
            reason: 'timeout',
          ),
        ],
        skippedActive: 1,
        groupsSkippedActive: <String>['rec_activa'],
        groupsFailed: <String>[],
        reservationTokens: <String, int>{'rec_1': 3},
        foreignReleaseRejected: 2,
        orphanWriteTestFiles: 1,
        conflicts: 1,
      );

      final ReconciliationSummary parsed =
          ReconciliationSummary.fromMap(summary.toMap());

      expect(parsed.state, ReconciliationState.done);
      expect(parsed.runId, 'run-7');
      expect(parsed.durationMs, 500);
      expect(parsed.groupsTotal, 2);
      expect(parsed.recordingsReconciled, 1);
      expect(parsed.segmentsRenamed, 1);
      expect(parsed.segmentsClassified, 2);
      expect(parsed.orphansReconstructed, 1);
      expect(parsed.duplicatesQuarantined, 1);
      expect(parsed.pending, 1);
      expect(parsed.segmentsPending.length, 1);
      expect(parsed.segmentsPending.first.recordingId, 'rec_2');
      expect(parsed.segmentsPending.first.reason, 'timeout');
      expect(parsed.skippedActive, 1);
      expect(parsed.groupsSkippedActive, <String>['rec_activa']);
      expect(parsed.reservationTokens, <String, int>{'rec_1': 3});
      expect(parsed.foreignReleaseRejected, 2);
      expect(parsed.orphanWriteTestFiles, 1);
      expect(parsed.conflicts, 1);
    });

    test('ReconciliationSummary con mapa incompleto no inventa valores',
        () {
      final ReconciliationSummary parsed =
          ReconciliationSummary.fromMap(<Object?, Object?>{});

      expect(parsed.state, ReconciliationState.notStarted);
      expect(parsed.runId, isNull);
      expect(parsed.durationMs, 0);
      expect(parsed.recordingsReconciled, 0);
      expect(parsed.pending, 0);
      expect(parsed.segmentsPending, isEmpty);
      expect(parsed.reservationTokens, isEmpty);
    });

    test('ReconciliationResult parsea segmentos con pending y el '
        'motivo en FAILED', () {
      final ReconciliationResult result = ReconciliationResult.fromMap(
        <Object?, Object?>{
          'state': 'DONE',
          'summary': <Object?, Object?>{
            'state': 'DONE',
            'runId': 'run-7',
            'recordingsReconciled': 1,
            'segmentsRenamed': 0,
            'pending': 1,
            'skipped_active': 0,
            'segmentsPending': <Object?>[
              <Object?, Object?>{
                'recordingId': 'rec_2',
                'segmentIndex': 0,
                'fileName': 'rec_2_seg000.tmp.mp4',
                'reason': 'timeout',
              },
            ],
          },
          'segments': <Object?>[
            <Object?, Object?>{
              'recordingId': 'rec_1',
              'segmentIndex': 0,
              'fileName': 'rec_1_seg000.mp4',
              'filePath': '/r/rec_1_seg000.mp4',
              'status': 'ready',
              'durationMs': 3000,
              'sizeBytes': 900000,
            },
            <Object?, Object?>{
              'recordingId': 'rec_2',
              'segmentIndex': 0,
              'fileName': 'rec_2_seg000.tmp.mp4',
              'filePath': '/r/rec_2_seg000.tmp.mp4',
              'status': 'pending',
              'durationMs': null,
              'sizeBytes': 500000,
              'reason': 'timeout',
            },
          ],
          'groupsSkippedActive': <Object?>[],
          'groupsFailed': <Object?>[],
          'unknownFiles': <Object?>['raro.bin'],
          'missingFiles': <Object?>[],
        },
      );

      expect(result.state, ReconciliationState.done);
      expect(result.summary.runId, 'run-7');
      expect(result.segments.length, 2);
      expect(result.usableSegments.length, 1);
      expect(result.pendingSegments.length, 1);
      expect(result.pendingSegments.first.recordingId, 'rec_2');
      expect(result.pendingSegments.first.usable, isFalse);
      expect(result.unknownFiles, <String>['raro.bin']);
      expect(result.errorCode, isNull);

      final ReconciliationResult failed = ReconciliationResult.fromMap(
        <Object?, Object?>{
          'state': 'FAILED',
          'summary': null,
          'segments': <Object?>[],
          'groupsSkippedActive': <Object?>[],
          'groupsFailed': <Object?>[],
          'unknownFiles': <Object?>[],
          'missingFiles': <Object?>[],
          'errorCode': 'reconciliation_failed',
          'errorMessage': 'La reconciliación falló.',
        },
      );
      expect(failed.state, ReconciliationState.failed);
      expect(failed.segments, isEmpty);
      expect(failed.summary.state, ReconciliationState.failed);
      expect(failed.errorCode, 'reconciliation_failed');
    });
  });
}
