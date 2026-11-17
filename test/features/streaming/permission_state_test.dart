import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';

void main() {
  group('PermissionKind', () {
    test('los nombres del canal son estables', () {
      expect(PermissionKind.camera.wireName, 'camera');
      expect(PermissionKind.microphone.wireName, 'microphone');
      expect(PermissionKind.notifications.wireName, 'notifications');
      expect(PermissionKind.fromWireName('camera'), PermissionKind.camera);
      expect(
        PermissionKind.fromWireName('microphone'),
        PermissionKind.microphone,
      );
      expect(
        PermissionKind.fromWireName('notifications'),
        PermissionKind.notifications,
      );
      expect(PermissionKind.fromWireName('otro'), isNull);
      expect(PermissionKind.fromWireName(null), isNull);
    });
  });

  group('PermissionStatus', () {
    test('los nombres del canal son estables', () {
      expect(PermissionStatus.notRequested.wireName, 'notRequested');
      expect(PermissionStatus.granted.wireName, 'granted');
      expect(PermissionStatus.denied.wireName, 'denied');
      expect(
        PermissionStatus.permanentlyDenied.wireName,
        'permanentlyDenied',
      );
    });

    test('un valor desconocido o ausente es nunca solicitado', () {
      expect(
        PermissionStatus.fromWireName('otro'),
        PermissionStatus.notRequested,
      );
      expect(
        PermissionStatus.fromWireName(null),
        PermissionStatus.notRequested,
      );
      expect(
        PermissionStatus.fromWireName('granted'),
        PermissionStatus.granted,
      );
    });
  });

  group('PermissionStates', () {
    test('toMap y fromMap son inversos', () {
      const PermissionStates states = PermissionStates(
        camera: PermissionStatus.granted,
        microphone: PermissionStatus.denied,
        notifications: PermissionStatus.permanentlyDenied,
      );
      expect(PermissionStates.fromMap(states.toMap()).toMap(), states.toMap());
    });

    test('las claves ausentes son nunca solicitadas', () {
      final PermissionStates states = PermissionStates.fromMap(
        <Object?, Object?>{},
      );
      expect(states.camera, PermissionStatus.notRequested);
      expect(states.microphone, PermissionStatus.notRequested);
      expect(states.notifications, PermissionStatus.notRequested);
    });

    test('la vista previa necesita solo la cámara', () {
      const PermissionStates granted = PermissionStates(
        camera: PermissionStatus.granted,
        microphone: PermissionStatus.notRequested,
        notifications: PermissionStatus.notRequested,
      );
      expect(granted.readyForPreview, isTrue);
      const PermissionStates denied = PermissionStates(
        camera: PermissionStatus.denied,
        microphone: PermissionStatus.granted,
        notifications: PermissionStatus.granted,
      );
      expect(denied.readyForPreview, isFalse);
    });

    test('la grabación con audio necesita cámara y micrófono', () {
      const PermissionStates onlyCamera = PermissionStates(
        camera: PermissionStatus.granted,
        microphone: PermissionStatus.notRequested,
        notifications: PermissionStatus.notRequested,
      );
      expect(onlyCamera.readyForRecording(audioEnabled: true), isFalse);
      expect(onlyCamera.readyForRecording(audioEnabled: false), isTrue);
      const PermissionStates both = PermissionStates(
        camera: PermissionStatus.granted,
        microphone: PermissionStatus.granted,
        notifications: PermissionStatus.notRequested,
      );
      expect(both.readyForRecording(audioEnabled: true), isTrue);
    });

    test('statusOf y copyWithStatus cambian un solo permiso', () {
      const PermissionStates states = PermissionStates.allNotRequested();
      expect(states.statusOf(PermissionKind.camera), PermissionStatus.notRequested);
      final PermissionStates updated = states.copyWithStatus(
        PermissionKind.microphone,
        PermissionStatus.granted,
      );
      expect(updated.microphone, PermissionStatus.granted);
      expect(updated.camera, PermissionStatus.notRequested);
      expect(updated.notifications, PermissionStatus.notRequested);
    });
  });
}
