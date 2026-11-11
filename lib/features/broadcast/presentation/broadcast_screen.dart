import 'package:flutter/material.dart';
import 'package:transmiti2/shared/widgets/feature_placeholder.dart';

class BroadcastScreen extends StatelessWidget {
  const BroadcastScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const FeaturePlaceholder(
      title: 'Estudio de transmisión',
      icon: Icons.sensors,
      description: 'Aquí configurarás y controlarás la transmisión en vivo.',
    );
  }
}
