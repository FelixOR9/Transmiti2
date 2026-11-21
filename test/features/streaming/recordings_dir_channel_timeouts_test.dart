import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/app/bootstrap.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';

import '../../support/fake_streaming.dart';

/// Timeouts del canal (subetapa 0, grupo 0-e): U59, U65 y U63.
///
/// U59 usa los valores de producción (3 s por intento, 250/500 ms):
/// el fake de canal nunca responde y el wrapper agota los 3
/// intentos. U65 usa 1 intento (reintentos desactivados) con un
/// fake que responde TARDE (5 s): el `Future` de Dart debe
/// completar a los 3 s, no a los 5 s, y la respuesta tardía no
/// produce segunda compleción ni registra ruta. La mitad nativa
/// de U65 (el bloqueo real con `Thread.sleep` y la respuesta
/// tardía única por `replyOnce`) la cubre U58b en Kotlin; un solo
/// test que cruce el puente MethodChannel no es ejecutable en
/// ningún harness de unitarias (ni Dart ni JVM tienen el puente),
/// así que U65 es la pareja U58b (nativo) + este test (Dart).
/// U63 discrimina los tres códigos con el wrapper real.

/// Servicio falso cuya `getRecordingsDir` la guioniza una función
/// (para futuros que nunca completan o que completan tarde: el
/// `recordingsDirScript` de `FakeStreamingService` solo admite
/// valores o excepciones inmediatas).
class _ScriptedRecordingsDirService extends FakeStreamingService {
  _ScriptedRecordingsDirService(this._next);

  final Future<String?> Function(int attempt) _next;

  @override
  Future<String?> getRecordingsDir({int attempt = 1}) {
    getRecordingsDirCalls++;
    getRecordingsDirAttempts.add(attempt);
    return _next(attempt);
  }
}

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

