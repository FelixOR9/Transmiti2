import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// El control del partido debe funcionar sin internet: el código de la app no
/// abre conexiones de red ni la versión de producción pide el permiso.
void main() {
  test('el código de la app no usa conexiones de red', () {
    final List<String> forbidden = <String>[
      'package:http/',
      'package:dio/',
      'dart:html',
      'HttpClient',
      'WebSocket',
      'Socket.connect',
      'RawSocket',
      'NetworkImage',
      'Image.network',
    ];
    final List<File> files = Directory('lib')
        .listSync(recursive: true)
        .whereType<File>()
        .where((File f) => f.path.endsWith('.dart'))
        .toList();
    expect(files, isNotEmpty);
    for (final File file in files) {
      final String content = file.readAsStringSync();
      for (final String word in forbidden) {
        expect(
          content.contains(word),
          isFalse,
          reason: '${file.path} usa "$word"',
        );
      }
    }
  });

  test('la versión de producción no pide el permiso de internet', () {
    final File manifest = File('android/app/src/main/AndroidManifest.xml');
    if (!manifest.existsSync()) {
      return;
    }
    expect(
      manifest.readAsStringSync().contains('android.permission.INTERNET'),
      isFalse,
    );
  });
}
