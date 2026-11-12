import 'package:flutter/foundation.dart';
import 'package:transmiti2/core/time.dart';
import 'package:transmiti2/features/match/data/repositories.dart';
import 'package:transmiti2/features/match/domain/exceptions.dart';
import 'package:transmiti2/features/match/domain/goal_minute.dart';
import 'package:transmiti2/features/match/domain/match_detail.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';

/// Partido actual: datos, cronómetro, marcador e historial.
///
/// Es la única puerta de entrada para cambiar el partido: cada acción se
/// guarda primero en el almacenamiento y después actualiza el estado en
/// memoria. Al abrir la app, [load] recupera todo tal como quedó.
class MatchSession extends ChangeNotifier {
  MatchSession({
    required MatchRepository matches,
    required TeamRepository teams,
    required PlayerRepository players,
    required SponsorRepository sponsors,
    NowMs? nowMs,
  }) : _matches = matches,
       _teams = teams,
       _playerRepo = players,
       _sponsorRepo = sponsors,
       _nowMs = nowMs ?? systemNowMs;

  final MatchRepository _matches;
  final TeamRepository _teams;
  final PlayerRepository _playerRepo;
  final SponsorRepository _sponsorRepo;
  final NowMs _nowMs;

  MatchDetail? _detail;
  MatchClockState _clock = MatchClockState.initial;
  List<MatchEvent> _events = const <MatchEvent>[];
  Map<int, Player> _playersById = const <int, Player>{};

  MatchDetail? get detail => _detail;
  bool get hasMatch => _detail != null;
  MatchClockState get clock => _clock;
  List<MatchEvent> get events => List<MatchEvent>.unmodifiable(_events);
  int get nowMs => _nowMs();

  /// Las duraciones solo se pueden cambiar antes de iniciar el partido.
  bool get canEditDurations =>
      _detail != null && _clock.phase == MatchPhase.notStarted;

  ScoreSummary get score {
    final MatchDetail? d = _detail;
    if (d == null) {
      return const ScoreSummary(0, 0);
    }
    return ScoreSummary.fromEvents(
      _events,
      homeTeamId: d.home.id,
      awayTeamId: d.away.id,
    );
  }

  Player? playerById(int? id) => id == null ? null : _playersById[id];

  List<Player> playersOf(int teamId) {
    final List<Player> list = _playersById.values
        .where((Player p) => p.teamId == teamId)
        .toList();
    list.sort((Player a, Player b) => a.number.compareTo(b.number));
    return list;
  }

  /// Lo que debe mostrar el reloj ahora (o en [atMs], para las pruebas).
  ClockReading reading([int? atMs]) {
    return ClockReading.of(
      _clock,
      nowMs: atMs ?? _nowMs(),
      halfMinutes: _detail?.match.halfMinutes ?? 45,
    );
  }

  // ---------------------------------------------------------------- carga

  /// Lee del almacenamiento el partido actual con todo su estado. También
  /// sirve para refrescar después de cambiar plantillas o patrocinadores.
  Future<void> load() async {
    final SoccerMatch? match = await _matches.current();
    if (match == null) {
      _detail = null;
      _clock = MatchClockState.initial;
      _events = const <MatchEvent>[];
      _playersById = const <int, Player>{};
      notifyListeners();
      return;
    }
    final Team? home = await _teams.byId(match.homeTeamId);
    final Team? away = await _teams.byId(match.awayTeamId);
    if (home == null || away == null) {
      throw StateError('Faltan los equipos del partido ${match.id}.');
    }
    final List<int> sponsorIds = await _matches.sponsorIds(match.id);
    final Map<int, Sponsor> allSponsors = <int, Sponsor>{
      for (final Sponsor s in await _sponsorRepo.all()) s.id: s,
    };
    final Map<int, Player> players = <int, Player>{
      for (final Player p in await _playerRepo.byTeam(home.id)) p.id: p,
      for (final Player p in await _playerRepo.byTeam(away.id)) p.id: p,
    };
    _detail = MatchDetail(
      match: match,
      home: home,
      away: away,
      sponsors: <Sponsor>[
        for (final int id in sponsorIds)
          if (allSponsors[id] != null) allSponsors[id]!,
      ],
    );
    _clock = await _matches.clock(match.id);
    _events = await _matches.events(match.id);
    _playersById = players;
    notifyListeners();
  }

