import 'package:flutter/material.dart';
import 'package:transmiti2/shared/widgets/feature_placeholder.dart';

class MatchScreen extends StatelessWidget {
  const MatchScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const FeaturePlaceholder(
      title: 'Partido',
      icon: Icons.videocam_outlined,
      description:
          'Aquí prepararás el partido y abrirás el estudio de transmisión.',
    );
  }
}
