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

    testWidgets(
      'camera_unavailable genérico conserva mensaje, clase y etapa',
      (WidgetTester tester) async {
        // Revisión 2: un fallo previo a la sesión (crear el productor)
        // ya no se muestra como «startPreview» sin datos: la tarjeta
        // enseña la etapa real, el mensaje real y la clase.
        await pumpController(tester);
        service.startPreviewError = StreamContract.cameraUnavailable;
        service.startPreviewErrorMessage =
            'Servicio de cámara no disponible.';
        service.startPreviewErrorDetails = <String, Object?>{
          'stage': 'createProducer',
          'cameraCount': null,
          'selectedCameraId': null,
          'selectedSize': null,
          'selectedFps': null,
          'exceptionClass': 'RuntimeException',
        };

        await preview.start();
        await tester.pump();

        expect(preview.state, PreviewState.unavailable);
        expect(preview.errorCode, StreamContract.cameraUnavailable);
        expect(
          preview.errorMessage,
          'Servicio de cámara no disponible.',
        );
        expect(preview.diagnostics?.stage, 'createProducer');
        expect(preview.diagnostics?.lastErrorStage, 'createProducer');
        expect(preview.diagnostics?.exceptionClass, 'RuntimeException');
        final String? text = preview.diagnosticText;
        expect(text, contains('Etapa alcanzada: createProducer'));
        expect(text, contains('Clase de excepción: RuntimeException'));
        expect(text, contains('Servicio de cámara no disponible.'));
        expect(text, contains('Última etapa con error: createProducer'));
        expect(text, contains('Cámaras detectadas: —'));
      },
    );

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

    testWidgets('un error del motor con mensaje en el evento lo conserva', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);

      await preview.start();
      await tester.pump();
      expect(preview.state, PreviewState.active);

      service.emitEvent(<String, Object?>{
        'type': StreamContract.eventPreviewError,
        'code': StreamContract.cameraTimeout,
        'message': 'Tiempo de espera agotado en la etapa openDevice (5 s).',
        'stage': 'openDevice',
        'cameraCount': 5,
        'selectedCameraId': '0',
        'selectedSize': '1280x720',
        'selectedFps': 30,
        'exceptionClass': 'TimeoutCancellationException',
      });
      await tester.pump();

      expect(preview.state, PreviewState.error);
      expect(preview.errorCode, StreamContract.cameraTimeout);
      expect(
        preview.errorMessage,
        'Tiempo de espera agotado en la etapa openDevice (5 s).',
      );
      expect(preview.diagnostics?.stage, 'openDevice');
      expect(preview.diagnostics?.lastErrorStage, 'openDevice');
      expect(preview.diagnostics?.cameraCount, 5);
      expect(preview.diagnostics?.selectedCameraId, '0');
      expect(
        preview.diagnosticText,
        contains('Tiempo de espera agotado en la etapa openDevice'),
      );
    });

    testWidgets('timeout queda en error con el código y el mensaje', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.startPreviewError = StreamContract.cameraTimeout;
      service.startPreviewErrorMessage =
          'Tiempo de espera agotado en la etapa openDevice (5 s).';
      service.startPreviewErrorDetails = <String, Object?>{
        'stage': 'openDevice',
        'cameraCount': 5,
        'selectedCameraId': '0',
        'selectedSize': '1280x720',
        'selectedFps': 30,
        'exceptionClass': 'TimeoutCancellationException',
      };

      await preview.start();
      await tester.pump();

      expect(preview.state, PreviewState.error);
      expect(preview.errorCode, StreamContract.cameraTimeout);
      expect(
        preview.errorMessage,
        'Tiempo de espera agotado en la etapa openDevice (5 s).',
      );
      expect(preview.diagnostics?.stage, 'openDevice');
      expect(preview.diagnostics?.cameraCount, 5);
      expect(preview.diagnostics?.selectedCameraId, '0');
      expect(preview.diagnostics?.selectedSize, '1280x720');
      expect(preview.diagnostics?.selectedFps, 30);
    });

    testWidgets('error de configuración queda en error con el código', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.startPreviewError = StreamContract.cameraConfigError;
      service.startPreviewErrorMessage =
          'Configuración de cámara no válida en la etapa startRepeating: '
          'rango FPS no admitido.';
      service.startPreviewErrorDetails = <String, Object?>{
        'stage': 'startRepeating',
        'cameraCount': 5,
        'selectedCameraId': '0',
        'exceptionClass': 'IllegalArgumentException',
      };

      await preview.start();
      await tester.pump();

      expect(preview.state, PreviewState.error);
      expect(preview.errorCode, StreamContract.cameraConfigError);
      expect(
        preview.errorMessage,
        contains('Configuración de cámara no válida'),
      );
      expect(preview.diagnostics?.lastErrorStage, 'startRepeating');
    });

    testWidgets('error de estado queda en error con el código', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.startPreviewError = StreamContract.cameraStateError;
      service.startPreviewErrorMessage =
          'La cámara o la sesión no está en un estado válido en la etapa '
          'createCaptureSession.';
      service.startPreviewErrorDetails = <String, Object?>{
        'stage': 'createCaptureSession',
        'exceptionClass': 'IllegalStateException',
      };

      await preview.start();
      await tester.pump();

      expect(preview.state, PreviewState.error);
      expect(preview.errorCode, StreamContract.cameraStateError);
      expect(preview.errorMessage, contains('no está en un estado válido'));
    });

    testWidgets(
      'cámara bloqueada por el sistema queda en error con la guía',
      (WidgetTester tester) async {
        await pumpController(tester);
        service.startPreviewError = StreamContract.cameraDisabledBySystem;
        service.startPreviewErrorMessage =
            'No se detectó ninguna cámara. Es posible que la cámara esté '
            'bloqueada a nivel del sistema (interruptor «Acceso a la '
            'cámara» o «Sensors Off»); comprueba que la app de cámara del '
            'sistema funciona.';
        service.startPreviewErrorDetails = <String, Object?>{
          'stage': 'enumerate',
          'cameraCount': 0,
          'exceptionClass': null,
        };

        await preview.start();
        await tester.pump();

        expect(preview.state, PreviewState.error);
        expect(preview.errorCode, StreamContract.cameraDisabledBySystem);
        expect(preview.errorMessage, contains('posible'));
        expect(preview.errorMessage, contains('sistema'));
        expect(preview.diagnostics?.cameraCount, 0);
        expect(preview.diagnostics?.stage, 'enumerate');
      },
    );

    testWidgets('el diagnóstico se construye con los campos del error', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.startPreviewError = StreamContract.cameraTimeout;
      service.startPreviewErrorMessage =
          'Tiempo de espera agotado en la etapa openDevice (5 s).';
      service.startPreviewErrorDetails = <String, Object?>{
        'stage': 'openDevice',
        'cameraCount': 5,
        'selectedCameraId': '0',
        'selectedSize': '1280x720',
        'selectedFps': 30,
        'exceptionClass': 'TimeoutCancellationException',
      };

      await preview.start();
      await tester.pump();

      final String? text = preview.diagnosticText;
      expect(text, isNotNull);
      expect(text, contains('Código de error: camera_timeout'));
      expect(text, contains('Tiempo de espera agotado'));
      expect(text, contains('Etapa alcanzada: openDevice'));
      expect(text, contains('Cámaras detectadas: 5'));
      expect(text, contains('Cámara seleccionada: 0'));
      expect(text, contains('Tamaño: 1280x720'));
      expect(text, contains('FPS: 30'));
      expect(text, contains('Última etapa con error: openDevice'));
    });

    testWidgets('sin detalles el diagnóstico muestra «—», no inventa', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.startPreviewError = StreamContract.cameraDisconnected;

      await preview.start();
      await tester.pump();

      expect(preview.state, PreviewState.error);
      expect(preview.errorMessage, isNull);
      final String? text = preview.diagnosticText;
      expect(text, contains('Código de error: camera_disconnected'));
      expect(text, contains('Cámaras detectadas: —'));
      expect(text, contains('Mensaje técnico: —'));
    });

    testWidgets(
      'con la vista previa activa el diagnóstico muestra la configuración',
      (WidgetTester tester) async {
        await pumpController(tester);

        await preview.start();
        await tester.pump();

        expect(preview.state, PreviewState.active);
        final String? text = preview.diagnosticText;
        expect(text, isNotNull);
        expect(text, contains('Estado: active'));
        expect(text, contains('Tamaño: 1280x720'));
        expect(text, contains('FPS: 30'));
        expect(text, contains('Última etapa con error: —'));
        expect(text, isNot(contains('Código de error')));
      },
    );

    testWidgets('detener limpia el mensaje y el diagnóstico', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.startPreviewError = StreamContract.cameraTimeout;
      service.startPreviewErrorMessage = 'Tiempo de espera agotado.';

      await preview.start();
      await tester.pump();
      expect(preview.state, PreviewState.error);
      expect(preview.diagnosticText, isNotNull);

      await preview.stop();
      await tester.pump();

      expect(preview.state, PreviewState.closed);
      expect(preview.errorCode, isNull);
      expect(preview.errorMessage, isNull);
      expect(preview.diagnostics, isNull);
      expect(preview.diagnosticText, isNull);
    });

    testWidgets('un nuevo intento limpia el diagnóstico anterior', (
      WidgetTester tester,
    ) async {
      await pumpController(tester);
      service.startPreviewError = StreamContract.cameraTimeout;
      service.startPreviewErrorMessage = 'Tiempo de espera agotado.';

      await preview.start();
      await tester.pump();
      expect(preview.state, PreviewState.error);

      service.startPreviewError = null;
      service.startPreviewErrorMessage = null;
      await preview.start();
      await tester.pump();

      expect(preview.state, PreviewState.active);
      expect(preview.errorCode, isNull);
      expect(preview.errorMessage, isNull);
      expect(preview.diagnostics?.lastErrorStage, isNull);
    });
  });
}
