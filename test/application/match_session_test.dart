import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/exceptions.dart';
import 'package:transmiti2/features/match/domain/goal_minute.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';

import '../support/in_memory_repositories.dart';
import '../support/test_services.dart';

const int minute = 60000;
const int second = 1000;
const int t0 = 1800000000000;

class Env {
  final InMemoryTeamRepository teams = InMemoryTeamRepository();
  final InMemoryPlayerRepository players = InMemoryPlayerRepository();
  final InMemorySponsorRepository sponsors = InMemorySponsorRepository();
  late final InMemoryMatchRepository matches = InMemoryMatchRepository(
    nowMs: () => now,
  );
  int now = t0;

  /// Una sesión nueva sobre los mismos repositorios equivale a cerrar y
  /// volver a abrir la app.
  MatchSession newSession() => MatchSession(
    matches: matches,
    teams: teams,
    players: players,
    sponsors: sponsors,
    nowMs: () => now,
  );
}

Future<MatchSession> readySession(Env env, {MatchSetup? setup}) async {
  final MatchSession session = env.newSession();
  await session.load();
  await session.saveSetup(setup ?? sampleSetup());
  return session;
}

List<MatchEventType> types(MatchSession s) =>
    s.events.map((MatchEvent e) => e.type).toList();

void main() {
  late Env env;
  setUp(() {
    env = Env();
  });

  group('preparación del partido', () {
    test('sin partido guardado el estado está vacío', () async {
      final MatchSession s = env.newSession();
      await s.load();
      expect(s.hasMatch, isFalse);
      expect(s.detail, isNull);
      expect(s.score, const ScoreSummary(0, 0));
      expect(s.clock.phase, MatchPhase.notStarted);
    });

    test('crear un partido entre dos equipos', () async {
      final MatchSession s = await readySession(
        env,
        setup: sampleSetup(tournament: ' Liga local '),
      );
      expect(s.hasMatch, isTrue);
      expect(s.detail!.home.name, 'Rojo');
      expect(s.detail!.away.name, 'Azul');
      expect(s.detail!.home.colorValue, 0xFFE53935);
      expect(s.detail!.match.tournament, 'Liga local');
      expect(s.detail!.match.halfMinutes, 45);
      expect(s.detail!.match.breakMinutes, 15);
      expect(s.detail!.match.status, MatchStatus.scheduled);
      expect(s.clock.phase, MatchPhase.notStarted);
      expect(s.events, isEmpty);
    });

    test('los datos inválidos no se guardan', () async {
      final MatchSession s = env.newSession();
      await s.load();
      await expectLater(
        s.saveSetup(sampleSetup(homeName: ' ', halfMinutes: 0)),
        throwsA(isA<ValidationException>()),
      );
      expect(s.hasMatch, isFalse);
      expect(await env.teams.all(), isEmpty);
      expect(await env.matches.current(), isNull);
    });

    test('guardar de nuevo edita el mismo partido antes de iniciar', () async {
      final MatchSession s = await readySession(env);
      final int id = s.detail!.match.id;
      await s.saveSetup(
        sampleSetup(halfMinutes: 30, breakMinutes: 10, tournament: 'Copa'),
      );
      expect(s.detail!.match.id, id);
      expect(s.detail!.match.halfMinutes, 30);
      expect(s.detail!.match.breakMinutes, 10);
      expect(s.detail!.match.tournament, 'Copa');

      await s.saveSetup(sampleSetup(tournament: '  '));
      expect(s.detail!.match.tournament, isNull);
    });

    test(
      'con el partido iniciado no se cambian equipos ni duraciones',
      () async {
        final MatchSession s = await readySession(env);
        await s.startMatch();
        await expectLater(
          s.saveSetup(sampleSetup(homeName: 'Verde')),
          throwsA(isA<ValidationException>()),
        );
        await s.saveSetup(sampleSetup(halfMinutes: 20, homeColor: 0xFF43A047));
        expect(s.detail!.match.halfMinutes, 45);
        expect(s.detail!.home.colorValue, 0xFF43A047);
      },
    );

    test('el escudo y el color del equipo se conservan', () async {
      final MatchSession s = env.newSession();
      await s.load();
      await s.saveSetup(
        MatchSetup(
          homeName: 'Rojo',
          homeColor: 0xFFE53935,
          homeCrestPath: 'crests/rojo.png',
          awayName: 'Azul',
          awayColor: 0xFF1E88E5,
          scheduledAtMs: t0,
          halfMinutes: 45,
          breakMinutes: 15,
        ),
      );
      final MatchSession reopened = env.newSession();
      await reopened.load();
      expect(reopened.detail!.home.crestPath, 'crests/rojo.png');
      expect(reopened.detail!.away.crestPath, isNull);
    });

    test('los patrocinadores elegidos se guardan en orden', () async {
      final Sponsor a = await env.sponsors.add(name: 'Alfa');
      final Sponsor b = await env.sponsors.add(name: 'Beta');
      final Sponsor c = await env.sponsors.add(name: 'Gama');
      final MatchSession s = await readySession(
        env,
        setup: sampleSetup(sponsorIds: <int>[c.id, a.id]),
      );
      expect(s.detail!.sponsors.map((Sponsor x) => x.name), <String>[
        'Gama',
        'Alfa',
      ]);

      await s.setSponsors(<int>[b.id, a.id, c.id, a.id]);
      expect(s.detail!.sponsors.map((Sponsor x) => x.name), <String>[
        'Beta',
        'Alfa',
        'Gama',
      ]);

      await s.setSponsors(<int>[]);
      expect(s.detail!.sponsors, isEmpty);
    });

    test('las duraciones solo se cambian antes de iniciar', () async {
      final MatchSession s = await readySession(env);
      expect(s.canEditDurations, isTrue);
      await s.updateDurations(halfMinutes: 25, breakMinutes: 5);
      expect(s.detail!.match.halfMinutes, 25);
      expect(s.detail!.match.breakMinutes, 5);

      await expectLater(
        s.updateDurations(halfMinutes: 0, breakMinutes: 5),
        throwsA(isA<ValidationException>()),
      );
      await s.startMatch();
      expect(s.canEditDurations, isFalse);
      await expectLater(
        s.updateDurations(halfMinutes: 30, breakMinutes: 5),
        throwsA(isA<StateError>()),
      );
    });

    test(
      'nuevo partido cancela el actual y cancelar lo deja sin partido',
      () async {
        final MatchSession s = await readySession(env);
        final int first = s.detail!.match.id;
        await s.saveSetup(sampleSetup(homeName: 'Verde'), createNew: true);
        expect(s.detail!.match.id, isNot(first));
        expect(s.detail!.home.name, 'Verde');

        await s.cancelCurrentMatch();
        expect(s.hasMatch, isFalse);
      },
    );
  });

  group('cronómetro', () {
    test('iniciar, pausar y reanudar miden el tiempo real', () async {
      final MatchSession s = await readySession(env);
      await s.startMatch();
      expect(s.detail!.match.status, MatchStatus.live);

      env.now = t0 + 10 * minute;
      expect(s.reading().mainText, '10:00');
      await s.pauseClock();

      env.now = t0 + 30 * minute;
      expect(s.reading().mainText, '10:00');
      expect(s.clock.running, isFalse);
      await s.resumeClock();

      env.now = t0 + 35 * minute;
      expect(s.reading().mainText, '15:00');
      // Solo el inicio del partido deja evento: pausar y reanudar no.
      expect(types(s), <MatchEventType>[MatchEventType.periodStart]);
    });

    test('primer tiempo, segundo tiempo y final dejan su evento', () async {
      final MatchSession s = await readySession(env);
      await s.startMatch();

      env.now = t0 + 47 * minute + 20 * second;
      await s.endFirstHalf();
      expect(s.clock.phase, MatchPhase.halfTime);

      env.now = t0 + 62 * minute;
      await s.startSecondHalf();
      expect(s.clock.phase, MatchPhase.secondHalf);

      env.now = t0 + 62 * minute + 45 * minute + 20 * second;
      await s.endMatch();
      expect(s.clock.phase, MatchPhase.fullTime);
      expect(s.detail!.match.status, MatchStatus.finished);
      expect(s.reading().mainText, '90:00');
      expect(s.reading().addedText, '+00:20');

      expect(types(s), <MatchEventType>[
        MatchEventType.periodStart,
        MatchEventType.periodEnd,
        MatchEventType.periodStart,
        MatchEventType.matchEnd,
      ]);
      final List<MatchEvent> e = s.events;
      expect(e[0].period, 1);
      expect(e[0].minute, 0);
      expect(e[1].period, 1);
      expect(e[1].minute, 45);
      expect(e[1].added, 3);
      expect(e[2].period, 2);
      expect(e[2].minute, 45);
      expect(e[2].added, 0);
      expect(e[3].period, 2);
      expect(e[3].minute, 90);
      expect(e[3].added, 1);
    });

    test('las acciones fuera de orden no cambian nada', () async {
      final MatchSession s = await readySession(env);
      await expectLater(s.pauseClock(), throwsA(isA<StateError>()));
      await expectLater(s.endMatch(), throwsA(isA<StateError>()));
      expect(s.clock.phase, MatchPhase.notStarted);
      expect(s.events, isEmpty);
    });
  });

  group('goles', () {
    test(
      'suman al marcador de cada equipo con el minuto del cronómetro',
      () async {
        final MatchSession s = await readySession(env);
        await s.startMatch();
        final int home = s.detail!.home.id;
        final int away = s.detail!.away.id;

        env.now = t0 + 12 * minute + 30 * second;
        await s.addGoal(teamId: home);
        env.now = t0 + 30 * minute;
        await s.addGoal(teamId: away);
        env.now = t0 + 41 * minute;
        await s.addGoal(teamId: home);

        expect(s.score, const ScoreSummary(2, 1));
        final List<MatchEvent> goals = s.events
            .where((MatchEvent e) => e.type == MatchEventType.goal)
            .toList();
        expect(goals.map((MatchEvent e) => e.minute), <int>[13, 31, 42]);
        expect(goals.map((MatchEvent e) => e.teamId), <int>[home, away, home]);
        expect(goals.every((MatchEvent e) => e.period == 1), isTrue);
      },
    );

    test('no se registran goles antes de iniciar el partido', () async {
      final MatchSession s = await readySession(env);
      await expectLater(
        s.addGoal(teamId: s.detail!.home.id),
        throwsA(isA<StateError>()),
      );
      expect(s.score, const ScoreSummary(0, 0));
    });

    test('solo se admiten los equipos del partido', () async {
      final MatchSession s = await readySession(env);
      await s.startMatch();
      await expectLater(s.addGoal(teamId: 9999), throwsA(isA<ArgumentError>()));
    });

    test('un gol se asocia a un jugador del mismo equipo', () async {
      final MatchSession s = await readySession(env);
      final int home = s.detail!.home.id;
      final int away = s.detail!.away.id;
      final Player luis = await env.players.add(
        teamId: home,
        name: 'Luis',
        number: 9,
      );
      await env.players.add(teamId: away, name: 'Pedro', number: 7);
      await s.load();
      await s.startMatch();

      await s.addGoal(teamId: home, playerId: luis.id);
      expect(s.events.last.playerId, luis.id);
      expect(s.playerById(luis.id)!.name, 'Luis');
      expect(s.playersOf(home).map((Player p) => p.number), <int>[9]);

      await expectLater(
        s.addGoal(teamId: away, playerId: luis.id),
        throwsA(isA<ArgumentError>()),
      );
    });

    test('se puede indicar el minuto a mano', () async {
      final MatchSession s = await readySession(env);
      await s.startMatch();
      await s.addGoal(
        teamId: s.detail!.home.id,
        minute: const GoalMinute(45, 2),
      );
      expect(s.events.last.minute, 45);
      expect(s.events.last.added, 2);
      await expectLater(
        s.addGoal(teamId: s.detail!.home.id, minute: const GoalMinute(0)),
        throwsA(isA<ArgumentError>()),
      );
    });

    test('corregir un gol cambia sus datos y deja una corrección', () async {
      final MatchSession s = await readySession(env);
      final int home = s.detail!.home.id;
      final Player luis = await env.players.add(
        teamId: home,
        name: 'Luis',
        number: 9,
      );
      await s.load();
      await s.startMatch();
      env.now = t0 + 10 * minute;
      await s.addGoal(teamId: home);
      final int goalId = s.events.last.id;

      env.now = t0 + 20 * minute;
      await s.editGoal(goalId, playerId: luis.id, minute: const GoalMinute(8));

      final MatchEvent goal = s.events.firstWhere(
        (MatchEvent e) => e.id == goalId,
      );
      expect(goal.minute, 8);
      expect(goal.playerId, luis.id);
      expect(goal.voided, isFalse);
      expect(s.score, const ScoreSummary(1, 0));

      final MatchEvent correction = s.events.last;
      expect(correction.type, MatchEventType.goalCorrection);
      expect(correction.refEventId, goalId);
      expect(correction.note, 'edited');
    });

    test(
      'pasar el gol al otro equipo cambia el marcador y quita al jugador',
      () async {
        final MatchSession s = await readySession(env);
        final int home = s.detail!.home.id;
        final int away = s.detail!.away.id;
        final Player luis = await env.players.add(
          teamId: home,
          name: 'Luis',
          number: 9,
        );
        await s.load();
        await s.startMatch();
        await s.addGoal(teamId: home, playerId: luis.id);
        final int goalId = s.events.last.id;
        expect(s.score, const ScoreSummary(1, 0));

        await s.editGoal(goalId, teamId: away);
        expect(s.score, const ScoreSummary(0, 1));
        final MatchEvent goal = s.events.firstWhere(
          (MatchEvent e) => e.id == goalId,
        );
        expect(goal.teamId, away);
        expect(goal.playerId, isNull);
      },
    );

    test('corregir sin cambios no agrega eventos', () async {
      final MatchSession s = await readySession(env);
      await s.startMatch();
      await s.addGoal(teamId: s.detail!.home.id);
      final int before = s.events.length;
      await s.editGoal(s.events.last.id, teamId: s.detail!.home.id);
      expect(s.events.length, before);
    });

    test('anular un gol baja el marcador y conserva el historial', () async {
      final MatchSession s = await readySession(env);
      final int home = s.detail!.home.id;
      await s.startMatch();
      await s.addGoal(teamId: home);
      await s.addGoal(teamId: home);
      final int goalId = s.events.last.id;
      expect(s.score, const ScoreSummary(2, 0));

      await s.voidGoal(goalId);
      expect(s.score, const ScoreSummary(1, 0));
      final MatchEvent voided = s.events.firstWhere(
        (MatchEvent e) => e.id == goalId,
      );
      expect(voided.voided, isTrue);
      expect(s.events.last.type, MatchEventType.goalCorrection);
      expect(s.events.last.note, 'voided');
      expect(s.events.last.refEventId, goalId);

      await expectLater(s.voidGoal(goalId), throwsA(isA<StateError>()));
      await expectLater(
        s.editGoal(goalId, minute: const GoalMinute(5)),
        throwsA(isA<StateError>()),
      );
    });

    test(
      'las correcciones siguen permitidas con el partido terminado',
      () async {
        final MatchSession s = await readySession(env);
        await s.startMatch();
        await s.addGoal(teamId: s.detail!.home.id);
        final int goalId = s.events.last.id;
        env.now = t0 + 50 * minute;
        await s.endMatch();
        await s.voidGoal(goalId);
        expect(s.score, const ScoreSummary(0, 0));
      },
    );
  });

  group('persistencia (cerrar y volver a abrir la app)', () {
    test('se recuperan partido, marcador, historial y cronómetro', () async {
      final MatchSession s = await readySession(env);
      final int home = s.detail!.home.id;
      final int away = s.detail!.away.id;
      await s.startMatch();
      env.now = t0 + 10 * minute;
      await s.addGoal(teamId: home);
      env.now = t0 + 20 * minute;
      await s.addGoal(teamId: away);
      await s.addGoal(teamId: away);
      await s.voidGoal(s.events.last.id);

      // La app se cierra y se abre otra vez.
      final MatchSession reopened = env.newSession();
      await reopened.load();

      expect(reopened.detail!.home.name, 'Rojo');
      expect(reopened.detail!.away.name, 'Azul');
      expect(reopened.score, const ScoreSummary(1, 1));
      expect(reopened.events, s.events);
      expect(reopened.clock, s.clock);
      expect(reopened.detail!.match.status, MatchStatus.live);
    });

    test('el cronómetro sigue contando mientras la app está cerrada', () async {
      final MatchSession s = await readySession(env);
      await s.startMatch();
      env.now = t0 + 5 * minute;
      await s.pauseClock();
      env.now = t0 + 8 * minute;
      await s.resumeClock();

      // Se cierra la app 4 minutos y se vuelve a abrir.
      env.now = t0 + 12 * minute;
      final MatchSession reopened = env.newSession();
      await reopened.load();
      expect(reopened.clock.running, isTrue);
      expect(reopened.reading().mainText, '09:00');

      env.now = t0 + 20 * minute;
      expect(reopened.reading().mainText, '17:00');
    });

    test('un cronómetro en pausa se recupera en pausa', () async {
      final MatchSession s = await readySession(env);
      await s.startMatch();
      env.now = t0 + 7 * minute;
      await s.pauseClock();

      env.now = t0 + 100 * minute;
      final MatchSession reopened = env.newSession();
      await reopened.load();
      expect(reopened.clock.running, isFalse);
      expect(reopened.reading().mainText, '07:00');
    });

    test(
      'un partido terminado sigue siendo el actual hasta preparar otro',
      () async {
        final MatchSession s = await readySession(env);
        await s.startMatch();
        await s.addGoal(teamId: s.detail!.home.id);
        env.now = t0 + 50 * minute;
        await s.endMatch();
        final int finishedId = s.detail!.match.id;

        final MatchSession reopened = env.newSession();
        await reopened.load();
        expect(reopened.detail!.match.id, finishedId);
        expect(reopened.detail!.match.status, MatchStatus.finished);
        expect(reopened.score, const ScoreSummary(1, 0));

        // Preparar otro partido crea uno nuevo y no toca el terminado.
        await reopened.saveSetup(sampleSetup(homeName: 'Verde'));
        expect(reopened.detail!.match.id, isNot(finishedId));
        expect(reopened.detail!.match.status, MatchStatus.scheduled);
        expect(reopened.events, isEmpty);
        expect(reopened.score, const ScoreSummary(0, 0));
      },
    );
  });

  test('la sesión avisa a la interfaz cuando cambia', () async {
    final MatchSession s = await readySession(env);
    int notifications = 0;
    s.addListener(() => notifications++);
    await s.startMatch();
    await s.addGoal(teamId: s.detail!.home.id);
    expect(notifications, 2);
  });
}
