import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/goal_minute.dart';
import 'package:transmiti2/features/match/domain/match_detail.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/shared/widgets/stored_image.dart';

/// Goles válidos del partido, con su minuto y jugador, y los botones para
/// corregirlos o anularlos.
class GoalList extends StatelessWidget {
  const GoalList({
    super.key,
    required this.session,
    required this.onEdit,
    required this.onVoid,
  });

  final MatchSession session;
  final ValueChanged<MatchEvent> onEdit;
  final ValueChanged<MatchEvent> onVoid;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final MatchDetail detail = session.detail!;
    final List<MatchEvent> goals = session.events
        .where((MatchEvent e) => e.isActiveGoal)
        .toList();
    if (goals.isEmpty) {
      return Text(
        'Todavía no hay goles.',
        key: const Key('no-goals'),
        style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
      );
    }
    return Column(
      children: <Widget>[
        for (final MatchEvent goal in goals)
          _GoalTile(
            goal: goal,
            team: detail.teamById(goal.teamId)!,
            player: session.playerById(goal.playerId),
            onEdit: () => onEdit(goal),
            onVoid: () => onVoid(goal),
          ),
      ],
    );
  }
}

class _GoalTile extends StatelessWidget {
  const _GoalTile({
    required this.goal,
    required this.team,
    required this.player,
    required this.onEdit,
    required this.onVoid,
  });

  final MatchEvent goal;
  final Team team;
  final Player? player;
  final VoidCallback onEdit;
  final VoidCallback onVoid;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final Player? scorer = player;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Container(
        key: Key('goal-${goal.id}'),
        padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
        decoration: BoxDecoration(
          color: AppColors.surfaceHigh,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: AppColors.outline),
        ),
        child: Row(
          children: <Widget>[
            SizedBox(
              width: 54,
              child: Text(
                GoalMinute(goal.minute, goal.added).label,
                style: text.titleMedium?.copyWith(
                  color: AppColors.primary,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
            StoredImage(
              path: team.crestPath,
              size: 40,
              circular: true,
              borderColor: Color(team.colorValue),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Text(
                    'Gol de ${team.name}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: text.titleSmall?.copyWith(
                      color: AppColors.textPrimary,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  Text(
                    scorer == null
                        ? 'Sin jugador asignado'
                        : '#${scorer.number} ${scorer.name}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: text.bodySmall?.copyWith(color: AppColors.textMuted),
                  ),
                ],
              ),
            ),
            IconButton(
              key: Key('edit-goal-${goal.id}'),
              tooltip: 'Corregir gol',
              onPressed: onEdit,
              icon: const Icon(Icons.edit_outlined, color: AppColors.textMuted),
            ),
            IconButton(
              key: Key('void-goal-${goal.id}'),
              tooltip: 'Anular gol',
              onPressed: onVoid,
              icon: const Icon(Icons.delete_outline, color: AppColors.live),
            ),
          ],
        ),
      ),
    );
  }
}
