import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:transmiti2/core/db/app_database.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/data/sqlite_repositories.dart';
import 'package:transmiti2/features/match/domain/exceptions.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';

import '../support/test_services.dart';

const int minute = 60000;
const int t0 = 1800000000000;

/// Pruebas con SQLite real (en el equipo de pruebas, sin teléfono).
void main() {
  sqfliteFfiInit();

  late Directory dir;
  late String dbPath;
  Database? db;
  int now = t0;

  setUp(() async {
    dir = await Directory.systemTemp.createTemp('transmiti2_db_');
    dbPath = p.join(dir.path, 'test.db');
    now = t0;
  });

  tearDown(() async {
    await db?.close();
    db = null;
    await dir.delete(recursive: true);
  });

  Future<Database> open({
    int? version,
    Map<int, List<String>> migrations = const <int, List<String>>{},
  }) async {
    final Database opened = await AppDatabase.open(
      path: dbPath,
      factory: databaseFactoryFfi,
      targetVersion: version ?? AppDatabase.version,
      extraMigrations: migrations,
    );
    db = opened;
    return opened;
  }

  Future<void> close() async {
    await db?.close();
    db = null;
  }

  MatchSession sessionOver(Database database) => MatchSession(
    matches: SqliteMatchRepository(database, nowMs: () => now),
    teams: SqliteTeamRepository(database, nowMs: () => now),
    players: SqlitePlayerRepository(database, nowMs: () => now),
    sponsors: SqliteSponsorRepository(database, nowMs: () => now),
    nowMs: () => now,
  );

  group('base de datos', () {
    test('crea todas las tablas y activa las claves foráneas', () async {
      final Database database = await open();
      final List<Map<String, Object?>> tables = await database.rawQuery(
        "SELECT name FROM sqlite_master WHERE type = 'table'",
      );
      final Set<Object?> names = tables
          .map((Map<String, Object?> r) => r['name'])
          .toSet();
      for (final String table in <String>[
        'teams',
        'players',
        'sponsors',
        'matches',
        'match_sponsors',
        'match_clock',
        'match_events',
      ]) {
        expect(names.contains(table), isTrue, reason: 'falta la tabla $table');
      }
      final List<Map<String, Object?>> fk = await database.rawQuery(
        'PRAGMA foreign_keys',
      );
      expect(fk.first['foreign_keys'], 1);
    });

    test('la migración a una versión nueva conserva los datos', () async {
      Database database = await open();
      await SqliteSponsorRepository(database).add(name: 'Casa Pepe');
      await close();

      database = await open(
        version: 2,
        migrations: <int, List<String>>{
          2: <String>['ALTER TABLE sponsors ADD COLUMN website TEXT'],
        },
      );
      final List<Sponsor> sponsors = await SqliteSponsorRepository(database)
          .all();
      expect(sponsors.map((Sponsor s) => s.name), <String>['Casa Pepe']);
      final List<Map<String, Object?>> columns = await database.rawQuery(
        'PRAGMA table_info(sponsors)',
      );
      expect(
        columns.map((Map<String, Object?> c) => c['name']),
        contains('website'),
      );
    });

    test(
      'crear la base directamente en una versión nueva aplica las migraciones',
      () async {
        final Database database = await open(
          version: 2,
          migrations: <int, List<String>>{
            2: <String>['ALTER TABLE sponsors ADD COLUMN website TEXT'],
          },
        );
        final List<Map<String, Object?>> columns = await database.rawQuery(
          'PRAGMA table_info(sponsors)',
        );
        expect(
          columns.map((Map<String, Object?> c) => c['name']),
          contains('website'),
        );
      },
    );

    test(
      'si falta una migración la apertura falla en lugar de dañar la base',
      () async {
        await open();
        await close();
        await expectLater(open(version: 3), throwsA(anything));
      },
    );
  });

  group('equipos', () {
    test(
      'un equipo se reconoce por su nombre sin importar mayúsculas',
      () async {
        final SqliteTeamRepository teams = SqliteTeamRepository(await open());
        final Team a = await teams.saveByName(
          name: 'Los Tigres',
          colorValue: 0xFFE53935,
          crestPath: 'crests/a.png',
        );
        final Team b = await teams.saveByName(
          name: '  los   TIGRES ',
          colorValue: 0xFF1E88E5,
        );
        expect(b.id, a.id);
        expect(b.name, 'los TIGRES');
        expect(b.colorValue, 0xFF1E88E5);
        expect(b.crestPath, isNull);

        final Team? stored = await teams.byId(a.id);
        expect(stored, b);
        expect((await teams.all()).length, 1);
      },
    );
  });

  group('jugadores', () {
    test('no permiten números repetidos dentro del mismo equipo', () async {
      final Database database = await open();
      final SqliteTeamRepository teams = SqliteTeamRepository(database);
      final SqlitePlayerRepository players = SqlitePlayerRepository(database);
      final Team rojo = await teams.saveByName(name: 'Rojo', colorValue: 1);
      final Team azul = await teams.saveByName(name: 'Azul', colorValue: 2);

      final Player luis = await players.add(
        teamId: rojo.id,
        name: ' Luis ',
        number: 9,
      );
      expect(luis.name, 'Luis');
      await expectLater(
        players.add(teamId: rojo.id, name: 'Otro', number: 9),
        throwsA(isA<DuplicateNumberException>()),
      );
      // El mismo número en el otro equipo sí se permite.
      await players.add(teamId: azul.id, name: 'Pedro', number: 9);

      final Player ana = await players.add(
        teamId: rojo.id,
        name: 'Ana',
        number: 10,
      );
      await expectLater(
        players.update(ana.copyWith(number: 9)),
        throwsA(isA<DuplicateNumberException>()),
      );
    });

    test('se editan, se ordenan por número y se eliminan', () async {
      final Database database = await open();
      final Team rojo = await SqliteTeamRepository(database)
          .saveByName(name: 'Rojo', colorValue: 1);
      final SqlitePlayerRepository players = SqlitePlayerRepository(database);
      final Player b = await players.add(
        teamId: rojo.id,
        name: 'B',
        number: 10,
      );
      await players.add(teamId: rojo.id, name: 'A', number: 3);

      expect((await players.byTeam(rojo.id)).map((Player p) => p.number), <int>[
        3,
        10,
      ]);

      await players.update(b.copyWith(name: 'Beto', number: 1));
      expect(
        (await players.byTeam(rojo.id)).map((Player p) => p.name),
        <String>['Beto', 'A'],
      );

      await players.delete(b.id);
      expect((await players.byTeam(rojo.id)).length, 1);
    });

    test('los datos inválidos se rechazan', () async {
      final Database database = await open();
      final Team rojo = await SqliteTeamRepository(database)
          .saveByName(name: 'Rojo', colorValue: 1);
      final SqlitePlayerRepository players = SqlitePlayerRepository(database);
      await expectLater(
        players.add(teamId: rojo.id, name: '', number: 5),
        throwsA(isA<ValidationException>()),
      );
      await expectLater(
        players.add(teamId: rojo.id, name: 'Luis', number: 100),
        throwsA(isA<ValidationException>()),
      );
    });
  });

  group('patrocinadores', () {
    test('se crean, editan, ordenan y eliminan sin límite', () async {
      final SqliteSponsorRepository sponsors = SqliteSponsorRepository(
        await open(),
      );
      final Sponsor b = await sponsors.add(
        name: 'beta',
        logoPath: 'logos/b.png',
      );
      await sponsors.add(name: 'Alfa');
      for (int i = 0; i < 30; i++) {
        await sponsors.add(name: 'Marca $i');
      }
      final List<Sponsor> all = await sponsors.all();
      expect(all.length, 32);
      expect(all.first.name, 'Alfa');
      expect(all[1].name, 'beta');

      final Sponsor edited = await sponsors.update(
        b.copyWith(name: ' Beta Plus ', clearLogo: true),
      );
      expect(edited.name, 'Beta Plus');
      expect(
        (await sponsors.all()).firstWhere((Sponsor s) => s.id == b.id).logoPath,
        isNull,
      );

      await sponsors.delete(b.id);
      expect((await sponsors.all()).length, 31);
    });
  });

  group('partidos', () {
    Future<(SqliteMatchRepository, int, int)> prepare(Database database) async {
      final SqliteTeamRepository teams = SqliteTeamRepository(database);
      final Team rojo = await teams.saveByName(name: 'Rojo', colorValue: 1);
      final Team azul = await teams.saveByName(name: 'Azul', colorValue: 2);
      return (SqliteMatchRepository(database), rojo.id, azul.id);
    }

    test(
      'crear deja el partido programado con el cronómetro en cero',
      () async {
        final (SqliteMatchRepository matches, int home, int away) =
            await prepare(await open());
        final SoccerMatch m = await matches.create(
          homeTeamId: home,
          awayTeamId: away,
          tournament: 'Liga',
          scheduledAtMs: t0,
          halfMinutes: 45,
          breakMinutes: 15,
        );
        expect(m.status, MatchStatus.scheduled);
        expect(await matches.current(), m);
        expect(await matches.clock(m.id), MatchClockState.initial);
        expect(await matches.events(m.id), isEmpty);
      },
    );

    test('el partido actual es el último no cancelado', () async {
      final (SqliteMatchRepository matches, int home, int away) = await prepare(
        await open(),
      );
      final SoccerMatch first = await matches.create(
        homeTeamId: home,
        awayTeamId: away,
        scheduledAtMs: t0,
        halfMinutes: 45,
        breakMinutes: 15,
      );
      final SoccerMatch second = await matches.create(
        homeTeamId: home,
        awayTeamId: away,
        scheduledAtMs: t0,
        halfMinutes: 30,
        breakMinutes: 10,
      );
      expect((await matches.current())!.id, second.id);

      await matches.setStatus(second.id, MatchStatus.finished);
      expect((await matches.current())!.id, second.id);

      await matches.setStatus(second.id, MatchStatus.cancelled);
      expect((await matches.current())!.id, first.id);

      await matches.update(
        first.copyWith(halfMinutes: 20, clearTournament: true),
      );
      expect((await matches.current())!.halfMinutes, 20);
    });

    test(
      'los patrocinadores del partido conservan el orden y no se repiten',
      () async {
        final Database database = await open();
        final (SqliteMatchRepository matches, int home, int away) =
            await prepare(database);
        final SqliteSponsorRepository sponsors = SqliteSponsorRepository(
          database,
        );
        final Sponsor a = await sponsors.add(name: 'A');
        final Sponsor b = await sponsors.add(name: 'B');
        final Sponsor c = await sponsors.add(name: 'C');
        final SoccerMatch m = await matches.create(
          homeTeamId: home,
          awayTeamId: away,
          scheduledAtMs: t0,
          halfMinutes: 45,
          breakMinutes: 15,
        );

        await matches.setSponsors(m.id, <int>[c.id, a.id, c.id, b.id]);
        expect(await matches.sponsorIds(m.id), <int>[c.id, a.id, b.id]);

        await matches.setSponsors(m.id, <int>[b.id]);
        expect(await matches.sponsorIds(m.id), <int>[b.id]);

        // Al eliminar un patrocinador deja de estar en los partidos.
        await sponsors.delete(b.id);
        expect(await matches.sponsorIds(m.id), isEmpty);
      },
    );

    test('el cronómetro, el estado y el evento se guardan juntos', () async {
      final (SqliteMatchRepository matches, int home, int away) = await prepare(
        await open(),
      );
      final SoccerMatch m = await matches.create(
        homeTeamId: home,
        awayTeamId: away,
        scheduledAtMs: t0,
        halfMinutes: 45,
        breakMinutes: 15,
      );
      final MatchClockState clock = MatchClockState.initial.start(t0);
      final MatchEvent? saved = await matches.commitClock(
        matchId: m.id,
        clock: clock,
        nowMs: t0,
        status: MatchStatus.live,
        event: MatchEvent(
          id: 0,
          matchId: m.id,
          type: MatchEventType.periodStart,
          period: 1,
          minute: 0,
          clockMs: 0,
          createdAtMs: t0,
        ),
      );
      expect(saved!.id, greaterThan(0));
      expect(await matches.clock(m.id), clock);
      expect((await matches.current())!.status, MatchStatus.live);
      expect(await matches.events(m.id), <MatchEvent>[saved]);

      final MatchClockState paused = clock.pause(t0 + minute);
      expect(
        await matches.commitClock(
          matchId: m.id,
          clock: paused,
          nowMs: t0 + minute,
        ),
        isNull,
      );
      expect(await matches.clock(m.id), paused);
    });

    test('corregir un gol actualiza el gol y agrega la corrección', () async {
      final (SqliteMatchRepository matches, int home, int away) = await prepare(
        await open(),
      );
      final SoccerMatch m = await matches.create(
        homeTeamId: home,
        awayTeamId: away,
        scheduledAtMs: t0,
        halfMinutes: 45,
        breakMinutes: 15,
      );
      final MatchEvent goal = await matches.addEvent(
        MatchEvent(
          id: 0,
          matchId: m.id,
          type: MatchEventType.goal,
          teamId: home,
          period: 1,
          minute: 10,
          clockMs: 10 * minute,
          createdAtMs: t0,
        ),
      );
      final MatchEvent correction = await matches.correctGoal(
        updatedGoal: goal.copyWith(voided: true),
        correction: MatchEvent(
          id: 0,
          matchId: m.id,
          type: MatchEventType.goalCorrection,
          teamId: home,
          period: 1,
          minute: 12,
          clockMs: 12 * minute,
          refEventId: goal.id,
          note: 'voided',
          createdAtMs: t0,
        ),
      );
      final List<MatchEvent> events = await matches.events(m.id);
      expect(events.length, 2);
      expect(events[0].voided, isTrue);
      expect(events[1], correction);
      expect(events[1].refEventId, goal.id);
    });

    test(
      'al borrar un partido se borran su cronómetro, eventos y patrocinadores',
      () async {
        final Database database = await open();
        final (SqliteMatchRepository matches, int home, int away) =
            await prepare(database);
        final SqliteSponsorRepository sponsors = SqliteSponsorRepository(
          database,
        );
        final Sponsor a = await sponsors.add(name: 'A');
        final SoccerMatch m = await matches.create(
          homeTeamId: home,
          awayTeamId: away,
          scheduledAtMs: t0,
          halfMinutes: 45,
          breakMinutes: 15,
        );
        await matches.setSponsors(m.id, <int>[a.id]);
        await matches.addEvent(
          MatchEvent(
            id: 0,
            matchId: m.id,
            type: MatchEventType.goal,
            teamId: home,
            period: 1,
            minute: 1,
            clockMs: 1,
            createdAtMs: t0,
          ),
        );

        await database.delete(
          'matches',
          where: 'id = ?',
          whereArgs: <Object?>[m.id],
        );
        for (final String table in <String>[
          'match_clock',
          'match_events',
          'match_sponsors',
        ]) {
          final List<Map<String, Object?>> rows = await database.query(table);
          expect(rows, isEmpty, reason: 'quedaron filas en $table');
        }
        // Los patrocinadores y los equipos no se tocan.
        expect((await sponsors.all()).length, 1);
      },
    );

    test('al eliminar un jugador sus goles se conservan sin jugador', () async {
      final Database database = await open();
      final (SqliteMatchRepository matches, int home, int away) = await prepare(
        database,
      );
      final Player luis = await SqlitePlayerRepository(database)
          .add(teamId: home, name: 'Luis', number: 9);
      final SoccerMatch m = await matches.create(
        homeTeamId: home,
        awayTeamId: away,
        scheduledAtMs: t0,
        halfMinutes: 45,
        breakMinutes: 15,
      );
      await matches.addEvent(
        MatchEvent(
          id: 0,
          matchId: m.id,
          type: MatchEventType.goal,
          teamId: home,
          playerId: luis.id,
          period: 1,
          minute: 5,
          clockMs: 5 * minute,
          createdAtMs: t0,
        ),
      );
      await SqlitePlayerRepository(database).delete(luis.id);
      final List<MatchEvent> events = await matches.events(m.id);
      expect(events.length, 1);
      expect(events.first.playerId, isNull);
    });
  });

  group('persistencia de todo el partido (cerrar y volver a abrir la app)', () {
    test('partidos, equipos, escudos, jugadores, patrocinadores, marcador, cronómetro e historial', () async {
      Database database = await open();
      final SqlitePlayerRepository players = SqlitePlayerRepository(database);
      final SqliteSponsorRepository sponsors = SqliteSponsorRepository(
        database,
      );
      final Sponsor casa = await sponsors.add(
        name: 'Casa Pepe',
        logoPath: 'logos/casa.png',
      );
      final Sponsor taller = await sponsors.add(name: 'Taller Sol');

      MatchSession session = sessionOver(database);
      await session.load();
      await session.saveSetup(
        sampleSetup(
          tournament: 'Liga local',
          sponsorIds: <int>[taller.id, casa.id],
        ).copyWithCrests(home: 'crests/rojo.png', away: 'crests/azul.jpg'),
      );
      final int home = session.detail!.home.id;
      final int away = session.detail!.away.id;
      final Player luis = await players.add(
        teamId: home,
        name: 'Luis',
        number: 9,
      );
      await session.load();

      await session.startMatch();
      now = t0 + 10 * minute;
      await session.addGoal(teamId: home, playerId: luis.id);
      now = t0 + 25 * minute;
      await session.addGoal(teamId: away);
      await session.addGoal(teamId: away);
      await session.voidGoal(session.events.last.id);
      now = t0 + 30 * minute;
      await session.pauseClock();

      final List<MatchEvent> eventsBefore = session.events;
      expect(session.score, const ScoreSummary(1, 1));

      // Se cierra la app: se cierra la base. Se abre otra vez, 1 hora después.
      await close();
      now = t0 + 90 * minute;
      database = await open();
      session = sessionOver(database);
      await session.load();

      expect(session.detail!.home.name, 'Rojo');
      expect(session.detail!.home.crestPath, 'crests/rojo.png');
      expect(session.detail!.away.crestPath, 'crests/azul.jpg');
      expect(session.detail!.match.tournament, 'Liga local');
      expect(session.detail!.sponsors.map((Sponsor s) => s.name), <String>[
        'Taller Sol',
        'Casa Pepe',
      ]);
      expect(session.detail!.sponsors.last.logoPath, 'logos/casa.png');
      expect(session.playersOf(home).single.name, 'Luis');
      expect(session.score, const ScoreSummary(1, 1));
      expect(session.events, eventsBefore);
      expect(session.clock.running, isFalse);
      expect(session.reading().mainText, '30:00');
      expect(session.detail!.match.status, MatchStatus.live);
    });

    test('un cronómetro en marcha sigue contando después de reabrir', () async {
      Database database = await open();
      MatchSession session = sessionOver(database);
      await session.load();
      await session.saveSetup(sampleSetup());
      await session.startMatch();

      await close();
      now = t0 + 17 * minute;
      database = await open();
      session = sessionOver(database);
      await session.load();
      expect(session.clock.running, isTrue);
      expect(session.reading().mainText, '17:00');
    });
  });
}

extension on MatchSetup {
  MatchSetup copyWithCrests({String? home, String? away}) => MatchSetup(
    homeName: homeName,
    homeColor: homeColor,
    homeCrestPath: home,
    awayName: awayName,
    awayColor: awayColor,
    awayCrestPath: away,
    tournament: tournament,
    scheduledAtMs: scheduledAtMs,
    halfMinutes: halfMinutes,
    breakMinutes: breakMinutes,
    sponsorIds: sponsorIds,
  );
}
