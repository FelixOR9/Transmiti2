import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';

/// Colores disponibles para identificar a un equipo (valores ARGB).
const List<int> kTeamColors = <int>[
  0xFFE53935, // rojo
  0xFFFB8C00, // naranja
  0xFFFDD835, // amarillo
  0xFF43A047, // verde
  0xFF00ACC1, // turquesa
  0xFF1E88E5, // azul
  0xFF5E35B1, // morado
  0xFFD81B60, // rosa
  0xFF6D4C41, // café
  0xFF212121, // negro
  0xFFFFFFFF, // blanco
  0xFF9E9E9E, // gris
];

/// Selector de color: círculos con una marca en el elegido.
class ColorPalette extends StatelessWidget {
  const ColorPalette({
    super.key,
    required this.selected,
    required this.onSelected,
    required this.keyPrefix,
    this.enabled = true,
  });

  final int selected;
  final ValueChanged<int> onSelected;

  /// Prefijo de las llaves: 'prefijo-color-N' (N = posición del color).
  final String keyPrefix;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 10,
      runSpacing: 10,
      children: <Widget>[
        for (int i = 0; i < kTeamColors.length; i++)
          _Swatch(
            key: Key('$keyPrefix-color-$i'),
            value: kTeamColors[i],
            selected: kTeamColors[i] == selected,
            onTap: enabled ? () => onSelected(kTeamColors[i]) : null,
          ),
      ],
    );
  }
}

class _Swatch extends StatelessWidget {
  const _Swatch({
    super.key,
    required this.value,
    required this.selected,
    required this.onTap,
  });

  final int value;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final Color color = Color(value);
    final Color mark = color.computeLuminance() > 0.5
        ? Colors.black
        : Colors.white;
    return Semantics(
      button: true,
      selected: selected,
      label: 'Color',
      child: InkResponse(
        onTap: onTap,
        radius: 28,
        child: Container(
          width: 44,
          height: 44,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: color,
            border: Border.all(
              color: selected ? AppColors.primary : AppColors.outlineStrong,
              width: selected ? 3 : 2,
            ),
          ),
          child: selected ? Icon(Icons.check, color: mark, size: 22) : null,
        ),
      ),
    );
  }
}
