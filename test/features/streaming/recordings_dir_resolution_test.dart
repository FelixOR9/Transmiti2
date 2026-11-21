import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';

import '../../support/fake_streaming.dart';

/// Resolución de la ruta canónica (subetapa 0, grupo 0-b): el wrapper
/// `AppServices.resolveRecordingsDir` con reintentos, timeout y
/// clasificación. Los tiempos se inyectan cortos para que las pruebas
/// sean rápidas; los valores de producción (3 s, 250/500 ms) los cubre
/// U59 en 0-e.
void main() {
  const Duration fastTimeout = Duration(milliseconds: 50);
  const List<Duration> fastDelays = <Duration>[
    Duration(milliseconds: 1),
    Duration(milliseconds: 1),
  ];


  Future<StreamingException> captureThrow(
    Future<String> Function() run,
  ) async {
    try {
      await run();
    } on StreamingException catch (e) {
      return e;
    }
    fail('Se esperaba StreamingException');
  }

  Future<String> resolve(
    FakeStreamingService service, {
    Duration attemptTimeout = fastTimeout,
    List<Duration> retryDelays = fastDelays,
  }) {
    return AppServices.resolveRecordingsDir(
      service,
      attemptTimeout: attemptTimeout,
      retryDelays: retryDelays,
    );
  }

  test('U50 MissingPluginException x3 da recordings_channel_unavailable', () async {
    final FakeStreamingService service = FakeStreamingService()
      ..recordingsDirScript = <Object?>[
        MissingPluginException(),
        MissingPluginException(),
        MissingPluginException(),
      ];
    final StreamingException thrown =
        await captureThrow(() => resolve(service));
    expect(thrown.code, StreamContract.recordingsChannelUnavailable);
    expect(
      thrown.message,
      'No se pudo contactar con el sistema de grabaciones del teléfono.',
    );
    expect(thrown.details?['stage'], 'missing_plugin');
    expect(thrown.details?['attempts'], 3);
    expect(thrown.details?['lastErrorCode'], isNull);
    expect(thrown.details?['exceptionClass'], 'MissingPluginException');
    expect(service.getRecordingsDirCalls, 3);
    expect(service.getRecordingsDirAttempts, <int>[1, 2, 3]);
  });

  test('U51 transitoria x2 y exito a la 3a registra la ruta', () async {
    final FakeStreamingService service = FakeStreamingService()
      ..recordingsDirScript = <Object?>[
        PlatformException(code: 'camera_unavailable'),
        PlatformException(code: 'camera_unavailable'),
        '/fake/recordings',
      ];
    final String path = await resolve(service);
    expect(path, '/fake/recordings');
    expect(service.getRecordingsDirCalls, 3);
    expect(service.getRecordingsDirAttempts, <int>[1, 2, 3]);
  });

  test('U52 fallo de disco es duro inmediato, 1 intento total', () async {
    final FakeStreamingService service = FakeStreamingService()
      ..recordingsDirScript = <Object?>[
        const StreamingException(
          StreamContract.recordingsDirUnavailable,
          'No se pudo preparar la carpeta de grabaciones.',
          <String, Object?>{'stage': 'mkdirs_failed'},
        ),
      ];
    final StreamingException thrown =
        await captureThrow(() => resolve(service));
    expect(thrown.code, StreamContract.recordingsDirUnavailable);
    expect(
      thrown.message,
      'No se pudo preparar la carpeta de grabaciones.',
    );
    expect(thrown.details?['stage'], 'mkdirs_failed');
    expect(service.getRecordingsDirCalls, 1);
    expect(service.getRecordingsDirAttempts, <int>[1]);
  });

  test('U62 respuesta nula da recordings_channel_contract_error', () async {
    final FakeStreamingService service = FakeStreamingService()
      ..recordingsDirScript = <Object?>[null];
    final StreamingException thrown =
        await captureThrow(() => resolve(service));
    expect(thrown.code, StreamContract.recordingsChannelContractError);
    expect(
      thrown.message,
      'La respuesta del sistema de grabaciones no es válida.',
    );
    expect(thrown.details?['stage'], 'null_response');
    expect(thrown.details?['attempts'], 1);
    expect(thrown.details?['exceptionClass'], isNull);
    expect(service.getRecordingsDirCalls, 1);
  });

  test('U62 respuesta vacia da recordings_channel_contract_error', () async {
    final FakeStreamingService empty = FakeStreamingService()
      ..recordingsDirScript = <Object?>[''];
    final StreamingException thrownEmpty =
        await captureThrow(() => resolve(empty));
    expect(thrownEmpty.code, StreamContract.recordingsChannelContractError);
    expect(thrownEmpty.details?['stage'], 'empty_response');
    expect(thrownEmpty.details?['attempts'], 1);
    expect(empty.getRecordingsDirCalls, 1);

    // Cadena de solo espacios: mismo tratamiento defensivo.
    final FakeStreamingService blank = FakeStreamingService()
      ..recordingsDirScript = <Object?>['   '];
    final StreamingException thrownBlank =
        await captureThrow(() => resolve(blank));
    expect(thrownBlank.code, StreamContract.recordingsChannelContractError);
    expect(thrownBlank.details?['stage'], 'empty_response');
    expect(blank.getRecordingsDirCalls, 1);
  });

  test('U22 fallo de ruta al arrancar es visible con codigo, sin ruta', () async {
    // Nivel wrapper: `create()` espera a `resolveRecordingsDir`
    // antes de construir `SqliteRecordingRepository`, así que un
    // error aquí significa que no se registra `recordingsDirPath`.
    final FakeStreamingService service = FakeStreamingService()
      ..recordingsDirScript = <Object?>[
        const StreamingException(
          StreamContract.recordingsDirUnavailable,
          'No se pudo preparar la carpeta de grabaciones.',
          <String, Object?>{'stage': 'mkdirs_failed'},
        ),
      ];
    final StreamingException thrown =
        await captureThrow(() => resolve(service));
    expect(thrown.code, StreamContract.recordingsDirUnavailable);
    expect(
      thrown.message,
      'No se pudo preparar la carpeta de grabaciones.',
    );
    expect(service.getRecordingsDirCalls, 1);
  });
}
