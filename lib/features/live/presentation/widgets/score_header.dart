import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/graphics/application/overlay_data_builder.dart';
import 'package:transmiti2/features/live/presentation/widgets/live_clock.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/match_detail.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';
import 'package:transmiti2/shared/widgets/stored_image.dart';

/// Texto del tiempo actual y del estado: "PRIMER TIEMPO", "DESCANSO",
/// "SEGUNDO TIEMPO · EN PAUSA"...
String statusLine(MatchClockState clock) {
  final String period = periodLabelFor(clock.phase);
  if (clock.isLive && !clock.running) {
    return '$period · EN PAUSA';
  }
  return period;
}

/// Cabecera de la pantalla en vivo: escudos, nombres, marcador, cronómetro
/// y estado del partido. Se actualiza sola cuando cambian los datos.
class ScoreHeader extends StatelessWidget {
  const ScoreHeader({super.key, required this.session});

  final MatchSession session;

  @override
  Widget build(BuildContext context) {
    final MatchDetail detail = session.detail!;
    final TextTheme text = Theme.of(context).textTheme;
    final Duration? tick = AppScope.of(context).clockTick;
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 20),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: AppColors.outline),
      ),
      child: Column(
        children: <Widget>[
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Expanded(child: _TeamBlock(team: detail.home)),
              const SizedBox(width: 12),
              Expanded(child: _TeamBlock(team: detail.away)),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            session.score.text,
            key: const Key('live-score'),
            style: text.displayMedium?.copyWith(
              color: AppColors.textPrimary,
              fontWeight: FontWeight.w900,
              fontFeatures: const <FontFeature>[FontFeature.tabularFigures()],
            ),
          ),
          LiveClock(
            session: session,
            tick: tick,
            builder: (BuildContext context, ClockReading reading) {
              return Column(
                children: <Widget>[
                  FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.baseline,
                      textBaseline: TextBaseline.alphabetic,
                      children: <Widget>[
                        Text(
                          reading.mainText,
                          key: const Key('live-clock'),
                          style: text.displaySmall?.copyWith(
                            color: AppColors.primary,
                            fontWeight: FontWeight.w800,
                            fontFeatures: const <FontFeature>[
                              FontFeature.tabularFigures(),
                            ],
                          ),
                        ),
                        if (reading.addedText != null) ...<Widget>[
                          const SizedBox(width: 8),
                          Text(
                            reading.addedText!,
                            key: const Key('live-added'),
                            style: text.titleLarge?.copyWith(
                              color: AppColors.live,
                              fontWeight: FontWeight.w800,
                              fontFeatures: const <FontFeature>[
                                FontFeature.tabularFigures(),
                              ],
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    statusLine(session.clock),
                    key: const Key('live-status'),
                    style: text.labelLarge?.copyWith(
                      color: AppColors.textMuted,
                      letterSpacing: 1.6,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ],
              );
            },
          ),
        ],
      ),
    );
  }
}

class _TeamBlock extends StatelessWidget {
  const _TeamBlock({required this.team});

  final Team team;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Column(
      children: <Widget>[
        StoredImage(
          key: Key('live-crest-${team.id}'),
          path: team.crestPath,
          size: 76,
          circular: true,
          borderColor: Color(team.colorValue),
        ),
        const SizedBox(height: 8),
        Text(
          team.name,
          key: Key('live-name-${team.id}'),
          textAlign: TextAlign.center,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: text.titleMedium?.copyWith(
            color: AppColors.textPrimary,
            fontWeight: FontWeight.w800,
          ),
        ),
      ],
    );
  }
}
