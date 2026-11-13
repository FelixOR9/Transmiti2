import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_routes.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/core/formatting.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/match_detail.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/features/match/presentation/players_screen.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';
import 'package:transmiti2/shared/widgets/stored_image.dart';

/// Pestaña Partido: resumen del partido guardado, con los escudos, el
/// resultado y los patrocinadores elegidos.
class MatchScreen extends StatelessWidget {
  const MatchScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final MatchSession session = AppScope.of(context).session;
    return ListenableBuilder(
      listenable: session,
      builder: (BuildContext context, Widget? child) {
        final MatchDetail? detail = session.detail;
        return Scaffold(
          appBar: AppBar(title: const Text('Partido')),
          body: SafeArea(
            child: detail == null
                ? const _EmptyMatch()
                : _MatchSummary(session: session, detail: detail),
          ),
        );
      },
    );
  }
}

class _EmptyMatch extends StatelessWidget {
  const _EmptyMatch();

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            const Icon(
              Icons.sports_soccer,
              size: 56,
              color: AppColors.textMuted,
            ),
            const SizedBox(height: 16),
            Text(
              'Todavía no hay un partido preparado.',
              textAlign: TextAlign.center,
              style: text.titleMedium?.copyWith(color: AppColors.textPrimary),
            ),
            const SizedBox(height: 24),
            AppButton.primary(
              key: const Key('prepare-from-tab'),
              label: 'PREPARAR PARTIDO',
              onPressed: () =>
                  Navigator.of(context).pushNamed(AppRoutes.matchSetup),
            ),
          ],
        ),
      ),
    );
  }
}

class _MatchSummary extends StatelessWidget {
  const _MatchSummary({required this.session, required this.detail});

  final MatchSession session;
  final MatchDetail detail;

  String get _statusLabel {
    switch (session.clock.phase) {
      case MatchPhase.notStarted:
        return 'Por comenzar';
      case MatchPhase.firstHalf:
        return 'Primer tiempo';
      case MatchPhase.halfTime:
        return 'Descanso';
      case MatchPhase.secondHalf:
        return 'Segundo tiempo';
      case MatchPhase.fullTime:
        return 'Finalizado';
    }
  }

  String get _details {
    final String? tournament = detail.match.tournament;
    return <String>[
      formatDate(detail.match.scheduledAtMs),
      if (tournament != null) tournament,
    ].join(' · ');
  }

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 760),
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
          children: <Widget>[
            Container(
              padding: const EdgeInsets.all(20),
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
                      Expanded(child: _TeamColumn(team: detail.home)),
                      Padding(
                        padding: const EdgeInsets.only(top: 18),
                        child: Text(
                          session.score.text,
                          key: const Key('match-score'),
                          style: text.displaySmall?.copyWith(
                            color: AppColors.textPrimary,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ),
                      Expanded(child: _TeamColumn(team: detail.away)),
                    ],
                  ),
                  const SizedBox(height: 16),
                  Text(
                    _statusLabel,
                    style: text.titleMedium?.copyWith(
                      color: AppColors.primary,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    _details,
                    textAlign: TextAlign.center,
                    style: text.bodyMedium?.copyWith(
                      color: AppColors.textMuted,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${detail.match.halfMinutes} min por tiempo · descanso de ${detail.match.breakMinutes} min',
                    textAlign: TextAlign.center,
                    style: text.bodySmall?.copyWith(color: AppColors.textMuted),
                  ),
                ],
              ),
            ),
            if (detail.sponsors.isNotEmpty) ...<Widget>[
              const SizedBox(height: 16),
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: AppColors.outline),
                ),
                child: Wrap(
                  spacing: 10,
                  runSpacing: 10,
                  children: <Widget>[
                    for (final Sponsor sponsor in detail.sponsors)
                      Tooltip(
                        message: sponsor.name,
                        child: StoredImage(
                          key: Key('match-sponsor-${sponsor.id}'),
                          path: sponsor.logoPath,
                          size: 56,
                          fallbackIcon: Icons.storefront_outlined,
                        ),
                      ),
                  ],
                ),
              ),
            ],
            const SizedBox(height: 24),
            AppButton.primary(
              key: const Key('edit-match'),
              label: 'EDITAR PARTIDO',
              onPressed: () =>
                  Navigator.of(context).pushNamed(AppRoutes.matchSetup),
            ),
            const SizedBox(height: 12),
            AppButton.secondary(
              key: const Key('tab-players-home'),
              label: 'JUGADORES · ${detail.home.name}',
              onPressed: () => Navigator.of(context).pushNamed(
                AppRoutes.players,
                arguments: PlayersArgs(
                  teamId: detail.home.id,
                  teamName: detail.home.name,
                ),
              ),
            ),
            const SizedBox(height: 12),
            AppButton.secondary(
              key: const Key('tab-players-away'),
              label: 'JUGADORES · ${detail.away.name}',
              onPressed: () => Navigator.of(context).pushNamed(
                AppRoutes.players,
                arguments: PlayersArgs(
                  teamId: detail.away.id,
                  teamName: detail.away.name,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _TeamColumn extends StatelessWidget {
  const _TeamColumn({required this.team});

  final Team team;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Column(
      children: <Widget>[
        StoredImage(
          key: Key('match-crest-${team.id}'),
          path: team.crestPath,
          size: 76,
          circular: true,
          borderColor: Color(team.colorValue),
        ),
        const SizedBox(height: 10),
        Text(
          team.name,
          textAlign: TextAlign.center,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: text.titleMedium?.copyWith(
            color: AppColors.textPrimary,
            fontWeight: FontWeight.w700,
          ),
        ),
      ],
    );
  }
}
