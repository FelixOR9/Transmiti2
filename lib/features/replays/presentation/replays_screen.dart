import 'package:flutter/material.dart';
import 'package:transmiti2/shared/widgets/feature_placeholder.dart';

class ReplaysScreen extends StatelessWidget {
  const ReplaysScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const FeaturePlaceholder(
      title: 'Repeticiones',
      icon: Icons.replay,
      description: 'Aquí generarás repeticiones y clips de jugadas.',
    );
  }
}
