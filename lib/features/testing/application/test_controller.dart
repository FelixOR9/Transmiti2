import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';
import 'package:transmiti2/features/testing/domain/test_contract.dart';

/// Controlador de la infraestructura de pruebas (FASE 2, grupo 2-d).
///
/// Gestiona la campaña (`testRunId` activo, continuación), la sesión,
/// el muestreador periódico (5 s, solo con sesión activa y grabación
/// en curso), el estado de almacenamiento, la eliminación con
/// confirmación y la exportación. Observa el `EventChannel` ya
/// existente (eventos `recording_started`/`recording_stopped`/
/// `recording_error`); no crea otro canal ni duplica el
/// `RecordingController`.
///
/// Con `testMode == false` el controlador es inerte: no registra
/// eventos, no muestrea y no expone entrada de pruebas (test nº 1).
/// Los métodos de acción lanzan `TestException` con
/// `test_mode_unavailable` sin tocar el canal; los de lectura
/// registran el error en `lastError` sin lanzar.
class TestController extends ChangeNotifier {
  TestController({
    required TestService testService,
    required StreamingService streamingService,
    this.testMode = false,
    this.sampleInterval = const Duration(seconds: 5),
  }) : _testService = testService,
       _streamingService = streamingService;

  final TestService _testService;
  final StreamingService _streamingService;

  /// Puerta de compilación, leída al arrancar por `getTestMode`
  /// (`AppServices.create`); `refreshTestMode()` la vuelve a leer.
  bool testMode;

  /// Intervalo del muestreador (5 s en producción; corto e inyectado
  /// en las pruebas).
  final Duration sampleInterval;

  final Stopwatch _monotonic = Stopwatch()..start();

  bool _isBusy = false;
  bool get isBusy => _isBusy;

  TestException? _lastError;
  TestException? get lastError => _lastError;

  TestSession? _activeSession;
  TestSession? get activeSession => _activeSession;

  /// Campaña activa (`testRunId` de la sesión en curso, o de la
  /// última sesión conocida).
  String? get activeTestRunId => _activeSession?.testRunId ?? _lastTestRunId;
  String? _lastTestRunId;

  List<TestEvent> _events = const <TestEvent>[];
  List<TestEvent> get events => _events;

  List<TestSessionInfo> _sessions = const <TestSessionInfo>[];
  List<TestSessionInfo> get sessions => _sessions;

  List<TestRun> _runs = const <TestRun>[];
  List<TestRun> get runs => _runs;

  TestStorageState _storageState = const TestStorageState(
    sessionCount: 0,
    limit: TestContract.maxSessions,
    full: false,
  );
  TestStorageState get storageState => _storageState;

  Map<PausePoint, PauseState> _pauseStates = const <PausePoint, PauseState>{};
  Map<PausePoint, PauseState> get pauseStates => _pauseStates;

  ArmedPauseIntent? _armedIntent;
  ArmedPauseIntent? get armedIntent => _armedIntent;

  TestRegistrySnapshot? _registrySnapshot;
  TestRegistrySnapshot? get registrySnapshot => _registrySnapshot;

  TestExportResult? _lastExport;
  TestExportResult? get lastExport => _lastExport;

  /// `recordingId` de la grabación en curso (del `EventChannel`
  /// existente), o `null`.
  String? _activeRecordingId;
  String? get activeRecordingId => _activeRecordingId;

  bool get isRecording => _activeRecordingId != null;

  /// Muestras `RECORDING_SAMPLE` tomadas por el muestreador Dart
  /// (solo memoria: no hay método de canal para enviarlas al lado
  /// nativo; ver `TestContract`).
  List<TestEvent> _samples = const <TestEvent>[];
  List<TestEvent> get samples => _samples;

  /// Banderas `DIVERGENCE_SUSPECTED`/`STALLED_TMP` calculadas sobre
  /// las muestras (diseño base §8; inferidas, solo para análisis).
  List<String> _sampleFlags = const <String>[];
  List<String> get sampleFlags => _sampleFlags;

  bool get samplerActive => _sampler != null;

