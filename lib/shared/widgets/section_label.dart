import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';

/// Rótulo pequeño en mayúsculas que encabeza una sección.
class SectionLabel extends StatelessWidget {
  const SectionLabel(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: Theme.of(context).textTheme.labelMedium?.copyWith(
        color: AppColors.textMuted,
        letterSpacing: 2.0,
        fontWeight: FontWeight.w700,
      ),
    );
  }
}
