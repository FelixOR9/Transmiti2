import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';

/// Franja con el estado de cámara, micrófono y red.
///
/// Fase 3.0: cámara y micrófono todavía no se piden al usuario; se pedirán
/// en la Fase 3.1, cuando se vaya a usar la vista previa. La red sigue
/// "Sin comprobar" hasta que haya transmisión.
class DeviceStatusStrip extends StatelessWidget {
  const DeviceStatusStrip({super.key});

  static const String _notChecked = 'Sin comprobar';

  /// Cámara y micrófono: no pedidos todavía (Fase 3.0).
  static const String _phase31 = 'Se pedirá en 3.1';

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 8),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AppColors.outline),
      ),
      child: const Row(
        children: <Widget>[
          Expanded(
            child: _StatusItem(label: 'CÁMARA', value: _phase31),
          ),
          Expanded(
            child: _StatusItem(label: 'MICRÓFONO', value: _phase31),
          ),
          Expanded(
            child: _StatusItem(label: 'RED', value: _notChecked),
          ),
        ],
      ),
    );
  }
}

class _StatusItem extends StatelessWidget {
  const _StatusItem({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          FittedBox(
            fit: BoxFit.scaleDown,
            child: Text(
              label,
              maxLines: 1,
              style: text.labelMedium?.copyWith(
                color: AppColors.textMuted,
                letterSpacing: 1.4,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          const SizedBox(height: 4),
          FittedBox(
            fit: BoxFit.scaleDown,
            child: Text(
              value,
              maxLines: 1,
              style: text.titleSmall?.copyWith(
                color: AppColors.textPrimary,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