  int _attachedObservers = 0;
  StreamSubscription<Map<String, Object?>>? _eventsSubscription;
  Timer? _sampler;

  void _requireTestMode() {
    if (!testMode) {
      throw const TestException(
        TestContract.testModeUnavailable,
        'El modo de pruebas no está activo en esta compilación.',
      );
    }
  }

  TestException _recordError(Object error) {
    final TestException testError = error is TestException
        ? error
        : TestException(TestContract.testError, '$error');
    _lastError = testError;
    notifyListeners();
    return testError;
  }

  Future<T> _guarded<T>(Future<T> Function() run) async {
    _isBusy = true;
    _lastError = null;
    notifyListeners();
    try {
      return await run();
    } catch (e) {
      _isBusy = false;
      throw _recordError(e);
    } finally {
      _isBusy = false;
      notifyListeners();
    }
  }

  /// Lee `getTestMode` y lo guarda. Un fallo del canal deja
  /// `testMode` en `false` (fail-closed) sin lanzar.
  Future<void> refreshTestMode() async {
    try {
      testMode = await _testService.getTestMode();
    } catch (_) {
      testMode = false;
    }
    notifyListeners();
  }

  void attach() {
    _attachedObservers++;
    if (_attachedObservers == 1) {
      _eventsSubscription = _streamingService.events.listen(_onEvent);
      unawaited(refreshAll());
    }
  }

  void detach() {
    if (_attachedObservers == 0) {
      return;
    }
    _attachedObservers--;
    if (_attachedObservers == 0) {
      unawaited(_eventsSubscription?.cancel());
      _eventsSubscription = null;
      _stopSampler();
    }
  }

  void _onEvent(Map<String, Object?> event) {
    final String? type = event['type'] as String?;
    switch (type) {
      case StreamContract.eventRecordingStarted:
        _activeRecordingId = event['recordingId'] as String?;
        _maybeStartSampler();
        notifyListeners();
      case StreamContract.eventRecordingStopped:
      case StreamContract.eventRecordingError:
        _activeRecordingId = null;
        _stopSampler();
        notifyListeners();
      case StreamContract.eventServiceStopped:
        _activeRecordingId = null;
        _stopSampler();
        notifyListeners();
    }
  }

  // ------------------------------------------------------------------
  // Sesión y campaña
  // ------------------------------------------------------------------

  /// Nueva campaña: genera un `testRunId` libre y arranca una sesión
  /// adoptándolo (botón «Nueva campaña», Revisión 1 §9.1).
  Future<TestSession> startNewCampaign() {
    return _guarded(() async {
      _requireTestMode();
      final String testRunId = await _testService.startTestRun();
      return _startSession(adoptTestRunId: testRunId);
    });
  }

  /// Continúa una campaña existente (misma `testRunId`, nueva
  /// `sessionId`).
  Future<TestSession> continueCampaign(String testRunId) {
    return _guarded(
      () async {
        _requireTestMode();
        return _startSession(adoptTestRunId: testRunId);
      },
    );
  }

  /// Arranca una sesión (campaña nueva si no se adopta ninguna).
  Future<TestSession> startSession({String? adoptTestRunId}) {
    return _guarded(
      () => _startSession(adoptTestRunId: adoptTestRunId),
    );
  }

  Future<TestSession> _startSession({String? adoptTestRunId}) async {
    _requireTestMode();
    final TestSession session = await _testService.startTestSession(
      adoptTestRunId: adoptTestRunId,
    );
    _activeSession = session;
    _lastTestRunId = session.testRunId;
    _maybeStartSampler();
    await refreshAll();
    return session;
  }

  /// Termina la sesión activa (`verdict` opcional). Libera las
  /// pausas pendientes y borra la intención de su campaña (lado
  /// nativo). `null` si no había ninguna.
  Future<TestSession?> endSession({String? verdict}) {
    return _guarded(() async {
      _requireTestMode();
      final TestSession? ended = await _testService.endTestSession(
        verdict: verdict,
      );
      _activeSession = null;
      _armedIntent = null;
      _stopSampler();
      await refreshAll();
      return ended;
    });
  }

