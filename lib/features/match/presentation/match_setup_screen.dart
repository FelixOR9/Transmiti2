import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_routes.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/core/formatting.dart';
import 'package:transmiti2/core/storage/image_draft.dart';
import 'package:transmiti2/core/storage/image_store.dart';
import 'package:transmiti2/features/match/domain/exceptions.dart';
import 'package:transmiti2/features/match/domain/match_detail.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/features/match/presentation/players_screen.dart';
import 'package:transmiti2/features/match/presentation/widgets/team_editor_card.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';
import 'package:transmiti2/shared/widgets/app_input.dart';
import 'package:transmiti2/shared/widgets/color_palette.dart';
import 'package:transmiti2/shared/widgets/confirm_dialog.dart';
import 'package:transmiti2/shared/widgets/number_stepper.dart';
import 'package:transmiti2/shared/widgets/section_label.dart';
import 'package:transmiti2/shared/widgets/stored_image.dart';

/// Formulario para preparar el partido: equipos, escudos, colores, fecha,
/// duraciones y patrocinadores. Guarda de verdad en SQLite a través de
/// [MatchSession]; las imágenes se copian al almacén de la app.
class MatchSetupScreen extends StatefulWidget {
  const MatchSetupScreen({super.key});

  @override
  State<MatchSetupScreen> createState() => _MatchSetupScreenState();
}

class _MatchSetupScreenState extends State<MatchSetupScreen> {
  late AppServices _services;
  bool _initialized = false;

  late TeamDraft _home;
  late TeamDraft _away;
  final TextEditingController _tournament = TextEditingController();
  DateTime _date = _today();
  int _half = 45;
  int _break = 15;

  /// Partido guardado que se está editando (nulo si se prepara uno nuevo).
  MatchDetail? _editing;
  bool _createNew = false;
  bool _saving = false;

  List<Sponsor> _sponsors = <Sponsor>[];
  List<int> _selected = <int>[];
  Map<String, Team> _knownTeams = <String, Team>{};
  Map<String, String> _errors = <String, String>{};

  static DateTime _today() {
    final DateTime now = DateTime.now();
    return DateTime(now.year, now.month, now.day);
  }

