import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_routes.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/core/action_guard.dart';
import 'package:transmiti2/features/home/presentation/widgets/access_button.dart';
import 'package:transmiti2/features/live/presentation/widgets/clock_card.dart';
import 'package:transmiti2/features/live/presentation/widgets/control_bar.dart';
import 'package:transmiti2/features/live/presentation/widgets/goal_dialog.dart';
import 'package:transmiti2/features/live/presentation/widgets/goal_list.dart';
import 'package:transmiti2/features/live/presentation/widgets/score_header.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/goal_minute.dart';
import 'package:transmiti2/features/match/domain/match_detail.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';
import 'package:transmiti2/shared/widgets/confirm_dialog.dart';
import 'package:transmiti2/shared/widgets/section_label.dart';

/// Pantalla de control del partido en vivo: escudos, nombres, marcador,
/// cronómetro y estado arriba; goles y cronómetro abajo, al alcance del pulgar.
///
/// Lee y escribe solo a través de [MatchSession]: cada gol y cada cambio del
/// cronómetro se guardan en SQLite antes de verse en pantalla.
class LiveMatchScreen extends StatefulWidget {
  const LiveMatchScreen({
    super.key,
    this.title = 'Partido en vivo',
    this.clockFirst = false,
  });

  final String title;

  /// Muestra primero la tarjeta del cronómetro (en lugar de la de goles).
  final bool clockFirst;

  @override
  State<LiveMatchScreen> createState() => _LiveMatchScreenState();
}

class _LiveMatchScreenState extends State<LiveMatchScreen> {
  late AppServices _services;
  late ActionGuard _guard;
  bool _initialized = false;

