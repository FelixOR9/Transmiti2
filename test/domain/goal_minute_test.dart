import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/match/domain/goal_minute.dart';

const int minute = 60000;

void main() {
  group('GoalMinute.tryParse', () {
    test('lee minutos simples y con apóstrofo', () {
      expect(GoalMinute.tryParse('23'), const GoalMinute(23));
      expect(GoalMinute.tryParse("23'"), const GoalMinute(23));
      expect(GoalMinute.tryParse('  7 '), const GoalMinute(7));
    });

    test('lee minutos con tiempo añadido', () {
      expect(GoalMinute.tryParse('45+2'), const GoalMinute(45, 2));
      expect(GoalMinute.tryParse(" 90 + 3' "), const GoalMinute(90, 3));
    });

    test('rechaza textos no válidos', () {
      for (final String raw in <String>[
        '',
        'abc',
        '0',
        '151',
        '45+',
        '+2',
        '45+123',
        '-5',
        '4.5',
      ]) {
        expect(GoalMinute.tryParse(raw), isNull, reason: 'texto: "$raw"');
      }
    });
  });

  group('GoalMinute.fromElapsed', () {
    GoalMinute at(int period, int elapsedMs) => GoalMinute.fromElapsed(
      period: period,
      elapsedMs: elapsedMs,
      halfMinutes: 45,
    );

    test('primer tiempo: el primer minuto es el 1', () {
      expect(at(1, 0), const GoalMinute(1));
      expect(at(1, minute - 1), const GoalMinute(1));
      expect(at(1, minute), const GoalMinute(2));
      expect(at(1, 44 * minute + 59000), const GoalMinute(45));
    });

    test('primer tiempo: pasados los 45 minutos cuenta como añadido', () {
      expect(at(1, 45 * minute), const GoalMinute(45, 1));
      expect(at(1, 47 * minute + 10000), const GoalMinute(45, 3));
    });

    test('segundo tiempo: continúa desde el minuto 46', () {
      expect(at(2, 0), const GoalMinute(46));
      expect(at(2, 44 * minute + 59000), const GoalMinute(90));
      expect(at(2, 45 * minute), const GoalMinute(90, 1));
      expect(at(2, 48 * minute), const GoalMinute(90, 4));
    });

    test('usa la duración configurada', () {
      final GoalMinute m = GoalMinute.fromElapsed(
        period: 2,
        elapsedMs: 0,
        halfMinutes: 20,
      );
      expect(m, const GoalMinute(21));
    });
  });

  test('label muestra el apóstrofo y el tiempo añadido', () {
    expect(const GoalMinute(23).label, "23'");
    expect(const GoalMinute(45, 2).label, "45+2'");
  });
}
