import 'package:flutter/material.dart';
import 'package:transmiti2/shared/widgets/feature_placeholder.dart';

class MatchClockScreen extends StatelessWidget {
  const MatchClockScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const FeaturePlaceholder(
      title: 'Cronómetro',
      icon: Icons.timer,
      description: 'Aquí controlarás el tiempo del partido.',
    );
  }
}
