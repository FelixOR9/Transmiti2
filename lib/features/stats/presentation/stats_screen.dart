import 'package:flutter/material.dart';
import 'package:transmiti2/shared/widgets/feature_placeholder.dart';

class StatsScreen extends StatelessWidget {
  const StatsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const FeaturePlaceholder(
      title: 'Estadísticas',
      icon: Icons.bar_chart,
      description: 'Aquí verás las estadísticas del partido.',
    );
  }
}
