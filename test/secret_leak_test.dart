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
      for (final String word in forbidden) {
        expect(
          content.contains(word),
          isFalse,
          reason: '${file.path} contiene "$word"',
        );
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