  // ---------------------------------------------------------- preparación

  /// Guarda el formulario de preparación. Edita el partido actual si todavía
  /// no terminó; si no hay partido, o ya terminó, o [createNew] es verdadero,
  /// crea uno nuevo (y en ese último caso cancela el actual sin terminar).
  Future<void> saveSetup(MatchSetup setup, {bool createNew = false}) async {
    final Map<String, String> errors = setup.validate();
    if (errors.isNotEmpty) {
      throw ValidationException(errors);
    }
    final MatchDetail? current = _detail;
    final bool editing =
        !createNew &&
        current != null &&
        current.match.status != MatchStatus.finished;
    final bool started = _clock.phase != MatchPhase.notStarted;

    if (editing && started) {
      final bool sameTeams =
          Team.keyOf(setup.homeName) == Team.keyOf(current.home.name) &&
          Team.keyOf(setup.awayName) == Team.keyOf(current.away.name);
      if (!sameTeams) {
        throw const ValidationException(<String, String>{
          'homeName':
              'No se pueden cambiar los equipos con el partido iniciado.',
        });
      }
    }

    final Team home = await _teams.saveByName(
      name: setup.homeName,
      colorValue: setup.homeColor,
      crestPath: setup.homeCrestPath,
    );
    final Team away = await _teams.saveByName(
      name: setup.awayName,
      colorValue: setup.awayColor,
      crestPath: setup.awayCrestPath,
    );

    final int matchId;
    if (editing) {
      final SoccerMatch match = current.match;
      await _matches.update(
        match.copyWith(
          homeTeamId: home.id,
          awayTeamId: away.id,
          tournament: setup.cleanTournament,
          clearTournament: setup.cleanTournament == null,
          scheduledAtMs: setup.scheduledAtMs,
          halfMinutes: started ? match.halfMinutes : setup.halfMinutes,
          breakMinutes: started ? match.breakMinutes : setup.breakMinutes,
        ),
      );
      matchId = match.id;
    } else {
      if (createNew && current != null) {
        await _matches.setStatus(current.match.id, MatchStatus.cancelled);
      }
      final SoccerMatch created = await _matches.create(
        homeTeamId: home.id,
        awayTeamId: away.id,
        tournament: setup.cleanTournament,
        scheduledAtMs: setup.scheduledAtMs,
        halfMinutes: setup.halfMinutes,
        breakMinutes: setup.breakMinutes,
      );
      matchId = created.id;
    }
    await _matches.setSponsors(matchId, setup.sponsorIds);
    await load();
  }

  /// Cancela el partido actual (queda guardado, pero deja de ser el actual).
  Future<void> cancelCurrentMatch() async {
    final MatchDetail d = _requireMatch();
    await _matches.setStatus(d.match.id, MatchStatus.cancelled);
    await load();
  }

  Future<void> updateDurations({
    required int halfMinutes,
    required int breakMinutes,
  }) async {
    final MatchDetail d = _requireMatch();
    if (_clock.phase != MatchPhase.notStarted) {
      throw StateError(
        'Las duraciones solo se pueden cambiar antes de iniciar el partido.',
      );
    }
    final Map<String, String> errors = MatchRules.validateDurations(
      halfMinutes: halfMinutes,
      breakMinutes: breakMinutes,
    );
    if (errors.isNotEmpty) {
      throw ValidationException(errors);
    }
    await _matches.update(
      d.match.copyWith(halfMinutes: halfMinutes, breakMinutes: breakMinutes),
    );
    await load();
  }

  Future<void> setSponsors(List<int> sponsorIds) async {
    final MatchDetail d = _requireMatch();
    await _matches.setSponsors(d.match.id, sponsorIds);
    await load();
  }

