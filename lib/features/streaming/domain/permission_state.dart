/// Estados de permiso de cámara, micrófono y notificaciones (Fase 3.1.0).
///
/// Los estados viven solo en la UI: no se guardan en SQLite. El lado
/// nativo los calcula en cada consulta a partir del permiso concedido, del
/// historial persistente de solicitudes y de
/// `shouldShowRequestPermissionRationale()`; por eso se vuelven a
/// consultar al regresar a la app y nunca se cachean en memoria.
enum PermissionKind {
  camera,
  microphone,
  notifications;

  /// Nombre que viaja por el MethodChannel (`camera`, `microphone`,
  /// `notifications`).
  String get wireName => name;

  static PermissionKind? fromWireName(String? value) {
    for (final PermissionKind kind in PermissionKind.values) {
      if (kind.wireName == value) {
        return kind;
      }
    }
    return null;
  }
}

/// Estado de un permiso.
///
/// `shouldShowRequestPermissionRationale()` por sí solo no distingue una
/// primera solicitud de una denegación permanente: en ambos casos
/// devuelve `false` antes de pedir. El lado nativo combina ese valor con
/// un historial persistente de solicitudes (nunca en SQLite) para
/// distinguir `notRequested` de `permanentlyDenied`.
enum PermissionStatus {
  notRequested,
  granted,
  denied,
  permanentlyDenied;

  /// Nombre que viaja por el MethodChannel.
  String get wireName => name;

  /// Un valor desconocido o ausente se interpreta como nunca solicitado:
  /// nunca se finge un permiso concedido.
  static PermissionStatus fromWireName(String? value) {
    for (final PermissionStatus status in PermissionStatus.values) {
      if (status.wireName == value) {
        return status;
      }
    }
    return PermissionStatus.notRequested;
  }
}

/// Estados de los tres permisos, con las condiciones derivadas que usa la
/// UI para decidir qué puede hacerse.
class PermissionStates {
  const PermissionStates({
    required this.camera,
    required this.microphone,
    required this.notifications,
  });

  const PermissionStates.allNotRequested()
    : camera = PermissionStatus.notRequested,
      microphone = PermissionStatus.notRequested,
      notifications = PermissionStatus.notRequested;

  final PermissionStatus camera;
  final PermissionStatus microphone;
  final PermissionStatus notifications;

  PermissionStatus statusOf(PermissionKind kind) {
    switch (kind) {
      case PermissionKind.camera:
        return camera;
      case PermissionKind.microphone:
        return microphone;
      case PermissionKind.notifications:
        return notifications;
    }
  }

  PermissionStates copyWithStatus(PermissionKind kind, PermissionStatus status) {
    switch (kind) {
      case PermissionKind.camera:
        return PermissionStates(
          camera: status,
          microphone: microphone,
          notifications: notifications,
        );
      case PermissionKind.microphone:
        return PermissionStates(
          camera: camera,
          microphone: status,
          notifications: notifications,
        );
      case PermissionKind.notifications:
        return PermissionStates(
          camera: camera,
          microphone: microphone,
          notifications: status,
        );
    }
  }

  /// La vista previa necesita solo la cámara concedida.
  bool get readyForPreview => camera == PermissionStatus.granted;

  /// La grabación necesita la cámara y, si el audio está activado, el
  /// micrófono. La grabación en sí llega en la subetapa 3.1.2.
  bool readyForRecording({required bool audioEnabled}) =>
      camera == PermissionStatus.granted &&
      (!audioEnabled || microphone == PermissionStatus.granted);

  factory PermissionStates.fromMap(Map<Object?, Object?> map) {
    return PermissionStates(
      camera: PermissionStatus.fromWireName(map['camera'] as String?),
      microphone: PermissionStatus.fromWireName(map['microphone'] as String?),
      notifications:
          PermissionStatus.fromWireName(map['notifications'] as String?),
    );
  }

  Map<String, String> toMap() {
    return <String, String>{
      'camera': camera.wireName,
      'microphone': microphone.wireName,
      'notifications': notifications.wireName,
    };
  }
}
