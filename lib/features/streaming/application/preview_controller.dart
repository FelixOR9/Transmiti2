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

/// Datos de diagnóstico del último intento de vista previa (Fase A).
///
/// Vienen de los `details` del error nativo o de la `PreviewInfo`
/// cuando la preview está activa. Un campo `null` significa que el dato
/// no estaba disponible en esa etapa: la UI muestra «—», nunca se
/// inventa un valor.
class PreviewDiagnostics {
  const PreviewDiagnostics({
    this.stage,
    this.cameraCount,
    this.selectedCameraId,
    this.selectedSize,
    this.selectedFps,
    this.exceptionClass,
    this.lastErrorStage,
  });

  /// Etapa alcanzada en el intento (`enumerate`, `openDevice`,
  /// `createCaptureSession`, `startRepeating`, `activa`...).
  final String? stage;

  /// Número de cámaras detectadas por `cameraIdList`.
  final int? cameraCount;

  /// Cámara elegida (`0`, `1`…).
  final String? selectedCameraId;

  /// Tamaño elegido (`1280x720`).
  final String? selectedSize;

  /// FPS elegido (máximo del rango).
  final int? selectedFps;

  /// Clase de la excepción nativa, si la hubo.
  final String? exceptionClass;

  /// Etapa en la que falló el intento (`null` si no hubo error).
  final String? lastErrorStage;

  /// Diagnóstico de un error: `stage` es a la vez la etapa alcanzada
  /// y la última etapa con error.
  factory PreviewDiagnostics.fromDetails(Map<String, Object?>? details) {
    if (details == null) {
      return const PreviewDiagnostics();
    }
    final String? stage = details['stage'] as String?;
    return PreviewDiagnostics(
      stage: stage,
      cameraCount: (details['cameraCount'] as num?)?.toInt(),
      selectedCameraId: details['selectedCameraId'] as String?,
      selectedSize: details['selectedSize'] as String?,
      selectedFps: (details['selectedFps'] as num?)?.toInt(),
      exceptionClass: details['exceptionClass'] as String?,
      lastErrorStage: stage,
    );
  }

  /// Diagnóstico de una vista previa activa: la configuración elegida.
  factory PreviewDiagnostics.fromInfo(PreviewInfo info) {
    return PreviewDiagnostics(
      stage: 'activa',
      selectedSize: '${info.width}x${info.height}',
      selectedFps: info.fps,
    );
  }
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
/// Fase B, 3.1.2: con una grabación activa (`isRecordingActive`) nada
/// de lo anterior para ni reinicia la vista previa (plan §6.4): el FGS
/// + wake lock mantienen la grabación, la orientación quedó fijada al
/// iniciar y la vista previa rota solo en presentación (`RotatedBox`).
/// La parada manual tampoco detiene la vista previa mientras se graba
/// (primero hay que detener la grabación).
///
/// Los eventos del motor (`preview_error`, `preview_surface_lost`,
/// `preview_started`) llegan por el canal de eventos del servicio; un
/// error cierra el estado a `error` y la pérdida de superficie a
/// `recovering` hasta que el motor la recupera.
class PreviewController extends ChangeNotifier with WidgetsBindingObserver {
  PreviewController({
    required StreamingService service,
    required PermissionController permissions,
    bool Function()? isRecordingActive,
  }) : _service = service,
       _permissions = permissions,
       _isRecordingActive = isRecordingActive ?? (() => false);

  final StreamingService _service;
  final PermissionController _permissions;

  /// `true` mientras hay una grabación en curso (la vista previa no se
  /// para ni se reinicia; la fija `StreamingController` con el estado
  /// del `RecordingController`).
  final bool Function() _isRecordingActive;

  PreviewState _state = PreviewState.closed;
  PreviewState get state => _state;

  PreviewInfo? _info;
  PreviewInfo? get info => _info;

  String? _errorCode;
  String? get errorCode => _errorCode;

  /// Mensaje técnico nativo del último error (Fase A). Antes se
  /// descartaba: solo viajaba el código.
  String? _errorMessage;
  String? get errorMessage => _errorMessage;