  MatchSession get _session => _services.session;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_initialized) {
      return;
    }
    _initialized = true;
    _services = AppScope.of(context);
    _guard = ActionGuard(cooldown: _services.tapCooldown);
  }

  /// Ejecuta una acción con protección contra doble toque y muestra un
  /// mensaje si el partido no la admite o el guardado falla.
  Future<void> _run(Future<void> Function() body) async {
    final ScaffoldMessengerState messenger = ScaffoldMessenger.of(context);
    await _guard.run(() async {
      try {
        await body();
      } on StateError catch (e) {
        messenger.showSnackBar(SnackBar(content: Text(e.message)));
      } catch (_) {
        messenger.showSnackBar(
          const SnackBar(
            content: Text('No se pudo guardar el cambio. Inténtalo de nuevo.'),
          ),
        );
      }
    });
  }

  Future<void> _mainAction() => _run(() async {
    final MatchClockState clock = _session.clock;
    if (clock.canStart) {
      await _session.startMatch();
    } else if (clock.canPause) {
      await _session.pauseClock();
    } else if (clock.canResume) {
      await _session.resumeClock();
    } else if (clock.canStartSecondHalf) {
      await _session.startSecondHalf();
    }
  });

  Future<void> _endFirstHalf() => _run(() async {
    final bool confirmed = await confirmAction(
      context,
      title: 'Finalizar primer tiempo',
      message:
          'Se registrará el final del primer tiempo y empezará el descanso.',
      confirmLabel: 'FINALIZAR',
    );
    if (confirmed) {
      await _session.endFirstHalf();
    }
  });

  Future<void> _endMatch() => _run(() async {
    final bool confirmed = await confirmAction(
      context,
      title: 'Finalizar partido',
      message:
          '¿Finalizar el partido ahora? El cronómetro se detendrá y ya no se podrá reanudar. Los goles se podrán seguir corrigiendo.',
      confirmLabel: 'FINALIZAR PARTIDO',
    );
    if (confirmed) {
      await _session.endMatch();
    }
  });

  Future<void> _addGoal(int teamId) => _run(() async {
    await showGoalDialog(context, session: _session, teamId: teamId);
  });

  Future<void> _editGoal(MatchEvent goal) => _run(() async {
    await showGoalDialog(
      context,
      session: _session,
      teamId: goal.teamId!,
      editing: goal,
    );
  });

  Future<void> _voidGoal(MatchEvent goal) => _run(() async {
    final MatchDetail detail = _session.detail!;
    final String team = detail.teamById(goal.teamId)?.name ?? 'el equipo';
    final bool confirmed = await confirmAction(
      context,
      title: 'Anular gol',
      message:
          '¿Anular el gol de $team del minuto ${GoalMinute(goal.minute, goal.added).label}? Quedará registrado en el historial como corrección.',
      confirmLabel: 'ANULAR GOL',
    );
    if (confirmed) {
      await _session.voidGoal(goal.id);
    }
  });

  void _open(String route) => Navigator.of(context).pushNamed(route);

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _session,
      builder: (BuildContext context, Widget? child) {
        final MatchDetail? detail = _session.detail;
        final bool finished = _session.clock.phase == MatchPhase.fullTime;
        return Scaffold(
          appBar: AppBar(
            title: Text(widget.title),
            actions: <Widget>[
              if (detail != null)
                IconButton(
                  key: const Key('open-history'),
                  tooltip: 'Historial de eventos',
                  onPressed: () => _open(AppRoutes.history),
                  icon: const Icon(Icons.history),
                ),
            ],
          ),
          body: SafeArea(
            child: detail == null ? _emptyState() : _content(),
          ),
          bottomNavigationBar: (detail == null || finished)
              ? null
              : ControlBar(
                  session: _session,
                  onGoal: _addGoal,
                  onMainAction: _mainAction,
                ),
        );
      },
    );
  }

  Widget _emptyState() {
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
              'Primero prepara un partido para poder controlarlo.',
              textAlign: TextAlign.center,
              style: text.titleMedium?.copyWith(color: AppColors.textPrimary),
            ),
            const SizedBox(height: 24),
            AppButton.primary(
              key: const Key('live-prepare'),
              label: 'PREPARAR PARTIDO',
              onPressed: () => _open(AppRoutes.matchSetup),
            ),
          ],
        ),
      ),
    );
  }

  Widget _content() {
    final Widget clockCard = ClockCard(
      session: _session,
      onEndFirstHalf: _endFirstHalf,
      onEndMatch: _endMatch,
      onPrepareAnother: () => _open(AppRoutes.matchSetup),
    );
    final Widget goalsCard = _SectionCard(
      title: 'GOLES',
      child: GoalList(
        session: _session,
        onEdit: _editGoal,
        onVoid: _voidGoal,
      ),
    );
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 760),
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
          children: <Widget>[
            ScoreHeader(session: _session),
            const SizedBox(height: 16),
            if (widget.clockFirst) ...<Widget>[
              clockCard,
              const SizedBox(height: 16),
              goalsCard,
            ] else ...<Widget>[
              goalsCard,
              const SizedBox(height: 16),
              clockCard,
            ],
            const SizedBox(height: 16),
            GridView(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 2,
                mainAxisSpacing: 12,
                crossAxisSpacing: 12,
                mainAxisExtent: 56,
              ),
              children: <Widget>[
                AccessButton(
                  key: const Key('shortcut-history'),
                  label: 'HISTORIAL',
                  onTap: () => _open(AppRoutes.history),
                ),
                AccessButton(
                  key: const Key('shortcut-stats'),
                  label: 'ESTADÍSTICAS',
                  onTap: () => _open(AppRoutes.stats),
                ),
                AccessButton(
                  key: const Key('shortcut-graphics'),
                  label: 'GRÁFICOS',
                  onTap: () => _open(AppRoutes.graphics),
                ),
                AccessButton(
                  key: const Key('shortcut-edit'),
                  label: 'EDITAR PARTIDO',
                  onTap: () => _open(AppRoutes.matchSetup),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _SectionCard extends StatelessWidget {
  const _SectionCard({required this.title, required this.child});

  final String title;
  final Widget child;

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
          child,
        ],
      ),
    );
  }
}
