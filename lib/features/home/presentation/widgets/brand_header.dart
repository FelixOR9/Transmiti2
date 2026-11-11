import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/core/app_info.dart';

/// Logotipo y nombre de TRANSMITI2 en la parte superior de la pantalla inicial.
class BrandHeader extends StatelessWidget {
  const BrandHeader({super.key});

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Row(
      children: <Widget>[
        const _LogoMark(),
        const SizedBox(width: 16),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              FittedBox(
                fit: BoxFit.scaleDown,
                alignment: Alignment.centerLeft,
                child: Text(
                  AppInfo.name,
                  maxLines: 1,
                  style: text.headlineMedium?.copyWith(
                    color: AppColors.textPrimary,
                    fontWeight: FontWeight.w900,
                    letterSpacing: 1.5,
                  ),
                ),
              ),
              const SizedBox(height: 2),
              Text(
                AppInfo.tagline,
                style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _LogoMark extends StatelessWidget {
  const _LogoMark();

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: 'Logotipo de ${AppInfo.name}',
      excludeSemantics: true,
      child: SizedBox(
        width: 64,
        height: 64,
        child: Stack(
          clipBehavior: Clip.none,
          children: <Widget>[
            Container(
              width: 64,
              height: 64,
              decoration: const BoxDecoration(
                shape: BoxShape.circle,
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: <Color>[Color(0xFF34D96F), Color(0xFF15803D)],
                ),
              ),
              child: const Icon(
                Icons.sports_soccer,
                size: 36,
                color: AppColors.onPrimary,
              ),
            ),
            Positioned(
              right: -2,
              top: -2,
              child: Container(
                width: 18,
                height: 18,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: AppColors.live,
                  border: Border.all(color: AppColors.background, width: 3),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
