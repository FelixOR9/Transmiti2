import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';

/// Botón grande de la aplicación: principal (verde) o secundario (azul).
class AppButton extends StatelessWidget {
  const AppButton.primary({
    super.key,
    required this.label,
    required this.onPressed,
  }) : _isPrimary = true;

  const AppButton.secondary({
    super.key,
    required this.label,
    required this.onPressed,
  }) : _isPrimary = false;

  final String label;
  final VoidCallback onPressed;
  final bool _isPrimary;

  @override
  Widget build(BuildContext context) {
    return FilledButton(
      onPressed: onPressed,
      style: FilledButton.styleFrom(
        backgroundColor: _isPrimary ? AppColors.primary : AppColors.surfaceHigh,
        foregroundColor: _isPrimary
            ? AppColors.onPrimary
            : AppColors.textPrimary,
        minimumSize: const Size.fromHeight(60),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
          side: _isPrimary
              ? BorderSide.none
              : const BorderSide(color: AppColors.outlineStrong),
        ),
        textStyle: Theme.of(context).textTheme.labelLarge?.copyWith(
          fontSize: 17,
          fontWeight: FontWeight.w800,
          letterSpacing: 1.0,
        ),
      ),
      child: FittedBox(fit: BoxFit.scaleDown, child: Text(label)),
    );
  }
}
