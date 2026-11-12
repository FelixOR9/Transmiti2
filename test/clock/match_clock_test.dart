import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';

const int minute = 60000;
const int t0 = 1000000000;

void main() {
  group('MatchClockState', () {
    test('empieza sin iniciar y sin tiempo', () {
      const MatchClockState s = MatchClockState.initial;
      expect(s.phase, MatchPhase.notStarted);
      expect(s.running, isFalse);
      expect(s.elapsedAt(t0), 0);
      expect(s.canStart, isTrue);
      expect(s.canPause, isFalse);
    });

    test('iniciar pone a correr el primer tiempo', () {
      final MatchClockState s = MatchClockState.initial.start(t0);
      expect(s.phase, MatchPhase.firstHalf);
      expect(s.period, 1);
      expect(s.running, isTrue);
      expect(s.elapsedAt(t0), 0);
      expect(s.elapsedAt(t0 + minute), minute);
    });

    test('pausar congela el tiempo y reanudar continúa sin perder nada', () {
      MatchClockState s = MatchClockState.initial.start(t0);
      s = s.pause(t0 + 10 * minute);
      expect(s.running, isFalse);
      expect(s.elapsedAt(t0 + 10 * minute), 10 * minute);
      expect(s.elapsedAt(t0 + 30 * minute), 10 * minute);

      s = s.resume(t0 + 30 * minute);
      expect(s.running, isTrue);
      expect(s.elapsedAt(t0 + 35 * minute), 15 * minute);
    });

    test('el tiempo no retrocede si el reloj del teléfono se atrasa', () {
      final MatchClockState s = MatchClockState.initial.start(t0 + 10000);
      expect(s.elapsedAt(t0), 0);
    });

    test('finalizar el primer tiempo pasa al descanso', () {
      final MatchClockState s = MatchClockState.initial
          .start(t0)
          .endFirstHalf(t0 + 46 * minute);
      expect(s.phase, MatchPhase.halfTime);
      expect(s.running, isFalse);
      expect(s.firstHalfMs, 46 * minute);
      expect(s.breakStartedAtMs, t0 + 46 * minute);
      expect(s.elapsedAt(t0 + 99 * minute), 46 * minute);
    });

    test('se puede finalizar el primer tiempo estando en pausa', () {
      final MatchClockState s = MatchClockState.initial
          .start(t0)
          .pause(t0 + 20 * minute)
          .endFirstHalf(t0 + 25 * minute);
      expect(s.phase, MatchPhase.halfTime);
      expect(s.firstHalfMs, 20 * minute);
    });

    test('el segundo tiempo empieza desde cero en el periodo 2', () {
      final MatchClockState s = MatchClockState.initial
          .start(t0)
          .endFirstHalf(t0 + 45 * minute)
          .startSecondHalf(t0 + 60 * minute);
      expect(s.phase, MatchPhase.secondHalf);
      expect(s.period, 2);
      expect(s.running, isTrue);
      expect(s.elapsedAt(t0 + 60 * minute), 0);
      expect(s.elapsedAt(t0 + 70 * minute), 10 * minute);
      expect(s.firstHalfMs, 45 * minute);
    });

    test('finalizar el partido desde el segundo tiempo congela el tiempo', () {
      final MatchClockState s = MatchClockState.initial
          .start(t0)
          .endFirstHalf(t0 + 45 * minute)
          .startSecondHalf(t0 + 60 * minute)
          .endMatch(t0 + 107 * minute);
      expect(s.phase, MatchPhase.fullTime);
      expect(s.running, isFalse);
      expect(s.period, 2);
      expect(s.elapsedAt(t0 + 500 * minute), 47 * minute);
    });

    test(
      'finalizar el partido desde el descanso conserva el primer tiempo',
      () {
        final MatchClockState s = MatchClockState.initial
            .start(t0)
            .endFirstHalf(t0 + 45 * minute)
            .endMatch(t0 + 50 * minute);
        expect(s.phase, MatchPhase.fullTime);
        expect(s.period, 1);
        expect(s.elapsedAt(t0 + 99 * minute), 45 * minute);
      },
    );

    test('las acciones que no corresponden lanzan StateError', () {
      const MatchClockState initial = MatchClockState.initial;
      expect(() => initial.pause(t0), throwsA(isA<StateError>()));
      expect(() => initial.resume(t0), throwsA(isA<StateError>()));
      expect(() => initial.endFirstHalf(t0), throwsA(isA<StateError>()));
      expect(() => initial.startSecondHalf(t0), throwsA(isA<StateError>()));
      expect(() => initial.endMatch(t0), throwsA(isA<StateError>()));

      final MatchClockState running = initial.start(t0);
      expect(() => running.start(t0), throwsA(isA<StateError>()));
      expect(() => running.resume(t0), throwsA(isA<StateError>()));
      expect(() => running.startSecondHalf(t0), throwsA(isA<StateError>()));

      final MatchClockState done = running.endMatch(t0 + minute);
      expect(() => done.endMatch(t0), throwsA(isA<StateError>()));
      expect(() => done.pause(t0), throwsA(isA<StateError>()));
    });

    test(
      'guardar y recuperar: el tiempo sigue correcto tras cerrar la app',
      () {
        final MatchClockState before = MatchClockState.initial
            .start(t0)
            .pause(t0 + 5 * minute)
            .resume(t0 + 8 * minute);

        // La app se cierra: solo queda el mapa guardado en la base de datos.
        final Map<String, Object?> saved = before.toMap();
        final MatchClockState restored = MatchClockState.fromMap(saved);

        expect(restored, before);
        // Pasan 20 minutos con la app cerrada y el cronómetro sigue contando.
        expect(restored.elapsedAt(t0 + 28 * minute), 25 * minute);
      },
    );

    test('un cronómetro en pausa se recupera en pausa', () {
      final MatchClockState before = MatchClockState.initial
          .start(t0)
          .pause(t0 + 12 * minute);
      final MatchClockState restored = MatchClockState.fromMap(before.toMap());
      expect(restored, before);
      expect(restored.elapsedAt(t0 + 99 * minute), 12 * minute);
    });
  });

  group('ClockReading', () {
    ClockReading read(MatchClockState s, int now) =>
        ClockReading.of(s, nowMs: now, halfMinutes: 45);

    test('sin iniciar muestra 00:00', () {
      final ClockReading r = read(MatchClockState.initial, t0);
      expect(r.mainText, '00:00');
      expect(r.addedText, isNull);
      expect(r.totalMs, 0);
    });

    test('durante el primer tiempo muestra el tiempo transcurrido', () {
      final MatchClockState s = MatchClockState.initial.start(t0);
      final ClockReading r = read(s, t0 + 12 * minute + 34000);
      expect(r.mainText, '12:34');
      expect(r.addedText, isNull);
    });

    test('pasada la duración del primer tiempo se muestra tiempo añadido', () {
      final MatchClockState s = MatchClockState.initial.start(t0);
      final ClockReading r = read(s, t0 + 46 * minute + 30000);
      expect(r.mainText, '45:00');
      expect(r.addedText, '+01:30');
      expect(r.totalMs, 46 * minute + 30000);
    });

    test('el segundo tiempo continúa desde el final del primero', () {
      final MatchClockState s = MatchClockState.initial
          .start(t0)
          .endFirstHalf(t0 + 45 * minute)
          .startSecondHalf(t0 + 60 * minute);
      final ClockReading start = read(s, t0 + 60 * minute);
      expect(start.mainText, '45:00');
      expect(start.addedText, isNull);

      final ClockReading mid = read(s, t0 + 70 * minute);
      expect(mid.mainText, '55:00');

      final ClockReading late = read(s, t0 + 107 * minute);
      expect(late.mainText, '90:00');
      expect(late.addedText, '+02:00');
    });

    test(
      'durante el descanso muestra el final del primer tiempo y el descanso',
      () {
        final MatchClockState s = MatchClockState.initial
            .start(t0)
            .endFirstHalf(t0 + 45 * minute);
        final ClockReading r = read(s, t0 + 50 * minute);
        expect(r.phase, MatchPhase.halfTime);
        expect(r.mainText, '45:00');
        expect(r.breakElapsedMs, 5 * minute);
      },
    );

    test('al terminar el partido el reloj queda congelado', () {
      final MatchClockState s = MatchClockState.initial
          .start(t0)
          .endFirstHalf(t0 + 45 * minute)
          .startSecondHalf(t0 + 60 * minute)
          .endMatch(t0 + 105 * minute);
      final ClockReading r = read(s, t0 + 999 * minute);
      expect(r.phase, MatchPhase.fullTime);
      expect(r.mainText, '90:00');
      expect(r.addedText, isNull);
    });

    test('respeta la duración configurada de cada tiempo', () {
      final MatchClockState s = MatchClockState.initial.start(t0);
      final ClockReading r = ClockReading.of(
        s,
        nowMs: t0 + 32 * minute,
        halfMinutes: 30,
      );
      expect(r.mainText, '30:00');
      expect(r.addedText, '+02:00');
    });
  });
}