  // ----------------------------------------------------------- cronómetro

  Future<void> startMatch() => _transition(
    (MatchClockState c, int now) => c.start(now),
    MatchEventType.periodStart,
    status: MatchStatus.live,
  );

  Future<void> pauseClock() =>
      _transition((MatchClockState c, int now) => c.pause(now), null);

  Future<void> resumeClock() =>
      _transition((MatchClockState c, int now) => c.resume(now), null);

  Future<void> endFirstHalf() => _transition(
    (MatchClockState c, int now) => c.endFirstHalf(now),
    MatchEventType.periodEnd,
  );

  Future<void> startSecondHalf() => _transition(
    (MatchClockState c, int now) => c.startSecondHalf(now),
    MatchEventType.periodStart,
  );

  Future<void> endMatch() => _transition(
    (MatchClockState c, int now) => c.endMatch(now),
    MatchEventType.matchEnd,
    status: MatchStatus.finished,
  );

  Future<void> _transition(
    MatchClockState Function(MatchClockState clock, int nowMs) apply,
    MatchEventType? eventType, {
    MatchStatus? status,
  }) async {
    final MatchDetail d = _requireMatch();
    final int now = _nowMs();
    final MatchClockState next = apply(_clock, now);
    final MatchEvent? draft = eventType == null
        ? null
        : _clockEvent(d, eventType, next, now);
    final MatchEvent? saved = await _matches.commitClock(
      matchId: d.match.id,
      clock: next,
      nowMs: now,
      status: status,
      event: draft,
    );
    _clock = next;
    if (saved != null) {
      _events = <MatchEvent>[..._events, saved];
    }
    if (status != null) {
      _detail = MatchDetail(
        match: d.match.copyWith(status: status),
        home: d.home,
        away: d.away,
        sponsors: d.sponsors,
      );
    }
    notifyListeners();
  }

  MatchEvent _clockEvent(
    MatchDetail d,
    MatchEventType type,
    MatchClockState next,
    int now,
  ) {
    final int half = d.match.halfMinutes;
    final ClockReading reading = ClockReading.of(
      next,
      nowMs: now,
      halfMinutes: half,
    );
    final int minute;
    final int added;
    if (type == MatchEventType.periodStart) {
      minute = next.period >= 2 ? half : 0;
      added = 0;
    } else {
      final GoalMinute m = GoalMinute.fromElapsed(
        period: next.period,
        elapsedMs: next.elapsedAt(now),
        halfMinutes: half,
      );
      minute = m.minute;
      added = m.added;
    }
    return MatchEvent(
      id: 0,
      matchId: d.match.id,
      type: type,
      period: next.period,
      minute: minute,
      added: added,
      clockMs: reading.totalMs,
      createdAtMs: now,
    );
  }

  // ---------------------------------------------------------------- goles

  /// Registra un gol. Sin [minute] usa el minuto actual del cronómetro.
  Future<void> addGoal({
    required int teamId,
    int? playerId,
    GoalMinute? minute,
  }) async {
    final MatchDetail d = _requireMatch();
    if (_clock.phase == MatchPhase.notStarted) {
      throw StateError('Inicia el partido antes de registrar goles.');
    }
    _checkTeam(d, teamId);
    _checkPlayer(teamId, playerId);
    if (minute != null) {
      _checkMinute(minute);
    }
    final int now = _nowMs();
    final GoalMinute mark =
        minute ??
        GoalMinute.fromElapsed(
          period: _clock.period,
          elapsedMs: _clock.elapsedAt(now),
          halfMinutes: d.match.halfMinutes,
        );
    final MatchEvent saved = await _matches.addEvent(
      MatchEvent(
        id: 0,
        matchId: d.match.id,
        type: MatchEventType.goal,
        teamId: teamId,
        playerId: playerId,
        period: _clock.period,
        minute: mark.minute,
        added: mark.added,
        clockMs: reading(now).totalMs,
        createdAtMs: now,
      ),
    );
    _events = <MatchEvent>[..._events, saved];
    notifyListeners();
  }

