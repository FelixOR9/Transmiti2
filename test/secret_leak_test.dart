import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// La clave de transmisión nunca aparece en el código, los logs, los
/// errores ni la base de datos. Esta prueba escanea el código de la app
/// (Dart y Kotlin de producción) en busca de los patrones prohibidos.
void main() {
  test('no hay filtraciones de claves ni atajos inseguros en el código', () {
    // Patrones que nunca deben aparecer en código de producción.
    const List<String> forbidden = <String>[
      // Guardar el keyset en texto claro como alternativa silenciosa.
      'doNotUseKeystore',
      // Dependencia obsoleta y retirada como solución definitiva.
      'EncryptedSharedPreferences',
      'security-crypto',
      // La clave nunca se registra.
      'Log.d(',
      'Log.e(',
      'Log.i(',
      'Log.v(',
      'Log.w(',
    ];
    // Fase A: los tres archivos del motor de cámara registran
    // diagnóstico con tag `Transmiti2Camera` (etapa, cámara elegida,
    // configuración, clase de excepción). Siguen prohibidos en todo
    // lo demás, y en ellos ninguna línea de log puede mencionar una
    // clave, una URL ni un secreto (se comprueba abajo).
    const List<String> logAllowed = <String>[
      'streaming/StreamChannelHandler.kt',
      'streaming/StreamEngineImpl.kt',
      'streaming/CameraSession.kt',
    ];
    const List<String> logForbiddenWords = <String>[
      'key',
      'clave',
      'secret',
      'url',
      'ingest',
      'streamKey',
    ];
    final List<Directory> roots = <Directory>[
      Directory('lib'),
      Directory('android/app/src/main'),
    ];
    final List<File> files = <File>[];
    for (final Directory root in roots) {
      files.addAll(
        root
            .listSync(recursive: true)
            .whereType<File>()
            .where(
              (File f) =>
                  f.path.endsWith('.dart') || f.path.endsWith('.kt'),
            ),
      );
    }
    expect(files, isNotEmpty);
    for (final File file in files) {
      final String content = file.readAsStringSync();
      final bool logsAllowed = logAllowed.any(
        (String suffix) => file.path.endsWith(suffix),
      );
      for (final String word in forbidden) {
        // Los `Log.*` solo se permiten en los archivos de diagnóstico.
        if (logsAllowed && word.startsWith('Log.')) {
          continue;
        }
        expect(
          content.contains(word),
          isFalse,
          reason: '${file.path} contiene "$word"',
        );
      }
      if (logsAllowed) {
        // Ninguna llamada de log menciona claves, URLs ni secretos
        // (la llamada puede ocupar varias líneas: se busca completa).
        final RegExp logCall = RegExp(
          r'Log\.[a-z]+\([^;]*?\);',
          dotAll: true,
        );
        for (final Match match in logCall.allMatches(content)) {
          final String call = match.group(0)!.toLowerCase();
          for (final String word in logForbiddenWords) {
            expect(
              call.contains(word),
              isFalse,
              reason: '${file.path} registra un secreto: ${match.group(0)}',
            );
          }
        }
      }
      // Ningún print/debugPrint puede interpolar una variable llamada clave.
      for (final String line in content.split('\n')) {
        final String trimmed = line.trim();
        if ((trimmed.contains('print(') ||
                trimmed.contains('debugPrint(')) &&
            trimmed.contains('key')) {
          fail('${file.path} registra una clave: $trimmed');
        }
      }
    }
  });

  test('la base de datos no tiene ninguna columna para la clave', () {
    final String schema = File(
      'lib/core/db/schema.dart',
    ).readAsStringSync();
    expect(schema.contains('stream_key'), isFalse);
    expect(schema.contains('secret'), isFalse);
    // Solo el indicador de que hay una clave guardada.
    expect(schema.contains('has_key'), isTrue);
  });
}
