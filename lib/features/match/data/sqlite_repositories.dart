import 'package:sqflite/sqflite.dart';
import 'package:transmiti2/core/time.dart';
import 'package:transmiti2/features/match/data/repositories.dart';
import 'package:transmiti2/features/match/domain/exceptions.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';

class SqliteTeamRepository implements TeamRepository {
  SqliteTeamRepository(this._db, {NowMs? nowMs})
    : _nowMs = nowMs ?? systemNowMs;

  final Database _db;
  final NowMs _nowMs;

  static Team _fromRow(Map<String, Object?> r) => Team(
    id: r['id']! as int,
    name: r['name']! as String,
    colorValue: r['color']! as int,
    crestPath: r['crest_path'] as String?,
  );

  @override
  Future<Team?> byId(int id) async {
    final List<Map<String, Object?>> rows = await _db.query(
      'teams',
      where: 'id = ?',
      whereArgs: <Object?>[id],
      limit: 1,
    );
    return rows.isEmpty ? null : _fromRow(rows.first);
  }

  @override
  Future<List<Team>> all() async {
    final List<Map<String, Object?>> rows = await _db.query(
      'teams',
      orderBy: 'name COLLATE NOCASE ASC',
    );
    return rows.map(_fromRow).toList();
  }

  @override
  Future<Team> saveByName({
    required String name,
    required int colorValue,
    String? crestPath,
  }) {
    final String clean = Team.cleanName(name);
    final String key = Team.keyOf(name);
    return _db.transaction<Team>((Transaction txn) async {
      final List<Map<String, Object?>> rows = await txn.query(
        'teams',
        where: 'name_key = ?',
        whereArgs: <Object?>[key],
        limit: 1,
      );
      if (rows.isEmpty) {
        final int id = await txn.insert('teams', <String, Object?>{
          'name': clean,
          'name_key': key,
          'color': colorValue,
          'crest_path': crestPath,
          'created_at_ms': _nowMs(),
        });
        return Team(
          id: id,
          name: clean,
          colorValue: colorValue,
          crestPath: crestPath,
        );
      }
      final int id = rows.first['id']! as int;
      await txn.update(
        'teams',
        <String, Object?>{
          'name': clean,
          'color': colorValue,
          'crest_path': crestPath,
        },
        where: 'id = ?',
        whereArgs: <Object?>[id],
      );
      return Team(
        id: id,
        name: clean,
        colorValue: colorValue,
        crestPath: crestPath,
      );
    });
  }
}

class SqlitePlayerRepository implements PlayerRepository {
  SqlitePlayerRepository(this._db, {NowMs? nowMs})
    : _nowMs = nowMs ?? systemNowMs;

  final Database _db;
  final NowMs _nowMs;

  static Player _fromRow(Map<String, Object?> r) => Player(
    id: r['id']! as int,
    teamId: r['team_id']! as int,
    name: r['name']! as String,
    number: r['number']! as int,
  );

  static void _validate(String name, int number) {
    final Map<String, String> errors = PlayerRules.validate(
      name: name,
      number: number,
    );
    if (errors.isNotEmpty) {
      throw ValidationException(errors);
    }
  }

  @override
  Future<List<Player>> byTeam(int teamId) async {
    final List<Map<String, Object?>> rows = await _db.query(
      'players',
      where: 'team_id = ?',
      whereArgs: <Object?>[teamId],
      orderBy: 'number ASC',
    );
    return rows.map(_fromRow).toList();
  }

  @override
  Future<Player> add({
    required int teamId,
    required String name,
    required int number,
  }) async {
    _validate(name, number);
    final String clean = name.trim();
    try {
      final int id = await _db.insert('players', <String, Object?>{
        'team_id': teamId,
        'name': clean,
        'number': number,
        'created_at_ms': _nowMs(),
      });
      return Player(id: id, teamId: teamId, name: clean, number: number);
    } on DatabaseException catch (e) {
      if (e.isUniqueConstraintError()) {
        throw DuplicateNumberException(number);
      }
      rethrow;
    }
  }

  @override
  Future<Player> update(Player player) async {
    _validate(player.name, player.number);
    final String clean = player.name.trim();
    try {
      await _db.update(
        'players',
        <String, Object?>{'name': clean, 'number': player.number},
        where: 'id = ?',
        whereArgs: <Object?>[player.id],
      );
      return player.copyWith(name: clean);
    } on DatabaseException catch (e) {
      if (e.isUniqueConstraintError()) {
        throw DuplicateNumberException(player.number);
      }
      rethrow;
    }
  }

  @override
  Future<void> delete(int id) async {
    await _db.delete('players', where: 'id = ?', whereArgs: <Object?>[id]);
  }
}