  /// Corrige un gol: equipo, jugador y/o minuto. Deja constancia en el
  /// historial con un evento de corrección.
  Future<void> editGoal(
    int eventId, {
    int? teamId,
    int? playerId,
    bool clearPlayer = false,
    GoalMinute? minute,
  }) async {
    final MatchDetail d = _requireMatch();
    final MatchEvent goal = _activeGoal(eventId);
    final int newTeam = teamId ?? goal.teamId!;
    _checkTeam(d, newTeam);
    final bool teamChanged = newTeam != goal.teamId;
    final int? newPlayer = (clearPlayer || (teamChanged && playerId == null))
        ? null
        : (playerId ?? goal.playerId);
    _checkPlayer(newTeam, newPlayer);
    if (minute != null) {
      _checkMinute(minute);
    }
    final MatchEvent updated = goal.copyWith(
      teamId: newTeam,
      playerId: newPlayer,
      clearPlayer: newPlayer == null,
      minute: minute?.minute,
      added: minute?.added,
    );
    if (updated == goal) {
      return;
    }
    await _saveCorrection(d, goal, updated, 'edited');
  }

  /// Anula un gol registrado por error. Deja constancia en el historial.
  Future<void> voidGoal(int eventId) async {
    final MatchDetail d = _requireMatch();
    final MatchEvent goal = _activeGoal(eventId);
    await _saveCorrection(d, goal, goal.copyWith(voided: true), 'voided');
  }

  Future<void> _saveCorrection(
    MatchDetail d,
    MatchEvent original,
    MatchEvent updated,
    String note,
  ) async {
    final int now = _nowMs();
    final ClockReading r = reading(now);
    final GoalMinute at = GoalMinute.fromElapsed(
      period: _clock.period == 0 ? original.period : _clock.period,
      elapsedMs: _clock.elapsedAt(now),
      halfMinutes: d.match.halfMinutes,
    );
    final MatchEvent saved = await _matches.correctGoal(
      updatedGoal: updated,
      correction: MatchEvent(
        id: 0,
        matchId: d.match.id,
        type: MatchEventType.goalCorrection,
        teamId: updated.teamId,
        playerId: updated.playerId,
        period: _clock.period == 0 ? original.period : _clock.period,
        minute: at.minute,
        added: at.added,
        clockMs: r.totalMs,
        refEventId: original.id,
        note: note,
        createdAtMs: now,
      ),
    );
    _events = <MatchEvent>[
      for (final MatchEvent e in _events) e.id == original.id ? updated : e,
      saved,
    ];
    notifyListeners();
  }

  // ------------------------------------------------------------- utilidades

  MatchDetail _requireMatch() {
    final MatchDetail? d = _detail;
    if (d == null) {
      throw StateError('No hay un partido preparado.');
    }
    return d;
  }

  MatchEvent _activeGoal(int eventId) {
    for (final MatchEvent e in _events) {
      if (e.id == eventId && e.type == MatchEventType.goal) {
        if (e.voided) {
          throw StateError('Ese gol ya fue anulado.');
        }
        return e;
      }
    }
    throw ArgumentError.value(eventId, 'eventId', 'No existe ese gol.');
  }

  void _checkTeam(MatchDetail d, int teamId) {
    if (teamId != d.home.id && teamId != d.away.id) {
      throw ArgumentError.value(
        teamId,
        'teamId',
        'El equipo no juega este partido.',
      );
    }
  }

  void _checkPlayer(int teamId, int? playerId) {
    if (playerId == null) {
      return;
    }
    final Player? p = _playersById[playerId];
    if (p == null || p.teamId != teamId) {
      throw ArgumentError.value(
        playerId,
        'playerId',
        'El jugador no pertenece a ese equipo.',
      );
    }
  }

  void _checkMinute(GoalMinute minute) {
    if (minute.minute < 1 ||
        minute.minute > GoalMinute.maxMinute ||
        minute.added < 0) {
      throw ArgumentError.value(minute, 'minute', 'Minuto no válido.');
    }
  }
}
