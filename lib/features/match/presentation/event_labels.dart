import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/goal_minute.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';

/// Cómo se muestra un evento del historial.
class EventLine {
  const EventLine({
    required this.title,
    this.subtitle,
    required this.icon,
    required this.color,
    this.struck = false,
  });

  final String title;
  final String? subtitle;
  final IconData icon;
  final Color color;

  /// Tachado (gol anulado).
  final bool struck;
}

/// Minuto del evento, por ejemplo 23' o 45+2'.
String eventTimeLabel(MatchEvent e) => GoalMinute(e.minute, e.added).label;

/// Marca del tiempo en que ocurrió: 1T o 2T.
String eventPeriodTag(MatchEvent e) => e.period >= 2 ? '2T' : '1T';

String? _playerText(MatchSession session, int? playerId) {
  final Player? p = session.playerById(playerId);
  return p == null ? null : '#${p.number} ${p.name}';
}

/// Convierte un evento guardado en el texto, el icono y el color del historial.
EventLine describeEvent(MatchEvent e, MatchSession session) {
  final String team = session.detail?.teamById(e.teamId)?.name ?? 'Equipo';
  final Color teamColor = Color(
    session.detail?.teamById(e.teamId)?.colorValue ?? 0xFF9E9E9E,
  );
  switch (e.type) {
    case MatchEventType.goal:
      return EventLine(
        title: e.voided ? 'Gol anulado de $team' : 'Gol de $team',
        subtitle: _playerText(session, e.playerId) ?? 'Sin jugador asignado',
        icon: Icons.sports_soccer,
        color: teamColor,
        struck: e.voided,
      );
    case MatchEventType.goalCorrection:
      MatchEvent? goal;
      for (final MatchEvent other in session.events) {
        if (other.id == e.refEventId) {
          goal = other;
        }
      }
      final String minute = goal == null ? '' : ' · minuto ${eventTimeLabel(goal)}';
      final String who = _playerText(session, e.playerId) ?? 'sin jugador';
      if (e.note == 'voided') {
        return EventLine(
          title: 'Corrección: gol anulado',
          subtitle: '$team · $who$minute',
          icon: Icons.block,
          color: AppColors.live,
        );
      }
      return EventLine(
        title: 'Corrección: gol modificado',
        subtitle: 'Ahora: $team · $who$minute',
        icon: Icons.edit_outlined,
        color: AppColors.live,
      );
    case MatchEventType.periodStart:
      return EventLine(
        title: e.period >= 2
            ? 'Inicio del segundo tiempo'
            : 'Inicio del primer tiempo',
        subtitle: e.period >= 2 ? 'Termina el descanso' : null,
        icon: Icons.play_arrow,
        color: AppColors.primary,
      );
    case MatchEventType.periodEnd:
      return EventLine(
        title: e.period >= 2
            ? 'Fin del segundo tiempo'
            : 'Fin del primer tiempo · Descanso',
        icon: Icons.pause_circle_outline,
        color: AppColors.primary,
      );
    case MatchEventType.matchEnd:
      return EventLine(
        title: 'Final del partido',
        subtitle: session.clock.phase == MatchPhase.fullTime
            ? 'Resultado: ${session.score.text}'
            : null,
        icon: Icons.flag_outlined,
        color: AppColors.primary,
      );
  }
}