  /// Diagnóstico del último intento (Fase A). Se limpia al cerrar la
  /// preview o al iniciar un nuevo intento: nunca se muestra un
  /// diagnóstico viejo como si fuera del intento actual.
  PreviewDiagnostics? _diagnostics;
  PreviewDiagnostics? get diagnostics => _diagnostics;

  /// Texto del bloque de diagnóstico que muestra el Estudio (Fase A):
  /// `null` cuando no hay ningún intento que mostrar (cerrada,
  /// preparando, recuperando o permiso denegado).
  String? get diagnosticText {
    if (_state != PreviewState.active &&
        _state != PreviewState.error &&
        _state != PreviewState.unavailable) {
      return null;
    }
    final PreviewDiagnostics diag =
        _diagnostics ?? const PreviewDiagnostics();
    final StringBuffer text = StringBuffer('Diagnóstico de vista previa');
    text.writeln();
    text.writeln('Estado: ${_state.name}');
    text.writeln('Etapa alcanzada: ${diag.stage ?? '—'}');
    if (_errorCode != null) {
      text.writeln('Código de error: $_errorCode');
    }
    text.writeln('Mensaje técnico: ${_errorMessage ?? '—'}');
    text.writeln(
      'Clase de excepción: ${diag.exceptionClass ?? '—'}',
    );
    text.writeln(
      'Cámaras detectadas: ${diag.cameraCount?.toString() ?? '—'}',
    );
    text.writeln('Cámara seleccionada: ${diag.selectedCameraId ?? '—'}');
    text.writeln('Tamaño: ${diag.selectedSize ?? '—'}');
    text.writeln('FPS: ${diag.selectedFps?.toString() ?? '—'}');
    text.write('Última etapa con error: ${diag.lastErrorStage ?? '—'}');
    return text.toString();
  }

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
      // Fase B, 3.1.2: con grabación activa no se para la vista previa
      // (el FGS + wake lock mantienen la grabación; plan §6.4).
      if (_isRecordingActive()) {
        return;
      }
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
    // Fase B, 3.1.2: con grabación activa no se reinicia (la
    // orientación quedó fijada al iniciar; plan §6.4).
    if (_isRecordingActive()) {
      return;
    }
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
      _errorMessage = null;
      _diagnostics = null;
      notifyListeners();
      return;
    }
    final int generation = ++_generation;
    _state = PreviewState.preparing;
    _errorCode = null;
    _errorMessage = null;
    _diagnostics = null;
    notifyListeners();
    try {
      final PreviewInfo preview = await _service.startPreview();
      if (generation != _generation) {
        // Un reinicio o una parada dejaron obsoleto este inicio.
        return;
      }
      _info = preview;
      _diagnostics = PreviewDiagnostics.fromInfo(preview);
      _state = PreviewState.active;
      notifyListeners();
    } on StreamingException catch (e) {
      if (generation != _generation) {
        return;
      }
      _info = null;
      _errorCode = e.code;
      _errorMessage = e.message;
      _diagnostics = PreviewDiagnostics.fromDetails(e.details);
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
  /// Con grabación activa no se para (primero hay que detener la
  /// grabación; plan §6.4).
  Future<void> stop({bool restoreOnResume = false}) async {
    // Con grabación activa no se para: es un no-op completo (ni se
    // invalida un inicio en curso ni se toca el estado).
    if (_isRecordingActive()) {
      return;
    }
    _generation++;
    _wasActiveBeforeBackground = restoreOnResume;
    if (_state == PreviewState.closed) {
      return;
    }
    _state = PreviewState.closed;
    _info = null;
    _errorCode = null;
    _errorMessage = null;
    _diagnostics = null;
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
          // Fase A: el evento lleva el mensaje técnico y los datos de
          // diagnóstico cuando el motor los tiene; en una desconexión
          // asíncrona solo llega el código y el resto queda en «—».
          _errorMessage = event['message'] as String?;
          _diagnostics = PreviewDiagnostics.fromDetails(event);
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
