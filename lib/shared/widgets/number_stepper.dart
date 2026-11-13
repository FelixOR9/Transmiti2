import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';

/// Campo numérico con botones -5, -1, +1 y +5 (cómodo con una sola mano).
class NumberStepper extends StatelessWidget {
  const NumberStepper({
    super.key,
    required this.label,
    required this.value,
    required this.min,
    required this.max,
    required this.onChanged,
    required this.keyPrefix,
    this.unit = 'min',
    this.enabled = true,
    this.errorText,
  });

  final String label;
  final int value;
  final int min;
  final int max;
  final ValueChanged<int> onChanged;

  /// Prefijo de las llaves: 'prefijo-minus5', 'prefijo-minus1', 'prefijo-plus1',
  /// 'prefijo-plus5' y 'prefijo-value'.
  final String keyPrefix;
  final String unit;
  final bool enabled;
  final String? errorText;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Text(
          label,
          style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
        ),
        const SizedBox(height: 8),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: <Widget>[
            _step('minus5', '-5', -5),
            _step('minus1', '-1', -1),
            Expanded(
              child: Center(
                child: Text(
                  '$value $unit',
                  key: Key('$keyPrefix-value'),
                  style: text.titleLarge?.copyWith(
                    color: enabled
                        ? AppColors.textPrimary
                        : AppColors.textMuted,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
            ),
            _step('plus1', '+1', 1),
            _step('plus5', '+5', 5),
          ],
        ),
        if (errorText != null)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(
              errorText!,
              style: text.bodySmall?.copyWith(color: AppColors.live),
            ),
          ),
      ],
    );
  }

  Widget _step(String id, String text, int delta) {
    final int next = (value + delta).clamp(min, max);
    final bool canChange = enabled && next != value;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 3),
      child: SizedBox(
        width: 48,
        height: 48,
        child: OutlinedButton(
          key: Key('$keyPrefix-$id'),
          onPressed: canChange ? () => onChanged(next) : null,
          style: OutlinedButton.styleFrom(
            padding: EdgeInsets.zero,
            foregroundColor: AppColors.textPrimary,
            side: const BorderSide(color: AppColors.outlineStrong),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
            ),
          ),
          child: FittedBox(
            fit: BoxFit.scaleDown,
            child: Text(
              text,
              style: const TextStyle(fontWeight: FontWeight.w800),
            ),
          ),
        ),
      ),
    );
  }
}
