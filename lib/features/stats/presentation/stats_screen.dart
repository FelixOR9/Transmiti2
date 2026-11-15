import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/match_detail.dart';
import 'package:transmiti2/features/match/domain/match_stats.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';
import 'package:transmiti2/shared/widgets/section_label.dart';
import 'package:transmiti2/shared/widgets/stored_image.dart';

/// Estadísticas básicas calculadas SOLO con los eventos registrados.
class StatsScreen extends StatelessWidget {
  const StatsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final MatchSession session = AppScope.of(context).session;
    return ListenableBuilder(
      listenable: session,
      builder: (BuildContext context, Widget? child) {
        final MatchDetail? detail = session.detail;
        return Scaffold(
          appBar: AppBar(title: const Text('Estadísticas')),
          body: SafeArea(
            child: detail == null
                ? Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Text(
                        'Todavía no hay un partido preparado.',
                        key: const Key('stats-empty'),
                        textAlign: TextAlign.center,
                        style: Theme.of(context).textTheme.titleMedium
                            ?.copyWith(color: AppColors.textMuted),
                      ),
                    ),
                  )
                : _StatsBody(session: session, detail: detail),
          ),
        );
      },
    );
  }
}

class _StatsBody extends StatelessWidget {
  const _StatsBody({required this.session, required this.detail});

  final MatchSession session;
  final MatchDetail detail;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final MatchStats stats = MatchStats.fromEvents(
      session.events,
      homeTeamId: detail.home.id,
      awayTeamId: detail.away.id,
    );
    final bool finished = session.clock.phase == MatchPhase.fullTime;
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 760),
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
          children: <Widget>[
            _Card(
              title: finished ? 'RESULTADO FINAL' : 'RESULTADO ACTUAL',
              children: <Widget>[
                Row(
                  children: <Widget>[
                    Expanded(child: _TeamName(team: detail.home)),
                    Text(
                      stats.score.text,
                      key: const Key('stats-score'),
                      style: text.headlineMedium?.copyWith(
                        color: AppColors.textPrimary,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                    Expanded(
                      child: _TeamName(team: detail.away, alignEnd: true),
                    ),
                  ],
                ),
              ],
            ),
            const SizedBox(height: 16),
            _Card(
              title: 'GOLES POR EQUIPO',
              children: <Widget>[
                _StatRow(
                  label: detail.home.name,
                  value: '${stats.score.home}',
                  valueKey: const Key('stats-goals-home'),
                ),
                _StatRow(
                  label: detail.away.name,
                  value: '${stats.score.away}',
                  valueKey: const Key('stats-goals-away'),
                ),
              ],
            ),
            const SizedBox(height: 16),
            _Card(
              title: 'GOLES POR JUGADOR',
              children: <Widget>[
                if (stats.scorers.isEmpty)
                  Text(
                    'Todavía no hay goles con jugador asignado.',
                    key: const Key('stats-no-scorers'),
                    style: text.bodyMedium?.copyWith(
                      color: AppColors.textMuted,
                    ),
                  )
                else
                  for (final PlayerGoals scorer in stats.scorers)
                    _StatRow(
                      label: _scorerLabel(scorer),
                      value: '${scorer.goals}',
                      valueKey: Key('stats-scorer-${scorer.playerId}'),
                    ),
                if (stats.goalsWithoutPlayer > 0)
                  _StatRow(
                    label: 'Goles sin jugador asignado',
                    value: '${stats.goalsWithoutPlayer}',
                    valueKey: const Key('stats-no-player'),
                  ),
              ],
            ),
            const SizedBox(height: 16),
            _Card(
              title: 'EVENTOS REGISTRADOS',
              children: <Widget>[
                _StatRow(
                  label: 'Eventos en el historial',
                  value: '${stats.totalEvents}',
                  valueKey: const Key('stats-events-total'),
                ),
                _StatRow(
                  label: 'Goles válidos',
                  value: '${stats.activeGoals}',
                  valueKey: const Key('stats-goals-valid'),
                ),
                _StatRow(
                  label: 'Goles anulados',
                  value: '${stats.voidedGoals}',
                  valueKey: const Key('stats-goals-voided'),
                ),
                _StatRow(
                  label: 'Correcciones',
                  value: '${stats.corrections}',
                  valueKey: const Key('stats-corrections'),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Text(
              'Solo se muestran datos registrados durante el partido. Tarjetas, sustituciones y otras estadísticas se añadirán en una etapa posterior.',
              style: text.bodySmall?.copyWith(color: AppColors.textMuted),
            ),
          ],
        ),
      ),
    );
  }

  String _scorerLabel(PlayerGoals scorer) {
    final Player? player = session.playerById(scorer.playerId);
    final String team = detail.teamById(scorer.teamId)?.name ?? '';
    if (player == null) {
      return 'Jugador eliminado · $team';
    }
    return '#${player.number} ${player.name} · $team';
  }
}

class _Card extends StatelessWidget {
  const _Card({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
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
          SectionLabel(title),
          const SizedBox(height: 14),
          ...children,
        ],
      ),
    );
  }
}

class _TeamName extends StatelessWidget {
  const _TeamName({required this.team, this.alignEnd = false});

  final Team team;
  final bool alignEnd;

  @override
  Widget build(BuildContext context) {
    final Widget crest = StoredImage(
      path: team.crestPath,
      size: 40,
      circular: true,
      borderColor: Color(team.colorValue),
    );
    final Widget name = Flexible(
      child: Text(
        team.name,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        textAlign: alignEnd ? TextAlign.end : TextAlign.start,
        style: Theme.of(context).textTheme.titleSmall?.copyWith(
          color: AppColors.textPrimary,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
    return Row(
      mainAxisAlignment: alignEnd
          ? MainAxisAlignment.end
          : MainAxisAlignment.start,
      children: alignEnd
          ? <Widget>[name, const SizedBox(width: 8), crest]
          : <Widget>[crest, const SizedBox(width: 8), name],
    );
  }
}

class _StatRow extends StatelessWidget {
  const _StatRow({
    required this.label,
    required this.value,
    required this.valueKey,
  });

  final String label;
  final String value;
  final Key valueKey;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: <Widget>[
          Expanded(
            child: Text(
              label,
              style: text.bodyLarge?.copyWith(color: AppColors.textPrimary),
            ),
          ),
          Text(
            value,
            key: valueKey,
            style: text.titleLarge?.copyWith(
              color: AppColors.primary,
              fontWeight: FontWeight.w800,
            ),
          ),
        ],
      ),
    );
  }
}
