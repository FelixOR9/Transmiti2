import 'package:flutter/material.dart';
import 'package:transmiti2/core/app_info.dart';
import 'package:transmiti2/shared/widgets/feature_placeholder.dart';

class MoreScreen extends StatelessWidget {
  const MoreScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return FeaturePlaceholder(
      title: 'Más',
      icon: Icons.more_horiz,
      description:
          'Aquí estarán los ajustes y la información de la aplicación.',
      footnote: 'Versión ${AppInfo.version}',
    );
  }
}
