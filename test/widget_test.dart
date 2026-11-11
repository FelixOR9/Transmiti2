import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/app/app.dart';
import 'package:transmiti2/features/broadcast/presentation/broadcast_screen.dart';
import 'package:transmiti2/features/home/presentation/home_screen.dart';
import 'package:transmiti2/features/match_clock/presentation/match_clock_screen.dart';
import 'package:transmiti2/features/replays/presentation/replays_screen.dart';
import 'package:transmiti2/features/scoreboard/presentation/scoreboard_screen.dart';
import 'package:transmiti2/features/stats/presentation/stats_screen.dart';

void usePhoneScreen(WidgetTester tester) {
  tester.view.physicalSize = const Size(1080, 2340);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.reset);
}

void main() {
  testWidgets('La pantalla principal muestra identidad y accesos', (
    WidgetTester tester,
  ) async {
    usePhoneScreen(tester);
    await tester.pumpWidget(const Transmiti2App());

    expect(find.byType(HomeScreen), findsOneWidget);
    expect(find.text('TRANSMITI2'), findsOneWidget);
    expect(find.text('INICIAR TRANSMISIÓN'), findsOneWidget);
    expect(find.text('Marcador'), findsOneWidget);
    expect(find.text('Cronómetro'), findsOneWidget);
    expect(find.text('Estadísticas'), findsOneWidget);
    expect(find.text('Repeticiones'), findsOneWidget);
  });

  Future<void> expectNavigation(
    WidgetTester tester,
    String tapText,
    Type destination,
  ) async {
    usePhoneScreen(tester);
    await tester.pumpWidget(const Transmiti2App());

    final Finder button = find.text(tapText);
    await tester.ensureVisible(button);
    await tester.tap(button);
    await tester.pumpAndSettle();
    expect(find.byType(destination), findsOneWidget);

    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(find.byType(HomeScreen), findsOneWidget);
  }

  testWidgets('INICIAR TRANSMISIÓN abre la pantalla de transmisión', (
    WidgetTester tester,
  ) {
    return expectNavigation(tester, 'INICIAR TRANSMISIÓN', BroadcastScreen);
  });

  testWidgets('Marcador abre su pantalla', (WidgetTester tester) {
    return expectNavigation(tester, 'Marcador', ScoreboardScreen);
  });

  testWidgets('Cronómetro abre su pantalla', (WidgetTester tester) {
    return expectNavigation(tester, 'Cronómetro', MatchClockScreen);
  });

  testWidgets('Estadísticas abre su pantalla', (WidgetTester tester) {
    return expectNavigation(tester, 'Estadísticas', StatsScreen);
  });

  testWidgets('Repeticiones abre su pantalla', (WidgetTester tester) {
    return expectNavigation(tester, 'Repeticiones', ReplaysScreen);
  });
}
