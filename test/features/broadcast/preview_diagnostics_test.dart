import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/features/broadcast/presentation/broadcast_screen.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';

import '../../support/fake_streaming.dart';
import '../../support/test_services.dart';

/// Pruebas del bloque de diagnóstico de vista previa (Fase A):
/// se muestra tras un intento, con el código, el mensaje técnico y la
/// configuración, y el botón «Copiar diagnóstico» lo deja en el
/// portapapeles con confirmación visible.
void main() {
  group('BroadcastScreen: diagnóstico de vista previa', () {
    // El canal de plataforma del sistema usa JSONMethodCodec (no el
    // StandardMethodCodec por defecto): con el codec equivocado el
    // mock rompe todos los mensajes del framework.
    const MethodChannel platformChannel = MethodChannel(
      'flutter/platform',
      JSONMethodCodec(),
    );
    String? copiedText;

    Future<void> pumpStudio(
      WidgetTester tester,
      FakeStreamingService service,
    ) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 3.0;
      addTearDown(tester.view.reset);
      final AppServices services = createTestServices(
        streamingService: service,
      );
      await tester.pumpWidget(
        MaterialApp(
          home: AppScope(services: services, child: const BroadcastScreen()),
        ),
      );
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

    setUp(() {
      copiedText = null;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(platformChannel, (
            MethodCall call,
          ) async {
            switch (call.method) {
              case 'Clipboard.setData':
                final Map<Object?, Object?> args =
                    call.arguments as Map<Object?, Object?>;
                copiedText = args['text'] as String?;
                return null;
              case 'Clipboard.hasStrings':
                // El motor real devuelve {'value': bool}.
                return <String, Object?>{'value': false};
              case 'LiveText.isLiveTextInputAvailable':
                return false;
              default:
                return null;
            }
          });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(platformChannel, null);
    });

    testWidgets('en error muestra el bloque con código y mensaje', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
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
      await pumpStudio(tester, service);

      await tester.ensureVisible(find.text('VISTA PREVIA'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('VISTA PREVIA'));
      await tester.pumpAndSettle();

      expect(find.text('DIAGNÓSTICO DE VISTA PREVIA'), findsOneWidget);
      // El código aparece también en el texto del error: se busca la
      // línea exacta del bloque para no contar dos widgets.
      expect(
        find.textContaining('Código de error: camera_timeout'),
        findsOneWidget,
      );
      expect(
        find.textContaining('Tiempo de espera agotado'),
        findsOneWidget,
      );
      expect(find.textContaining('Etapa alcanzada: openDevice'), findsOneWidget);
      expect(find.textContaining('Cámaras detectadas: 5'), findsOneWidget);
      expect(find.textContaining('Cámara seleccionada: 0'), findsOneWidget);
      expect(find.text('COPIAR DIAGNÓSTICO'), findsOneWidget);
      expect(find.text('REINTENTAR'), findsOneWidget);
    });

    testWidgets('copiar diagnóstico deja el texto en el portapapeles', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      service.startPreviewError = StreamContract.cameraDisabledBySystem;
      service.startPreviewErrorMessage =
          'No se detectó ninguna cámara. Es posible que la cámara esté '
          'bloqueada a nivel del sistema.';
      service.startPreviewErrorDetails = <String, Object?>{
        'stage': 'enumerate',
        'cameraCount': 0,
      };
      await pumpStudio(tester, service);

      await tester.ensureVisible(find.text('VISTA PREVIA'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('VISTA PREVIA'));
      await tester.pumpAndSettle();

      await tester.ensureVisible(find.text('COPIAR DIAGNÓSTICO'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('COPIAR DIAGNÓSTICO'));
      await tester.pumpAndSettle();

      expect(copiedText, isNotNull);
      expect(copiedText, contains('camera_disabled_by_system'));
      expect(copiedText, contains('Cámaras detectadas: 0'));
      expect(copiedText, contains('bloqueada a nivel del sistema'));
      expect(
        find.text('Diagnóstico copiado al portapapeles.'),
        findsOneWidget,
      );
    });

    testWidgets('con la vista previa activa muestra la configuración', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      await pumpStudio(tester, service);

      await tester.ensureVisible(find.text('VISTA PREVIA'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('VISTA PREVIA'));
      await tester.pumpAndSettle();

      expect(find.byType(Texture), findsOneWidget);
      expect(find.text('DIAGNÓSTICO DE VISTA PREVIA'), findsOneWidget);
      expect(find.textContaining('Tamaño: 1280x720'), findsOneWidget);
      expect(find.textContaining('FPS: 30'), findsOneWidget);
      expect(find.text('COPIAR DIAGNÓSTICO'), findsOneWidget);
    });

    testWidgets('al detener desaparece el bloque de diagnóstico', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      await pumpStudio(tester, service);

      await tester.ensureVisible(find.text('VISTA PREVIA'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('VISTA PREVIA'));
      await tester.pumpAndSettle();
      expect(find.text('DIAGNÓSTICO DE VISTA PREVIA'), findsOneWidget);

      await tester.ensureVisible(find.text('DETENER VISTA PREVIA'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('DETENER VISTA PREVIA'));
      await tester.pumpAndSettle();

      expect(find.text('DIAGNÓSTICO DE VISTA PREVIA'), findsNothing);
      expect(find.text('COPIAR DIAGNÓSTICO'), findsNothing);
    });
  });
}
