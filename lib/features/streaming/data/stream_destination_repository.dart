import 'package:sqflite/sqflite.dart';
import 'package:transmiti2/features/streaming/domain/stream_destination.dart';

/// Acceso a la tabla `stream_destinations` (base v2).
///
/// La clave de transmisión NO pasa por aquí: solo se guarda `has_key`.
abstract class StreamDestinationRepository {
  Future<List<StreamDestination>> all();
  Future<StreamDestination?> byId(int id);
  Future<StreamDestination?> active();
  Future<StreamDestination> insertManual({
    required String label,
    required String ingestUrl,
    required int nowMs,
  });
  Future<void> setHasKey(int id, bool hasKey, {required int nowMs});
  Future<void> setActive(int id, {required int nowMs});
  Future<void> delete(int id);
}

String _kindToDb(StreamDestinationKind kind) {
  return switch (kind) {
    StreamDestinationKind.manual => 'manual',
    StreamDestinationKind.facebookGraph => 'facebook_graph',
  };
}

StreamDestinationKind _kindFromDb(String value) {
  return switch (value) {
    'manual' => StreamDestinationKind.manual,
    'facebook_graph' => StreamDestinationKind.facebookGraph,
    _ => throw StateError('Tipo de destino desconocido: $value'),
  };
}

StreamDestination _fromRow(Map<String, Object?> row) {
  return StreamDestination(
    id: row['id'] as int,
    kind: _kindFromDb(row['kind'] as String),
    label: row['label'] as String,
    ingestUrl: row['ingest_url'] as String,
    pageId: row['page_id'] as String?,
    pageName: row['page_name'] as String?,
    hasKey: (row['has_key'] as int) == 1,
    isActive: (row['is_active'] as int) == 1,
    expiresAtMs: row['expires_at_ms'] as int?,
    createdAtMs: row['created_at_ms'] as int,
    updatedAtMs: row['updated_at_ms'] as int,
  );
}

class SqliteStreamDestinationRepository
    implements StreamDestinationRepository {
  SqliteStreamDestinationRepository(this._db);

  final Database _db;

  @override
  Future<List<StreamDestination>> all() async {
    final List<Map<String, Object?>> rows = await _db.query(
      'stream_destinations',
      orderBy: 'id ASC',
    );
    return rows.map(_fromRow).toList();
  }

  @override
  Future<StreamDestination?> byId(int id) async {
    final List<Map<String, Object?>> rows = await _db.query(
      'stream_destinations',
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
  Future<StreamDestination?> active() async {
    final List<Map<String, Object?>> rows = await _db.query(
      'stream_destinations',
      where: 'is_active = 1',
      limit: 1,
    );
    if (rows.isEmpty) {
      return null;
    }
    return _fromRow(rows.first);
  }

  @override
  Future<StreamDestination> insertManual({
    required String label,
    required String ingestUrl,
    required int nowMs,
  }) async {
    if (label.trim().isEmpty) {
      throw ArgumentError('La etiqueta del destino no puede estar vacía.');
    }
    if (!StreamDestination.isValidIngestUrl(ingestUrl)) {
      throw ArgumentError('La URL de ingesta debe empezar por rtmps://.');
    }
    final int id = await _db.insert('stream_destinations', <String, Object?>{
      'kind': _kindToDb(StreamDestinationKind.manual),
      'label': label.trim(),
      'ingest_url': ingestUrl.trim(),
      'page_id': null,
      'page_name': null,
      'has_key': 0,
      'is_active': 0,
      'expires_at_ms': null,
      'created_at_ms': nowMs,
      'updated_at_ms': nowMs,
    });
    final StreamDestination? created = await byId(id);
    return created!;
  }

  @override
  Future<void> setHasKey(int id, bool hasKey, {required int nowMs}) async {
    await _db.update(
      'stream_destinations',
      <String, Object?>{'has_key': hasKey ? 1 : 0, 'updated_at_ms': nowMs},
      where: 'id = ?',
      whereArgs: <Object?>[id],
    );
  }

  @override
  Future<void> setActive(int id, {required int nowMs}) async {
    // Solo un destino activo: se desactiva el resto en la misma transacción.
    await _db.transaction((Transaction txn) async {
      await txn.update(
        'stream_destinations',
        <String, Object?>{'is_active': 0, 'updated_at_ms': nowMs},
      );
      final int updated = await txn.update(
        'stream_destinations',
        <String, Object?>{'is_active': 1, 'updated_at_ms': nowMs},
        where: 'id = ?',
        whereArgs: <Object?>[id],
      );
      if (updated != 1) {
        throw StateError('Destino no encontrado: $id');
      }
    });
  }

  @override
  Future<void> delete(int id) async {
    await _db.delete(
      'stream_destinations',
      where: 'id = ?',
      whereArgs: <Object?>[id],
    );
  }
}
