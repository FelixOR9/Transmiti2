import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/theme/app_colors.dart';

/// Escudo o logotipo guardado en el almacén de imágenes.
///
/// Se muestra completo y sin deformarse ([BoxFit.contain]). Si no hay imagen,
/// o el archivo ya no existe, muestra un icono en su lugar.
class StoredImage extends StatelessWidget {
  const StoredImage({
    super.key,
    required this.path,
    required this.size,
    this.circular = false,
    this.fallbackIcon = Icons.shield_outlined,
    this.borderColor,
  });

  /// Ruta relativa dentro del almacén de imágenes (nula = sin imagen).
  final String? path;
  final double size;
  final bool circular;
  final IconData fallbackIcon;
  final Color? borderColor;

  Widget _placeholder() {
    return Center(
      child: Icon(fallbackIcon, size: size * 0.5, color: AppColors.textMuted),
    );
  }

  @override
  Widget build(BuildContext context) {
    final BorderRadius radius = BorderRadius.circular(circular ? size / 2 : 14);
    final String? relative = path;
    Widget content = _placeholder();
    if (relative != null) {
      try {
        content = Image.file(
          AppScope.of(context).images.file(relative),
          fit: BoxFit.contain,
          gaplessPlayback: true,
          errorBuilder:
              (BuildContext context, Object error, StackTrace? stackTrace) =>
                  _placeholder(),
        );
      } on ArgumentError {
        content = _placeholder();
      }
    }
    return Container(
      width: size,
      height: size,
      padding: EdgeInsets.all(size * 0.1),
      decoration: BoxDecoration(
        color: AppColors.surfaceHigh,
        borderRadius: radius,
        border: Border.all(
          color: borderColor ?? AppColors.outlineStrong,
          width: 2,
        ),
      ),
      child: ClipRRect(borderRadius: radius, child: content),
    );
  }
}
