import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/core/formatting.dart';
import 'package:transmiti2/features/live/presentation/widgets/live_clock.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';
import 'package:transmiti2/shared/widgets/section_label.dart';

/// Información del cronómetro y sus acciones menos frecuentes (finalizar el
/// primer tiempo o el partido). Las acciones del día a día (iniciar, pausar,
/// reanudar, segundo tiempo) están en la barra inferior.
class ClockCard extends StatelessWidget {
  const ClockCard({
    super.key,
    required this.session,
    required this.onEndFirstHalf,
    required this.onEndMatch,
    required this.onPrepareAnother,
  });

  final MatchSession session;
  final VoidCallback onEndFirstHalf;
  final VoidCallback onEndMatch;
  final VoidCallback onPrepareAnother;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final SoccerMatch match = session.detail!.match;
    final MatchClockState clock = session.clock;
    final Duration? tick = AppScope.of(context).clockTick;
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: AppColors.outline),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          const SectionLabel('CRONÓMETRO'),
          const SizedBox(height: 12),
          Text(
            '${match.halfMinutes} min por tiempo · descanso de ${match.breakMinutes} min',
            style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
          ),
          if (clock.phase == MatchPhase.halfTime) ...<Widget>[
            const SizedBox(height: 12),
            LiveClock(
              session: session,
              tick: tick,
              builder: (BuildContext context, ClockReading reading) {
                final int elapsed = reading.breakElapsedMs ?? 0;
                final bool over = elapsed > match.breakMinutes * 60000;
                return Text(
                  'Descanso: ${formatClock(elapsed)} de ${formatClock(match.breakMinutes * 60000)}',
                  key: const Key('break-clock'),
                  style: text.titleMedium?.copyWith(
                    color: over ? AppColors.live : AppColors.textPrimary,
                    fontWeight: FontWeight.w700,
                  ),
                );
              },
            ),
          ],
          if (clock.phase == MatchPhase.notStarted) ...<Widget>[
            const SizedBox(height: 8),
            Text(
              'El partido todavía no empieza. Usa el botón INICIAR PARTIDO.',
              style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
            ),
          ],
          if (clock.canEndFirstHalf) ...<Widget>[
            const SizedBox(height: 16),
            AppButton.secondary(
              key: const Key('end-first-half'),
              label: 'FINALIZAR PRIMER TIEMPO',
              onPressed: onEndFirstHalf,
            ),
          ],
          if (clock.canEndMatch) ...<Widget>[
            const SizedBox(height: 12),
            _DangerButton(
              key: const Key('end-match'),
              label: 'FINALIZAR PARTIDO',
              onPressed: onEndMatch,
            ),
          ],
          if (clock.phase == MatchPhase.fullTime) ...<Widget>[
            const SizedBox(height: 12),
            Text(
              'El partido terminó ${session.score.text}.',
              key: const Key('match-finished'),
              style: text.titleMedium?.copyWith(
                color: AppColors.textPrimary,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 16),
            AppButton.secondary(
              key: const Key('prepare-another'),
              label: 'PREPARAR OTRO PARTIDO',
              onPressed: onPrepareAnother,
            ),
          ],
        ],
      ),
    );
  }
}

class _DangerButton extends StatelessWidget {
  const _DangerButton({super.key, required this.label, required this.onPressed});

  final String label;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return OutlinedButton(
      onPressed: onPressed,
      style: OutlinedButton.styleFrom(
        foregroundColor: AppColors.live,
        side: const BorderSide(color: AppColors.live, width: 1.5),
        minimumSize: const Size.fromHeight(56),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        textStyle: Theme.of(context).textTheme.labelLarge?.copyWith(
          fontSize: 16,
          fontWeight: FontWeight.w800,
          letterSpacing: 1.0,
        ),
      ),
      child: FittedBox(fit: BoxFit.scaleDown, child: Text(label)),
    );
  }
}
