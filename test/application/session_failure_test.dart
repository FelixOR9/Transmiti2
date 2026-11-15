import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';

import '../support/flaky_repositories.dart';
import '../support/in_memory_repositories.dart';
import '../support/test_services.dart';

const int minute = 60000;
const int t0 = 1800000000000;

/// Un error de almacenamiento nunca debe dejar el marcador, el cronómetro y el
/// historial en estados distintos.
void main() {
  late InMemoryTeamRepository teams;
  late InMemoryPlayerRepository players;
  late InMemorySponsorRepository sponsors;
  late FlakyMatchRepository matches;
  late MatchSession session;
  int now = t0;

  MatchSession newSession() => MatchSession(
    matches: matches,
    teams: teams,
    players: players,
    sponsors: sponsors,
    nowMs: () => now,
  );

  setUp(() async {
    now = t0;
    teams = InMemoryTeamRepository();
    players = InMemoryPlayerRepository();
    sponsors = InMemorySponsorRepository();
    matches = FlakyMatchRepository(nowMs: () => now);
    session = newSession();
    await session.load();
    await session.saveSetup(sampleSetup());
  });

  test('si falla guardar el inicio, el partido sigue sin empezar', () async {
    matches.failClock = true;
    await expectLater(session.startMatch(), throwsA(isA<StateError>()));
    expect(session.clock.phase, MatchPhase.notStarted);
    expect(session.events, isEmpty);
    expect(session.detail!.match.status, MatchStatus.scheduled);

    // Lo guardado coincide con lo que se ve en pantalla.
    final MatchSession reopened = newSession();
    await reopened.load();
    expect(reopened.clock.phase, MatchPhase.notStarted);
    expect(reopened.events, isEmpty);
  });

  test('si falla el evento del inicio, el cronómetro tampoco cambia', () async {
    matches.failEvents = true;
    await expectLater(session.startMatch(), throwsA(isA<StateError>()));
    expect(session.clock.phase, MatchPhase.notStarted);

    final MatchSession reopened = newSession();
    await reopened.load();
    expect(reopened.clock.phase, MatchPhase.notStarted);
    expect(reopened.detail!.match.status, MatchStatus.scheduled);
  });

  test('tras un fallo se puede reintentar y todo queda consistente', () async {
    matches.failEvents = true;
    await expectLater(session.startMatch(), throwsA(isA<StateError>()));
    matches.failEvents = false;
    await session.startMatch();
    expect(session.clock.phase, MatchPhase.firstHalf);
    expect(session.events.length, 1);

    final MatchSession reopened = newSession();
    await reopened.load();
    expect(reopened.clock, session.clock);
    expect(reopened.events, session.events);
  });

  test('si falla guardar un gol, el marcador y el historial no cambian', () async {
    await session.startMatch();
    await session.addGoal(teamId: session.detail!.home.id);
    final List<MatchEvent> before = session.events;

    matches.failEvents = true;
    await expectLater(
      session.addGoal(teamId: session.detail!.away.id),
      throwsA(isA<StateError>()),
    );
    expect(session.score, const ScoreSummary(1, 0));
    expect(session.events, before);

    final MatchSession reopened = newSession();
    await reopened.load();
    expect(reopened.score, const ScoreSummary(1, 0));
    expect(reopened.events, before);
  });

  test('si falla anular un gol, el gol y su historial quedan como estaban', () async {
    await session.startMatch();
    await session.addGoal(teamId: session.detail!.home.id);
    final int goalId = session.events.last.id;
    final List<MatchEvent> before = session.events;

    matches.failCorrection = true;
    await expectLater(session.voidGoal(goalId), throwsA(isA<StateError>()));
    expect(session.score, const ScoreSummary(1, 0));
    expect(session.events, before);

    final MatchSession reopened = newSession();
    await reopened.load();
    expect(reopened.score, const ScoreSummary(1, 0));
    expect(reopened.events, before);

    // Al reintentar funciona y queda la corrección registrada.
    matches.failCorrection = false;
    await session.voidGoal(goalId);
    expect(session.score, const ScoreSummary(0, 0));
    expect(session.events.last.type, MatchEventType.goalCorrection);
  });

  test('si falla guardar el final del partido, el partido sigue en juego', () async {
    await session.startMatch();
    now = t0 + 50 * minute;
    matches.failClock = true;
    await expectLater(session.endMatch(), throwsA(isA<StateError>()));
    expect(session.clock.phase, MatchPhase.firstHalf);
    expect(session.detail!.match.status, MatchStatus.live);
    expect(session.events.length, 1);
  });

  test('preparar el partido otra vez edita el mismo, no crea una segunda sesión activa', () async {
    final int id = session.detail!.match.id;
    await session.saveSetup(sampleSetup(tournament: 'Copa'));
    await session.saveSetup(sampleSetup(tournament: 'Liga'));
    expect(session.detail!.match.id, id);
    expect((await matches.current())!.id, id);
  });

  test('iniciar dos veces el mismo partido no duplica el inicio', () async {
    await session.startMatch();
    await expectLater(session.startMatch(), throwsA(isA<StateError>()));
    expect(
      session.events.where((MatchEvent e) => e.type == MatchEventType.periodStart).length,
      1,
    );
  });
}
