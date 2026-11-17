import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/streaming/application/permission_controller.dart';
import 'package:transmiti2/features/streaming/application/preview_controller.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';

import '../../support/fake_streaming.dart';

void main() {
  group('PreviewController', () {
    late FakeStreamingService service;
    late PermissionController permissions;
    late PreviewController preview;

    Future<void> pumpController(WidgetTester tester) async {
      await tester.pumpWidget(const SizedBox());
    }

    setUp(() async {
      service = FakeStreamingService();
      service.permissionStates = const PermissionStates(
        camera: PermissionStatus.granted,
        microphone: PermissionStatus.notRequested,
        notifications: PermissionStatus.notRequested,
      );
      permissions = PermissionController(service: service);
      // Como en la app: los estados se consultan al entrar, antes de
      // que el controlador de vista previa los lea.
      await permissions.refresh();
      preview = PreviewController(service: service, permissions: permissions);
      preview.attach();
    });

    tearDown(() {
      preview.detach();
    });

    testWidgets('inicia la vista previa con el permiso concedido', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);

      await preview.start();
      await tester.pump();

      expect(preview.state, PreviewState.active);
      expect(preview.info?.textureId, 1);
      expect(preview.info?.width, 1280);
      expect(preview.info?.height, 720);
      expect(service.startPreviewCalls, 1);
    });

    testWidgets('iniciar dos veces no abre la cámara dos veces', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);

      await preview.start();
      await preview.start();
      await tester.pump();

      expect(preview.state, PreviewState.active);
      expect(service.startPreviewCalls, 1);
    });

    testWidgets('sin permiso no llama al motor y queda en permissionDenied', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.permissionStates = const PermissionStates(
        camera: PermissionStatus.denied,
        microphone: PermissionStatus.notRequested,
        notifications: PermissionStatus.notRequested,
      );
      await permissions.refresh();

      await preview.start();
      await tester.pump();

      expect(preview.state, PreviewState.permissionDenied);
      expect(service.startPreviewCalls, 0);
    });

    testWidgets('permiso revocado entre la comprobación y la apertura', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.startPreviewError = StreamContract.permissionDenied;

      await preview.start();
      await tester.pump();

      expect(preview.state, PreviewState.permissionDenied);
      expect(preview.errorCode, StreamContract.permissionDenied);
    });

    testWidgets('cámara en uso queda en error con el código', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.startPreviewError = StreamContract.cameraInUse;

      await preview.start();
      await tester.pump();

      expect(preview.state, PreviewState.error);
      expect(preview.errorCode, StreamContract.cameraInUse);
    });

    testWidgets('cámara no disponible queda en unavailable', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.startPreviewError = StreamContract.cameraUnavailable;

      await preview.start();
      await tester.pump();

      expect(preview.state, PreviewState.unavailable);
      expect(preview.errorCode, StreamContract.cameraUnavailable);
    });

    testWidgets('detener cierra y es idempotente', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);

      await preview.start();
      await tester.pump();
      await preview.stop();
      await tester.pump();

      expect(preview.state, PreviewState.closed);
      expect(preview.info, isNull);
      expect(service.stopPreviewCalls, 1);

      await preview.stop();
      await tester.pump();
      expect(service.stopPreviewCalls, 1);
    });

    testWidgets('al ir a segundo plano se detiene y al volver se restaura', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);

      await preview.start();
      await tester.pump();
      expect(preview.state, PreviewState.active);

      preview.didChangeAppLifecycleState(AppLifecycleState.paused);
      await tester.pump();
      expect(preview.state, PreviewState.closed);
      expect(service.stopPreviewCalls, 1);

      preview.didChangeAppLifecycleState(AppLifecycleState.resumed);
      await tester.pump();
      expect(preview.state, PreviewState.active);
      expect(service.startPreviewCalls, 2);
    });

    testWidgets('al ir a segundo plano cerrada no se restaura nada', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);

      preview.didChangeAppLifecycleState(AppLifecycleState.paused);
      await tester.pump();
      preview.didChangeAppLifecycleState(AppLifecycleState.resumed);
      await tester.pump();

      expect(preview.state, PreviewState.closed);
      expect(service.startPreviewCalls, 0);
    });

    testWidgets('un error del motor mientras está activa queda en error', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);

      await preview.start();
      await tester.pump();
      expect(preview.state, PreviewState.active);

      service.emitEvent(<String, Object?>{
        'type': StreamContract.eventPreviewError,
        'code': StreamContract.cameraDisconnected,
      });
      await tester.pump();

      expect(preview.state, PreviewState.error);
      expect(preview.errorCode, StreamContract.cameraDisconnected);
    });

    testWidgets('pérdida de superficie pasa a recovering y se recupera', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);

      await preview.start();
      await tester.pump();
      expect(preview.state, PreviewState.active);

      service.emitEvent(<String, Object?>{
        'type': StreamContract.eventPreviewSurfaceLost,
      });
      await tester.pump();
      expect(preview.state, PreviewState.recovering);

      service.emitEvent(<String, Object?>{
        'type': StreamContract.eventPreviewStarted,
        'textureId': 1,
        'width': 1280,
        'height': 720,
        'fps': 30,
      });
      await tester.pump();
      expect(preview.state, PreviewState.active);
    });
  });
}
