import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/match_stats.dart';

const int home = 10;
const int away = 20;
int nextId = 1;

MatchEvent goal(int teamId, {int? playerId, bool voided = false}) => MatchEvent(
  id: nextId++,
  matchId: 1,
  type: MatchEventType.goal,
  teamId: teamId,
  playerId: playerId,
  period: 1,
  minute: 10,
  clockMs: 600000,
  voided: voided,
  createdAtMs: 1,
);

MatchEvent other(MatchEventType type) => MatchEvent(
  id: nextId++,
  matchId: 1,
  type: type,
  period: 1,
  minute: 0,
  clockMs: 0,
  createdAtMs: 1,
);

MatchStats stats(List<MatchEvent> events) =>
    MatchStats.fromEvents(events, homeTeamId: home, awayTeamId: away);

void main() {
  setUp(() => nextId = 1);

  test('sin eventos todo está en cero y no se inventa nada', () {
    final MatchStats s = stats(<MatchEvent>[]);
    expect(s.score, const ScoreSummary(0, 0));
    expect(s.totalEvents, 0);
    expect(s.activeGoals, 0);
    expect(s.voidedGoals, 0);
    expect(s.goalsWithoutPlayer, 0);
    expect(s.corrections, 0);
    expect(s.scorers, isEmpty);
    expect(s.countOf(MatchEventType.goal), 0);
  });

  test('cuenta los goles por equipo y el total de eventos', () {
    final MatchStats s = stats(<MatchEvent>[
      other(MatchEventType.periodStart),
      goal(home),
      goal(away),
      goal(home),
      other(MatchEventType.periodEnd),
    ]);
    expect(s.score, const ScoreSummary(2, 1));
    expect(s.totalEvents, 5);
    expect(s.activeGoals, 3);
    expect(s.countOf(MatchEventType.periodStart), 1);
    expect(s.countOf(MatchEventType.periodEnd), 1);
    expect(s.countOf(MatchEventType.goal), 3);
  });

  test('los goles anulados no cuentan en el marcador pero sí en los eventos', () {
    final MatchStats s = stats(<MatchEvent>[
      goal(home),
      goal(home, voided: true),
      other(MatchEventType.goalCorrection),
    ]);
    expect(s.score, const ScoreSummary(1, 0));
    expect(s.activeGoals, 1);
    expect(s.voidedGoals, 1);
    expect(s.corrections, 1);
    expect(s.totalEvents, 3);
  });

  test('goles por jugador: de más a menos y solo los que tienen jugador', () {
    final MatchStats s = stats(<MatchEvent>[
      goal(home, playerId: 5),
      goal(away, playerId: 8),
      goal(home, playerId: 5),
      goal(home, playerId: 3),
      goal(away),
      goal(home, playerId: 5, voided: true),
    ]);
    expect(s.scorers, <PlayerGoals>[
      const PlayerGoals(playerId: 5, teamId: home, goals: 2),
      const PlayerGoals(playerId: 3, teamId: home, goals: 1),
      const PlayerGoals(playerId: 8, teamId: away, goals: 1),
    ]);
    expect(s.goalsWithoutPlayer, 1);
    expect(s.activeGoals, 5);
  });

  test('en empate de goles el orden es estable por jugador', () {
    final MatchStats s = stats(<MatchEvent>[
      goal(home, playerId: 9),
      goal(home, playerId: 2),
    ]);
    expect(s.scorers.map((PlayerGoals p) => p.playerId), <int>[2, 9]);
  });
}