void main() {
  test('U59 canal que nunca responde agota 3 intentos a los 3 s',
      () async {
    // Fake de canal que NUNCA responde: un futuro nuevo sin
    // completar en cada intento.
    final _ScriptedRecordingsDirService service =
        _ScriptedRecordingsDirService(
      (_) => Completer<String?>().future,
    );
    final Stopwatch stopwatch = Stopwatch()..start();
    final StreamingException thrown = await captureThrow(
      () => AppServices.resolveRecordingsDir(
        service,
        // Valores de producción: 3 s por intento, 250/500 ms.
        attemptTimeout: const Duration(seconds: 3),
        retryDelays: const <Duration>[
          Duration(milliseconds: 250),
          Duration(milliseconds: 500),
        ],
      ),
    );
    stopwatch.stop();
    final int elapsedMs = stopwatch.elapsedMilliseconds;
    // 3 x 3 s + 250 ms + 500 ms = 9,75 s: los tres timeouts
    // dispararon de verdad (no un fallo rápido) y el `Future` no
    // quedó colgado.
    expect(elapsedMs, greaterThanOrEqualTo(9000));
    expect(elapsedMs, lessThan(13000));
    expect(thrown.code, StreamContract.recordingsChannelUnavailable);
    expect(
      thrown.message,
      'No se pudo contactar con el sistema de grabaciones del teléfono.',
    );
    expect(thrown.details?['stage'], 'timeout');
    expect(thrown.details?['attempts'], 3);
    // Gana el timeout Dart: no hay código nativo que registrar
    // (revisión 4 §3.10 F3).
    expect(thrown.details?['lastErrorCode'], isNull);
    expect(thrown.details?['exceptionClass'], 'TimeoutException');
    expect(service.getRecordingsDirCalls, 3);
    expect(service.getRecordingsDirAttempts, <int>[1, 2, 3]);
  });

  test('U65 bloqueo de disco no congela la UI: el Future completa a los 3 s',
      () async {
    // Fake que responde TARDE (5 s), como un nativo bloqueado en
    // una syscall que retorna tras el timeout Dart: la respuesta
    // existe, pero llega después de que el `Future` completara.
    final Completer<String?> lateReply = Completer<String?>();
    final _ScriptedRecordingsDirService service =
        _ScriptedRecordingsDirService((_) {
      Future<void>.delayed(const Duration(seconds: 5), () {
        if (!lateReply.isCompleted) {
          lateReply.complete('/fake/recordings');
        }
      });
      return lateReply.future;
    });
    final Stopwatch stopwatch = Stopwatch()..start();
    final StreamingException thrown = await captureThrow(
      () => AppServices.resolveRecordingsDir(
        service,
        attemptTimeout: const Duration(seconds: 3),
        // Reintentos desactivados: 1 solo intento, para aislar
        // el comportamiento (revisión 4 §3.8).
        retryDelays: const <Duration>[],
      ),
    );
    stopwatch.stop();
    final int elapsedMs = stopwatch.elapsedMilliseconds;
    // El `Future` completa a los 3 s ± margen (no a los 5 s,
    // nunca infinito): la espera de la interfaz está acotada por
    // el timeout Dart aunque el trabajo nativo siga bloqueado.
    expect(elapsedMs, greaterThanOrEqualTo(2500));
    expect(elapsedMs, lessThan(4500));
    expect(thrown.code, StreamContract.recordingsChannelUnavailable);
    expect(thrown.details?['stage'], 'timeout');
    expect(thrown.details?['attempts'], 1);
    expect(thrown.details?['lastErrorCode'], isNull);
    expect(service.getRecordingsDirCalls, 1);
    // La UI muestra error/reintento, nunca éxito ni ruta del
    // intento caducado: el formateador de arranque da el mensaje
    // de canal (no el de disco ni un éxito).
    final StartupErrorText shown = startupErrorText(thrown);
    expect(
      shown.title,
      'No se pudo contactar con el sistema de grabaciones del teléfono.',
    );
    // La respuesta tardía llega (~5 s) y es una sola, sin efecto
    // secundario: el resultado observado sigue siendo el error
    // (el `Future` ya completado no cambia).
    expect(await lateReply.future, '/fake/recordings');
    expect(thrown.code, StreamContract.recordingsChannelUnavailable);
    // La segunda llamada (FS normal) completa con éxito y
    // devuelve la ruta: el wrapper no quedó envenenado por el
    // intento caducado (la liberación del hilo IO nativo la
    // prueba U58b en Kotlin).
    final String path = await AppServices.resolveRecordingsDir(
      FakeStreamingService(),
      attemptTimeout: const Duration(seconds: 3),
      retryDelays: const <Duration>[],
    );
    expect(path, '/fake/recordings');
  });

  test('U63 canal, disco y contrato: tres codigos y tres mensajes distintos',
      () async {
    const Duration fastTimeout = Duration(milliseconds: 50);
    const List<Duration> fastDelays = <Duration>[
      Duration(milliseconds: 1),
      Duration(milliseconds: 1),
    ];

    Future<String> resolve(FakeStreamingService service) {
      return AppServices.resolveRecordingsDir(
        service,
        attemptTimeout: fastTimeout,
        retryDelays: fastDelays,
      );
    }

    // (1) Canal: MissingPluginException x3 -> duro de canal.
    final FakeStreamingService channel = FakeStreamingService()
      ..recordingsDirScript = <Object?>[
        MissingPluginException(),
        MissingPluginException(),
        MissingPluginException(),
      ];
    final StreamingException channelError =
        await captureThrow(() => resolve(channel));

    // (2) Disco: recordings_dir_unavailable -> duro inmediato.
    final FakeStreamingService disk = FakeStreamingService()
      ..recordingsDirScript = <Object?>[
        const StreamingException(
          StreamContract.recordingsDirUnavailable,
          'No se pudo preparar la carpeta de grabaciones.',
          <String, Object?>{'stage': 'mkdirs_failed'},
        ),
      ];
    final StreamingException diskError =
        await captureThrow(() => resolve(disk));

    // (3) Contrato: respuesta nula -> error de contrato.
    final FakeStreamingService contract = FakeStreamingService()
      ..recordingsDirScript = <Object?>[null];
    final StreamingException contractError =
        await captureThrow(() => resolve(contract));

    // Tres códigos distintos...
    final List<String> codes = <String>[
      channelError.code,
      diskError.code,
      contractError.code,
    ];
    expect(codes.toSet().length, 3);
    expect(channelError.code, StreamContract.recordingsChannelUnavailable);
    expect(diskError.code, StreamContract.recordingsDirUnavailable);
    expect(contractError.code, StreamContract.recordingsChannelContractError);
    // ...tres mensajes distintos (los que ve el usuario)...
    final List<String> titles = <String>[
      startupErrorText(channelError).title,
      startupErrorText(diskError).title,
      startupErrorText(contractError).title,
    ];
    expect(titles.toSet().length, 3);
    expect(
      titles[0],
      'No se pudo contactar con el sistema de grabaciones del teléfono.',
    );
    expect(titles[1], 'No se pudo preparar la carpeta de grabaciones.');
    expect(
      titles[2],
      'La respuesta del sistema de grabaciones no es válida.',
    );
    // ...y NINGUNA ejecución produce stage = channel_unavailable
    // (ese stage no existe; rev. 3 §3.2).
    for (final StreamingException error in <StreamingException>[
      channelError,
      diskError,
      contractError,
    ]) {
      expect(error.details?['stage'], isNot('channel_unavailable'));
      expect(
        error.details?.values,
        isNot(contains('channel_unavailable')),
      );
    }
  });
}