  // ------------------------------------------------------------------
  // Lecturas (nunca lanzan: el error queda en `lastError`)
  // ------------------------------------------------------------------

  Future<void> refreshAll() async {
    await refreshEvents();
    await refreshSessions();
    await refreshRuns();
    await refreshStorageState();
    await refreshPauseStates();
  }

  Future<void> refreshEvents() async {
    if (!testMode) {
      _events = const <TestEvent>[];
      return;
    }
    try {
      _events = await _testService.getTestEvents(
        sessionId: _activeSession?.sessionId,
      );
    } catch (e) {
      _recordError(e);
      return;
    }
    notifyListeners();
  }

  Future<void> refreshSessions() async {
    if (!testMode) {
      _sessions = const <TestSessionInfo>[];
      return;
    }
    try {
      _sessions = await _testService.getTestSessions();
    } catch (e) {
      _recordError(e);
      return;
    }
    notifyListeners();
  }

  Future<void> refreshRuns() async {
    if (!testMode) {
      _runs = const <TestRun>[];
      return;
    }
    try {
      _runs = await _testService.getTestRuns();
    } catch (e) {
      _recordError(e);
      return;
    }
    notifyListeners();
  }

  Future<void> refreshStorageState() async {
    if (!testMode) {
      _storageState = const TestStorageState(
        sessionCount: 0,
        limit: TestContract.maxSessions,
        full: false,
      );
      return;
    }
    try {
      _storageState = await _testService.getStorageState();
    } catch (e) {
      _recordError(e);
      return;
    }
    notifyListeners();
  }

  Future<void> refreshPauseStates() async {
    if (!testMode) {
      _pauseStates = const <PausePoint, PauseState>{};
      return;
    }
    try {
      _pauseStates = await _testService.getPauseStates();
    } catch (e) {
      _recordError(e);
      return;
    }
    notifyListeners();
  }

  Future<void> refreshRegistrySnapshot() async {
    if (!testMode) {
      _registrySnapshot = null;
      return;
    }
    try {
      _registrySnapshot = await _testService.getRegistrySnapshot();
    } catch (e) {
      _recordError(e);
      return;
    }
    notifyListeners();
  }

  // ------------------------------------------------------------------
  // Pausas
  // ------------------------------------------------------------------

  /// Arma la pausa en `point`; exige sesión activa (el lado nativo
  /// responde `test_no_active_session` si no la hay).
  Future<ArmedPauseIntent> armPause(PausePoint point) {
    return _guarded(() async {
      _requireTestMode();
      final ArmedPauseIntent intent = await _testService
          .armReconciliationPause(point);
      _armedIntent = intent;
      await refreshPauseStates();
      return intent;
    });
  }

  /// Reanuda la pausa en `point`. `true` si había una pausa
  /// suspendida que se liberó.
  Future<bool> resumePause(PausePoint point) {
    return _guarded(() async {
      _requireTestMode();
      final bool resumed = await _testService.resumeReconciliation(point);
      await refreshPauseStates();
      return resumed;
    });
  }

  /// Reanuda todas las pausas; devuelve cuántas se liberaron.
  Future<int> resumeAllPauses() {
    return _guarded(() async {
      _requireTestMode();
      final int count = await _testService.resumeAllReconciliation();
      await refreshPauseStates();
      return count;
    });
  }

  /// Inyección de error solo-para-pruebas (J3-c, 3.1.4; autorización
  /// J0.3): fuerza en caliente un fallo del encoder (`encoder_error`)
  /// o de escritura en el drenado (`drain_write_error`) en la
  /// grabación en curso (prueba física J3-P1). Exige `testMode`;
  /// `injected: false` no lanza (ver `TestErrorInjectionResult`).
  Future<TestErrorInjectionResult> injectTestError(String kind) {
    return _guarded(() async {
      _requireTestMode();
      return _testService.injectTestError(kind);
    });
  }

  // ------------------------------------------------------------------
  // Eliminación y exportación
  // ------------------------------------------------------------------

