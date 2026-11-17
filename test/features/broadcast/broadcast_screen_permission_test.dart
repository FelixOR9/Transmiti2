import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/features/broadcast/presentation/broadcast_screen.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';

import '../../support/fake_streaming.dart';
import '../../support/test_services.dart';

Future<void> pumpStudio(
  WidgetTester tester,
  FakeStreamingService service,
) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.reset);
  final AppServices services = createTestServices(streamingService: service);
  await tester.pumpWidget(
    MaterialApp(
      home: AppScope(services: services, child: const BroadcastScreen()),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> tapPreview(WidgetTester tester) async {
  await tester.ensureVisible(find.text('VISTA PREVIA'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('VISTA PREVIA'));
  await tester.pumpAndSettle();
}

void main() {
  group('BroadcastScreen: permisos de cámara', () {
    testWidgets('al entrar no se pide ningún permiso', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = FakeStreamingService();
      await pumpStudio(tester, service);

      expect(service.requestPermissionsCalls, 0);
      expect(find.byType(AlertDialog), findsNothing);
      expect(find.text('VISTA PREVIA'), findsOneWidget);
      expect(find.textContaining('solo cuando tocas'), findsOneWidget);
      expect(find.text('TRANSMITIR'), findsOneWidget);
      expect(find.text('GRABAR EN EL TELÉFONO'), findsOneWidget);
    });

    testWidgets('conceder la cámara muestra el estado honesto de la 3.1.1', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = FakeStreamingService();
      service.requestOutcomes[PermissionKind.camera] =
          PermissionStatus.granted;
      await pumpStudio(tester, service);

      await tapPreview(tester);
      expect(find.text('Permiso de cámara'), findsOneWidget);

      await tester.tap(find.text('PERMITIR CÁMARA'));
      await tester.pumpAndSettle();

      expect(service.requestPermissionsCalls, 1);
      expect(
        service.lastRequestedPermissions,
        <PermissionKind>[PermissionKind.camera],
      );
      expect(find.textContaining('subetapa 3.1.1'), findsWidgets);
      expect(find.text('Disponible en la Fase 3.1.1'), findsOneWidget);
    });

    testWidgets('denegar la cámara permite reintentarlo', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = FakeStreamingService();
      service.requestOutcomes[PermissionKind.camera] =
          PermissionStatus.denied;
      await pumpStudio(tester, service);

      await tapPreview(tester);
      await tester.tap(find.text('PERMITIR CÁMARA'));
      await tester.pumpAndSettle();
      expect(find.textContaining('denegado'), findsWidgets);

      await tapPreview(tester);
      expect(find.text('Permiso de cámara'), findsOneWidget);
      await tester.tap(find.text('PERMITIR CÁMARA'));
      await tester.pumpAndSettle();
      expect(service.requestPermissionsCalls, 2);
    });

    testWidgets('denegar permanentemente ofrece abrir los ajustes', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = FakeStreamingService();
      service.requestOutcomes[PermissionKind.camera] =
          PermissionStatus.permanentlyDenied;
      await pumpStudio(tester, service);

      await tapPreview(tester);
      await tester.tap(find.text('PERMITIR CÁMARA'));
      await tester.pumpAndSettle();
      expect(find.text('Permiso bloqueado'), findsOneWidget);

      await tester.tap(find.text('ABRIR AJUSTES'));
      await tester.pumpAndSettle();
      expect(service.openAppSettingsCalls, 1);
    });

    testWidgets('con la cámara concedida no se vuelve a pedir el permiso', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = FakeStreamingService();
      service.permissionStates = const PermissionStates(
        camera: PermissionStatus.granted,
        microphone: PermissionStatus.notRequested,
        notifications: PermissionStatus.notRequested,
      );
      await pumpStudio(tester, service);

      expect(service.requestPermissionsCalls, 0);
      expect(find.text('VISTA PREVIA'), findsOneWidget);
      expect(find.text('Disponible en la Fase 3.1.1'), findsOneWidget);
      expect(find.textContaining('Cámara concedida'), findsOneWidget);
    });
  });
}
