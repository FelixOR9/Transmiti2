import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:transmiti2/features/streaming/application/permission_controller.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';

/// Estado de la vista previa en la UI.
enum PreviewState {
  /// Cámara cerrada (estado inicial y tras detener).
  closed,

  /// Esperando a que Camera2 abra la cámara y configure la sesión.
  preparing,

  /// Imagen real de la cámara en pantalla.
  active,

  /// La superficie de Flutter se perdió temporalmente; el motor la
  /// recupera solo y vuelve a `active` sin tocar nada.
  recovering,

  /// La cámara no existe o no tiene configuración compatible.
  unavailable,

  /// El permiso de cámara no está concedido (la pantalla debe pedirlo).
  permissionDenied,

  /// Error al iniciar la vista previa (cámara en uso, desconexión...).
  error,
}

/// Vista previa real de cámara (subetapa 3.1.1).
///
/// Orquesta `startPreview` / `stopPreview` del servicio sin mostrar
/// diálogos: la pantalla es la que pide el permiso (motivo y ajustes) y
/// este controlador solo comprueba que esté concedido antes de abrir la
/// cámara y traduce los errores del motor a estados de UI.
///
/// Ciclo de vida (obligatorio):
/// - Al ir a segundo plano se detiene la vista previa (no se mantiene
///   la cámara abierta innecesariamente) y al volver se restaura sola
///   si estaba activa.
/// - Al cambiar la orientación (apaisado <-> vertical) se reinicia para
///   recalcular la rotación con la que se muestra la imagen.
/// - Al salir de la pantalla la cámara se cierra (`stop()` en `dispose`).
///
/// Los eventos del motor (`preview_error`, `preview_surface_lost`,
/// `preview_started`) llegan por el canal de eventos del servicio; un
/// error cierra el estado a `error` y la pérdida de superficie a
/// `recovering` hasta que el motor la recupera.
class PreviewController extends ChangeNotifier with WidgetsBindingObserver {
  PreviewController({
    required StreamingService service,
    required PermissionController permissions,
  }) : _service = service,
       _permissions = permissions;

  final StreamingService _service;
  final PermissionController _permissions;

  PreviewState _state = PreviewState.closed;
  PreviewState get state => _state;

  PreviewInfo? _info;
  PreviewInfo? get info => _info;

  String? _errorCode;
  String? get errorCode => _errorCode;

  /// Generación del inicio en curso: un reinicio o una parada dejan
  /// obsoletos los resultados de inicios anteriores.
  int _generation = 0;

  /// Si la vista previa estaba activa al ir a segundo plano, para
  /// restaurarla al volver.
  bool _wasActiveBeforeBackground = false;

  /// Orientación (apaisado o no) de la última medición, para reiniciar
  /// solo cuando cambia de verdad.
  bool _lastLandscape = false;

  StreamSubscription<Map<String, Object?>>? _eventsSubscription;

  /// Observadores registrados con [attach]. Con contador: varias
  /// pantallas pueden compartir este controlador sin que el `detach` de
  /// una deje a la otra sin restauración al regresar.
  int _attachedObservers = 0;

  void attach() {
    _attachedObservers++;
    if (_attachedObservers == 1) {
      WidgetsBinding.instance.addObserver(this);
      _lastLandscape = _isLandscapeNow();
      _eventsSubscription = _service.events.listen(_onEvent);
    }
  }

  void detach() {
    if (_attachedObservers == 0) {
      return;
    }
    _attachedObservers--;
    if (_attachedObservers == 0) {
      WidgetsBinding.instance.removeObserver(this);
      unawaited(_eventsSubscription?.cancel());
      _eventsSubscription = null;
    }
  }

  bool _isLandscapeNow() {
    final Size size =
        WidgetsBinding.instance.platformDispatcher.views.first.physicalSize;
    return size.width > size.height;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.hidden ||
        state == AppLifecycleState.detached) {
      if (_state == PreviewState.active ||
          _state == PreviewState.preparing ||
          _state == PreviewState.recovering) {
        _wasActiveBeforeBackground = true;
        unawaited(stop(restoreOnResume: true));
      }
    } else if (state == AppLifecycleState.resumed) {
      if (_wasActiveBeforeBackground) {
        _wasActiveBeforeBackground = false;
        unawaited(start());
      }
    }
  }

  @override
  void didChangeMetrics() {
    final bool landscape = _isLandscapeNow();
    if (landscape == _lastLandscape) {
      return;
    }
    _lastLandscape = landscape;
    if (_state == PreviewState.active) {
      unawaited(_restart());
    }
  }

  /// Abre la cámara y muestra la vista previa. Si el permiso no está
  /// concedido no se llama al motor y el estado es `permissionDenied`
  /// (la pantalla debe pedirlo). Idempotente: llamar dos veces seguidas
  /// no abre la cámara dos veces.
  Future<void> start() async {
    if (_state == PreviewState.active ||
        _state == PreviewState.preparing) {
      return;
    }
    if (_permissions.states.camera != PermissionStatus.granted) {
      _state = PreviewState.permissionDenied;
      _errorCode = null;
      notifyListeners();
      return;
    }
    final int generation = ++_generation;
    _state = PreviewState.preparing;
    _errorCode = null;
    notifyListeners();
    try {
      final PreviewInfo preview = await _service.startPreview();
      if (generation != _generation) {
        // Un reinicio o una parada dejaron obsoleto este inicio.
        return;
      }
      _info = preview;
      _state = PreviewState.active;
      notifyListeners();
    } on StreamingException catch (e) {
      if (generation != _generation) {
        return;
      }
      _info = null;
      _errorCode = e.code;
      switch (e.code) {
        case StreamContract.permissionDenied:
          _state = PreviewState.permissionDenied;
        case StreamContract.cameraUnavailable:
          _state = PreviewState.unavailable;
        default:
          _state = PreviewState.error;
      }
      notifyListeners();
    }
  }

  /// Cierra la vista previa y libera la cámara. Idempotente: parar sin
  /// vista previa no falla ni llama al motor. Con `restoreOnResume`, al
  /// volver a primer plano se restaura sola (para la parada por ir a
  /// segundo plano); la parada manual del usuario no se restaura.
  Future<void> stop({bool restoreOnResume = false}) async {
    _generation++;
    _wasActiveBeforeBackground = restoreOnResume;
    if (_state == PreviewState.closed) {
      return;
    }
    _state = PreviewState.closed;
    _info = null;
    _errorCode = null;
    notifyListeners();
    try {
      await _service.stopPreview();
    } on StreamingException {
      // Mejor esfuerzo: la UI ya está cerrada y la cámara se libera
      // igual en el lado nativo al destruirse la sesión.
    }
  }

  Future<void> _restart() async {
    if (_state != PreviewState.active) {
      return;
    }
    await stop();
    await start();
  }

  void _onEvent(Map<String, Object?> event) {
    final String? type = event['type'] as String?;
    switch (type) {
      case StreamContract.eventPreviewError:
        if (_state == PreviewState.active ||
            _state == PreviewState.preparing ||
            _state == PreviewState.recovering) {
          _info = null;
          _errorCode = event['code'] as String?;
          _state = PreviewState.error;
          notifyListeners();
        }
      case StreamContract.eventPreviewSurfaceLost:
        if (_state == PreviewState.active) {
          _state = PreviewState.recovering;
          notifyListeners();
        }
      case StreamContract.eventPreviewStarted:
        if (_state == PreviewState.recovering) {
          _state = PreviewState.active;
          notifyListeners();
        }
    }
  }
}
