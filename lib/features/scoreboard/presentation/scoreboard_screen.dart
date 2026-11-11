import 'package:flutter/material.dart';
import 'package:transmiti2/shared/widgets/feature_placeholder.dart';

class ScoreboardScreen extends StatelessWidget {
  const ScoreboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const FeaturePlaceholder(
      title: 'Marcador',
      icon: Icons.sports_score,
      description: 'Aquí registrarás los goles de ambos equipos.',
    );
  }
}
