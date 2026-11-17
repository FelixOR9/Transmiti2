import 'package:transmiti2/core/time.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/data/stream_destination_repository.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';
import 'package:transmiti2/features/streaming/domain/stream_destination.dart';

/// Coordinación entre SQLite (destinos) y el almacén seguro (claves).
///
/// Flujo sin huérfanos al guardar un destino manual:
/// 1. Insertar el destino en SQLite con `hasKey = false`.
/// 2. Guardar la clave cifrada en Android (Tink + Keystore).
/// 3. Si el paso 2 falla, borrar el destino y devolver el error.
/// 4. Si el paso 2 funciona, marcar `hasKey = true`.
///
/// Al borrar un destino se borra primero su clave cifrada y después la
/// fila, para no dejar claves huérfanas. La clave nunca se guarda en
/// ningún campo de esta clase.
class StreamingController {
  StreamingController({
    required StreamDestinationRepository destinations,
    required StreamingService service,
    NowMs? nowMs,
  }) : _destinations = destinations,
       _service = service,
       _nowMs = nowMs ?? systemNowMs;

  final StreamDestinationRepository _destinations;
  final StreamingService _service;
  final NowMs _nowMs;

  Future<List<StreamDestination>> listDestinations() =>
      _destinations.all();

  Future<StreamDestination?> activeDestination() => _destinations.active();

  Future<StreamStatus> getStatus() => _service.getStatus();

  Future<({bool camera, bool microphone})> checkPermissions() =>
      _service.checkPermissions();

  Future<StreamDestination> saveManualDestination({
    required String label,
    required String ingestUrl,
    required String key,
  }) async {
    if (key.isEmpty) {
      throw const StreamingException(StreamContract.emptyKey);
    }
    final StreamDestination created = await _destinations.insertManual(
      label: label,
      ingestUrl: ingestUrl,
      nowMs: _nowMs(),
    );
    try {
      await _service.saveStreamKey(destinationId: created.id, key: key);
    } catch (e) {
      await _destinations.delete(created.id);
      rethrow;
    }
    await _destinations.setHasKey(created.id, true, nowMs: _nowMs());
    final StreamDestination? saved = await _destinations.byId(created.id);
    return saved!;
  }

  Future<void> setActiveDestination(int id) async {
    await _destinations.setActive(id, nowMs: _nowMs());
  }

  Future<void> deleteDestination(int id) async {
    await _service.deleteStreamKey(destinationId: id);
    await _destinations.delete(id);
  }
}
