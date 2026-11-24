import 'package:transmiti2/features/testing/domain/test_contract.dart';

/// Servicio de pruebas falso, solo para pruebas: todo en memoria, sin
/// canal real. Nunca se usa en la app.
///
/// Imita al `TestChannelHandler` real: con `testMode == false` todos
/// los métodos salvo `getTestMode` lanzan `test_mode_unavailable`;
/// `deleteTestSession` sin `confirmed: true` lanza
/// `confirmation_required` sin borrar nada; con 10 sesiones
/// guardadas `startTestSession` lanza `test_storage_full`.
class FakeTestService implements TestService {
  FakeTestService({this.testMode = true});

  bool testMode;

  TestSession? activeSession;

  final List<TestSessionInfo> sessions = <TestSessionInfo>[];
  final List<TestEvent> events = <TestEvent>[];
  final Map<PausePoint, PauseState> pauseStates = <PausePoint, PauseState>{
    for (final PausePoint p in PausePoint.values) p: PauseState.none,
  };
  ArmedPauseIntent? armedIntent;

  TestStagingSample stagingSample = const TestStagingSample(
    tmpSizeBytes: 1024,
    active: true,
  );

  TestRegistrySnapshot registrySnapshot = const TestRegistrySnapshot(
    available: false,
    reason: 'no_recording_session',
  );

  /// Resultado enlatado de `injectTestError` (J3-c).
  TestErrorInjectionResult injectionResult =
      const TestErrorInjectionResult(
        injected: true,
        kind: 'encoder_error',
      );

  /// `kind` recibidos por `injectTestError`, en orden (J3-c).
  final List<String> injectCalls = <String>[];

  int _sessionCounter = 0;

  int startTestSessionCalls = 0;
  int endTestSessionCalls = 0;
  final List<({String sessionId, bool confirmed})> deleteCalls =
      <({String sessionId, bool confirmed})>[];
  final List<String> exportCalls = <String>[];
  final List<PausePoint> armCalls = <PausePoint>[];
  final List<PausePoint> resumeCalls = <PausePoint>[];
  int resumeAllCalls = 0;

  TestStorageState get currentStorageState => TestStorageState(
    sessionCount: sessions.length,
    limit: TestContract.maxSessions,
    full: sessions.length >= TestContract.maxSessions,
  );

  void _requireTestMode() {
    if (!testMode) {
      throw const TestException(
        TestContract.testModeUnavailable,
        'El modo de pruebas no está activo en esta compilación.',
      );
    }
  }

  /// Llena el almacén hasta el tope de 10 sesiones (para la prueba
  /// 4c). Devuelve las sesiones creadas.
  List<TestSessionInfo> fillToLimit({String testRunId = 'run_20261123_120000_ab12'}) {
    while (sessions.length < TestContract.maxSessions) {
      final int n = sessions.length + 1;
      sessions.add(
        TestSessionInfo(
          sessionId: 'sess_20261123_1200${n.toString().padLeft(2, '0')}',
          testRunId: testRunId,
          startedAtWallMs: 1764000000000 + n * 60000,
          sizeBytes: 1024 * n,
          verdict: 'INCOMPLETE',
        ),
      );
    }
    return sessions;
  }

  @override
  Future<bool> getTestMode() async => testMode;

  @override
  Future<String> startTestRun() async {
    _requireTestMode();
    _sessionCounter++;
    return 'run_20261123_120000_${_sessionCounter.toString().padLeft(4, '0')}';
  }

  @override
  Future<TestSession> startTestSession({String? adoptTestRunId}) async {
    _requireTestMode();
    startTestSessionCalls++;
    if (activeSession != null) {
      throw const TestException(
        TestContract.testError,
        'Ya hay una sesión de pruebas activa.',
      );
    }
    if (sessions.length >= TestContract.maxSessions) {
      throw const TestException(
        TestContract.testStorageFull,
        'Almacenamiento de pruebas lleno.',
      );
    }
    _sessionCounter++;
    final String testRunId =
        adoptTestRunId ??
        'run_20261123_120000_${_sessionCounter.toString().padLeft(4, '0')}';
    final String sessionId =
        'sess_20261123_1200${_sessionCounter.toString().padLeft(2, '0')}';
    final TestSession session = TestSession(
      sessionId: sessionId,
      testRunId: testRunId,
    );
    activeSession = session;
    sessions.add(
      TestSessionInfo(
        sessionId: sessionId,
        testRunId: testRunId,
        startedAtWallMs: DateTime.now().millisecondsSinceEpoch,
        sizeBytes: 0,
      ),
    );
    events.add(
      TestEvent(
        sessionId: sessionId,
        testRunId: testRunId,
        event: TestEventType.sessionStarted,
        monotonicMs: _sessionCounter * 1000,
        wallMs: DateTime.now().millisecondsSinceEpoch,
      ),
    );
    return session;
  }

  @override
  Future<TestSession?> endTestSession({String? verdict}) async {
    _requireTestMode();
    endTestSessionCalls++;
    final TestSession? ended = activeSession;
    if (ended != null) {
      events.add(
        TestEvent(
          sessionId: ended.sessionId,
          testRunId: ended.testRunId,
          event: TestEventType.sessionEnded,
          monotonicMs: _sessionCounter * 1000 + 500,
          wallMs: DateTime.now().millisecondsSinceEpoch,
        ),
      );
      activeSession = null;
      armedIntent = null;
    }
    return ended;
  }

