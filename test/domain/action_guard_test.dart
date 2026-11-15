import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/core/action_guard.dart';

void main() {
  late DateTime time;
  ActionGuard guard({Duration cooldown = const Duration(milliseconds: 500)}) =>
      ActionGuard(cooldown: cooldown, now: () => time);

  setUp(() => time = DateTime(2026, 11, 13, 20));

  test('la primera acción se ejecuta', () async {
    int runs = 0;
    final bool executed = await guard().run(() async => runs++);
    expect(executed, isTrue);
    expect(runs, 1);
  });

  test('un segundo toque inmediato se ignora (doble toque)', () async {
    final ActionGuard g = guard();
    int runs = 0;
    expect(await g.run(() async => runs++), isTrue);
    expect(await g.run(() async => runs++), isFalse);
    expect(runs, 1);
  });

  test('pasado el tiempo de espera se puede volver a ejecutar', () async {
    final ActionGuard g = guard();
    int runs = 0;
    await g.run(() async => runs++);
    time = time.add(const Duration(milliseconds: 499));
    expect(await g.run(() async => runs++), isFalse);
    time = time.add(const Duration(milliseconds: 2));
    expect(await g.run(() async => runs++), isTrue);
    expect(runs, 2);
  });

  test('mientras una acción sigue en curso las demás se ignoran', () async {
    final ActionGuard g = guard(cooldown: Duration.zero);
    final Completer<void> release = Completer<void>();
    int runs = 0;
    final Future<bool> first = g.run(() async {
      runs++;
      await release.future;
    });
    expect(g.busy, isTrue);
    expect(await g.run(() async => runs++), isFalse);
    release.complete();
    expect(await first, isTrue);
    expect(g.busy, isFalse);
    expect(runs, 1);
  });

  test('con espera cero las acciones seguidas se ejecutan todas', () async {
    final ActionGuard g = guard(cooldown: Duration.zero);
    int runs = 0;
    await g.run(() async => runs++);
    await g.run(() async => runs++);
    await g.run(() async => runs++);
    expect(runs, 3);
  });

  test('si la acción falla el error se propaga y el guardián se libera', () async {
    final ActionGuard g = guard(cooldown: Duration.zero);
    await expectLater(
      g.run(() async => throw StateError('falló')),
      throwsA(isA<StateError>()),
    );
    expect(g.busy, isFalse);
    int runs = 0;
    expect(await g.run(() async => runs++), isTrue);
    expect(runs, 1);
  });
}
