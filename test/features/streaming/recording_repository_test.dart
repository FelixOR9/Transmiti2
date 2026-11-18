import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:transmiti2/core/db/app_database.dart';
import 'package:transmiti2/features/streaming/data/recording_repository.dart';

/// Pruebas del repositorio de grabaciones con SQLite real (en el equipo
/// de pruebas, sin teléfono). Sin migración en 3.1.2: la tabla
/// `recordings` ya existe en v2.
void main() {
  sqfliteFfiInit();

  late Directory dir;
  late String dbPath;
  Database? db;

  setUp(() async {
    dir = await Directory.systemTemp.createTemp('transmiti2_rec_');
    dbPath = p.join(dir.path, 'test.db');
  });

  tearDown(() async {
    await db?.close();
    db = null;
    await dir.delete(recursive: true);
  });

  Future<SqliteRecordingRepository> open() async {
    final Database opened = await AppDatabase.open(
      path: dbPath,
      factory: databaseFactoryFfi,
      targetVersion: AppDatabase.version,
    );
    db = opened;
    return SqliteRecordingRepository(
      opened,
      recordingsDirPath: '/docs/recordings',
    );
  }

  test('insertRecording crea la fila en recording con el final previsto', () async {
    final SqliteRecordingRepository repo = await open();

    final RecordingRow row = await repo.insertRecording(
      recordingId: 'rec_20261118_120000_ab12',
      resolution: '720p',
      fps: 30,
      videoBitrateBps: 2500000,
      nowMs: 1000,
    );

    expect(row.status, 'recording');
    expect(row.fileName, 'rec_20261118_120000_ab12_final.mp4');
    expect(row.filePath, '/docs/recordings/rec_20261118_120000_ab12_final.mp4');
    // Sin partido asociado: `match_id` es NULL (con un id inexistente
    // la FK `REFERENCES matches(id)` rechaza el insert).
    expect(row.matchId, isNull);
    expect(row.resolution, '720p');
    expect(row.fps, 30);
    expect(row.videoBitrateBps, 2500000);
    expect(row.durationMs, isNull);
    expect(row.sizeBytes, isNull);
    expect(row.recordingId, 'rec_20261118_120000_ab12');
  });

  test('updateOnStop marca ready con duración y tamaño medidos', () async {
    final SqliteRecordingRepository repo = await open();
    await repo.insertRecording(
      recordingId: 'rec_20261118_120000_ab12',
      resolution: '720p',
      fps: 30,
      videoBitrateBps: 2500000,
      nowMs: 1000,
    );

    await repo.updateOnStop(
      recordingId: 'rec_20261118_120000_ab12',
      status: 'ready',
      durationMs: 12000,
      sizeBytes: 3750000,
      nowMs: 2000,
    );

    final RecordingRow? row = await repo.byRecordingId(
      'rec_20261118_120000_ab12',
    );
    expect(row!.status, 'ready');
    expect(row.durationMs, 12000);
    expect(row.sizeBytes, 3750000);
    expect(row.updatedAtMs, 2000);
  });

  test('updateOnStop marca corrupt cuando no hay segmento utilizable', () async {
    final SqliteRecordingRepository repo = await open();
    await repo.insertRecording(
      recordingId: 'rec_20261118_120000_ab12',
      resolution: '720p',
      fps: 30,
      videoBitrateBps: 2500000,
      nowMs: 1000,
    );

    await repo.updateOnStop(
      recordingId: 'rec_20261118_120000_ab12',
      status: 'corrupt',
      durationMs: 0,
      sizeBytes: 0,
      nowMs: 2000,
    );

    final RecordingRow? row = await repo.byRecordingId(
      'rec_20261118_120000_ab12',
    );
    expect(row!.status, 'corrupt');
  });

  test('updateOnStop rechaza partial: el CHECK no lo admite', () async {
    final SqliteRecordingRepository repo = await open();
    await repo.insertRecording(
      recordingId: 'rec_20261118_120000_ab12',
      resolution: '720p',
      fps: 30,
      videoBitrateBps: 2500000,
      nowMs: 1000,
    );

    await expectLater(
      repo.updateOnStop(
        recordingId: 'rec_20261118_120000_ab12',
        status: 'partial',
        nowMs: 2000,
      ),
      throwsArgumentError,
    );
  });

  test('interrupted devuelve solo recording y finalizing', () async {
    final SqliteRecordingRepository repo = await open();
    await repo.insertRecording(
      recordingId: 'rec_20261118_110000_aa11',
      resolution: '720p',
      fps: 30,
      videoBitrateBps: 2500000,
      nowMs: 1000,
    );
    await repo.insertRecording(
      recordingId: 'rec_20261118_120000_ab12',
      resolution: '720p',
      fps: 30,
      videoBitrateBps: 2500000,
      nowMs: 1000,
    );
    await repo.updateOnStop(
      recordingId: 'rec_20261118_120000_ab12',
      status: 'ready',
      durationMs: 12000,
      sizeBytes: 3750000,
      nowMs: 2000,
    );

    final List<RecordingRow> interrupted = await repo.interrupted();

    expect(interrupted.length, 1);
    expect(interrupted.first.recordingId, 'rec_20261118_110000_aa11');
  });

  test('byFileName encuentra la fila por el nombre del archivo', () async {
    final SqliteRecordingRepository repo = await open();
    await repo.insertRecording(
      recordingId: 'rec_20261118_120000_ab12',
      resolution: '720p',
      fps: 30,
      videoBitrateBps: 2500000,
      nowMs: 1000,
    );

    expect(
      (await repo.byFileName('rec_20261118_120000_ab12_final.mp4'))
          ?.recordingId,
      'rec_20261118_120000_ab12',
    );
    expect(
      await repo.byFileName('rec_20261118_120000_ab12_seg000.mp4'),
      isNull,
    );
  });
}
