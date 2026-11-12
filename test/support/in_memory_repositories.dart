import 'package:transmiti2/core/time.dart';
import 'package:transmiti2/features/match/data/repositories.dart';
import 'package:transmiti2/features/match/domain/exceptions.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';

// Repositorios en memoria para pruebas rápidas. Reproducen las reglas de los
// repositorios SQLite; crear otra MatchSession sobre los mismos repositorios
// equivale a cerrar y volver a abrir la app.

class InMemoryTeamRepository implements TeamRepository {
  final Map<int, Team> _teams = <int, Team>{};
  int _nextId = 1;

  @override
  Future<Team?> byId(int id) async => _teams[id];

  @override
  Future<List<Team>> all() async => _teams.values.toList();

  @override
  Future<Team> saveByName({
    required String name,
    required int colorValue,
    String? crestPath,
  }) async {
    final String key = Team.keyOf(name);
    final String clean = Team.cleanName(name);
    for (final Team t in _teams.values) {
      if (Team.keyOf(t.name) == key) {
        final Team updated = Team(
          id: t.id,
          name: clean,
          colorValue: colorValue,
          crestPath: crestPath,
        );
        _teams[t.id] = updated;
        return updated;
      }
    }
    final Team created = Team(
      id: _nextId++,
      name: clean,
      colorValue: colorValue,
      crestPath: crestPath,
    );
    _teams[created.id] = created;
    return created;
  }
}

class InMemoryPlayerRepository implements PlayerRepository {
  final Map<int, Player> _players = <int, Player>{};
  int _nextId = 1;

  void _validate(String name, int number) {
    final Map<String, String> errors = PlayerRules.validate(
      name: name,
      number: number,
    );
    if (errors.isNotEmpty) {
      throw ValidationException(errors);
    }
  }

  void _checkUnique(int teamId, int number, {int? exceptId}) {
    for (final Player p in _players.values) {
      if (p.teamId == teamId && p.number == number && p.id != exceptId) {
        throw DuplicateNumberException(number);
      }
    }
  }

  @override
  Future<List<Player>> byTeam(int teamId) async {
    final List<Player> list = _players.values
        .where((Player p) => p.teamId == teamId)
        .toList();
    list.sort((Player a, Player b) => a.number.compareTo(b.number));
    return list;
  }

  @override
  Future<Player> add({
    required int teamId,
    required String name,
    required int number,
  }) async {
    _validate(name, number);
    _checkUnique(teamId, number);
    final Player player = Player(
      id: _nextId++,
      teamId: teamId,
      name: name.trim(),
      number: number,
    );
    _players[player.id] = player;
    return player;
  }

  @override
  Future<Player> update(Player player) async {
    _validate(player.name, player.number);
    _checkUnique(player.teamId, player.number, exceptId: player.id);
    final Player updated = player.copyWith(name: player.name.trim());
    _players[player.id] = updated;
    return updated;
  }

  @override
  Future<void> delete(int id) async {
    _players.remove(id);
  }
}

class InMemorySponsorRepository implements SponsorRepository {
  final Map<int, Sponsor> _sponsors = <int, Sponsor>{};
  int _nextId = 1;

  void _validate(String name) {
    final Map<String, String> errors = SponsorRules.validate(name: name);
    if (errors.isNotEmpty) {
      throw ValidationException(errors);
    }
  }

  @override
  Future<List<Sponsor>> all() async {
    final List<Sponsor> list = _sponsors.values.toList();
    list.sort((Sponsor a, Sponsor b) {
      final int byName = a.name.toLowerCase().compareTo(b.name.toLowerCase());
      return byName != 0 ? byName : a.id.compareTo(b.id);
    });
    return list;
  }

  @override
  Future<Sponsor> add({required String name, String? logoPath}) async {
    _validate(name);
    final Sponsor sponsor = Sponsor(
      id: _nextId++,
      name: name.trim(),
      logoPath: logoPath,
    );
    _sponsors[sponsor.id] = sponsor;
    return sponsor;
  }

