import 'package:flutter/material.dart';
import 'package:transmiti2/features/home/presentation/home_screen.dart';
import 'package:transmiti2/features/match/presentation/match_screen.dart';
import 'package:transmiti2/features/more/presentation/more_screen.dart';
import 'package:transmiti2/features/replays/presentation/replays_screen.dart';
import 'package:transmiti2/shared/widgets/app_bottom_nav.dart';

/// Estructura principal: pestañas con la barra de navegación inferior.
///
/// Cada pestaña se crea la primera vez que se visita y después se conserva.
class MainShell extends StatefulWidget {
  const MainShell({super.key});

  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> {
  static const List<AppBottomNavItem> _items = <AppBottomNavItem>[
    AppBottomNavItem(icon: Icons.home_outlined, label: 'Inicio'),
    AppBottomNavItem(icon: Icons.videocam_outlined, label: 'Partido'),
    AppBottomNavItem(icon: Icons.replay, label: 'Repeticiones'),
    AppBottomNavItem(icon: Icons.more_horiz, label: 'Más'),
  ];
  static const int _replaysTab = 2;

  int _index = 0;
  final Set<int> _visited = <int>{0};

  void _select(int index) {
    setState(() {
      _index = index;
      _visited.add(index);
    });
  }

  Widget _tab(int index) {
    if (!_visited.contains(index)) {
      return const SizedBox.shrink();
    }
    return switch (index) {
      0 => HomeScreen(onOpenReplays: () => _select(_replaysTab)),
      1 => const MatchScreen(),
      2 => const ReplaysScreen(),
      _ => const MoreScreen(),
    };
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(
        index: _index,
        children: <Widget>[for (int i = 0; i < _items.length; i++) _tab(i)],
      ),
      bottomNavigationBar: AppBottomNav(
        items: _items,
        currentIndex: _index,
        onSelected: _select,
      ),
    );
  }
}
