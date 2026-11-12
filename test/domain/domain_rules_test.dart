import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/core/formatting.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match/domain/team.dart';

MatchSetup setup({
  String home = 'Rojo',
  String away = 'Azul',
  int homeColor = 0xFFE53935,
  int awayColor = 0xFF1E88E5,
  String? tournament,
  int date = 1760000000000,
  int half = 45,
  int breakMin = 15,
}) {
  return MatchSetup(
    homeName: home,
    homeColor: homeColor,
    awayName: away,
    awayColor: awayColor,
    tournament: tournament,
    scheduledAtMs: date,
    halfMinutes: half,
    breakMinutes: breakMin,
  );
}

MatchEvent goal(int id, int teamId, {bool voided = false}) => MatchEvent(
  id: id,
  matchId: 1,
  type: MatchEventType.goal,
  teamId: teamId,
  period: 1,
  minute: 10,
  clockMs: 600000,
  voided: voided,
  createdAtMs: 1,
);

void main() {
  group('MatchSetup.validate', () {
    test('un formulario correcto no tiene errores', () {
      expect(setup().validate(), isEmpty);
      expect(setup(tournament: 'Liga local', breakMin: 0).validate(), isEmpty);
    });

    test('exige el nombre de ambos equipos', () {
      final Map<String, String> errors = setup(home: '  ', away: '').validate();
      expect(errors.containsKey('homeName'), isTrue);
      expect(errors.containsKey('awayName'), isTrue);
    });

    test(
      'los nombres deben ser distintos sin importar mayúsculas ni espacios',
      () {
        expect(
          setup(home: 'Los  Tigres', away: ' los tigres ').validate().keys,
          contains('awayName'),
        );
      },
    );

    test('los colores deben ser distintos', () {
      expect(
        setup(homeColor: 0xFF000000, awayColor: 0xFF000000).validate().keys,
        contains('awayColor'),
      );
    });

    test('valida los límites de las duraciones', () {
      expect(setup(half: 0).validate().keys, contains('halfMinutes'));
      expect(setup(half: 91).validate().keys, contains('halfMinutes'));
      expect(setup(breakMin: -1).validate().keys, contains('breakMinutes'));
      expect(setup(breakMin: 61).validate().keys, contains('breakMinutes'));
      expect(setup(half: 1, breakMin: 60).validate(), isEmpty);
      expect(setup(half: 90, breakMin: 0).validate(), isEmpty);
    });

    test('exige fecha y limita el largo de los textos', () {
      expect(setup(date: 0).validate().keys, contains('date'));
      expect(
        setup(tournament: 'x' * 61).validate().keys,
        contains('tournament'),
      );
      expect(setup(home: 'x' * 41).validate().keys, contains('homeName'));
    });

    test('el torneo vacío se trata como ausente', () {
      expect(setup(tournament: '   ').cleanTournament, isNull);
      expect(setup(tournament: ' Copa ').cleanTournament, 'Copa');
    });
  });

  group('Team', () {
    test('cleanName y keyOf normalizan espacios y mayúsculas', () {
      expect(Team.cleanName('  Los   Tigres '), 'Los Tigres');
      expect(Team.keyOf('  Los   TIGRES '), 'los tigres');
    });
  });

  group('PlayerRules', () {
    test('valida nombre y número', () {
      expect(PlayerRules.validate(name: 'Luis', number: 9), isEmpty);
      expect(PlayerRules.validate(name: ' ', number: 9).keys, contains('name'));
      expect(
        PlayerRules.validate(name: 'x' * 41, number: 9).keys,
        contains('name'),
      );
      expect(
        PlayerRules.validate(name: 'Luis', number: null).keys,
        contains('number'),
      );
      expect(
        PlayerRules.validate(name: 'Luis', number: 0).keys,
        contains('number'),
      );
      expect(
        PlayerRules.validate(name: 'Luis', number: 100).keys,
        contains('number'),
      );
      expect(PlayerRules.validate(name: 'Luis', number: 99), isEmpty);
    });
  });

  group('SponsorRules', () {
    test('valida el nombre', () {
      expect(SponsorRules.validate(name: 'Casa Pepe'), isEmpty);
      expect(SponsorRules.validate(name: '  ').keys, contains('name'));
      expect(SponsorRules.validate(name: 'x' * 51).keys, contains('name'));
    });
  });

  group('ScoreSummary', () {
    test('cuenta los goles de cada equipo', () {
      final ScoreSummary s = ScoreSummary.fromEvents(
        <MatchEvent>[goal(1, 10), goal(2, 20), goal(3, 10)],
        homeTeamId: 10,
        awayTeamId: 20,
      );
      expect(s, const ScoreSummary(2, 1));
      expect(s.text, '2 - 1');
    });

    test('ignora los goles anulados y los eventos que no son goles', () {
      final ScoreSummary s = ScoreSummary.fromEvents(
        <MatchEvent>[
          goal(1, 10),
          goal(2, 10, voided: true),
          const MatchEvent(
            id: 3,
            matchId: 1,
            type: MatchEventType.goalCorrection,
            teamId: 10,
            period: 1,
            minute: 12,
            clockMs: 700000,
            createdAtMs: 1,
          ),
        ],
        homeTeamId: 10,
        awayTeamId: 20,
      );
      expect(s, const ScoreSummary(1, 0));
    });
  });

  group('formato', () {
    test('formatClock', () {
      expect(formatClock(0), '00:00');
      expect(formatClock(65000), '01:05');
      expect(formatClock(100 * 60000), '100:00');
      expect(formatClock(-5), '00:00');
    });

    test('formatDate', () {
      expect(
        formatDate(DateTime(2026, 11, 5).millisecondsSinceEpoch),
        '05/11/2026',
      );
    });
  });
}
