import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';

/// Flujo de permisos en la UI (subetapa 3.1.0).
///
/// Orquesta la consulta y la solicitud de permisos sin guardar estados en
/// SQLite ni depender de la memoria entre reanudaciones: cada vez que la
/// app vuelve a primer plano se vuelve a consultar el lado nativo
/// ([refresh]), que calcula los estados con el permiso concedido, el
/// historial persistente de solicitudes y
/// `shouldShowRequestPermissionRationale()`.
///
/// La UI (diálogos de motivo y de ajustes) vive en la pantalla; este
/// controlador solo guarda los estados y decide si una solicitud puede
/// lanzarse: nunca se pide un permiso ya concedido ni uno denegado de
/// forma permanente (el diálogo del sistema ya no haría nada en ese
/// caso).
class PermissionController extends ChangeNotifier
    with WidgetsBindingObserver {
  PermissionController({required StreamingService service})
    : _service = service;

  final StreamingService _service;

  PermissionStates _states = const PermissionStates.allNotRequested();
  PermissionStates get states => _states;

  bool _isRequesting = false;
  bool get isRequesting => _isRequesting;

  /// Observadores registrados con [attach]. Con contador: varias
  /// pantallas pueden compartir este controlador sin que el `detach` de
  /// una deje a la otra sin re-consulta al regresar.
  int _attachedObservers = 0;

  /// Empieza a re-consultar los permisos cada vez que la app vuelve a
  /// primer plano. Llamar en `initState` o `didChangeDependencies` y
  /// [detach] en `dispose`.
  void attach() {
    _attachedObservers++;
    if (_attachedObservers == 1) {
      WidgetsBinding.instance.addObserver(this);
    }
  }

  void detach() {
    if (_attachedObservers == 0) {
      return;
    }
    _attachedObservers--;
    if (_attachedObservers == 0) {
      WidgetsBinding.instance.removeObserver(this);
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(refresh());
    }
  }

  /// Vuelve a consultar los estados al lado nativo, sin mostrar
  /// diálogos. Si la consulta falla se conservan los estados anteriores
  /// y no se muestra ningún error: es solo lectura.
  Future<void> refresh() async {
    try {
      final PermissionStates fresh = await _service.checkPermissions();
      _states = fresh;
      notifyListeners();
    } on StreamingException {
      // Sin cambios: se conservan los estados anteriores.
    }
  }

  /// Pide [kind] con el diálogo del sistema y devuelve el estado
  /// resultante. Si ya está concedido o denegado permanentemente no
  /// lanza nada y devuelve el estado actual.
  Future<PermissionStatus> request(PermissionKind kind) async {
    final PermissionStatus current = _states.statusOf(kind);
    if (current == PermissionStatus.granted ||
        current == PermissionStatus.permanentlyDenied) {
      return current;
    }
    _isRequesting = true;
    notifyListeners();
    try {
      final PermissionStates updated = await _service.requestPermissions(
        permissions: <PermissionKind>[kind],
      );
      _states = updated;
      return updated.statusOf(kind);
    } finally {
      _isRequesting = false;
      notifyListeners();
    }
  }

  /// Abre los ajustes de la aplicación (denegación permanente).
  Future<void> openAppSettings() => _service.openAppSettings();
}
