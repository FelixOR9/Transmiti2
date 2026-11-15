import 'package:flutter/material.dart';
import 'package:transmiti2/features/live/presentation/live_match_screen.dart';

/// Cronómetro: la pantalla de control del partido, con el cronómetro primero.
class MatchClockScreen extends StatelessWidget {
  const MatchClockScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const LiveMatchScreen(title: 'Cronómetro', clockFirst: true);
  }
}