  @override
  Future<Sponsor> update(Sponsor sponsor) async {
    _validate(sponsor.name);
    final Sponsor updated = sponsor.copyWith(name: sponsor.name.trim());
    _sponsors[sponsor.id] = updated;
    return updated;
  }

  @override
  Future<void> delete(int id) async {
    _sponsors.remove(id);
  }
}

class InMemoryMatchRepository implements MatchRepository {
  InMemoryMatchRepository({NowMs? nowMs}) : _nowMs = nowMs ?? systemNowMs;

  final NowMs _nowMs;
  final Map<int, SoccerMatch> _matches = <int, SoccerMatch>{};
  final Map<int, List<int>> _sponsorIds = <int, List<int>>{};
  final Map<int, MatchClockState> _clocks = <int, MatchClockState>{};
  final List<MatchEvent> _events = <MatchEvent>[];
  int _nextMatchId = 1;
  int _nextEventId = 1;

  @override
  Future<SoccerMatch?> current() async {
    SoccerMatch? best;
    for (final SoccerMatch m in _matches.values) {
      if (m.status == MatchStatus.cancelled) {
        continue;
      }
      if (best == null || m.id > best.id) {
        best = m;
      }
    }
    return best;
  }

  @override
  Future<SoccerMatch> create({
    required int homeTeamId,
    required int awayTeamId,
    String? tournament,
    required int scheduledAtMs,
    required int halfMinutes,
    required int breakMinutes,
  }) async {
    final SoccerMatch match = SoccerMatch(
      id: _nextMatchId++,
      homeTeamId: homeTeamId,
      awayTeamId: awayTeamId,
      tournament: tournament,
      scheduledAtMs: scheduledAtMs,
      halfMinutes: halfMinutes,
      breakMinutes: breakMinutes,
      status: MatchStatus.scheduled,
      createdAtMs: _nowMs(),
    );
    _matches[match.id] = match;
    _clocks[match.id] = MatchClockState.initial;
    return match;
  }

  @override
  Future<void> update(SoccerMatch match) async {
    _matches[match.id] = match;
  }

  @override
  Future<void> setStatus(int matchId, MatchStatus status) async {
    _matches[matchId] = _matches[matchId]!.copyWith(status: status);
  }

  @override
  Future<List<int>> sponsorIds(int matchId) async =>
      List<int>.of(_sponsorIds[matchId] ?? const <int>[]);

  @override
  Future<void> setSponsors(int matchId, List<int> sponsorIds) async {
    _sponsorIds[matchId] = sponsorIds.toSet().toList();
  }

  @override
  Future<MatchClockState> clock(int matchId) async =>
      _clocks[matchId] ?? MatchClockState.initial;

  @override
  Future<MatchEvent?> commitClock({
    required int matchId,
    required MatchClockState clock,
    required int nowMs,
    MatchStatus? status,
    MatchEvent? event,
  }) async {
    _clocks[matchId] = clock;
    if (status != null) {
      _matches[matchId] = _matches[matchId]!.copyWith(status: status);
    }
    return event == null ? null : addEvent(event);
  }

  @override
  Future<List<MatchEvent>> events(int matchId) async =>
      _events.where((MatchEvent e) => e.matchId == matchId).toList();

  @override
  Future<MatchEvent> addEvent(MatchEvent event) async {
    final MatchEvent saved = event.copyWith(id: _nextEventId++);
    _events.add(saved);
    return saved;
  }

  @override
  Future<MatchEvent> correctGoal({
    required MatchEvent updatedGoal,
    required MatchEvent correction,
  }) async {
    final int index = _events.indexWhere(
      (MatchEvent e) => e.id == updatedGoal.id,
    );
    _events[index] = updatedGoal;
    return addEvent(correction);
  }
}
