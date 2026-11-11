import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';

/// Pantalla provisional para funciones que se construirán en etapas
/// posteriores. Cada función la reemplaza por su pantalla real.
class FeaturePlaceholder extends StatelessWidget {
  const FeaturePlaceholder({
    super.key,
    required this.title,
    required this.icon,
    required this.description,
    this.footnote,
  });

  final String title;
  final IconData icon;
  final String description;
  final String? footnote;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                Container(
                  width: 96,
                  height: 96,
                  decoration: const BoxDecoration(
                    shape: BoxShape.circle,
                    color: AppColors.surfaceHigh,
                  ),
                  child: Icon(icon, size: 48, color: AppColors.primary),
                ),
                const SizedBox(height: 20),
                Text(
                  description,
                  textAlign: TextAlign.center,
                  style: text.titleMedium?.copyWith(
                    color: AppColors.textPrimary,
                  ),
                ),
                const SizedBox(height: 16),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: AppColors.outline),
                  ),
                  child: Text(
                    'PRÓXIMAMENTE',
                    style: text.labelMedium?.copyWith(
                      color: AppColors.textMuted,
                      letterSpacing: 1.4,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                if (footnote != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 24),
                    child: Text(
                      footnote!,
                      style: text.bodySmall?.copyWith(
                        color: AppColors.textMuted,
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
