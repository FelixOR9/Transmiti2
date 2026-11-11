import 'package:flutter/material.dart';
import 'package:transmiti2/shared/widgets/feature_placeholder.dart';

class MatchSetupScreen extends StatelessWidget {
  const MatchSetupScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const FeaturePlaceholder(
      title: 'Preparar partido',
      icon: Icons.sports_soccer,
      description: 'Aquí configurarás equipos, hora y cancha del partido.',
    );
  }
}
