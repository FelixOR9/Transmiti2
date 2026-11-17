import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/features/broadcast/presentation/broadcast_screen.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';

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

FakeStreamingService grantedService() {
  final FakeStreamingService service = FakeStreamingService();
  service.permissionStates = const PermissionStates(
    camera: PermissionStatus.granted,
    microphone: PermissionStatus.notRequested,
    notifications: PermissionStatus.notRequested,
  );
  return service;
}

void main() {
  group('BroadcastScreen: vista previa real', () {
    testWidgets('al entrar no se abre la cámara', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      await pumpStudio(tester, service);

      expect(service.startPreviewCalls, 0);
      expect(find.byType(Texture), findsNothing);
      expect(find.text('VISTA PREVIA'), findsOneWidget);
    });

    testWidgets('inicia la vista previa y muestra la textura', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      await pumpStudio(tester, service);

      await tapPreview(tester);

      expect(service.startPreviewCalls, 1);
      expect(find.byType(Texture), findsOneWidget);
      expect(find.text('DETENER VISTA PREVIA'), findsOneWidget);
      expect(find.textContaining('Vista previa activa'), findsOneWidget);
    });

    testWidgets('detener cierra la vista previa', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      await pumpStudio(tester, service);

      await tapPreview(tester);
      expect(find.byType(Texture), findsOneWidget);

      await tester.ensureVisible(find.text('DETENER VISTA PREVIA'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('DETENER VISTA PREVIA'));
      await tester.pumpAndSettle();

      expect(service.stopPreviewCalls, 1);
      expect(find.byType(Texture), findsNothing);
      expect(find.text('VISTA PREVIA'), findsOneWidget);
    });

    testWidgets('se puede volver a iniciar varias veces', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      await pumpStudio(tester, service);

      await tapPreview(tester);
      await tester.ensureVisible(find.text('DETENER VISTA PREVIA'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('DETENER VISTA PREVIA'));
      await tester.pumpAndSettle();
      await tapPreview(tester);

      expect(service.startPreviewCalls, 2);
      expect(service.stopPreviewCalls, 1);
      expect(find.byType(Texture), findsOneWidget);
    });

    testWidgets('al salir de la pantalla la cámara se cierra', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      await pumpStudio(tester, service);

      await tapPreview(tester);
      expect(service.startPreviewCalls, 1);

      await tester.pumpWidget(const MaterialApp(home: SizedBox()));
      await tester.pumpAndSettle();

      expect(service.stopPreviewCalls, 1);
    });

    testWidgets('cámara en uso muestra el error y permite reintentar', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      service.startPreviewError = StreamContract.cameraInUse;
      await pumpStudio(tester, service);

      await tapPreview(tester);

      expect(service.startPreviewCalls, 1);
      expect(find.byType(Texture), findsNothing);
      expect(find.textContaining('No se pudo iniciar'), findsOneWidget);
      expect(find.textContaining('camera_in_use'), findsOneWidget);
      expect(find.text('REINTENTAR'), findsOneWidget);

      service.startPreviewError = null;
      await tester.tap(find.text('REINTENTAR'));
      await tester.pumpAndSettle();

      expect(service.startPreviewCalls, 2);
      expect(find.byType(Texture), findsOneWidget);
    });

    testWidgets('cámara no disponible muestra su estado', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      service.startPreviewError = StreamContract.cameraUnavailable;
      await pumpStudio(tester, service);

      await tapPreview(tester);

      expect(find.textContaining('Cámara no disponible'), findsOneWidget);
      expect(find.text('REINTENTAR'), findsOneWidget);
      expect(find.byType(Texture), findsNothing);
    });

    testWidgets('al girar el teléfono la vista previa se reinicia', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      await pumpStudio(tester, service);

      await tapPreview(tester);
      expect(service.startPreviewCalls, 1);
      expect(find.byType(Texture), findsOneWidget);

      tester.view.physicalSize = const Size(2400, 1080);
      await tester.pumpAndSettle();

      expect(service.startPreviewCalls, 2);
      expect(service.stopPreviewCalls, 1);
      expect(find.byType(Texture), findsOneWidget);
    });

    testWidgets('la textura se muestra sin deformar (apaisado rotado)', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      await pumpStudio(tester, service);

      await tapPreview(tester);

      final Texture texture = tester.widget<Texture>(find.byType(Texture));
      expect(texture.textureId, 1);
      // 1280×720 del sensor con rotación de 90°: la vista es vertical.
      final AspectRatio ratio = tester.widget<AspectRatio>(
        find.ancestor(
          of: find.byType(Texture),
          matching: find.byType(AspectRatio),
        ),
      );
      expect(ratio.aspectRatio, closeTo(720 / 1280, 0.001));
      final RotatedBox rotated = tester.widget<RotatedBox>(
        find.ancestor(
          of: find.byType(Texture),
          matching: find.byType(RotatedBox),
        ),
      );
      expect(rotated.quarterTurns, 1);
    });
  });
}
