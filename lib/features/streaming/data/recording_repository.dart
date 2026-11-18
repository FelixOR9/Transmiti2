import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

/// Fila de la tabla `recordings` (base v2, sin migración en 3.1.2).
///
/// Una fila por GRABACIÓN (no por segmento): se crea al iniciar
/// (`status = 'recording'`, `file_path` = ruta prevista del final
/// fusionado `<recordingId>_final.mp4`) y se actualiza al detener
/// (`status = 'ready'` o `'corrupt'`, con `duration_ms` y `size_bytes`
/// medidos). Los segmentos se identifican por convención de nombres
/// (§5.1 del plan) y sus metadatos se derivan de los archivos (§5.3);
/// la fila `ready` exige al menos un segmento utilizable (§7.3).
class RecordingRow {
  const RecordingRow({
    required this.id,
    this.matchId,
    required this.filePath,
    required this.fileName,
    required this.resolution,
    required this.fps,
    required this.videoBitrateBps,
    this.durationMs,
    this.sizeBytes,
    required this.status,
    required this.createdAtMs,
    required this.updatedAtMs,
  });

  final int id;
  final int? matchId;
  final String filePath;
  final String fileName;
  final String resolution;
  final int fps;
  final int videoBitrateBps;
  final int? durationMs;
  final int? sizeBytes;

  /// `recording`, `finalizing`, `ready`, `corrupt` o `deleted`
  /// (el `CHECK` de la tabla no admite `partial`: un segmento
  /// `partial` es utilizable y la fila queda `ready`).
  final String status;
  final int createdAtMs;
  final int updatedAtMs;

  /// `recordingId` de la fila (el `file_name` es `<id>_final.mp4`).
  String get recordingId => fileName.replaceAll('_final.mp4', '');

  RecordingRow copyWith({
    int? durationMs,
    int? sizeBytes,
    String? status,
    int? updatedAtMs,
  }) {
    return RecordingRow(
      id: id,
      matchId: matchId,
      filePath: filePath,
      fileName: fileName,
      resolution: resolution,
      fps: fps,
      videoBitrateBps: videoBitrateBps,
      durationMs: durationMs ?? this.durationMs,
      sizeBytes: sizeBytes ?? this.sizeBytes,
      status: status ?? this.status,
      createdAtMs: createdAtMs,
      updatedAtMs: updatedAtMs ?? this.updatedAtMs,
    );
  }
}

/// Acceso a la tabla `recordings` (base v2, Fase B, 3.1.2).
///
/// Sin migración: la tabla ya existe desde v2. El repositorio solo
/// escribe la fila de la grabación; nunca toca los archivos MP4.
abstract class RecordingRepository {
  /// Crea la fila al iniciar la grabación (`status = 'recording'`).
  Future<RecordingRow> insertRecording({
    required String recordingId,
    int? matchId,
    required String resolution,
    required int fps,
    required int videoBitrateBps,
    required int nowMs,
  });

  /// Actualiza la fila al detener la grabación (`ready` o `corrupt`,
  /// con duración y tamaño medidos). `status` debe ser uno de los
  /// admitidos por el `CHECK` de la tabla.
  Future<void> updateOnStop({
    required String recordingId,
    required String status,
    int? durationMs,
    int? sizeBytes,
    required int nowMs,
  });

  /// Filas de grabaciones interrumpidas (`recording` o `finalizing`):
  /// la recuperación las clasifica al arrancar (plan §7.3).
  Future<List<RecordingRow>> interrupted();

  Future<RecordingRow?> byRecordingId(String recordingId);
  Future<RecordingRow?> byFileName(String fileName);
}

/// Estados admitidos por el `CHECK` de la tabla `recordings`.
const Set<String> recordingRowStatuses = <String>{
  'recording',
  'finalizing',
  'ready',
  'corrupt',
  'deleted',
};

RecordingRow _fromRow(Map<String, Object?> row) {
  return RecordingRow(
    id: row['id'] as int,
    matchId: row['match_id'] as int?,
    filePath: row['file_path'] as String,
    fileName: row['file_name'] as String,
    resolution: row['resolution'] as String,
    fps: row['fps'] as int,
    videoBitrateBps: row['video_bitrate_bps'] as int,
    durationMs: row['duration_ms'] as int?,
    sizeBytes: row['size_bytes'] as int?,
    status: row['status'] as String,
    createdAtMs: row['created_at_ms'] as int,
    updatedAtMs: row['updated_at_ms'] as int,
  );
}

class SqliteRecordingRepository implements RecordingRepository {
  SqliteRecordingRepository(this._db, {required this.recordingsDirPath});

  final Database _db;

  /// Carpeta `<documents>/recordings/` (para la ruta prevista del
  /// final fusionado `<recordingId>_final.mp4`, plan §8.1).
  final String recordingsDirPath;

  @override
  Future<RecordingRow> insertRecording({
    required String recordingId,
    int? matchId,
    required String resolution,
    required int fps,
    required int videoBitrateBps,
    required int nowMs,
  }) async {
    final String fileName = '${recordingId}_final.mp4';
    final int id = await _db.insert('recordings', <String, Object?>{
      'match_id': matchId,
      'stream_session_id': null,
      'file_path': p.join(recordingsDirPath, fileName),
      'file_name': fileName,
      'resolution': resolution,
      'fps': fps,
      'video_bitrate_bps': videoBitrateBps,
      'duration_ms': null,
      'size_bytes': null,
      'status': 'recording',
      'created_at_ms': nowMs,
      'updated_at_ms': nowMs,
    });
    final RecordingRow? created = await byId(id);
    return created!;
  }

  Future<RecordingRow?> byId(int id) async {
    final List<Map<String, Object?>> rows = await _db.query(
      'recordings',
      where: 'id = ?',
      whereArgs: <Object?>[id],
      limit: 1,
    );
    if (rows.isEmpty) {
      return null;
    }
    return _fromRow(rows.first);
  }

  @override
  Future<void> updateOnStop({
    required String recordingId,
    required String status,
    int? durationMs,
    int? sizeBytes,
    required int nowMs,
  }) async {
    if (!recordingRowStatuses.contains(status)) {
      throw ArgumentError('Estado de grabación no admitido: $status');
    }
    await _db.update(
      'recordings',
      <String, Object?>{
        'status': status,
        'duration_ms': durationMs,
        'size_bytes': sizeBytes,
        'updated_at_ms': nowMs,
      },
      where: 'file_name = ?',
      whereArgs: <Object?>['${recordingId}_final.mp4'],
    );
  }

  @override
  Future<List<RecordingRow>> interrupted() async {
    final List<Map<String, Object?>> rows = await _db.query(
      'recordings',
      where: "status IN ('recording', 'finalizing')",
      orderBy: 'id ASC',
    );
    return rows.map(_fromRow).toList();
  }

  @override
  Future<RecordingRow?> byRecordingId(String recordingId) async {
    return byFileName('${recordingId}_final.mp4');
  }

  @override
  Future<RecordingRow?> byFileName(String fileName) async {
    final List<Map<String, Object?>> rows = await _db.query(
      'recordings',
      where: 'file_name = ?',
      whereArgs: <Object?>[fileName],
      limit: 1,
    );
    if (rows.isEmpty) {
      return null;
    }
    return _fromRow(rows.first);
  }
}
