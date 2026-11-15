import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/goal_minute.dart';
import 'package:transmiti2/features/match/domain/match_detail.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/shared/widgets/app_input.dart';

/// Abre el diálogo para registrar un gol (o corregir uno si se pasa
/// [editing]). Devuelve verdadero si se guardó.
Future<bool?> showGoalDialog(
  BuildContext context, {
  required MatchSession session,
  required int teamId,
  MatchEvent? editing,
}) {
  return showDialog<bool>(
    context: context,
    builder: (BuildContext dialogContext) =>
        GoalDialog(session: session, teamId: teamId, editing: editing),
  );
}

/// Registro o corrección de un gol: minuto (sugerido por el cronómetro pero
/// editable) y jugador (opcional). El gol se guarda en SQLite al confirmar.
class GoalDialog extends StatefulWidget {
  const GoalDialog({
    super.key,
    required this.session,
    required this.teamId,
    this.editing,
  });

  final MatchSession session;
  final int teamId;

  /// Gol que se corrige; nulo para registrar uno nuevo.
  final MatchEvent? editing;

  @override
  State<GoalDialog> createState() => _GoalDialogState();
}

class _GoalDialogState extends State<GoalDialog> {
  late int _teamId = widget.teamId;
  late int? _playerId = widget.editing?.playerId;
  late final TextEditingController _minute = TextEditingController(
    text: _initialMinuteText(),
  );
  String? _minuteError;
  String? _generalError;
  bool _saving = false;

  String _initialMinuteText() {
    final MatchEvent? editing = widget.editing;
    final GoalMinute m = editing != null
        ? GoalMinute(editing.minute, editing.added)
        : _suggestedMinute();
    return m.added > 0 ? '${m.minute}+${m.added}' : '${m.minute}';
  }

  /// Minuto que marca el cronómetro en este instante.
  GoalMinute _suggestedMinute() {
    final MatchSession s = widget.session;
    final MatchDetail detail = s.detail!;
    return GoalMinute.fromElapsed(
      period: s.clock.period,
      elapsedMs: s.clock.elapsedAt(s.nowMs),
      halfMinutes: detail.match.halfMinutes,
    );
  }

  @override
  void dispose() {
    _minute.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_saving) {
      return;
    }
    final GoalMinute? minute = GoalMinute.tryParse(_minute.text);
    if (minute == null) {
      setState(
        () => _minuteError = 'Escribe un minuto válido, por ejemplo 23 o 45+2.',
      );
      return;
    }
    setState(() {
      _saving = true;
      _minuteError = null;
      _generalError = null;
    });
    try {
      final MatchEvent? editing = widget.editing;
      if (editing == null) {
        await widget.session.addGoal(
          teamId: _teamId,
          playerId: _playerId,
          minute: minute,
        );
      } else {
        await widget.session.editGoal(
          editing.id,
          teamId: _teamId != editing.teamId ? _teamId : null,
          playerId: _playerId,
          clearPlayer: _playerId == null,
          minute: minute,
        );
      }
      if (mounted) {
        Navigator.of(context).pop(true);
      }
    } on StateError catch (e) {
      if (mounted) {
        setState(() {
          _generalError = e.message;
          _saving = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _generalError = 'No se pudo guardar el gol. Inténtalo de nuevo.';
          _saving = false;
        });
      }
    }
  }

  Widget _chip({
    required Key key,
    required String label,
    required bool selected,
    required VoidCallback onSelected,
  }) {
    return ChoiceChip(
      key: key,
      label: Text(label),
      selected: selected,
      showCheckmark: false,
      onSelected: (_) => onSelected(),
      selectedColor: AppColors.primary,
      backgroundColor: AppColors.surfaceHigh,
      side: const BorderSide(color: AppColors.outlineStrong),
      labelStyle: TextStyle(
        color: selected ? AppColors.onPrimary : AppColors.textPrimary,
        fontWeight: FontWeight.w700,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final MatchDetail detail = widget.session.detail!;
    final Team team = detail.teamById(_teamId)!;
    final List<Player> roster = widget.session.playersOf(_teamId);
    final bool editing = widget.editing != null;
    return AlertDialog(
      title: Text(editing ? 'Corregir gol' : 'Gol de ${team.name}'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            const SizedBox(height: 8),
            if (editing) ...<Widget>[
              Text(
                'Equipo',
                style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: <Widget>[
                  _chip(
                    key: const Key('goal-team-home'),
                    label: detail.home.name,
                    selected: _teamId == detail.home.id,
                    onSelected: () => setState(() {
                      if (_teamId != detail.home.id) {
                        _teamId = detail.home.id;
                        _playerId = null;
                      }
                    }),
                  ),
                  _chip(
                    key: const Key('goal-team-away'),
                    label: detail.away.name,
                    selected: _teamId == detail.away.id,
                    onSelected: () => setState(() {
                      if (_teamId != detail.away.id) {
                        _teamId = detail.away.id;
                        _playerId = null;
                      }
                    }),
                  ),
                ],
              ),
              const SizedBox(height: 16),
            ],
            TextField(
              key: const Key('goal-minute'),
              controller: _minute,
              keyboardType: TextInputType.text,
              onSubmitted: (_) => _submit(),
              decoration: appInputDecoration(
                label: 'Minuto del gol',
                hint: 'Por ejemplo 23 o 45+2',
                errorText: _minuteError,
              ),
            ),
            const SizedBox(height: 16),
            Text(
              'Jugador que anotó (opcional)',
              style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
            ),
            const SizedBox(height: 8),
            if (roster.isEmpty)
              Text(
                'Este equipo no tiene jugadores registrados.',
                style: text.bodySmall?.copyWith(color: AppColors.textMuted),
              )
            else
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: <Widget>[
                  _chip(
                    key: const Key('goal-player-none'),
                    label: 'Sin jugador',
                    selected: _playerId == null,
                    onSelected: () => setState(() => _playerId = null),
                  ),
                  for (final Player p in roster)
                    _chip(
                      key: Key('goal-player-${p.id}'),
                      label: '#${p.number} ${p.name}',
                      selected: _playerId == p.id,
                      onSelected: () => setState(() => _playerId = p.id),
                    ),
                ],
              ),
            if (_generalError != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(
                  _generalError!,
                  style: text.bodySmall?.copyWith(color: AppColors.live),
                ),
              ),
          ],
        ),
      ),
      actions: <Widget>[
        TextButton(
          key: const Key('goal-cancel'),
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('CANCELAR'),
        ),
        FilledButton(
          key: const Key('goal-confirm'),
          onPressed: _saving ? null : _submit,
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.primary,
            foregroundColor: AppColors.onPrimary,
          ),
          child: Text(editing ? 'GUARDAR CAMBIOS' : 'REGISTRAR GOL'),
        ),
      ],
    );
  }
}