  /// Elimina una sesión persistida. Sin `confirmed: true` lanza
  /// `confirmation_required` SIN llamar al canal (Revisión 2 §3):
  /// nada se borra y nada se registra.
  Future<void> deleteSession(
    String sessionId, {
    required bool confirmed,
  }) {
    return _guarded(() async {
      _requireTestMode();
      if (!confirmed) {
        throw const TestException(
          TestContract.confirmationRequired,
          'La eliminación exige confirmed: true.',
        );
      }
      await _testService.deleteTestSession(
        sessionId: sessionId,
        confirmed: true,
      );
      await refreshSessions();
      await refreshRuns();
      await refreshStorageState();
    });
  }

  /// Exporta el informe de una sesión (JSON + `.txt`).
  Future<TestExportResult> exportReport(String sessionId) {
    return _guarded(() async {
      _requireTestMode();
      final TestExportResult result = await _testService.exportTestReport(
        sessionId: sessionId,
      );
      _lastExport = result;
      return result;
    });
  }

  /// Exporta todas las sesiones.
  Future<List<TestExportResult>> exportAllReports() {
    return _guarded(() async {
      _requireTestMode();
      final List<TestExportResult> results = await _testService
          .exportTestSessions();
      if (results.isNotEmpty) {
        _lastExport = results.last;
      }
      return results;
    });
  }

  // ------------------------------------------------------------------
  // Muestreador (diseño base §8)
  // ------------------------------------------------------------------

  /// Muestreador periódico solo de pruebas: mientras haya sesión
  /// activa y grabación en curso, cada 5 s toma una muestra (estado
  /// Dart, snapshot nativo y tamaño del `.tmp` activo). Con la
  /// instrumentación desactivada, el muestreador no existe (cero
  /// coste). Un fallo de muestreo nunca rompe la grabación: se
  /// traga en silencio.
  void _maybeStartSampler() {
    if (!testMode || _activeSession == null || _activeRecordingId == null) {
      return;
    }
    _sampler ??= Timer.periodic(sampleInterval, (_) {
      unawaited(sampleNow());
    });
  }

  void _stopSampler() {
    _sampler?.cancel();
    _sampler = null;
  }

  /// Una muestra ahora (también la usa el temporizador). No hace nada
  /// sin sesión activa ni grabación en curso.
  Future<void> sampleNow() async {
    if (!testMode || _activeSession == null || _activeRecordingId == null) {
      return;
    }
    final TestSession session = _activeSession!;
    try {
      final RecordingStatusSnapshot status = await _streamingService
          .getRecordingStatus();
      if (status.state != 'recording' ||
          status.recordingId != _activeRecordingId) {
        return;
      }
      final TestStagingSample size = await _testService
          .sampleActiveStagingSize();
      final TestEvent sample = TestEvent(
        sessionId: session.sessionId,
        testRunId: session.testRunId,
        recordingId: _activeRecordingId,
        operation: 'sample',
        event: TestEventType.recordingSample,
        monotonicMs: _monotonic.elapsedMilliseconds,
        wallMs: DateTime.now().millisecondsSinceEpoch,
        detail: <String, Object?>{
          'segmentDurationMs': status.segmentDurationMs,
          'tmpSizeBytes': size.tmpSizeBytes,
          'dartState': 'recording',
        },
      );
      _samples = <TestEvent>[..._samples, sample];
      _sampleFlags = detectSampleFlags(_samples);
      notifyListeners();
    } catch (_) {
      // El muestreo nunca rompe la grabación ni la sesión.
    }
  }

  /// Veredicto local de la sesión activa, calculado sobre los
  /// eventos en memoria (regla de `TestReportBuilder`; sin resumen
  /// de reconciliación los criterios quedan `not_evaluable`).
  TestReportEvaluation evaluateActiveSession() {
    return evaluateTestReport(
      <TestEvent>[..._events, ..._samples],
      armedIntent: _armedIntent,
      testRunId: activeTestRunId,
    );
  }

  @override
  void dispose() {
    _stopSampler();
    unawaited(_eventsSubscription?.cancel());
    super.dispose();
  }
}
