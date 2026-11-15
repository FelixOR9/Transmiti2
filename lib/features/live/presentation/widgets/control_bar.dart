import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/match_detail.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';

/// Barra inferior con los controles que más se usan, al alcance del pulgar:
/// un botón de gol por equipo y la acción principal del cronómetro.
class ControlBar extends StatelessWidget {
  const ControlBar({
    super.key,
    required this.session,
    required this.onGoal,
    required this.onMainAction,
  });

  final MatchSession session;
  final ValueChanged<int> onGoal;
  final VoidCallback onMainAction;

  @override
  Widget build(BuildContext context) {
    final MatchDetail detail = session.detail!;
    final MatchClockState clock = session.clock;
    final bool canGoal = clock.isLive || clock.phase == MatchPhase.halfTime;
    final TextTheme text = Theme.of(context).textTheme;
    return Material(
      color: AppColors.surface,
      child: DecoratedBox(
        decoration: const BoxDecoration(
          border: Border(top: BorderSide(color: AppColors.outline)),
        ),
        child: SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                Row(
                  children: <Widget>[
                    Expanded(
                      child: _GoalButton(
                        key: const Key('goal-home'),
                        team: detail.home,
                        onPressed: canGoal ? () => onGoal(detail.home.id) : null,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: _GoalButton(
                        key: const Key('goal-away'),
                        team: detail.away,
                        onPressed: canGoal ? () => onGoal(detail.away.id) : null,
                      ),
                    ),
                  ],
                ),
                if (clock.phase == MatchPhase.notStarted)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text(
                      'Inicia el partido para registrar goles.',
                      key: const Key('goals-disabled-hint'),
                      style: text.bodySmall?.copyWith(
                        color: AppColors.textMuted,
                      ),
                    ),
                  ),
                const SizedBox(height: 10),
                _mainButton(clock),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _mainButton(MatchClockState clock) {
    if (clock.canStart) {
      return AppButton.primary(
        key: const Key('main-action'),
        label: 'INICIAR PARTIDO',
        onPressed: onMainAction,
      );
    }
    if (clock.canPause) {
      return AppButton.secondary(
        key: const Key('main-action'),
        label: 'PAUSAR',
        onPressed: onMainAction,
      );
    }
    if (clock.canResume) {
      return AppButton.primary(
        key: const Key('main-action'),
        label: 'REANUDAR',
        onPressed: onMainAction,
      );
    }
    return AppButton.primary(
      key: const Key('main-action'),
      label: 'INICIAR SEGUNDO TIEMPO',
      onPressed: onMainAction,
    );
  }
}

class _GoalButton extends StatelessWidget {
  const _GoalButton({super.key, required this.team, required this.onPressed});

  final Team team;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final Color color = Color(team.colorValue);
    final Color onColor = color.computeLuminance() > 0.5
        ? Colors.black
        : Colors.white;
    return FilledButton(
      onPressed: onPressed,
      style: FilledButton.styleFrom(
        backgroundColor: color,
        foregroundColor: onColor,
        disabledBackgroundColor: AppColors.surfaceHigh,
        disabledForegroundColor: AppColors.textMuted,
        minimumSize: const Size.fromHeight(68),
        padding: const EdgeInsets.symmetric(horizontal: 8),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          const Text(
            '+ GOL',
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.w900,
              letterSpacing: 1.2,
            ),
          ),
          Text(
            team.name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }
}
