import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_routes.dart';
import 'package:transmiti2/features/broadcast/presentation/broadcast_screen.dart';
import 'package:transmiti2/features/home/presentation/home_screen.dart';
import 'package:transmiti2/features/match_clock/presentation/match_clock_screen.dart';
import 'package:transmiti2/features/replays/presentation/replays_screen.dart';
import 'package:transmiti2/features/scoreboard/presentation/scoreboard_screen.dart';
import 'package:transmiti2/features/stats/presentation/stats_screen.dart';

/// Traduce el nombre de una ruta en su pantalla.
///
/// Se usa el Navigator de Flutter sin paquetes externos; si más adelante
/// hace falta algo más avanzado, este es el único archivo que cambia.
abstract final class AppRouter {
  static Route<void> onGenerateRoute(RouteSettings settings) {
    final Widget page = switch (settings.name) {
      AppRoutes.home => const HomeScreen(),
      AppRoutes.broadcast => const BroadcastScreen(),
      AppRoutes.scoreboard => const ScoreboardScreen(),
      AppRoutes.matchClock => const MatchClockScreen(),
      AppRoutes.stats => const StatsScreen(),
      AppRoutes.replays => const ReplaysScreen(),
      _ => const HomeScreen(),
    };
    return MaterialPageRoute<void>(
      builder: (BuildContext context) => page,
      settings: settings,
    );
  }
}
