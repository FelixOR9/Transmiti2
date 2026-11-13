import 'dart:io';

import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/core/storage/image_draft.dart';
import 'package:transmiti2/core/storage/image_store.dart';
import 'package:transmiti2/shared/widgets/stored_image.dart';

/// Campo para elegir una imagen de la galería: vista previa, cargar,
/// reemplazar y quitar. Trabaja sobre un [ImageDraft]; el dueño del
/// formulario decide cuándo guardar o descartar.
class ImageField extends StatefulWidget {
  const ImageField({
    super.key,
    required this.draft,
    required this.pickLabel,
    required this.keyPrefix,
    this.circular = false,
    this.size = 84,
    this.borderColor,
    this.fallbackIcon = Icons.shield_outlined,
    this.enabled = true,
    this.onChanged,
  });

  final ImageDraft draft;

  /// Texto del botón cuando todavía no hay imagen (por ejemplo CARGAR ESCUDO).
  final String pickLabel;

  /// Prefijo de las llaves de los botones: 'prefijo-pick' y 'prefijo-remove'.
  final String keyPrefix;
  final bool circular;
  final double size;
  final Color? borderColor;
  final IconData fallbackIcon;
  final bool enabled;
  final VoidCallback? onChanged;

  @override
  State<ImageField> createState() => _ImageFieldState();
}

class _ImageFieldState extends State<ImageField> {
  bool _busy = false;

  Future<void> _pick() async {
    final AppServices services = AppScope.of(context);
    final ScaffoldMessengerState messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      final File? file = await services.gallery.pickImage();
      if (file != null) {
        await widget.draft.replaceWith(file);
        widget.onChanged?.call();
      }
    } on ImageStorageException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
    } catch (_) {
      messenger.showSnackBar(
        const SnackBar(content: Text('No se pudo abrir la galería.')),
      );
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  void _remove() {
    widget.draft.clear();
    setState(() {});
    widget.onChanged?.call();
  }

  @override
  Widget build(BuildContext context) {
    final bool hasImage = widget.draft.hasImage;
    return Row(
      children: <Widget>[
        StoredImage(
          path: widget.draft.current,
          size: widget.size,
          circular: widget.circular,
          fallbackIcon: widget.fallbackIcon,
          borderColor: widget.borderColor,
        ),
        const SizedBox(width: 16),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              _SmallButton(
                key: Key('${widget.keyPrefix}-pick'),
                icon: Icons.photo_library_outlined,
                label: hasImage ? 'REEMPLAZAR' : widget.pickLabel,
                onPressed: (_busy || !widget.enabled) ? null : _pick,
              ),
              if (hasImage) ...<Widget>[
                const SizedBox(height: 8),
                _SmallButton(
                  key: Key('${widget.keyPrefix}-remove'),
                  icon: Icons.delete_outline,
                  label: 'QUITAR',
                  destructive: true,
                  onPressed: widget.enabled ? _remove : null,
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _SmallButton extends StatelessWidget {
  const _SmallButton({
    super.key,
    required this.icon,
    required this.label,
    required this.onPressed,
    this.destructive = false,
  });

  final IconData icon;
  final String label;
  final VoidCallback? onPressed;
  final bool destructive;

  @override
  Widget build(BuildContext context) {
    final Color color = destructive ? AppColors.live : AppColors.textPrimary;
    return OutlinedButton.icon(
      onPressed: onPressed,
      icon: Icon(icon, size: 20),
      label: FittedBox(fit: BoxFit.scaleDown, child: Text(label)),
      style: OutlinedButton.styleFrom(
        foregroundColor: color,
        side: BorderSide(
          color: destructive ? AppColors.live : AppColors.outlineStrong,
        ),
        minimumSize: const Size(0, 44),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    );
  }
}
