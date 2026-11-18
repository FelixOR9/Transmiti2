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

FakeStreamingService grantedService() {
  final FakeStreamingService service = FakeStreamingService();
  service.permissionStates = const PermissionStates(
    camera: PermissionStatus.granted,
    microphone: PermissionStatus.notRequested,
    notifications: PermissionStatus.notRequested,
  );
  return service;
}

Future<void> startPreview(WidgetTester tester) async {
  await tester.ensureVisible(find.text('VISTA PREVIA'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('VISTA PREVIA'));
  await tester.pumpAndSettle();
}

Future<void> tapRecord(WidgetTester tester) async {
  await tester.ensureVisible(find.text('GRABAR'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('GRABAR'));
  await tester.pumpAndSettle();
}

void main() {
  group('BroadcastScreen: grabación local (Fase B, 3.1.2)', () {
    testWidgets('al entrar el botón es GRABAR, deshabilitado sin vista previa', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      await pumpStudio(tester, service);

      expect(find.text('GRABAR'), findsOneWidget);
      expect(find.text('TRANSMITIR'), findsOneWidget);
      expect(
        find.text('Activa la vista previa para grabar.'),
        findsOneWidget,
      );
      final FilledButton button = tester.widget<FilledButton>(
        find.widgetWithText(FilledButton, 'GRABAR'),
      );
      expect(button.onPressed, isNull);
      expect(service.startRecordingCalls, 0);
    });

    testWidgets('con vista previa GRABAR inicia y muestra GRABANDO', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      await pumpStudio(tester, service);
      await startPreview(tester);
      expect(find.text('DETENER VISTA PREVIA'), findsOneWidget);

      await tapRecord(tester);

      expect(service.startRecordingCalls, 1);
      expect(find.text('DETENER GRABACIÓN'), findsOneWidget);
      expect(find.text('GRABANDO'), findsOneWidget);
      expect(find.text('00:00'), findsOneWidget);
      expect(find.textContaining('Segmento 1'), findsOneWidget);
      expect(find.text('Grabación iniciada.'), findsOneWidget);
      // La vista previa no se puede detener mientras se graba: tocar
      // su botón no hace nada.
      await tester.ensureVisible(find.text('GRABANDO…'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('GRABANDO…'));
      await tester.pumpAndSettle();
      expect(service.stopPreviewCalls, 0);
    });

    testWidgets('DETENER GRABACIÓN confirma con segmentos y duración', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      await pumpStudio(tester, service);
      await startPreview(tester);
      await tapRecord(tester);
      expect(find.text('DETENER GRABACIÓN'), findsOneWidget);
      // El segmento verificado llega por evento durante la grabación
      // (en el flujo real, cada segmento cerrado emite
      // `recording_segment` antes de la parada).
      service.emitEvent(<String, Object?>{
        'type': StreamContract.eventRecordingSegment,
        'recordingId': 'rec_20261118_120000_ab12',
        'segmentIndex': 0,
        'fileName': 'rec_20261118_120000_ab12_seg000.mp4',
        'filePath': '/docs/recordings/rec_20261118_120000_ab12_seg000.mp4',
        'status': 'ready',
        'durationMs': 12000,
        'sizeBytes': 3750000,
        'closedCleanly': true,
      });
      await tester.pumpAndSettle();

      // El SnackBar de inicio («Grabación iniciada.») sigue visible:
      // el de parada se pondría en cola detrás. Se deja que se
      // descarte (4 s) antes de detener.
      await tester.pump(const Duration(seconds: 5));
      await tester.ensureVisible(find.text('DETENER GRABACIÓN'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('DETENER GRABACIÓN'));
      await tester.pumpAndSettle();

      expect(service.stopRecordingCalls, 1);
      expect(find.text('GRABAR'), findsOneWidget);
      expect(find.text('GRABANDO'), findsNothing);
      expect(
        find.text('Grabación detenida: 1 segmento(s), duración total 00:12.'),
        findsOneWidget,
      );
    });

    testWidgets('el error de inicio se muestra en lenguaje normal', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      service.startRecordingError = StreamContract.encoderError;
      service.startRecordingErrorMessage = 'Sin codificador H.264.';
      await pumpStudio(tester, service);
      await startPreview(tester);

      await tapRecord(tester);

      expect(service.startRecordingCalls, 1);
      expect(find.text('GRABAR'), findsOneWidget);
      expect(
        find.textContaining('El codificador no aceptó la configuración.'),
        findsWidgets,
      );
      expect(find.textContaining(StreamContract.encoderError), findsWidgets);
    });

    testWidgets('la tarjeta de diagnóstico de grabación se puede copiar', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = grantedService();
      await pumpStudio(tester, service);
      await startPreview(tester);
      await tapRecord(tester);

      service.emitEvent(<String, Object?>{
        'type': StreamContract.eventRecordingStarted,
        'recordingId': 'rec_20261118_120000_ab12',
        'width': 1280,
        'height': 720,
        'fps': 30,
        'bitrateBps': 2500000,
        'encoderName': 'c2.qti.avc.encoder',
        'rotationDegrees': 90,
        'cbrSupported': true,
      });
      await tester.pumpAndSettle();

      expect(find.text('DIAGNÓSTICO DE GRABACIÓN'), findsOneWidget);
      expect(find.textContaining('c2.qti.avc.encoder'), findsOneWidget);
      expect(find.text('COPIAR DIAGNÓSTICO'), findsWidgets);
    });
  });
}