  bool get _started =>
      _editing != null && _services.session.clock.phase != MatchPhase.notStarted;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_initialized) {
      return;
    }
    _initialized = true;
    _services = AppScope.of(context);
    _fillFromSession();
    _loadLists();
  }

  @override
  void dispose() {
    // Si se sale sin guardar, se borran los escudos cargados en esta pantalla.
    _home.crest.discard();
    _away.crest.discard();
    _home.dispose();
    _away.dispose();
    _tournament.dispose();
    super.dispose();
  }

  TeamDraft _newDraft(Team? team, {required int defaultColor}) {
    return TeamDraft(
      name: TextEditingController(text: team?.name ?? ''),
      color: team?.colorValue ?? defaultColor,
      crest: ImageDraft(
        store: _services.images,
        kind: ImageKind.crest,
        initial: team?.crestPath,
        isInUse: _services.isImageInUse,
      ),
    );
  }

  void _fillFromSession() {
    final MatchDetail? detail = _services.session.detail;
    final MatchDetail? editing =
        (detail != null && detail.match.status != MatchStatus.finished)
        ? detail
        : null;
    _editing = editing;
    _home = _newDraft(editing?.home, defaultColor: kTeamColors[0]);
    _away = _newDraft(editing?.away, defaultColor: kTeamColors[5]);
    if (editing != null) {
      _tournament.text = editing.match.tournament ?? '';
      final DateTime when = DateTime.fromMillisecondsSinceEpoch(
        editing.match.scheduledAtMs,
      );
      _date = DateTime(when.year, when.month, when.day);
      _half = editing.match.halfMinutes;
      _break = editing.match.breakMinutes;
      _selected = editing.sponsors.map((Sponsor s) => s.id).toList();
    }
  }

  Future<void> _loadLists() async {
    final List<Sponsor> sponsors = await _services.sponsors.all();
    final List<Team> teams = await _services.teams.all();
    if (!mounted) {
      return;
    }
    setState(() {
      _sponsors = sponsors;
      _knownTeams = <String, Team>{
        for (final Team t in teams) Team.keyOf(t.name): t,
      };
      final Set<int> valid = sponsors.map((Sponsor s) => s.id).toSet();
      _selected = _selected.where(valid.contains).toList();
    });
  }

  /// Si el nombre coincide con un equipo ya registrado, toma su escudo y su
  /// color para no perderlos al repetir el equipo en otro partido.
  void _onNameChanged(TeamDraft draft, String text) {
    final String key = Team.keyOf(text);
    final Team? known = _knownTeams[key];
    setState(() {
      if (known != null) {
        if (draft.adoptedKey != key && !draft.crest.hasImage) {
          draft.adoptedKey = key;
          draft.adoptedPath = known.crestPath;
          draft.color = known.colorValue;
          draft.crest.adopt(known.crestPath);
        }
      } else if (draft.adoptedKey != null) {
        if (draft.crest.current == draft.adoptedPath) {
          draft.crest.adopt(null);
        }
        draft.adoptedKey = null;
        draft.adoptedPath = null;
      }
    });
  }

  Future<void> _pickDate() async {
    final DateTime? picked = await showDatePicker(
      context: context,
      initialDate: _date,
      firstDate: DateTime(2020),
      lastDate: DateTime(2100),
    );
    if (picked != null && mounted) {
      setState(() {
        _date = DateTime(picked.year, picked.month, picked.day);
        _errors = Map<String, String>.of(_errors)..remove('date');
      });
    }
  }

  void _toggleSponsor(int id) {
    setState(() {
      if (_selected.contains(id)) {
        _selected = _selected.where((int s) => s != id).toList();
      } else {
        _selected = <int>[..._selected, id];
      }
    });
  }

  Future<void> _openSponsors() async {
    await Navigator.of(context).pushNamed(AppRoutes.sponsors);
    if (mounted) {
      await _loadLists();
    }
  }

  void _openPlayers(Team team) {
    Navigator.of(context).pushNamed(
      AppRoutes.players,
      arguments: PlayersArgs(teamId: team.id, teamName: team.name),
    );
  }

  Future<void> _startNewMatch() async {
    final bool confirmed = await confirmAction(
      context,
      title: 'Nuevo partido',
      message:
          'El partido actual quedará cancelado y empezarás uno nuevo desde cero.',
      confirmLabel: 'EMPEZAR NUEVO',
    );
    if (!confirmed || !mounted) {
      return;
    }
    await _home.crest.discard();
    await _away.crest.discard();
    setState(() {
      for (final (TeamDraft draft, int color) in <(TeamDraft, int)>[
        (_home, kTeamColors[0]),
        (_away, kTeamColors[5]),
      ]) {
        draft.name.clear();
        draft.color = color;
        draft.crest.adopt(null);
        draft.adoptedKey = null;
        draft.adoptedPath = null;
      }
      _tournament.clear();
      _date = _today();
      _half = 45;
      _break = 15;
      _selected = <int>[];
      _errors = <String, String>{};
      _editing = null;
      _createNew = true;
    });
  }

  Future<void> _save() async {
    if (_saving) {
      return;
    }
    final ScaffoldMessengerState messenger = ScaffoldMessenger.of(context);
    final MatchSetup setup = MatchSetup(
      homeName: _home.name.text,
      homeColor: _home.color,
      homeCrestPath: _home.crest.current,
      awayName: _away.name.text,
      awayColor: _away.color,
      awayCrestPath: _away.crest.current,
      tournament: _tournament.text,
      scheduledAtMs: _date.millisecondsSinceEpoch,
      halfMinutes: _half,
      breakMinutes: _break,
      sponsorIds: _selected,
    );
    final Map<String, String> errors = setup.validate();
    if (errors.isNotEmpty) {
      setState(() => _errors = errors);
      messenger.showSnackBar(
        const SnackBar(content: Text('Revisa los datos marcados en rojo.')),
      );
      return;
    }
    setState(() {
      _saving = true;
      _errors = <String, String>{};
    });
    try {
      await _services.session.saveSetup(setup, createNew: _createNew);
      await _home.crest.commit();
      await _away.crest.commit();
      await _loadLists();
      if (!mounted) {
        return;
      }
      setState(() {
        _createNew = false;
        _editing = _services.session.detail;
      });
      messenger.showSnackBar(const SnackBar(content: Text('Partido guardado.')));
    } on ValidationException catch (e) {
      if (mounted) {
        setState(() => _errors = e.errors);
      }
    } catch (_) {
      messenger.showSnackBar(
        const SnackBar(
          content: Text('No se pudo guardar el partido. Inténtalo de nuevo.'),
        ),
      );
    } finally {
      if (mounted) {
        setState(() => _saving = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final MatchDetail? saved = _editing;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Preparar partido'),
        actions: <Widget>[
          if (saved != null)
            TextButton(
              key: const Key('new-match'),
              onPressed: _startNewMatch,
              child: const Text('NUEVO PARTIDO'),
            ),
        ],
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
              children: <Widget>[
                if (_started)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 16),
                    child: Text(
                      'El partido ya empezó: los equipos y las duraciones no se pueden cambiar.',
                      style: text.bodyMedium?.copyWith(
                        color: AppColors.textMuted,
                      ),
                    ),
                  ),
                TeamEditorCard(
                  title: 'EQUIPO LOCAL',
                  keyPrefix: 'home',
                  draft: _home,
                  locked: _started,
                  nameError: _errors['homeName'],
                  onChanged: () => setState(() {}),
                  onNameChanged: (String v) => _onNameChanged(_home, v),
                ),
                const SizedBox(height: 16),
                TeamEditorCard(
                  title: 'EQUIPO VISITANTE',
                  keyPrefix: 'away',
                  draft: _away,
                  locked: _started,
                  nameError: _errors['awayName'],
                  colorError: _errors['awayColor'],
                  onChanged: () => setState(() {}),
                  onNameChanged: (String v) => _onNameChanged(_away, v),
                ),
                const SizedBox(height: 16),
                _SectionCard(
                  title: 'PARTIDO',
                  children: <Widget>[
                    TextField(
                      key: const Key('tournament'),
                      controller: _tournament,
                      textCapitalization: TextCapitalization.words,
                      decoration: appInputDecoration(
                        label: 'Torneo o competición (opcional)',
                        errorText: _errors['tournament'],
                      ),
                    ),
                    const SizedBox(height: 16),
                    OutlinedButton.icon(
                      key: const Key('pick-date'),
                      onPressed: _pickDate,
                      icon: const Icon(Icons.calendar_today_outlined),
                      label: Text('Fecha: ${formatDate(_date.millisecondsSinceEpoch)}'),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: AppColors.textPrimary,
                        side: BorderSide(
                          color: _errors['date'] != null
                              ? AppColors.live
                              : AppColors.outlineStrong,
                        ),
                        minimumSize: const Size.fromHeight(52),
                        alignment: Alignment.centerLeft,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(14),
                        ),
                      ),
                    ),
                    if (_errors['date'] != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 6),
                        child: Text(
                          _errors['date']!,
                          style: text.bodySmall?.copyWith(color: AppColors.live),
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 16),
                _SectionCard(
                  title: 'DURACIÓN',
                  children: <Widget>[
                    NumberStepper(
                      label: 'Duración de cada tiempo',
                      keyPrefix: 'half',
                      value: _half,
                      min: MatchRules.minHalfMinutes,
                      max: MatchRules.maxHalfMinutes,
                      enabled: !_started,
                      errorText: _errors['halfMinutes'],
                      onChanged: (int v) => setState(() => _half = v),
                    ),
                    const SizedBox(height: 20),
                    NumberStepper(
                      label: 'Descanso entre tiempos',
                      keyPrefix: 'break',
                      value: _break,
                      min: MatchRules.minBreakMinutes,
                      max: MatchRules.maxBreakMinutes,
                      enabled: !_started,
                      errorText: _errors['breakMinutes'],
                      onChanged: (int v) => setState(() => _break = v),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                _SectionCard(
                  title: 'PATROCINADORES DEL PARTIDO',
                  children: <Widget>[
                    if (_sponsors.isEmpty)
                      Text(
                        'Todavía no hay patrocinadores registrados.',
                        style: text.bodyMedium?.copyWith(
                          color: AppColors.textMuted,
                        ),
                      )
                    else ...<Widget>[
                      for (final Sponsor sponsor in _sponsors)
                        _SponsorOption(
                          sponsor: sponsor,
                          order: _selected.indexOf(sponsor.id) + 1,
                          onTap: () => _toggleSponsor(sponsor.id),
                        ),
                      const SizedBox(height: 4),
                      Text(
                        'Puedes elegir los que quieras. El orden en que los eliges será el orden en que se muestran.',
                        style: text.bodySmall?.copyWith(
                          color: AppColors.textMuted,
                        ),
                      ),
                    ],
                    const SizedBox(height: 16),
                    AppButton.secondary(
                      key: const Key('manage-sponsors'),
                      label: 'ADMINISTRAR PATROCINADORES',
                      onPressed: _openSponsors,
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                _SectionCard(
                  title: 'JUGADORES (OPCIONAL)',
                  children: <Widget>[
                    if (saved == null)
                      Text(
                        'Guarda el partido para poder registrar los jugadores de cada equipo. Puedes empezar sin plantillas completas.',
                        style: text.bodyMedium?.copyWith(
                          color: AppColors.textMuted,
                        ),
                      )
                    else ...<Widget>[
                      AppButton.secondary(
                        key: const Key('players-home'),
                        label: 'JUGADORES · ${saved.home.name}',
                        onPressed: () => _openPlayers(saved.home),
                      ),
                      const SizedBox(height: 12),
                      AppButton.secondary(
                        key: const Key('players-away'),
                        label: 'JUGADORES · ${saved.away.name}',
                        onPressed: () => _openPlayers(saved.away),
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: 24),
                AppButton.primary(
                  key: const Key('save-match'),
                  label: _saving ? 'GUARDANDO…' : 'GUARDAR PARTIDO',
                  onPressed: _saving ? () {} : _save,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SectionCard extends StatelessWidget {
  const _SectionCard({required this.title, required this.children});

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
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          SectionLabel(title),
          const SizedBox(height: 16),
          ...children,
        ],
      ),
    );
  }
}

class _SponsorOption extends StatelessWidget {
  const _SponsorOption({
    required this.sponsor,
    required this.order,
    required this.onTap,
  });

  final Sponsor sponsor;

  /// Posición en la selección (0 = no elegido).
  final int order;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final bool selected = order > 0;
    return InkWell(
      key: Key('sponsor-option-${sponsor.id}'),
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(
          children: <Widget>[
            StoredImage(
              path: sponsor.logoPath,
              size: 48,
              fallbackIcon: Icons.storefront_outlined,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                sponsor.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: text.titleMedium?.copyWith(
                  color: AppColors.textPrimary,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            if (selected)
              Container(
                width: 26,
                height: 26,
                alignment: Alignment.center,
                decoration: const BoxDecoration(
                  shape: BoxShape.circle,
                  color: AppColors.primary,
                ),
                child: Text(
                  '$order',
                  style: const TextStyle(
                    color: AppColors.onPrimary,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
            Checkbox(
              key: Key('sponsor-check-${sponsor.id}'),
              value: selected,
              activeColor: AppColors.primary,
              checkColor: AppColors.onPrimary,
              onChanged: (_) => onTap(),
            ),
          ],
        ),
      ),
    );
  }
}
