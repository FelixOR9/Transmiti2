import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/app/app.dart';
import 'package:transmiti2/features/broadcast/presentation/broadcast_screen.dart';
import 'package:transmiti2/features/home/presentation/home_screen.dart';
import 'package:transmiti2/features/home/presentation/widgets/access_button.dart';
import 'package:transmiti2/features/match/presentation/match_screen.dart';
import 'package:transmiti2/features/match/presentation/match_setup_screen.dart';
import 'package:transmiti2/features/match_clock/presentation/match_clock_screen.dart';
import 'package:transmiti2/features/more/presentation/more_screen.dart';
import 'package:transmiti2/features/replays/presentation/replays_screen.dart';
import 'package:transmiti2/features/scoreboard/presentation/scoreboard_screen.dart';
import 'package:transmiti2/features/stats/presentation/stats_screen.dart';
import 'package:transmiti2/shared/widgets/app_bottom_nav.dart';

Future<void> openApp(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(const Transmiti2App());
}

Finder inAccess(String label) {
  return find.descendant(
    of: find.byType(AccessButton),
    matching: find.text(label),
  );
}

Finder inNav(String label) {
  return find.descendant(
    of: find.byType(AppBottomNav),
    matching: find.text(label),
  );
}

Future<void> tapAndSettle(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

Future<void> expectOpensAndReturns(
  WidgetTester tester,
  Finder button,
  Type destination,
) async {
  await openApp(tester);
  await tapAndSettle(tester, button);
  expect(find.byType(destination), findsOneWidget);

  await tester.pageBack();
  await tester.pumpAndSettle();
  expect(find.byType(HomeScreen), findsOneWidget);
}

void main() {
  testWidgets('La pantalla de inicio muestra identidad, partido y accesos', (
    WidgetTester tester,
  ) async {
    await openApp(tester);

    expect(find.byType(HomeScreen), findsOneWidget);
    expect(find.text('TRANSMITI2'), findsOneWidget);
    expect(find.text('PRÓXIMO PARTIDO'), findsOneWidget);
    expect(find.text('Sin partido configurado'), findsOneWidget);
    expect(find.text('PREPARAR PARTIDO'), findsOneWidget);
    expect(find.text('ABRIR ESTUDIO DE TRANSMISIÓN'), findsOneWidget);
    expect(find.text('ACCESOS'), findsOneWidget);
    for (final String label in <String>[
      'MARCADOR',
      'CRONÓMETRO',
      'ESTADÍSTICAS',
      'REPETICIONES',
    ]) {
      expect(inAccess(label), findsOneWidget);
    }
    expect(find.text('CÁMARA'), findsOneWidget);
    expect(find.text('MICRÓFONO'), findsOneWidget);
    expect(find.text('RED'), findsOneWidget);
    expect(find.text('Sin comprobar'), findsNWidgets(3));
  });

  testWidgets('La barra inferior muestra las cuatro pestañas', (
    WidgetTester tester,
  ) async {
    await openApp(tester);

    for (final String label in <String>[
      'Inicio',
      'Partido',
      'Repeticiones',
      'Más',
    ]) {
      expect(inNav(label), findsOneWidget);
    }
  });

  testWidgets('PREPARAR PARTIDO abre la preparación del partido', (
    WidgetTester tester,
  ) {
    return expectOpensAndReturns(
      tester,
      find.text('PREPARAR PARTIDO'),
      MatchSetupScreen,
    );
  });

  testWidgets('ABRIR ESTUDIO DE TRANSMISIÓN abre el estudio', (
    WidgetTester tester,
  ) {
    return expectOpensAndReturns(
      tester,
      find.text('ABRIR ESTUDIO DE TRANSMISIÓN'),
      BroadcastScreen,
    );
  });

  testWidgets('MARCADOR abre su pantalla', (WidgetTester tester) {
    return expectOpensAndReturns(
      tester,
      inAccess('MARCADOR'),
      ScoreboardScreen,
    );
  });

  testWidgets('CRONÓMETRO abre su pantalla', (WidgetTester tester) {
    return expectOpensAndReturns(
      tester,
      inAccess('CRONÓMETRO'),
      MatchClockScreen,
    );
  });

  testWidgets('ESTADÍSTICAS abre su pantalla', (WidgetTester tester) {
    return expectOpensAndReturns(tester, inAccess('ESTADÍSTICAS'), StatsScreen);
  });

  testWidgets('REPETICIONES cambia a la pestaña de repeticiones', (
    WidgetTester tester,
  ) async {
    await openApp(tester);
    await tapAndSettle(tester, inAccess('REPETICIONES'));

    expect(find.byType(ReplaysScreen), findsOneWidget);
    expect(find.byType(HomeScreen), findsNothing);
  });

  testWidgets('La barra inferior cambia de pestaña y vuelve a Inicio', (
    WidgetTester tester,
  ) async {
    await openApp(tester);

    await tapAndSettle(tester, inNav('Partido'));
    expect(find.byType(MatchScreen), findsOneWidget);
    expect(find.byType(HomeScreen), findsNothing);

    await tapAndSettle(tester, inNav('Más'));
    expect(find.byType(MoreScreen), findsOneWidget);
    expect(find.text('Versión 0.1.0'), findsOneWidget);

    await tapAndSettle(tester, inNav('Inicio'));
    expect(find.byType(HomeScreen), findsOneWidget);
    expect(find.byType(MoreScreen), findsNothing);
  });
}
