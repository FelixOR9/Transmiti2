import 'package:flutter/material.dart';
import 'package:transmiti2/features/live/presentation/live_match_screen.dart';

/// Marcador: la pantalla de control del partido, con los goles primero.
class ScoreboardScreen extends StatelessWidget {
  const ScoreboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const LiveMatchScreen(title: 'Marcador');
  }
}
