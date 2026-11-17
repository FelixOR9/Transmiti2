import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// El minSdk efectivo es max(flutter.minSdkVersion, 23). Esta prueba es el
/// guarda contra regresiones en `flutter test`; la fuente de verdad en el
/// build es la tarea Gradle `printTransmiti2SdkVersions`, que el CI ejecuta
/// y que falla si el valor configurado no es exactamente ese máximo.
void main() {
  test('el minSdk efectivo es max(flutter.minSdkVersion, 23)', () {
    final String gradle = File(
      'android/app/build.gradle.kts',
    ).readAsStringSync();
    expect(
      gradle.contains('minSdk = maxOf(flutter.minSdkVersion, 23)'),
      isTrue,
      reason: 'build.gradle.kts no fija el minSdk como el máximo con 23',
    );
    expect(
      gradle.contains('printTransmiti2SdkVersions'),
      isTrue,
      reason: 'falta la tarea printTransmiti2SdkVersions',
    );
    expect(
      gradle.contains('check(effectiveMinSdk == expectedMinSdk)'),
      isTrue,
      reason: 'la tarea debe verificar el valor configurado, no solo el piso',
    );
  });
}
