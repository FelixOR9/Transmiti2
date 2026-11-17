import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/data/stream_destination_repository.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';
import 'package:transmiti2/features/streaming/domain/stream_destination.dart';

/// Servicio de transmisión falso, solo para pruebas: guarda las claves en
/// memoria en vez de en el almacén seguro de Android. Nunca se usa en la app.
class FakeStreamingService implements StreamingService {
  final Map<int, String> keys = <int, String>{};

  @override
  Future<StreamStatus> getStatus() async {
    return const StreamStatus(
      foregroundServiceAvailable: false,
      cameraGranted: false,
      microphoneGranted: false,
      streamingSupported: false,
    );
  }

  @override
  Future<({bool camera, bool microphone})> checkPermissions() async {
    return (camera: false, microphone: false);
  }

  @override
  Future<void> saveStreamKey({
    required int destinationId,
    required String key,
  }) async {
    if (key.isEmpty) {
      throw const StreamingException(StreamContract.emptyKey);
    }
    keys[destinationId] = key;
  }

  @override
  Future<void> deleteStreamKey({required int destinationId}) async {
    keys.remove(destinationId);
  }

  @override
  Future<bool> hasStreamKey({required int destinationId}) async {
    return keys.containsKey(destinationId);
  }

  @override
  Future<void> startPreview() async {
    throw const StreamingException(StreamContract.notAvailableInPhase30);
  }

  @override
  Future<void> startStream() async {
    throw const StreamingException(StreamContract.notAvailableInPhase30);
  }

  @override
  Future<void> startRecording() async {
    throw const StreamingException(StreamContract.notAvailableInPhase30);
  }

  @override
  Future<void> stopAll() async {
    throw const StreamingException(StreamContract.notAvailableInPhase30);
  }
}

/// Repositorio de destinos en memoria, solo para pruebas.
class InMemoryStreamDestinationRepository
    implements StreamDestinationRepository {
  final List<StreamDestination> _destinations = <StreamDestination>[];
  int _nextId = 1;

  @override
  Future<List<StreamDestination>> all() async {
    return List<StreamDestination>.unmodifiable(_destinations);
  }

  @override
  Future<StreamDestination?> byId(int id) async {
    for (final StreamDestination d in _destinations) {
      if (d.id == id) {
        return d;
      }
    }
    return null;
  }

  @override
  Future<StreamDestination?> active() async {
    for (final StreamDestination d in _destinations) {
      if (d.isActive) {
        return d;
      }
    }
    return null;
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
    final StreamDestination created = StreamDestination(
      id: _nextId++,
      kind: StreamDestinationKind.manual,
      label: label.trim(),
      ingestUrl: ingestUrl.trim(),
      hasKey: false,
      isActive: false,
      createdAtMs: nowMs,
      updatedAtMs: nowMs,
    );
    _destinations.add(created);
    return created;
  }

  @override
  Future<void> setHasKey(int id, bool hasKey, {required int nowMs}) async {
    final int index = _destinations.indexWhere((d) => d.id == id);
    if (index >= 0) {
      _destinations[index] = _destinations[index].copyWith(
        hasKey: hasKey,
        updatedAtMs: nowMs,
      );
    }
  }

  @override
  Future<void> setActive(int id, {required int nowMs}) async {
    bool found = false;
    for (int i = 0; i < _destinations.length; i++) {
      final bool active = _destinations[i].id == id;
      if (active) {
        found = true;
      }
      _destinations[i] = _destinations[i].copyWith(
        isActive: active,
        updatedAtMs: nowMs,
      );
    }
    if (!found) {
      throw StateError('Destino no encontrado: $id');
    }
  }

  @override
  Future<void> delete(int id) async {
    _destinations.removeWhere((d) => d.id == id);
  }
}
