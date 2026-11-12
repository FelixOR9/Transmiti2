enum MatchEventType { goal, goalCorrection, periodStart, periodEnd, matchEnd }

/// Evento del historial del partido.
///
/// Un gol corregido conserva su fila (con los datos nuevos) y además se
/// añade un evento [MatchEventType.goalCorrection] que apunta a él con
/// [refEventId]. Un gol anulado queda con [voided] en verdadero.
class MatchEvent {
  const MatchEvent({
    required this.id,
    required this.matchId,
    required this.type,
    this.teamId,
    this.playerId,
    required this.period,
    required this.minute,
    this.added = 0,
    required this.clockMs,
    this.refEventId,
    this.voided = false,
    this.note,
    required this.createdAtMs,
  });

  /// Los eventos nuevos usan id 0 hasta que el almacenamiento les asigna uno.
  final int id;
  final int matchId;
  final MatchEventType type;
  final int? teamId;
  final int? playerId;
  final int period;
  final int minute;
  final int added;
  final int clockMs;
  final int? refEventId;
  final bool voided;
  final String? note;
  final int createdAtMs;

  bool get isActiveGoal => type == MatchEventType.goal && !voided;

  MatchEvent copyWith({
    int? id,
    int? teamId,
    int? playerId,
    bool clearPlayer = false,
    int? minute,
    int? added,
    bool? voided,
  }) {
    return MatchEvent(
      id: id ?? this.id,
      matchId: matchId,
      type: type,
      teamId: teamId ?? this.teamId,
      playerId: clearPlayer ? null : (playerId ?? this.playerId),
      period: period,
      minute: minute ?? this.minute,
      added: added ?? this.added,
      clockMs: clockMs,
      refEventId: refEventId,
      voided: voided ?? this.voided,
      note: note,
      createdAtMs: createdAtMs,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is MatchEvent &&
      other.id == id &&
      other.matchId == matchId &&
      other.type == type &&
      other.teamId == teamId &&
      other.playerId == playerId &&
      other.period == period &&
      other.minute == minute &&
      other.added == added &&
      other.clockMs == clockMs &&
      other.refEventId == refEventId &&
      other.voided == voided &&
      other.note == note &&
      other.createdAtMs == createdAtMs;

  @override
  int get hashCode => Object.hash(
    id,
    matchId,
    type,
    teamId,
    playerId,
    period,
    minute,
    added,
    clockMs,
    refEventId,
    voided,
    note,
    createdAtMs,
  );

  @override
  String toString() => 'MatchEvent($id, ${type.name}, $minute+$added)';
}

/// Marcador calculado a partir de los goles no anulados.
class ScoreSummary {
  const ScoreSummary(this.home, this.away);

  factory ScoreSummary.fromEvents(
    Iterable<MatchEvent> events, {
    required int homeTeamId,
    required int awayTeamId,
  }) {
    int home = 0;
    int away = 0;
    for (final MatchEvent e in events) {
      if (!e.isActiveGoal) {
        continue;
      }
      if (e.teamId == homeTeamId) {
        home++;
      } else if (e.teamId == awayTeamId) {
        away++;
      }
    }
    return ScoreSummary(home, away);
  }

  final int home;
  final int away;

  String get text => '$home - $away';

  @override
  bool operator ==(Object other) =>
      other is ScoreSummary && other.home == home && other.away == away;

  @override
  int get hashCode => Object.hash(home, away);

  @override
  String toString() => 'ScoreSummary($text)';
}
