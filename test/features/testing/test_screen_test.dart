import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/app/app.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/features/more/presentation/more_screen.dart';
import 'package:transmiti2/features/testing/application/test_controller.dart';
import 'package:transmiti2/features/testing/domain/test_contract.dart';
import 'package:transmiti2/features/testing/presentation/test_screen.dart';
import 'package:transmiti2/shared/widgets/app_bottom_nav.dart';

import '../../support/fake_streaming.dart';
import '../../support/fake_testing.dart';
import '../../support/test_services.dart';

Future<void> _pumpTestScreen(
  WidgetTester tester,
  AppServices services,
) async {
  await tester.pumpWidget(
    AppScope(
      services: services,
      child: const MaterialApp(home: TestScreen()),
    ),
  );
  await tester.pumpAndSettle();
}

({FakeTestService service, TestController controller}) _testing({
  bool testMode = true,
}) {
  final FakeTestService service = FakeTestService(testMode: testMode);
  final TestController controller = TestController(
    testService: service,
    streamingService: FakeStreamingService(),
    testMode: testMode,
  );
  return (service: service, controller: controller);
}

Future<void> _openMoreTab(WidgetTester tester) async {
  await tester.tap(
    find.descendant(
      of: find.byType(AppBottomNav),
      matching: find.text('Más'),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  group('test nº 12 (parte Dart): compilación normal', () {
    testWidgets('noTestEntryWhenTestModeIsFalse', (WidgetTester tester) async {
      // Sin modo prueba: la fila no se renderiza en Más.
      await tester.pumpWidget(
        Transmiti2App(services: createTestServices(testMode: false)),
      );
      await tester.pumpAndSettle();
      await _openMoreTab(tester);
      expect(find.byType(MoreScreen), findsOneWidget);
      expect(find.byKey(const Key('more-test-row')), findsNothing);
      expect(find.text('Pruebas de diagnóstico'), findsNothing);

      // Y la ruta /pruebas muestra «No disponible», sin controles.
      final ({FakeTestService service, TestController controller}) t =
          _testing(testMode: false);
      await _pumpTestScreen(
        tester,
        createTestServices(testMode: false, testing: t.controller),
      );
      expect(
        find.text('No disponible en esta compilación'),
        findsOneWidget,
      );
      expect(find.byKey(const Key('test-mode-banner')), findsNothing);
      expect(find.byKey(const Key('test-start-session')), findsNothing);
      expect(find.byKey(const Key('test-new-campaign')), findsNothing);
      t.controller.dispose();
    });

    testWidgets('la fila aparece solo con testMode y abre /pruebas',
        (WidgetTester tester) async {
      final ({FakeTestService service, TestController controller}) t =
          _testing();
      await tester.pumpWidget(
        Transmiti2App(
          services: createTestServices(
            testMode: true,
            testing: t.controller,
          ),
        ),
      );
      await tester.pumpAndSettle();
      await _openMoreTab(tester);
      expect(find.byKey(const Key('more-test-row')), findsOneWidget);
      await tester.tap(find.byKey(const Key('more-test-row')));
      await tester.pumpAndSettle();
      expect(find.byType(TestScreen), findsOneWidget);
      expect(find.byKey(const Key('test-mode-banner')), findsOneWidget);
      expect(find.text('MODO PRUEBA'), findsOneWidget);
      t.controller.dispose();
    });
  });

  group('prueba 3d: deleteRequiresConfirmationDialog', () {
    testWidgets('tocar Eliminar muestra el diálogo; Cancelar no invoca '
        'al canal; Eliminar sí lo invoca con confirmed: true',
        (WidgetTester tester) async {
      final ({FakeTestService service, TestController controller}) t =
          _testing();
      const TestSessionInfo session = TestSessionInfo(
        sessionId: 'sess_20261123_120000_01',
        testRunId: 'run_20261123_120000_ab12',
        startedAtWallMs: 1764000000000,
        sizeBytes: 2048,
        verdict: 'INCOMPLETE',
      );
      t.service.sessions.add(session);
      await _pumpTestScreen(
        tester,
        createTestServices(testMode: true, testing: t.controller),
      );
      expect(
        find.byKey(const Key('test-session-sess_20261123_120000_01')),
        findsOneWidget,
      );

      // Tocar «Eliminar» muestra el diálogo con sesión, testRunId y
      // tamaño.
      await tester.tap(
        find.byKey(const Key('test-delete-sess_20261123_120000_01')),
      );
      await tester.pumpAndSettle();
      expect(find.text('Eliminar sesión'), findsOneWidget);
      expect(
        find.textContaining('sess_20261123_120000_01'),
        findsWidgets,
      );
      // testRunId y tamaño aparecen en la fila y en el diálogo.
      expect(find.textContaining('run_20261123_120000_ab12'), findsWidgets);
      expect(find.textContaining('2.0 KB'), findsWidgets);

      // «Cancelar» no invoca al canal.
      await tester.tap(find.byKey(const Key('test-delete-cancel')));
      await tester.pumpAndSettle();
      expect(find.text('Eliminar sesión'), findsNothing);
      expect(t.service.deleteCalls, isEmpty);
      expect(
        find.byKey(const Key('test-session-sess_20261123_120000_01')),
        findsOneWidget,
      );

      // «Eliminar» sí lo invoca con `confirmed: true`.
      await tester.tap(
        find.byKey(const Key('test-delete-sess_20261123_120000_01')),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('test-delete-confirm')));
      await tester.pumpAndSettle();
      expect(t.service.deleteCalls.length, 1);
      expect(t.service.deleteCalls.first.sessionId, session.sessionId);
      expect(t.service.deleteCalls.first.confirmed, isTrue);
      expect(
        find.byKey(const Key('test-session-sess_20261123_120000_01')),
        findsNothing,
      );
      t.controller.dispose();
    });
  });

  group('prueba 4c: storageFullBannerAndDeleteFlow', () {
    testWidgets('con el almacén lleno: aviso, lista de 10 sesiones y '
        'borrado con diálogo que libera el aviso',
        (WidgetTester tester) async {
      final ({FakeTestService service, TestController controller}) t =
          _testing();
      t.service.fillToLimit();
      await _pumpTestScreen(
        tester,
        createTestServices(testMode: true, testing: t.controller),
      );

      expect(find.byKey(const Key('test-storage-full-banner')), findsOneWidget);
      expect(
        find.textContaining('Almacenamiento de pruebas lleno (10/10 sesiones)'),
        findsOneWidget,
      );
      expect(find.textContaining('Sesiones guardadas: 10/10'), findsOneWidget);
      // La lista muestra fecha, testRunId, tamaño y veredicto.
      expect(find.textContaining('run_20261123_120000_ab12'), findsWidgets);
      expect(find.textContaining('veredicto: INCOMPLETE'), findsWidgets);
      expect(find.textContaining('1.0 KB'), findsWidgets);

      // El flujo de borrado exige el diálogo (prueba 3d).
      await tester.ensureVisible(
        find.byKey(const Key('test-delete-sess_20261123_120001')),
      );
      await tester.tap(
        find.byKey(const Key('test-delete-sess_20261123_120001')),
      );
      await tester.pumpAndSettle();
      expect(find.text('Eliminar sesión'), findsOneWidget);
      await tester.tap(find.byKey(const Key('test-delete-confirm')));
      await tester.pumpAndSettle();

      // Tras confirmar, el aviso desaparece y queda hueco.
      expect(find.byKey(const Key('test-storage-full-banner')), findsNothing);
      expect(find.textContaining('Sesiones guardadas: 9/10'), findsOneWidget);
      expect(
        find.byKey(const Key('test-session-sess_20261123_120001')),
        findsNothing,
      );
      t.controller.dispose();
    });
  });
}
