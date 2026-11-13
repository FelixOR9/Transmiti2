import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/match/data/repositories.dart';
import 'package:transmiti2/features/match/domain/exceptions.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';
import 'package:transmiti2/shared/widgets/app_input.dart';
import 'package:transmiti2/shared/widgets/confirm_dialog.dart';

/// Datos que recibe la pantalla de jugadores al abrirse.
class PlayersArgs {
  const PlayersArgs({required this.teamId, required this.teamName});

  final int teamId;
  final String teamName;
}

/// Lista de jugadores de un equipo: agregar, editar y eliminar. Todo se
/// guarda en SQLite; el registro de jugadores es opcional.
class PlayersScreen extends StatefulWidget {
  const PlayersScreen({
    super.key,
    required this.teamId,
    required this.teamName,
  });

  final int teamId;
  final String teamName;

  @override
  State<PlayersScreen> createState() => _PlayersScreenState();
}

class _PlayersScreenState extends State<PlayersScreen> {
  late AppServices _services;
  bool _initialized = false;
  List<Player> _players = const <Player>[];
  bool _loading = true;
  String? _loadError;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_initialized) {
      return;
    }
    _initialized = true;
    _services = AppScope.of(context);
    _reload();
  }

  Future<void> _reload({bool refreshSession = false}) async {
    try {
      final List<Player> list = await _services.players.byTeam(widget.teamId);
      if (refreshSession) {
        // Mantiene al partido actual al tanto de la plantilla.
        await _services.session.load();
      }
      if (!mounted) {
        return;
      }
      setState(() {
        _players = list;
        _loading = false;
        _loadError = null;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _loadError = 'No se pudieron leer los jugadores.';
        });
      }
    }
  }

  Future<void> _edit({Player? player}) async {
    final bool? saved = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) => PlayerDialog(
        repository: _services.players,
        teamId: widget.teamId,
        player: player,
      ),
    );
    if (saved == true) {
      await _reload(refreshSession: true);
    }
  }

  Future<void> _delete(Player player) async {
    final ScaffoldMessengerState messenger = ScaffoldMessenger.of(context);
    final bool confirmed = await confirmAction(
      context,
      title: 'Eliminar jugador',
      message:
          '¿Eliminar a ${player.name} (#${player.number})? Los goles que tenga registrados se conservarán sin jugador.',
      confirmLabel: 'ELIMINAR',
    );
    if (!confirmed) {
      return;
    }
    try {
      await _services.players.delete(player.id);
      await _reload(refreshSession: true);
    } catch (_) {
      messenger.showSnackBar(
        const SnackBar(content: Text('No se pudo eliminar al jugador.')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Scaffold(
      appBar: AppBar(title: Text('Jugadores · ${widget.teamName}')),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: Column(
              children: <Widget>[
                Expanded(child: _buildList(text)),
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
                  child: AppButton.primary(
                    key: const Key('add-player'),
                    label: 'AGREGAR JUGADOR',
                    onPressed: () => _edit(),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildList(TextTheme text) {
    if (_loading) {
      return const Center(
        child: CircularProgressIndicator(color: AppColors.primary),
      );
    }
    if (_loadError != null) {
      return Center(
        child: Text(
          _loadError!,
          style: text.bodyMedium?.copyWith(color: AppColors.live),
        ),
      );
    }
    if (_players.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            'Todavía no hay jugadores. Es opcional: puedes jugar sin registrar la plantilla.',
            textAlign: TextAlign.center,
            style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
          ),
        ),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 8),
      itemCount: _players.length,
      separatorBuilder: (BuildContext context, int index) =>
          const SizedBox(height: 10),
      itemBuilder: (BuildContext context, int index) {
        final Player player = _players[index];
        return _PlayerTile(
          player: player,
          onEdit: () => _edit(player: player),
          onDelete: () => _delete(player),
        );
      },
    );
  }
}

class _PlayerTile extends StatelessWidget {
  const _PlayerTile({
    required this.player,
    required this.onEdit,
    required this.onDelete,
  });

  final Player player;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(18),
      child: InkWell(
        key: Key('player-${player.id}'),
        onTap: onEdit,
        borderRadius: BorderRadius.circular(18),
        child: Container(
          padding: const EdgeInsets.fromLTRB(14, 10, 6, 10),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: AppColors.outline),
          ),
          child: Row(
            children: <Widget>[
              Container(
                width: 44,
                height: 44,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(color: AppColors.outlineStrong, width: 2),
                ),
                child: Text(
                  '${player.number}',
                  style: text.titleMedium?.copyWith(
                    color: AppColors.textPrimary,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Text(
                  player.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: text.titleMedium?.copyWith(
                    color: AppColors.textPrimary,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              IconButton(
                key: Key('delete-player-${player.id}'),
                tooltip: 'Eliminar jugador',
                onPressed: onDelete,
                icon: const Icon(Icons.delete_outline, color: AppColors.live),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Diálogo para agregar o editar un jugador (nombre y número de camiseta).
class PlayerDialog extends StatefulWidget {
  const PlayerDialog({
    super.key,
    required this.repository,
    required this.teamId,
    this.player,
  });

  final PlayerRepository repository;
  final int teamId;

  /// Jugador que se edita; nulo para agregar uno nuevo.
  final Player? player;

  @override
  State<PlayerDialog> createState() => _PlayerDialogState();
}

class _PlayerDialogState extends State<PlayerDialog> {
  late final TextEditingController _name = TextEditingController(
    text: widget.player?.name ?? '',
  );
  late final TextEditingController _number = TextEditingController(
    text: widget.player == null ? '' : '${widget.player!.number}',
  );
  String? _nameError;
  String? _numberError;
  String? _generalError;
  bool _saving = false;

  @override
  void dispose() {
    _name.dispose();
    _number.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_saving) {
      return;
    }
    final String name = _name.text;
    final int? number = int.tryParse(_number.text.trim());
    final Map<String, String> errors = PlayerRules.validate(
      name: name,
      number: number,
    );
    if (errors.isNotEmpty || number == null) {
      setState(() {
        _nameError = errors['name'];
        _numberError = errors['number'];
        _generalError = null;
      });
      return;
    }
    setState(() {
      _saving = true;
      _nameError = null;
      _numberError = null;
      _generalError = null;
    });
    try {
      final Player? existing = widget.player;
      if (existing == null) {
        await widget.repository.add(
          teamId: widget.teamId,
          name: name,
          number: number,
        );
      } else {
        await widget.repository.update(
          existing.copyWith(name: name, number: number),
        );
      }
      if (mounted) {
        Navigator.of(context).pop(true);
      }
    } on DuplicateNumberException {
      if (mounted) {
        setState(() {
          _numberError = 'Ese número ya lo usa otro jugador de este equipo.';
          _saving = false;
        });
      }
    } on ValidationException catch (e) {
      if (mounted) {
        setState(() {
          _nameError = e.errors['name'];
          _numberError = e.errors['number'];
          _saving = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _generalError = 'No se pudo guardar al jugador. Inténtalo de nuevo.';
          _saving = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return AlertDialog(
      title: Text(widget.player == null ? 'Agregar jugador' : 'Editar jugador'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            const SizedBox(height: 8),
            TextField(
              key: const Key('player-name'),
              controller: _name,
              autofocus: true,
              textCapitalization: TextCapitalization.words,
              textInputAction: TextInputAction.next,
              decoration: appInputDecoration(
                label: 'Nombre del jugador',
                errorText: _nameError,
              ),
            ),
            const SizedBox(height: 16),
            TextField(
              key: const Key('player-number'),
              controller: _number,
              keyboardType: TextInputType.number,
              inputFormatters: <TextInputFormatter>[
                FilteringTextInputFormatter.digitsOnly,
                LengthLimitingTextInputFormatter(2),
              ],
              onSubmitted: (_) => _submit(),
              decoration: appInputDecoration(
                label: 'Número de camiseta (1 a 99)',
                errorText: _numberError,
              ),
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
          key: const Key('player-cancel'),
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('CANCELAR'),
        ),
        FilledButton(
          key: const Key('player-save'),
          onPressed: _saving ? null : _submit,
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.primary,
            foregroundColor: AppColors.onPrimary,
          ),
          child: const Text('GUARDAR'),
        ),
      ],
    );
  }
}
