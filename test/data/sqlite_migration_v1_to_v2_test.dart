import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:transmiti2/core/db/app_database.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/data/sqlite_repositories.dart';
import 'package:transmiti2/features/match/domain/goal_minute.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';

import '../support/test_services.dart';

const int minute = 60000;
const int t0 = 1800000000000;

/// Tablas de la versión 1: todo lo que la migración debe conservar intacto.
const List<String> v1Tables = <String>[
  'teams',
  'players',
  'sponsors',
  'matches',
  'match_sponsors',
  'match_clock',
  'match_events',
];

/// Migración v1 -> v2 con una base real de Etapa 2: partidos, goles,
/// reloj, equipos y patrocinadores se conservan; las tablas nuevas
/// empiezan vacías.
void main() {
  sqfliteFfiInit();

  late Directory dir;
  late String dbPath;
  Database? db;
  int now = t0;

  setUp(() async {
    dir = await Directory.systemTemp.createTemp('transmiti2_mig_');
    dbPath = p.join(dir.path, 'test.db');
    now = t0;
  });

  tearDown(() async {
    await db?.close();
    db = null;
    await dir.delete(recursive: true);
  });

  Future<Database> open({int? version}) async {
    final Database opened = await AppDatabase.open(
      path: dbPath,
      factory: databaseFactoryFfi,
      targetVersion: version ?? AppDatabase.version,
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

  Future<Map<String, List<Map<String, Object?>>>> snapshot(
    Database database,
  ) async {
    final Map<String, List<Map<String, Object?>>> out =
        <String, List<Map<String, Object?>>>{};
    for (final String table in v1Tables) {
      if (table == 'match_clock' || table == 'match_sponsors') {
        continue;
      }
      out[table] = await database.query(table, orderBy: 'id ASC');
    }
    // match_clock y match_sponsors no tienen columna id: ordenar por clave.
    out['match_clock'] = await database.query(
      'match_clock',
      orderBy: 'match_id ASC',
    );
    out['match_sponsors'] = await database.query(
      'match_sponsors',
      orderBy: 'match_id ASC, sponsor_id ASC',
    );
    return out;
  }

  test('migra una base v1 real conservando partidos, goles y reloj', () async {
    // 1. Sembrar una base v1 con los repositorios reales de Etapa 2.
    final Database v1 = await open(version: 1);
    final MatchSession session = sessionOver(v1);
    final SqliteSponsorRepository sponsors = SqliteSponsorRepository(
      v1,
      nowMs: () => now,
    );
    final SqlitePlayerRepository players = SqlitePlayerRepository(
      v1,
      nowMs: () => now,
    );
    final Sponsor sponsorA = await sponsors.add(name: 'Patrocinador A');
    final Sponsor sponsorB = await sponsors.add(name: 'Patrocinador B');
    await session.saveSetup(
      sampleSetup(sponsorIds: <int>[sponsorA.id, sponsorB.id]),
      createNew: true,
    );
    final int homeId = session.detail!.home.id;
    final int awayId = session.detail!.away.id;
    final Player striker = await players.add(
      teamId: homeId,
      name: 'Delantero',
      number: 9,
    );
    await players.add(teamId: awayId, name: 'Extremo', number: 11);
    // Recargar la sesión para que conozca a los jugadores añadidos.
    await session.load();

    now = t0;
    await session.startMatch();
    now = t0 + 23 * minute;
    await session.addGoal(
      teamId: homeId,
      playerId: striker.id,
      minute: const GoalMinute(23),
    );
    final int firstGoalId =
        session.events.where((e) => e.isActiveGoal).first.id;
    now = t0 + 45 * minute;
    await session.endFirstHalf();
    now = t0 + 60 * minute;
    await session.startSecondHalf();
    now = t0 + 101 * minute;
    await session.addGoal(teamId: awayId, minute: const GoalMinute(41));
    now = t0 + 103 * minute;
    await session.addGoal(teamId: homeId, minute: const GoalMinute(43));
    final int thirdGoalId =
        session.events.where((e) => e.isActiveGoal).last.id;
    await session.voidGoal(thirdGoalId);
    await session.editGoal(firstGoalId, minute: const GoalMinute(24));
    expect(session.score.home, 1);
    expect(session.score.away, 1);

    final Map<String, List<Map<String, Object?>>> before = await snapshot(v1);
    await close();

    // 2. Reabrir en v2: corre la migración.
    final Database v2 = await open();
    expect(await v2.getVersion(), 2);

    // 3. Todas las tablas v1 quedan byte a byte iguales.
    final Map<String, List<Map<String, Object?>>> after = await snapshot(v2);
    for (final String table in v1Tables) {
      expect(after[table], before[table], reason: 'tabla $table cambió');
    }

    // 4. El partido sigue leyéndose igual: mismo marcador y mismo reloj.
    final MatchSession reloaded = sessionOver(v2);
    await reloaded.load();
    expect(reloaded.score.home, 1);
    expect(reloaded.score.away, 1);
    expect(reloaded.detail!.home.name, 'Rojo');
    expect(reloaded.detail!.away.name, 'Azul');
    expect(reloaded.detail!.sponsors.length, 2);
    expect(reloaded.clock.period, 2);
    expect(reloaded.clock.running, isTrue);

    // 5. Las tablas nuevas existen y empiezan vacías.
    for (final String table in <String>[
      'stream_destinations',
      'stream_sessions',
      'recordings',
    ]) {
      final List<Map<String, Object?>> rows = await v2.query(table);
      expect(rows, isEmpty, reason: 'tabla $table no empieza vacía');
    }
    await close();
  });

  test('una instalación nueva crea directamente la versión 2', () async {
    final Database database = await open();
    expect(await database.getVersion(), 2);
    final List<Map<String, Object?>> tables = await database.rawQuery(
      "SELECT name FROM sqlite_master WHERE type = 'table'",
    );
    final Set<Object?> names = tables
        .map((Map<String, Object?> r) => r['name'])
        .toSet();
    for (final String table in <String>[
      ...v1Tables,
      'stream_destinations',
      'stream_sessions',
      'recordings',
    ]) {
      expect(names, contains(table));
    }
    await close();
  });
}