class SqliteSponsorRepository implements SponsorRepository {
  SqliteSponsorRepository(this._db, {NowMs? nowMs})
    : _nowMs = nowMs ?? systemNowMs;

  final Database _db;
  final NowMs _nowMs;

  static Sponsor _fromRow(Map<String, Object?> r) => Sponsor(
    id: r['id']! as int,
    name: r['name']! as String,
    logoPath: r['logo_path'] as String?,
  );

  static void _validate(String name) {
    final Map<String, String> errors = SponsorRules.validate(name: name);
    if (errors.isNotEmpty) {
      throw ValidationException(errors);
    }
  }

  @override
  Future<List<Sponsor>> all() async {
    final List<Map<String, Object?>> rows = await _db.query(
      'sponsors',
      orderBy: 'name COLLATE NOCASE ASC, id ASC',
    );
    return rows.map(_fromRow).toList();
  }

  @override
  Future<Sponsor> add({required String name, String? logoPath}) async {
    _validate(name);
    final String clean = name.trim();
    final int id = await _db.insert('sponsors', <String, Object?>{
      'name': clean,
      'logo_path': logoPath,
      'created_at_ms': _nowMs(),
    });
    return Sponsor(id: id, name: clean, logoPath: logoPath);
  }

  @override
  Future<Sponsor> update(Sponsor sponsor) async {
    _validate(sponsor.name);
    final String clean = sponsor.name.trim();
    await _db.update(
      'sponsors',
      <String, Object?>{'name': clean, 'logo_path': sponsor.logoPath},
      where: 'id = ?',
      whereArgs: <Object?>[sponsor.id],
    );
    return sponsor.copyWith(name: clean);
  }

  @override
  Future<void> delete(int id) async {
    await _db.delete('sponsors', where: 'id = ?', whereArgs: <Object?>[id]);
  }
}

class SqliteMatchRepository implements MatchRepository {
  SqliteMatchRepository(this._db, {NowMs? nowMs})
    : _nowMs = nowMs ?? systemNowMs;

  final Database _db;
  final NowMs _nowMs;

  static SoccerMatch _matchFromRow(Map<String, Object?> r) => SoccerMatch(
    id: r['id']! as int,
    homeTeamId: r['home_team_id']! as int,
    awayTeamId: r['away_team_id']! as int,
    tournament: r['tournament'] as String?,
    scheduledAtMs: r['scheduled_at_ms']! as int,
    halfMinutes: r['half_minutes']! as int,
    breakMinutes: r['break_minutes']! as int,
    status: MatchStatus.values.byName(r['status']! as String),
    createdAtMs: r['created_at_ms']! as int,
  );

  static Map<String, Object?> _eventToRow(MatchEvent e) => <String, Object?>{
    'match_id': e.matchId,
    'type': e.type.name,
    'team_id': e.teamId,
    'player_id': e.playerId,
    'period': e.period,
    'minute': e.minute,
    'added': e.added,
    'clock_ms': e.clockMs,
    'ref_event_id': e.refEventId,
    'voided': e.voided ? 1 : 0,
    'note': e.note,
    'created_at_ms': e.createdAtMs,
  };

  static MatchEvent _eventFromRow(Map<String, Object?> r) => MatchEvent(
    id: r['id']! as int,
    matchId: r['match_id']! as int,
    type: MatchEventType.values.byName(r['type']! as String),
    teamId: r['team_id'] as int?,
    playerId: r['player_id'] as int?,
    period: r['period']! as int,
    minute: r['minute']! as int,
    added: r['added']! as int,
    clockMs: r['clock_ms']! as int,
    refEventId: r['ref_event_id'] as int?,
    voided: (r['voided']! as int) == 1,
    note: r['note'] as String?,
    createdAtMs: r['created_at_ms']! as int,
  );

  static Future<void> _saveClock(
    DatabaseExecutor ex,
    int matchId,
    MatchClockState clock,
    int nowMs,
  ) async {
    await ex.insert('match_clock', <String, Object?>{
      'match_id': matchId,
      ...clock.toMap(),
      'updated_at_ms': nowMs,
    }, conflictAlgorithm: ConflictAlgorithm.replace);
  }

  static Future<MatchEvent> _insertEvent(
    DatabaseExecutor ex,
    MatchEvent event,
  ) async {
    final int id = await ex.insert('match_events', _eventToRow(event));
    return event.copyWith(id: id);
  }

  @override
  Future<SoccerMatch?> current() async {
    final List<Map<String, Object?>> rows = await _db.query(
      'matches',
      where: 'status != ?',
      whereArgs: <Object?>[MatchStatus.cancelled.name],
      orderBy: 'id DESC',
      limit: 1,
    );
    return rows.isEmpty ? null : _matchFromRow(rows.first);
  }

