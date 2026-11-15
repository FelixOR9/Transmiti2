import 'package:transmiti2/features/match/domain/match_event.dart';

/// Goles de un jugador en el partido.
class PlayerGoals {
  const PlayerGoals({
    required this.playerId,
    required this.teamId,
    required this.goals,
  });

  final int playerId;
  final int teamId;
  final int goals;

  @override
  bool operator ==(Object other) =>
      other is PlayerGoals &&
      other.playerId == playerId &&
      other.teamId == teamId &&
      other.goals == goals;

  @override
  int get hashCode => Object.hash(playerId, teamId, goals);

  @override
  String toString() => 'PlayerGoals($playerId, equipo $teamId, $goals)';
}

/// Estadísticas básicas calculadas SOLO con los eventos registrados: no se
/// inventa ningún dato que no se haya anotado.
///
/// Para añadir más estadísticas (tarjetas, sustituciones...) basta con
/// registrar nuevos tipos de evento y sumar aquí su conteo.
class MatchStats {
  const MatchStats({
    required this.score,
    required this.totalEvents,
    required this.countsByType,
    required this.activeGoals,
    required this.voidedGoals,
    required this.goalsWithoutPlayer,
    required this.scorers,
  });

  factory MatchStats.fromEvents(
    Iterable<MatchEvent> events, {
    required int homeTeamId,
    required int awayTeamId,
  }) {
    final List<MatchEvent> all = events.toList();
    final Map<MatchEventType, int> counts = <MatchEventType, int>{};
    int active = 0;
    int voided = 0;
    int withoutPlayer = 0;
    final Map<int, PlayerGoals> byPlayer = <int, PlayerGoals>{};

    for (final MatchEvent e in all) {
      counts[e.type] = (counts[e.type] ?? 0) + 1;
      if (e.type != MatchEventType.goal) {
        continue;
      }
      if (e.voided) {
        voided++;
        continue;
      }
      active++;
      final int? playerId = e.playerId;
      final int? teamId = e.teamId;
      if (playerId == null || teamId == null) {
        withoutPlayer++;
        continue;
      }
      final PlayerGoals? previous = byPlayer[playerId];
      byPlayer[playerId] = PlayerGoals(
        playerId: playerId,
        teamId: teamId,
        goals: (previous?.goals ?? 0) + 1,
      );
    }

    final List<PlayerGoals> scorers = byPlayer.values.toList()
      ..sort((PlayerGoals a, PlayerGoals b) {
        final int byGoals = b.goals.compareTo(a.goals);
        return byGoals != 0 ? byGoals : a.playerId.compareTo(b.playerId);
      });

    return MatchStats(
      score: ScoreSummary.fromEvents(
        all,
        homeTeamId: homeTeamId,
        awayTeamId: awayTeamId,
      ),
      totalEvents: all.length,
      countsByType: Map<MatchEventType, int>.unmodifiable(counts),
      activeGoals: active,
      voidedGoals: voided,
      goalsWithoutPlayer: withoutPlayer,
      scorers: List<PlayerGoals>.unmodifiable(scorers),
    );
  }

  final ScoreSummary score;

  /// Cantidad de eventos del historial (incluye correcciones y goles anulados).
  final int totalEvents;
  final Map<MatchEventType, int> countsByType;

  /// Goles válidos (no anulados).
  final int activeGoals;
  final int voidedGoals;

  /// Goles válidos sin jugador asociado.
  final int goalsWithoutPlayer;

  /// Goleadores, de más a menos goles.
  final List<PlayerGoals> scorers;

  int countOf(MatchEventType type) => countsByType[type] ?? 0;

  /// Cantidad de correcciones registradas.
  int get corrections => countOf(MatchEventType.goalCorrection);
}
