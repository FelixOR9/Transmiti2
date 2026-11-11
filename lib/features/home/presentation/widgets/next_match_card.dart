import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';
import 'package:transmiti2/shared/widgets/section_label.dart';

/// Tarjeta del próximo partido con los botones para prepararlo y abrir el estudio.
///
/// Mientras no haya un partido configurado ([homeName] y [awayName] nulos)
/// muestra un estado vacío; no se inventan datos.
class NextMatchCard extends StatelessWidget {
  const NextMatchCard({
    super.key,
    this.homeName,
    this.awayName,
    this.details,
    required this.onPrepare,
    required this.onOpenStudio,
  });

  final String? homeName;
  final String? awayName;
  final String? details;
  final VoidCallback onPrepare;
  final VoidCallback onOpenStudio;

  bool get _configured => homeName != null && awayName != null;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
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
          const SectionLabel('PRÓXIMO PARTIDO'),
          const SizedBox(height: 16),
          _TeamRow(
            badge: 'LOC',
            name: homeName ?? 'Equipo local',
            muted: !_configured,
          ),
          const SizedBox(height: 12),
          _TeamRow(
            badge: 'VIS',
            name: awayName ?? 'Equipo visitante',
            muted: !_configured,
          ),
          const SizedBox(height: 16),
          Text(
            details ?? 'Sin partido configurado',
            style: text.titleMedium?.copyWith(color: AppColors.textMuted),
          ),
          const SizedBox(height: 20),
          AppButton.primary(label: 'PREPARAR PARTIDO', onPressed: onPrepare),
          const SizedBox(height: 12),
          AppButton.secondary(
            label: 'ABRIR ESTUDIO DE TRANSMISIÓN',
            onPressed: onOpenStudio,
          ),
        ],
      ),
    );
  }
}

class _TeamRow extends StatelessWidget {
  const _TeamRow({
    required this.badge,
    required this.name,
    required this.muted,
  });

  final String badge;
  final String name;
  final bool muted;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Row(
      children: <Widget>[
        Container(
          width: 44,
          height: 44,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            border: Border.all(color: AppColors.outlineStrong, width: 2),
          ),
          child: Text(
            badge,
            style: text.labelMedium?.copyWith(
              color: AppColors.textMuted,
              fontWeight: FontWeight.w800,
            ),
          ),
        ),
        const SizedBox(width: 16),
        Expanded(
          child: Text(
            name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: text.headlineSmall?.copyWith(
              color: muted ? AppColors.textMuted : AppColors.textPrimary,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
      ],
    );
  }
}