  @override
  Future<List<TestEvent>> getTestEvents({String? sessionId}) async {
    _requireTestMode();
    if (sessionId == null) {
      return List<TestEvent>.of(events);
    }
    return events.where((TestEvent e) => e.sessionId == sessionId).toList();
  }

  @override
  Future<List<TestRun>> getTestRuns() async {
    _requireTestMode();
    final Map<String, List<TestSessionInfo>> grouped =
        <String, List<TestSessionInfo>>{};
    for (final TestSessionInfo s in sessions) {
      grouped.putIfAbsent(s.testRunId ?? '', () => <TestSessionInfo>[]).add(s);
    }
    return grouped.entries
        .map(
          (MapEntry<String, List<TestSessionInfo>> e) => TestRun(
            testRunId: e.key,
            sessionCount: e.value.length,
            sessions: e.value,
          ),
        )
        .toList();
  }

  @override
  Future<List<TestSessionInfo>> getTestSessions() async {
    _requireTestMode();
    return List<TestSessionInfo>.of(sessions);
  }

  @override
  Future<TestStorageState> getStorageState() async {
    _requireTestMode();
    return currentStorageState;
  }

  @override
  Future<void> deleteTestSession({
    required String sessionId,
    required bool confirmed,
  }) async {
    _requireTestMode();
    deleteCalls.add((sessionId: sessionId, confirmed: confirmed));
    if (!confirmed) {
      throw const TestException(
        TestContract.confirmationRequired,
        'La eliminación exige confirmed: true.',
      );
    }
    final int index = sessions.indexWhere((s) => s.sessionId == sessionId);
    if (index < 0) {
      throw const TestException(
        TestContract.sessionNotFound,
        'Sesión no encontrada.',
      );
    }
    sessions.removeAt(index);
    events.removeWhere((TestEvent e) => e.sessionId == sessionId);
    if (activeSession?.sessionId == sessionId) {
      activeSession = null;
    }
  }

  @override
  Future<TestExportResult> exportTestReport({
    required String sessionId,
  }) async {
    _requireTestMode();
    exportCalls.add(sessionId);
    return TestExportResult(
      sessionId: sessionId,
      jsonPath: '/tmp/test-exports/$sessionId-report.json',
      txtPath: '/tmp/test-exports/$sessionId-report.txt',
    );
  }

  @override
  Future<List<TestExportResult>> exportTestSessions() async {
    _requireTestMode();
    final List<TestExportResult> results = <TestExportResult>[];
    for (final TestSessionInfo s in sessions) {
      results.add(await exportTestReport(sessionId: s.sessionId));
    }
    return results;
  }

  @override
  Future<ArmedPauseIntent> armReconciliationPause(PausePoint point) async {
    _requireTestMode();
    if (activeSession == null) {
      throw const TestException(
        TestContract.testNoActiveSession,
        'No hay sesión de pruebas activa para armar una pausa.',
      );
    }
    armCalls.add(point);
    final TestSession session = activeSession!;
    final int now = DateTime.now().millisecondsSinceEpoch;
    armedIntent = ArmedPauseIntent(
      version: 1,
      testRunId: session.testRunId,
      sessionId: session.sessionId,
      point: point,
      armedAtWallMs: now,
      armedAtMonotonicMs: _sessionCounter * 1000,
      expiresAtWallMs: now + 600000,
    );
    pauseStates[point] = PauseState.armed;
    events.add(
      TestEvent(
        sessionId: session.sessionId,
        testRunId: session.testRunId,
        event: TestEventType.pauseArmed,
        monotonicMs: _sessionCounter * 1000 + 100,
        wallMs: now,
        detail: <String, Object?>{'point': point.wireName},
      ),
    );
    return armedIntent!;
  }

  @override
  Future<bool> resumeReconciliation(PausePoint point) async {
    _requireTestMode();
    resumeCalls.add(point);
    if (pauseStates[point] == PauseState.paused) {
      pauseStates[point] = PauseState.released;
      return true;
    }
    return false;
  }

  @override
  Future<int> resumeAllReconciliation() async {
    _requireTestMode();
    resumeAllCalls++;
    int count = 0;
    for (final PausePoint point in PausePoint.values) {
      if (pauseStates[point] == PauseState.paused) {
        pauseStates[point] = PauseState.released;
        count++;
      }
    }
    return count;
  }

  @override
  Future<PauseState> getPauseState(PausePoint point) async {
    _requireTestMode();
    return pauseStates[point] ?? PauseState.none;
  }

  @override
  Future<Map<PausePoint, PauseState>> getPauseStates() async {
    _requireTestMode();
    return Map<PausePoint, PauseState>.of(pauseStates);
  }

  @override
  Future<TestRegistrySnapshot> getRegistrySnapshot() async {
    _requireTestMode();
    return registrySnapshot;
  }

  @override
  Future<TestStagingSample> sampleActiveStagingSize() async {
    _requireTestMode();
    return stagingSample;
  }

  @override
  Future<TestErrorInjectionResult> injectTestError(String kind) async {
    _requireTestMode();
    injectCalls.add(kind);
    return injectionResult;
  }
}
