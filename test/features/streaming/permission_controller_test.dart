import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/streaming/application/permission_controller.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';

import '../../support/fake_streaming.dart';

/// Sonda mínima que adhiere el controlador al ciclo de vida de la app.
class _Probe extends StatefulWidget {
  const _Probe({required this.controller});

  final PermissionController controller;

  @override
  State<_Probe> createState() => _ProbeState();
}

class _ProbeState extends State<_Probe> {
  @override
  void initState() {
    super.initState();
    widget.controller.attach();
  }

  @override
  void dispose() {
    widget.controller.detach();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => const SizedBox.shrink();
}

void main() {
  group('PermissionController', () {
    test('empieza sin ningún permiso solicitado', () {
      final PermissionController controller = PermissionController(
        service: FakeStreamingService(),
      );
      addTearDown(controller.dispose);
      expect(controller.states.camera, PermissionStatus.notRequested);
      expect(controller.states.microphone, PermissionStatus.notRequested);
      expect(controller.states.notifications, PermissionStatus.notRequested);
      expect(controller.isRequesting, isFalse);
    });

    test('refresh actualiza los estados desde el servicio', () async {
      final FakeStreamingService service = FakeStreamingService();
      service.permissionStates = const PermissionStates(
        camera: PermissionStatus.granted,
        microphone: PermissionStatus.denied,
        notifications: PermissionStatus.notRequested,
      );
      final PermissionController controller = PermissionController(
        service: service,
      );
      addTearDown(controller.dispose);
      await controller.refresh();
      expect(controller.states.camera, PermissionStatus.granted);
      expect(controller.states.microphone, PermissionStatus.denied);
      expect(controller.states.notifications, PermissionStatus.notRequested);
    });

    test('conceder la cámara actualiza el estado', () async {
      final FakeStreamingService service = FakeStreamingService();
      service.requestOutcomes[PermissionKind.camera] =
          PermissionStatus.granted;
      final PermissionController controller = PermissionController(
        service: service,
      );
      addTearDown(controller.dispose);
      final PermissionStatus result =
          await controller.request(PermissionKind.camera);
      expect(result, PermissionStatus.granted);
      expect(controller.states.camera, PermissionStatus.granted);
      expect(service.requestPermissionsCalls, 1);
      expect(
        service.lastRequestedPermissions,
        <PermissionKind>[PermissionKind.camera],
      );
    });

    test('denegar la cámara permite reintentarlo', () async {
      final FakeStreamingService service = FakeStreamingService();
      service.requestOutcomes[PermissionKind.camera] =
          PermissionStatus.denied;
      final PermissionController controller = PermissionController(
        service: service,
      );
      addTearDown(controller.dispose);
      expect(
        await controller.request(PermissionKind.camera),
        PermissionStatus.denied,
      );
      expect(controller.states.camera, PermissionStatus.denied);
      expect(
        await controller.request(PermissionKind.camera),
        PermissionStatus.denied,
      );
      expect(service.requestPermissionsCalls, 2);
    });

    test('denegar permanentemente no vuelve a lanzar el diálogo', () async {
      final FakeStreamingService service = FakeStreamingService();
      service.requestOutcomes[PermissionKind.camera] =
          PermissionStatus.permanentlyDenied;
      final PermissionController controller = PermissionController(
        service: service,
      );
      addTearDown(controller.dispose);
      expect(
        await controller.request(PermissionKind.camera),
        PermissionStatus.permanentlyDenied,
      );
      expect(service.requestPermissionsCalls, 1);
      expect(
        await controller.request(PermissionKind.camera),
        PermissionStatus.permanentlyDenied,
      );
      expect(service.requestPermissionsCalls, 1);
    });

    test('un permiso concedido no se vuelve a pedir', () async {
      final FakeStreamingService service = FakeStreamingService();
      service.permissionStates = const PermissionStates(
        camera: PermissionStatus.granted,
        microphone: PermissionStatus.notRequested,
        notifications: PermissionStatus.notRequested,
      );
      final PermissionController controller = PermissionController(
        service: service,
      );
      addTearDown(controller.dispose);
      await controller.refresh();
      expect(
        await controller.request(PermissionKind.camera),
        PermissionStatus.granted,
      );
      expect(service.requestPermissionsCalls, 0);
    });

    test('abrir los ajustes delega en el servicio', () async {
      final FakeStreamingService service = FakeStreamingService();
      final PermissionController controller = PermissionController(
        service: service,
      );
      addTearDown(controller.dispose);
      await controller.openAppSettings();
      expect(service.openAppSettingsCalls, 1);
    });

    testWidgets('al regresar a la app se vuelven a consultar los permisos', (
      WidgetTester tester,
    ) async {
      final FakeStreamingService service = FakeStreamingService();
      final PermissionController controller = PermissionController(
        service: service,
      );
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        MaterialApp(home: _Probe(controller: controller)),
      );
      await tester.pumpAndSettle();
      expect(controller.states.camera, PermissionStatus.notRequested);

      service.permissionStates = const PermissionStates(
        camera: PermissionStatus.granted,
        microphone: PermissionStatus.notRequested,
        notifications: PermissionStatus.notRequested,
      );
      tester.binding.handleAppLifecycleStateChanged(
        AppLifecycleState.paused,
      );
      await tester.pumpAndSettle();
      expect(controller.states.camera, PermissionStatus.notRequested);

      tester.binding.handleAppLifecycleStateChanged(
        AppLifecycleState.resumed,
      );
      await tester.pumpAndSettle();
      expect(controller.states.camera, PermissionStatus.granted);
    });
  });
}
