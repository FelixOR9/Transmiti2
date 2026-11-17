import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/streaming/application/streaming_controller.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';
import 'package:transmiti2/features/streaming/domain/stream_destination.dart';

import '../../support/fake_streaming.dart';

const String rtmpsUrl = 'rtmps://live-api-s.facebook.com:443/rtmp/';

class _FailingKeyService extends FakeStreamingService {
  @override
  Future<void> saveStreamKey({
    required int destinationId,
    required String key,
  }) async {
    throw const StreamingException(StreamContract.secureStoreUnavailable);
  }
}

void main() {
  group('destino manual', () {
    test('la URL de ingesta debe ser RTMPS', () {
      expect(StreamDestination.isValidIngestUrl(rtmpsUrl), isTrue);
      expect(
        StreamDestination.isValidIngestUrl(
          'rtmp://live-api-s.facebook.com:443/rtmp/',
        ),
        isFalse,
      );
      expect(
        StreamDestination.isValidIngestUrl('https://example.com/live'),
        isFalse,
      );
      expect(StreamDestination.isValidIngestUrl(''), isFalse);
      expect(StreamDestination.isValidIngestUrl('rtmps://'), isFalse);
    });

    test('guardar destino crea la fila y marca la clave como guardada', () async {
      final FakeStreamingService service = FakeStreamingService();
      final StreamingController controller = StreamingController(
        destinations: InMemoryStreamDestinationRepository(),
        service: service,
        nowMs: () => 1000,
      );
      final StreamDestination saved = await controller.saveManualDestination(
        label: 'Facebook Live',
        ingestUrl: rtmpsUrl,
        key: 'clave-de-prueba',
      );
      expect(saved.kind, StreamDestinationKind.manual);
      expect(saved.hasKey, isTrue);
      expect(saved.isActive, isFalse);
      expect(service.keys[saved.id], 'clave-de-prueba');
      expect((await controller.listDestinations()).length, 1);
    });

    test('si falla el almacén seguro, no queda destino huérfano', () async {
      final StreamingController controller = StreamingController(
        destinations: InMemoryStreamDestinationRepository(),
        service: _FailingKeyService(),
        nowMs: () => 1000,
      );
      await expectLater(
        controller.saveManualDestination(
          label: 'Facebook Live',
          ingestUrl: rtmpsUrl,
          key: 'clave-de-prueba',
        ),
        throwsA(isA<StreamingException>()),
      );
      expect(await controller.listDestinations(), isEmpty);
    });

    test('la clave vacía no crea destino', () async {
      final StreamingController controller = StreamingController(
        destinations: InMemoryStreamDestinationRepository(),
        service: FakeStreamingService(),
        nowMs: () => 1000,
      );
      await expectLater(
        controller.saveManualDestination(
          label: 'Facebook Live',
          ingestUrl: rtmpsUrl,
          key: '',
        ),
        throwsA(isA<StreamingException>()),
      );
      expect(await controller.listDestinations(), isEmpty);
    });

    test('la URL sin RTMPS no crea destino', () async {
      final StreamingController controller = StreamingController(
        destinations: InMemoryStreamDestinationRepository(),
        service: FakeStreamingService(),
        nowMs: () => 1000,
      );
      await expectLater(
        controller.saveManualDestination(
          label: 'Facebook Live',
          ingestUrl: 'rtmp://inseguro.example.com/live',
          key: 'clave-de-prueba',
        ),
        throwsA(isA<ArgumentError>()),
      );
      expect(await controller.listDestinations(), isEmpty);
    });

    test('borrar un destino borra también su clave', () async {
      final FakeStreamingService service = FakeStreamingService();
      final StreamingController controller = StreamingController(
        destinations: InMemoryStreamDestinationRepository(),
        service: service,
        nowMs: () => 1000,
      );
      final StreamDestination saved = await controller.saveManualDestination(
        label: 'Facebook Live',
        ingestUrl: rtmpsUrl,
        key: 'clave-de-prueba',
      );
      await controller.deleteDestination(saved.id);
      expect(service.keys.containsKey(saved.id), isFalse);
      expect(await controller.listDestinations(), isEmpty);
    });

    test('solo un destino puede estar activo', () async {
      final StreamingController controller = StreamingController(
        destinations: InMemoryStreamDestinationRepository(),
        service: FakeStreamingService(),
        nowMs: () => 1000,
      );
      final StreamDestination first = await controller.saveManualDestination(
        label: 'Uno',
        ingestUrl: rtmpsUrl,
        key: 'clave-uno',
      );
      final StreamDestination second = await controller.saveManualDestination(
        label: 'Dos',
        ingestUrl: rtmpsUrl,
        key: 'clave-dos',
      );
      await controller.setActiveDestination(first.id);
      expect((await controller.activeDestination())?.id, first.id);
      await controller.setActiveDestination(second.id);
      expect((await controller.activeDestination())?.id, second.id);
      final List<StreamDestination> all = await controller.listDestinations();
      expect(all.where((d) => d.isActive).length, 1);
    });
  });

  group('contrato de la Fase 3.0', () {
    test('el AAD canónico ata la clave a su destino', () {
      expect(streamKeyAad(1), 'transmiti2:stream-key:v1:1');
      expect(streamKeyAad(2), 'transmiti2:stream-key:v1:2');
      expect(streamKeyAad(1), isNot(streamKeyAad(2)));
    });

    test('el servicio falso no ofrece video en la Fase 3.0', () async {
      final FakeStreamingService service = FakeStreamingService();
      final StreamStatus status = await service.getStatus();
      expect(status.foregroundServiceAvailable, isFalse);
      expect(status.streamingSupported, isFalse);
      await expectLater(
        service.startPreview(),
        throwsA(
          isA<StreamingException>().having(
            (e) => e.code,
            'código',
            StreamContract.notAvailableInPhase30,
          ),
        ),
      );
      await expectLater(
        service.startStream(),
        throwsA(isA<StreamingException>()),
      );
      await expectLater(
        service.startRecording(),
        throwsA(isA<StreamingException>()),
      );
    });
  });
}
