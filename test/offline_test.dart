import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// El control del partido debe funcionar sin internet.
///
/// Fase 3.0: la red solo existe en el módulo de transmisión
/// (`lib/features/streaming/`, que habla con Kotlin por MethodChannel y no
/// abre conexiones desde Dart) y en el manifiesto, que declara INTERNET
/// para el futuro motor RTMP nativo. El resto de la app sigue sin red:
/// ningún otro archivo de `lib/` puede usar conexiones de red, y el
/// partido, el marcador y el reloj nunca dependen de internet.
void main() {
  test('el código de la app no usa conexiones de red fuera del streaming', () {
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

  test('el manifiesto declara INTERNET solo para el motor nativo', () {
    final File manifest = File('android/app/src/main/AndroidManifest.xml');
    expect(manifest.existsSync(), isTrue);
    final String content = manifest.readAsStringSync();
    // Declarado en la Fase 3.0 para el futuro motor RTMP (Kotlin).
    expect(content.contains('android.permission.INTERNET'), isTrue);
    expect(
      content.contains('android.permission.ACCESS_NETWORK_STATE'),
      isTrue,
    );
    // Declarados, pero NO solicitados en tiempo de ejecución en la 3.0.
    expect(content.contains('android.permission.CAMERA'), isTrue);
    expect(content.contains('android.permission.RECORD_AUDIO'), isTrue);
    // Subetapa 3.1.0: el servicio en primer plano ya está registrado con
    // los dos tipos (camera|microphone) y no exportado; todavía no
    // arranca sesiones reales de grabación (eso llega en 3.1.2).
    expect(content.contains('<service'), isTrue);
    expect(
      content.contains('android:foregroundServiceType="camera|microphone"'),
      isTrue,
    );
    expect(content.contains('android:exported="false"'), isTrue);
    expect(
      content.contains('android.permission.FOREGROUND_SERVICE"'),
      isTrue,
    );
    expect(
      content.contains('android.permission.FOREGROUND_SERVICE_CAMERA'),
      isTrue,
    );
    expect(
      content.contains('android.permission.FOREGROUND_SERVICE_MICROPHONE'),
      isTrue,
    );
    expect(
      content.contains('android.permission.POST_NOTIFICATIONS'),
      isTrue,
    );
    expect(content.contains('android.permission.WAKE_LOCK'), isTrue);
  });
}