  @override
  Future<SoccerMatch> create({
    required int homeTeamId,
    required int awayTeamId,
    String? tournament,
    required int scheduledAtMs,
    required int halfMinutes,
    required int breakMinutes,
  }) {
    return _db.transaction<SoccerMatch>((Transaction txn) async {
      final int now = _nowMs();
      final int id = await txn.insert('matches', <String, Object?>{
        'home_team_id': homeTeamId,
        'away_team_id': awayTeamId,
        'tournament': tournament,
        'scheduled_at_ms': scheduledAtMs,
        'half_minutes': halfMinutes,
        'break_minutes': breakMinutes,
        'status': MatchStatus.scheduled.name,
        'created_at_ms': now,
      });
      await _saveClock(txn, id, MatchClockState.initial, now);
      return SoccerMatch(
        id: id,
        homeTeamId: homeTeamId,
        awayTeamId: awayTeamId,
        tournament: tournament,
        scheduledAtMs: scheduledAtMs,
        halfMinutes: halfMinutes,
        breakMinutes: breakMinutes,
        status: MatchStatus.scheduled,
        createdAtMs: now,
      );
    });
  }

  @override
  Future<void> update(SoccerMatch match) async {
    await _db.update(
      'matches',
      <String, Object?>{
        'home_team_id': match.homeTeamId,
        'away_team_id': match.awayTeamId,
        'tournament': match.tournament,
        'scheduled_at_ms': match.scheduledAtMs,
        'half_minutes': match.halfMinutes,
        'break_minutes': match.breakMinutes,
        'status': match.status.name,
      },
      where: 'id = ?',
      whereArgs: <Object?>[match.id],
    );
  }

  @override
  Future<void> setStatus(int matchId, MatchStatus status) async {
    await _db.update(
      'matches',
      <String, Object?>{'status': status.name},
      where: 'id = ?',
      whereArgs: <Object?>[matchId],
    );
  }

  @override
  Future<List<int>> sponsorIds(int matchId) async {
    final List<Map<String, Object?>> rows = await _db.query(
      'match_sponsors',
      columns: <String>['sponsor_id'],
      where: 'match_id = ?',
      whereArgs: <Object?>[matchId],
      orderBy: 'position ASC',
    );
    return rows
        .map((Map<String, Object?> r) => r['sponsor_id']! as int)
        .toList();
  }

  @override
  Future<void> setSponsors(int matchId, List<int> sponsorIds) {
    final List<int> ordered = sponsorIds.toSet().toList();
    return _db.transaction<void>((Transaction txn) async {
      await txn.delete(
        'match_sponsors',
        where: 'match_id = ?',
        whereArgs: <Object?>[matchId],
      );
      for (int i = 0; i < ordered.length; i++) {
        await txn.insert('match_sponsors', <String, Object?>{
          'match_id': matchId,
          'sponsor_id': ordered[i],
          'position': i,
        });
      }
    });
  }

  @override
  Future<MatchClockState> clock(int matchId) async {
    final List<Map<String, Object?>> rows = await _db.query(
      'match_clock',
      where: 'match_id = ?',
      whereArgs: <Object?>[matchId],
      limit: 1,
    );
    return rows.isEmpty
        ? MatchClockState.initial
        : MatchClockState.fromMap(rows.first);
  }

  @override
  Future<MatchEvent?> commitClock({
    required int matchId,
    required MatchClockState clock,
    required int nowMs,
    MatchStatus? status,
    MatchEvent? event,
  }) {
    return _db.transaction<MatchEvent?>((Transaction txn) async {
      await _saveClock(txn, matchId, clock, nowMs);
      if (status != null) {
        await txn.update(
          'matches',
          <String, Object?>{'status': status.name},
          where: 'id = ?',
          whereArgs: <Object?>[matchId],
        );
      }
      if (event == null) {
        return null;
      }
      return _insertEvent(txn, event);
    });
  }

  @override
  Future<List<MatchEvent>> events(int matchId) async {
    final List<Map<String, Object?>> rows = await _db.query(
      'match_events',
      where: 'match_id = ?',
      whereArgs: <Object?>[matchId],
      orderBy: 'id ASC',
    );
    return rows.map(_eventFromRow).toList();
  }

  @override
  Future<MatchEvent> addEvent(MatchEvent event) => _insertEvent(_db, event);

  @override
  Future<MatchEvent> correctGoal({
    required MatchEvent updatedGoal,
    required MatchEvent correction,
  }) {
    return _db.transaction<MatchEvent>((Transaction txn) async {
      await txn.update(
        'match_events',
        _eventToRow(updatedGoal),
        where: 'id = ?',
        whereArgs: <Object?>[updatedGoal.id],
      );
      return _insertEvent(txn, correction);
    });
  }
}
