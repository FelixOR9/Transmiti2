import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/core/storage/image_draft.dart';
import 'package:transmiti2/core/storage/image_store.dart';
import 'package:transmiti2/features/match/data/repositories.dart';
import 'package:transmiti2/features/match/domain/exceptions.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';
import 'package:transmiti2/shared/widgets/app_input.dart';
import 'package:transmiti2/shared/widgets/confirm_dialog.dart';
import 'package:transmiti2/shared/widgets/image_field.dart';
import 'package:transmiti2/shared/widgets/stored_image.dart';

/// Administración de patrocinadores: nombre y logotipo. Todo se guarda en
/// SQLite y los logotipos en el almacén de imágenes de la app.
class SponsorsScreen extends StatefulWidget {
  const SponsorsScreen({super.key});

  @override
  State<SponsorsScreen> createState() => _SponsorsScreenState();
}

class _SponsorsScreenState extends State<SponsorsScreen> {
  late AppServices _services;
  bool _initialized = false;
  List<Sponsor> _sponsors = const <Sponsor>[];
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
      final List<Sponsor> list = await _services.sponsors.all();
      if (refreshSession) {
        // El partido actual muestra los patrocinadores que eligió.
        await _services.session.load();
      }
      if (!mounted) {
        return;
      }
      setState(() {
        _sponsors = list;
        _loading = false;
        _loadError = null;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _loadError = 'No se pudieron leer los patrocinadores.';
        });
      }
    }
  }

  Future<void> _edit({Sponsor? sponsor}) async {
    final bool? saved = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) =>
          SponsorDialog(services: _services, sponsor: sponsor),
    );
    if (saved == true) {
      await _reload(refreshSession: true);
    }
  }

  Future<void> _delete(Sponsor sponsor) async {
    final ScaffoldMessengerState messenger = ScaffoldMessenger.of(context);
    final bool confirmed = await confirmAction(
      context,
      title: 'Eliminar patrocinador',
      message:
          '¿Eliminar a ${sponsor.name}? También se quitará de los partidos donde esté elegido.',
      confirmLabel: 'ELIMINAR',
    );
    if (!confirmed) {
      return;
    }
    try {
      await _services.sponsors.delete(sponsor.id);
      final String? logo = sponsor.logoPath;
      if (logo != null && !await _services.isImageInUse(logo)) {
        await _services.images.delete(logo);
      }
      await _reload(refreshSession: true);
    } catch (_) {
      messenger.showSnackBar(
        const SnackBar(content: Text('No se pudo eliminar al patrocinador.')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Patrocinadores')),
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
                    key: const Key('add-sponsor'),
                    label: 'AGREGAR PATROCINADOR',
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
    if (_sponsors.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            'Todavía no hay patrocinadores. Agrega el primero con su nombre y su logotipo.',
            textAlign: TextAlign.center,
            style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
          ),
        ),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 8),
      itemCount: _sponsors.length,
      separatorBuilder: (BuildContext context, int index) =>
          const SizedBox(height: 10),
      itemBuilder: (BuildContext context, int index) {
        final Sponsor sponsor = _sponsors[index];
        return Material(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(18),
          child: InkWell(
            key: Key('sponsor-${sponsor.id}'),
            onTap: () => _edit(sponsor: sponsor),
            borderRadius: BorderRadius.circular(18),
            child: Container(
              padding: const EdgeInsets.fromLTRB(12, 10, 6, 10),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(18),
                border: Border.all(color: AppColors.outline),
              ),
              child: Row(
                children: <Widget>[
                  StoredImage(
                    path: sponsor.logoPath,
                    size: 56,
                    fallbackIcon: Icons.storefront_outlined,
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Text(
                      sponsor.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: text.titleMedium?.copyWith(
                        color: AppColors.textPrimary,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  IconButton(
                    key: Key('delete-sponsor-${sponsor.id}'),
                    tooltip: 'Eliminar patrocinador',
                    onPressed: () => _delete(sponsor),
                    icon: const Icon(
                      Icons.delete_outline,
                      color: AppColors.live,
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

/// Diálogo para agregar o editar un patrocinador (nombre y logotipo).
class SponsorDialog extends StatefulWidget {
  const SponsorDialog({super.key, required this.services, this.sponsor});

  final AppServices services;

  /// Patrocinador que se edita; nulo para agregar uno nuevo.
  final Sponsor? sponsor;

  @override
  State<SponsorDialog> createState() => _SponsorDialogState();
}

class _SponsorDialogState extends State<SponsorDialog> {
  late final TextEditingController _name = TextEditingController(
    text: widget.sponsor?.name ?? '',
  );
  late final ImageDraft _logo = ImageDraft(
    store: widget.services.images,
    kind: ImageKind.logo,
    initial: widget.sponsor?.logoPath,
    isInUse: widget.services.isImageInUse,
  );
  String? _nameError;
  String? _generalError;
  bool _saving = false;

  @override
  void dispose() {
    // Si se cierra sin guardar, se borra el logotipo cargado en este diálogo.
    _logo.discard();
    _name.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_saving) {
      return;
    }
    final Map<String, String> errors = SponsorRules.validate(name: _name.text);
    if (errors.isNotEmpty) {
      setState(() {
        _nameError = errors['name'];
        _generalError = null;
      });
      return;
    }
    setState(() {
      _saving = true;
      _nameError = null;
      _generalError = null;
    });
    try {
      final SponsorRepository repository = widget.services.sponsors;
      final Sponsor? existing = widget.sponsor;
      if (existing == null) {
        await repository.add(name: _name.text, logoPath: _logo.current);
      } else {
        await repository.update(
          existing.copyWith(
            name: _name.text,
            logoPath: _logo.current,
            clearLogo: _logo.current == null,
          ),
        );
      }
      // Ya guardado en la base: ahora sí se borra el logotipo anterior.
      await _logo.commit();
      if (mounted) {
        Navigator.of(context).pop(true);
      }
    } on ValidationException catch (e) {
      if (mounted) {
        setState(() {
          _nameError = e.errors['name'];
          _saving = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _generalError =
              'No se pudo guardar al patrocinador. Inténtalo de nuevo.';
          _saving = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return AlertDialog(
      title: Text(
        widget.sponsor == null ? 'Agregar patrocinador' : 'Editar patrocinador',
      ),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            const SizedBox(height: 8),
            TextField(
              key: const Key('sponsor-name'),
              controller: _name,
              autofocus: true,
              textCapitalization: TextCapitalization.words,
              decoration: appInputDecoration(
                label: 'Nombre del patrocinador',
                errorText: _nameError,
              ),
            ),
            const SizedBox(height: 20),
            ImageField(
              draft: _logo,
              pickLabel: 'CARGAR LOGOTIPO',
              keyPrefix: 'sponsor-logo',
              size: 88,
              fallbackIcon: Icons.storefront_outlined,
              onChanged: () => setState(() {}),
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
          key: const Key('sponsor-cancel'),
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('CANCELAR'),
        ),
        FilledButton(
          key: const Key('sponsor-save'),
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
