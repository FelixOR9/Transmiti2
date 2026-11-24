import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';

/// Contrato Dart del canal de pruebas
/// (`com.transmiti2.transmiti2/streaming/testing`).
///
/// Espejo fiel de `TestChannelHandler` (FASE 2, grupos 2-b/2-c): nombres
/// de método, códigos de error y formas de request/response. No se toca
/// `stream_contract.dart` ni el canal `.../streaming`.
///
/// Desviación conocida respecto al diseño base: el canal real no tiene
/// ningún método para enviar una muestra Dart al lado nativo, así que
/// las muestras del muestreador (`RECORDING_SAMPLE`) viven solo en la
/// memoria del controlador Dart; el informe persistido (JSONL nativo)
/// no las contiene. Las banderas `DIVERGENCE_SUSPECTED`/`STALLED_TMP`
/// se calculan en Dart sobre esas muestras y se muestran en pantalla.
abstract final class TestContract {
  static const String methodChannel =
      'com.transmiti2.transmiti2/streaming/testing';

  static const String getTestMode = 'getTestMode';
  static const String startTestRun = 'startTestRun';
  static const String startTestSession = 'startTestSession';
  static const String endTestSession = 'endTestSession';
  static const String getTestEvents = 'getTestEvents';
  static const String getTestRuns = 'getTestRuns';
  static const String getTestSessions = 'getTestSessions';
  static const String getStorageState = 'getStorageState';
  static const String deleteTestSession = 'deleteTestSession';
  static const String exportTestReport = 'exportTestReport';
  static const String exportTestSessions = 'exportTestSessions';
  static const String armReconciliationPause = 'armReconciliationPause';
  static const String resumeReconciliation = 'resumeReconciliation';
  static const String getPauseState = 'getPauseState';
  static const String getRegistrySnapshot = 'getRegistrySnapshot';
  static const String sampleActiveStagingSize = 'sampleActiveStagingSize';

  static const String testModeUnavailable = 'test_mode_unavailable';
  static const String testStorageFull = 'test_storage_full';
  static const String testNoActiveSession = 'test_no_active_session';
  static const String confirmationRequired = 'confirmation_required';
  static const String testPathOutsideRoot = 'test_path_outside_root';
  static const String sessionNotFound = 'session_not_found';
  static const String testNotAvailable = 'test_not_available';
  static const String testError = 'test_error';

  /// Tope de sesiones persistidas (Revisión 1 §6).
  static const int maxSessions = 10;
}

/// Error del canal de pruebas. El código nunca lleva secretos: solo el
/// código, para que pueda mostrarse o registrarse.
class TestException implements Exception {
  const TestException(this.code, [this.message, this.details]);

  final String code;
  final String? message;
  final Map<String, Object?>? details;

  @override
  String toString() => 'TestException($code)';
}

/// Lado nativo (Kotlin) de la infraestructura de pruebas, detrás de una
/// interfaz para poder sustituirlo en las pruebas.
abstract class TestService {
  /// Puerta de compilación (`BuildConfig.TRANSMITI2_TEST_MODE`).
  Future<bool> getTestMode();

  /// Genera un `testRunId` libre (sin crear sesión).
  Future<String> startTestRun();

  /// Arranca una sesión de pruebas. `adoptTestRunId` continúa una
  /// campaña existente (Revisión 1 §2.6); sin él se genera una
  /// campaña nueva.
  Future<TestSession> startTestSession({String? adoptTestRunId});

  /// Termina la sesión activa (`verdict` opcional). `null` si no
  /// había ninguna.
  Future<TestSession?> endTestSession({String? verdict});

  /// Eventos del anillo en memoria; `sessionId` opcional para filtrar.
  Future<List<TestEvent>> getTestEvents({String? sessionId});

  /// Sesiones agrupadas por `testRunId` (campañas).
  Future<List<TestRun>> getTestRuns();

  /// Lista plana de sesiones persistidas con metadatos.
  Future<List<TestSessionInfo>> getTestSessions();

  /// `{sessionCount, limit, full}` (tope de 10 sesiones).
  Future<TestStorageState> getStorageState();

  /// Elimina una sesión. Sin `confirmed: true` el lado nativo
  /// responde `confirmation_required` sin borrar nada; el controlador
  /// Dart ni siquiera llama al canal sin confirmación (Revisión 2 §3).
  Future<void> deleteTestSession({
    required String sessionId,
    required bool confirmed,
  });

  /// Exporta el informe de una sesión (JSON + `.txt`).
  Future<TestExportResult> exportTestReport({required String sessionId});

  /// Exporta todas las sesiones.
  Future<List<TestExportResult>> exportTestSessions();

  /// Arma la pausa en `point` (P0/P1/P2); exige sesión activa.
  Future<ArmedPauseIntent> armReconciliationPause(PausePoint point);

  /// Reanuda la pausa en `point`. `true` si había una pausa
  /// suspendida que se liberó.
  Future<bool> resumeReconciliation(PausePoint point);

  /// Reanuda todas las pausas; devuelve cuántas se liberaron.
  Future<int> resumeAllReconciliation();

  /// Estado de la pausa en `point`.
  Future<PauseState> getPauseState(PausePoint point);

  /// Estado de la pausa en los tres puntos.
  Future<Map<PausePoint, PauseState>> getPauseStates();

  /// Snapshot del registro de grabación activa (dependencia del
  /// motor; `available: false` sin sesión de grabación).
  Future<TestRegistrySnapshot> getRegistrySnapshot();

  /// Tamaño del `.tmp` activo (`null` sin grabación activa).
  Future<TestStagingSample> sampleActiveStagingSize();
}

class MethodChannelTestService implements TestService {
  MethodChannelTestService({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel(TestContract.methodChannel);

  final MethodChannel _channel;

  TestException _fromPlatform(PlatformException e) {
    final Object? rawDetails = e.details;
    final Map<String, Object?>? details = rawDetails is Map
        ? Map<String, Object?>.from(rawDetails)
        : null;
    return TestException(e.code, e.message, details);
  }

  Never _contractError(String method) {
    throw TestException(
      TestContract.testError,
      'La respuesta del canal de pruebas no es válida ($method).',
      null,
    );
  }

  @override
  Future<bool> getTestMode() async {
    try {
      final bool? result = await _channel.invokeMethod<bool>(
        TestContract.getTestMode,
      );
      if (result == null) {
        _contractError(TestContract.getTestMode);
      }
      return result;
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<String> startTestRun() async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(TestContract.startTestRun);
      final String? testRunId = result?['testRunId'] as String?;
      if (testRunId == null) {
        _contractError(TestContract.startTestRun);
      }
      return testRunId;
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<TestSession> startTestSession({String? adoptTestRunId}) async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(
            TestContract.startTestSession,
            <String, Object?>{
              if (adoptTestRunId != null) 'adoptTestRunId': adoptTestRunId,
            },
          );
      if (result == null) {
        _contractError(TestContract.startTestSession);
      }
      return TestSession.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<TestSession?> endTestSession({String? verdict}) async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(
            TestContract.endTestSession,
            <String, Object?>{
              if (verdict != null) 'verdict': verdict,
            },
          );
      if (result == null) {
        return null;
      }
      return TestSession.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<List<TestEvent>> getTestEvents({String? sessionId}) async {
    try {
      final List<Object?>? result = await _channel.invokeListMethod<Object?>(
        TestContract.getTestEvents,
        <String, Object?>{
          if (sessionId != null) 'sessionId': sessionId,
        },
      );
      if (result == null) {
        _contractError(TestContract.getTestEvents);
      }
      return result
          .whereType<Map<Object?, Object?>>()
          .map(TestEvent.fromMap)
          .whereType<TestEvent>()
          .toList();
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<List<TestRun>> getTestRuns() async {
    try {
      final List<Object?>? result = await _channel.invokeListMethod<Object?>(
        TestContract.getTestRuns,
      );
      if (result == null) {
        _contractError(TestContract.getTestRuns);
      }
      return result
          .whereType<Map<Object?, Object?>>()
          .map(TestRun.fromMap)
          .toList();
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<List<TestSessionInfo>> getTestSessions() async {
    try {
      final List<Object?>? result = await _channel.invokeListMethod<Object?>(
        TestContract.getTestSessions,
      );
      if (result == null) {
        _contractError(TestContract.getTestSessions);
      }
      return result
          .whereType<Map<Object?, Object?>>()
          .map(TestSessionInfo.fromMap)
          .toList();
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<TestStorageState> getStorageState() async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(TestContract.getStorageState);
      if (result == null) {
        _contractError(TestContract.getStorageState);
      }
      return TestStorageState.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<void> deleteTestSession({
    required String sessionId,
    required bool confirmed,
  }) async {
    try {
      await _channel.invokeMethod<void>(
        TestContract.deleteTestSession,
        <String, Object>{
          'sessionId': sessionId,
          'confirmed': confirmed,
        },
      );
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<TestExportResult> exportTestReport({
    required String sessionId,
  }) async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(
            TestContract.exportTestReport,
            <String, Object>{'sessionId': sessionId},
          );
      if (result == null) {
        _contractError(TestContract.exportTestReport);
      }
      return TestExportResult.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<List<TestExportResult>> exportTestSessions() async {
    try {
      final List<Object?>? result = await _channel.invokeListMethod<Object?>(
        TestContract.exportTestSessions,
      );
      if (result == null) {
        _contractError(TestContract.exportTestSessions);
      }
      return result
          .whereType<Map<Object?, Object?>>()
          .map(TestExportResult.fromMap)
          .toList();
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<ArmedPauseIntent> armReconciliationPause(PausePoint point) async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(
            TestContract.armReconciliationPause,
            <String, Object>{'point': point.wireName},
          );
      if (result == null) {
        _contractError(TestContract.armReconciliationPause);
      }
      return ArmedPauseIntent.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<bool> resumeReconciliation(PausePoint point) async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(
            TestContract.resumeReconciliation,
            <String, Object>{'point': point.wireName},
          );
      if (result == null) {
        _contractError(TestContract.resumeReconciliation);
      }
      return result['resumed'] as bool? ?? false;
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<int> resumeAllReconciliation() async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(
            TestContract.resumeReconciliation,
          );
      if (result == null) {
        _contractError(TestContract.resumeReconciliation);
      }
      return (result['resumedCount'] as num?)?.toInt() ?? 0;
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<PauseState> getPauseState(PausePoint point) async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(
            TestContract.getPauseState,
            <String, Object>{'point': point.wireName},
          );
      if (result == null) {
        _contractError(TestContract.getPauseState);
      }
      return PauseState.fromWireName(result['state'] as String?);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<Map<PausePoint, PauseState>> getPauseStates() async {
    try {
      final List<Object?>? result = await _channel.invokeListMethod<Object?>(
        TestContract.getPauseState,
      );
      if (result == null) {
        _contractError(TestContract.getPauseState);
      }
      final Map<PausePoint, PauseState> states = <PausePoint, PauseState>{};
      for (final Object? item in result) {
        if (item is! Map<Object?, Object?>) {
          continue;
        }
        final PausePoint? point = PausePoint.fromWireName(
          item['point'] as String?,
        );
        if (point != null) {
          states[point] = PauseState.fromWireName(item['state'] as String?);
        }
      }
      return states;
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<TestRegistrySnapshot> getRegistrySnapshot() async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(TestContract.getRegistrySnapshot);
      if (result == null) {
        _contractError(TestContract.getRegistrySnapshot);
      }
      return TestRegistrySnapshot.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<TestStagingSample> sampleActiveStagingSize() async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(
            TestContract.sampleActiveStagingSize,
          );
      if (result == null) {
        _contractError(TestContract.sampleActiveStagingSize);
      }
      return TestStagingSample.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }
}

/// Puntos de pausa de la reconciliación (Revisión 1 §4.2 + diseño
/// base §5). P0 antes del inventario (C2), P1 entre inventario y
/// bucle de grupos (C1, recomendado), P2 entre grupos.
enum PausePoint {
  p0,
  p1,
  p2;

  String get wireName => name.toUpperCase();

  static PausePoint? fromWireName(String? wire) {
    for (final PausePoint point in PausePoint.values) {
      if (point.wireName == wire) {
        return point;
      }
    }
    return null;
  }

  String get label => switch (this) {
    PausePoint.p0 => 'P0 (antes del inventario)',
    PausePoint.p1 => 'P1 (entre inventario y grupos)',
    PausePoint.p2 => 'P2 (entre grupos)',
  };
}

/// Estado observable de la pausa en un punto (Revisión 2 §2.3).
enum PauseState {
  none,
  armed,
  consumed,
  paused,
  released,
  timedOut,
  cancelled;

  String get wireName => name.toUpperCase();

  static PauseState fromWireName(String? wire) {
    if (wire == null) {
      return PauseState.none;
    }
    for (final PauseState state in PauseState.values) {
      if (state.wireName == wire) {
        return state;
      }
    }
    return PauseState.none;
  }

  String get label => switch (this) {
    PauseState.none => 'sin armar',
    PauseState.armed => 'armada',
    PauseState.consumed => 'consumida',
    PauseState.paused => 'pausada',
    PauseState.released => 'liberada',
    PauseState.timedOut => 'expirada por timeout',
    PauseState.cancelled => 'cancelada',
  };
}

/// Origen de un evento: observado de verdad o derivado de una ausencia.
enum TestEventOrigin {
  observed,
  inferred;

  String get wireName => name;

  static TestEventOrigin fromWireName(String? wire) =>
      wire == 'inferred' ? TestEventOrigin.inferred : TestEventOrigin.observed;
}

/// Resultado de un evento.
enum TestEventResult {
  ok,
  skipped,
  failed,
  timeout,
  pending;

  String get wireName => name;

  static TestEventResult fromWireName(String? wire) => switch (wire) {
    'skipped' => TestEventResult.skipped,
    'failed' => TestEventResult.failed,
    'timeout' => TestEventResult.timeout,
    'pending' => TestEventResult.pending,
    _ => TestEventResult.ok,
  };
}

/// Tipos de evento de la instrumentación (diseño base §4.A +
/// Revisión 1 §2/§3 + Revisión 2 §2.3 + §8 del diseño base). Espejo
/// de `TestEventType` en Kotlin.
enum TestEventType {
  sessionStarted,
  sessionEnded,
  reconciliationStarted,
  reconciliationPaused,
  reconciliationResumed,
  reconciliationTimeout,
  reconciliationFinished,
  groupReserved,
  groupSkippedActive,
  groupReleased,
  groupFailed,
  claimGranted,
  claimRejected,
  claimReleased,
  recordingStarted,
  recordingStopped,
  recordingStartRequested,
  registrySnapshot,
  recordingSample,
  segmentVerified,
  segmentRenamed,
  segmentPending,
  pauseArmed,
  armedPauseReplaced,
  intentConsumed,
  pauseEntered,
  pauseReleased,
  pauseTimeout,
  pauseCancelled,
  armedPauseConsumeFailed,
  armedPauseExpired,
  processInterrupted,
  testRunContinued,
  error;

  /// Nombre de cable (`SESSION_STARTED`, …). Es el mismo que el
  /// `name` del enum Kotlin.
  String get wireName {
    const Map<TestEventType, String> names = <TestEventType, String>{
      TestEventType.sessionStarted: 'SESSION_STARTED',
      TestEventType.sessionEnded: 'SESSION_ENDED',
      TestEventType.reconciliationStarted: 'RECONCILIATION_STARTED',
      TestEventType.reconciliationPaused: 'RECONCILIATION_PAUSED',
      TestEventType.reconciliationResumed: 'RECONCILIATION_RESUMED',
      TestEventType.reconciliationTimeout: 'RECONCILIATION_TIMEOUT',
      TestEventType.reconciliationFinished: 'RECONCILIATION_FINISHED',
      TestEventType.groupReserved: 'GROUP_RESERVED',
      TestEventType.groupSkippedActive: 'GROUP_SKIPPED_ACTIVE',
      TestEventType.groupReleased: 'GROUP_RELEASED',
      TestEventType.groupFailed: 'GROUP_FAILED',
      TestEventType.claimGranted: 'CLAIM_GRANTED',
      TestEventType.claimRejected: 'CLAIM_REJECTED',
      TestEventType.claimReleased: 'CLAIM_RELEASED',
      TestEventType.recordingStarted: 'RECORDING_STARTED',
      TestEventType.recordingStopped: 'RECORDING_STOPPED',
      TestEventType.recordingStartRequested: 'RECORDING_START_REQUESTED',
      TestEventType.registrySnapshot: 'REGISTRY_SNAPSHOT',
      TestEventType.recordingSample: 'RECORDING_SAMPLE',
      TestEventType.segmentVerified: 'SEGMENT_VERIFIED',
      TestEventType.segmentRenamed: 'SEGMENT_RENAMED',
      TestEventType.segmentPending: 'SEGMENT_PENDING',
      TestEventType.pauseArmed: 'PAUSE_ARMED',
      TestEventType.armedPauseReplaced: 'ARMED_PAUSE_REPLACED',
      TestEventType.intentConsumed: 'INTENT_CONSUMED',
      TestEventType.pauseEntered: 'PAUSE_ENTERED',
      TestEventType.pauseReleased: 'PAUSE_RELEASED',
      TestEventType.pauseTimeout: 'PAUSE_TIMEOUT',
      TestEventType.pauseCancelled: 'PAUSE_CANCELLED',
      TestEventType.armedPauseConsumeFailed: 'ARMED_PAUSE_CONSUME_FAILED',
      TestEventType.armedPauseExpired: 'ARMED_PAUSE_EXPIRED',
      TestEventType.processInterrupted: 'PROCESS_INTERRUPTED',
      TestEventType.testRunContinued: 'TEST_RUN_CONTINUED',
      TestEventType.error: 'ERROR',
    };
    return names[this]!;
  }

  static TestEventType? fromWireName(String? wire) {
    if (wire == null) {
      return null;
    }
    for (final TestEventType type in TestEventType.values) {
      if (type.wireName == wire) {
        return type;
      }
    }
    return null;
  }
}

/// Un evento de instrumentación. `origin` es obligatorio y sin valor
/// por defecto en el lado nativo (Revisión 1 §3.3): lo inferido nunca
/// se presenta como observado y nunca aprueba un criterio.
class TestEvent {
  const TestEvent({
    required this.sessionId,
    required this.testRunId,
    this.recordingId,
    this.operation = '',
    required this.event,
    required this.monotonicMs,
    required this.wallMs,
    this.result = TestEventResult.ok,
    this.errorCode,
    this.durationMs,
    this.detail = const <String, Object?>{},
    this.origin = TestEventOrigin.observed,
    this.wasSuspended,
  });

  final String sessionId;
  final String testRunId;
  final String? recordingId;
  final String operation;
  final TestEventType event;
  final int monotonicMs;
  final int wallMs;
  final TestEventResult result;
  final String? errorCode;
  final int? durationMs;
  final Map<String, Object?> detail;
  final TestEventOrigin origin;
  final bool? wasSuspended;

  /// Reconstruye un evento desde su mapa JSON; `null` si está
  /// corrupto (espejo de `TestEvent.fromJsonMap` en Kotlin).
  static TestEvent? fromMap(Map<Object?, Object?> map) {
    final String? sessionId = map['sessionId'] as String?;
    final String? testRunId = map['testRunId'] as String?;
    final TestEventType? event = TestEventType.fromWireName(
      map['event'] as String?,
    );
    final int? monotonicMs = (map['monotonicMs'] as num?)?.toInt();
    final int? wallMs = (map['wallMs'] as num?)?.toInt();
    if (sessionId == null ||
        testRunId == null ||
        event == null ||
        monotonicMs == null ||
        wallMs == null) {
      return null;
    }
    final Object? rawDetail = map['detail'];
    return TestEvent(
      sessionId: sessionId,
      testRunId: testRunId,
      recordingId: map['recordingId'] as String?,
      operation: map['operation'] as String? ?? '',
      event: event,
      monotonicMs: monotonicMs,
      wallMs: wallMs,
      result: TestEventResult.fromWireName(map['result'] as String?),
      errorCode: map['errorCode'] as String?,
      durationMs: (map['durationMs'] as num?)?.toInt(),
      detail: rawDetail is Map
          ? Map<String, Object?>.from(rawDetail)
          : const <String, Object?>{},
      origin: TestEventOrigin.fromWireName(map['origin'] as String?),
      wasSuspended: map['wasSuspended'] as bool?,
    );
  }

  Map<String, Object?> toMap() {
    return <String, Object?>{
      'sessionId': sessionId,
      'testRunId': testRunId,
      'recordingId': recordingId,
      'operation': operation,
      'event': event.wireName,
      'monotonicMs': monotonicMs,
      'wallMs': wallMs,
      'result': result.wireName,
      'errorCode': errorCode,
      'durationMs': durationMs,
      'detail': detail,
      'origin': origin.wireName,
      if (wasSuspended != null) 'wasSuspended': wasSuspended,
    };
  }
}

/// Sesión de pruebas activa: un proceso (`sessionId`) dentro de una
/// campaña (`testRunId`, `run_<yyyyMMdd_HHmmss>_<4 hex>`).
class TestSession {
  const TestSession({
    required this.sessionId,
    required this.testRunId,
    this.interruptedPrevious = const <InterruptedSessionInfo>[],
    this.ended = false,
  });

  final String sessionId;
  final String testRunId;

  /// Sesiones anteriores sin `SESSION_ENDED` detectadas al arrancar.
  final List<InterruptedSessionInfo> interruptedPrevious;

  /// Solo en la respuesta de `endTestSession`.
  final bool ended;

  static TestSession fromMap(Map<Object?, Object?> map) {
    final List<Object?> rawInterrupted =
        (map['interruptedPrevious'] as List?)?.cast<Object?>() ??
        <Object?>[];
    return TestSession(
      sessionId: map['sessionId'] as String? ?? '',
      testRunId: map['testRunId'] as String? ?? '',
      interruptedPrevious: rawInterrupted
          .whereType<Map<Object?, Object?>>()
          .map(InterruptedSessionInfo.fromMap)
          .toList(),
      ended: map['ended'] as bool? ?? false,
    );
  }
}

/// Una sesión anterior sin cierre, con su `PROCESS_INTERRUPTED` ya
/// registrado.
class InterruptedSessionInfo {
  const InterruptedSessionInfo({
    required this.previousSessionId,
    this.previousTestRunId,
    this.lastObservedEvent,
    this.recordedInNewSession = false,
    this.recordReason,
  });

  final String previousSessionId;
  final String? previousTestRunId;
  final String? lastObservedEvent;
  final bool recordedInNewSession;
  final String? recordReason;

  static InterruptedSessionInfo fromMap(Map<Object?, Object?> map) {
    return InterruptedSessionInfo(
      previousSessionId: map['previousSessionId'] as String? ?? '',
      previousTestRunId: map['previousTestRunId'] as String?,
      lastObservedEvent: map['lastObservedEvent'] as String?,
      recordedInNewSession: map['recordedInNewSession'] as bool? ?? false,
      recordReason: map['recordReason'] as String?,
    );
  }
}

/// Una campaña: sesiones agrupadas por `testRunId`.
class TestRun {
  const TestRun({
    required this.testRunId,
    required this.sessionCount,
    required this.sessions,
  });

  final String testRunId;
  final int sessionCount;
  final List<TestSessionInfo> sessions;

  static TestRun fromMap(Map<Object?, Object?> map) {
    final List<Object?> rawSessions =
        (map['sessions'] as List?)?.cast<Object?>() ?? <Object?>[];
    return TestRun(
      testRunId: map['testRunId'] as String? ?? '',
      sessionCount: (map['sessionCount'] as num?)?.toInt() ?? 0,
      sessions: rawSessions
          .whereType<Map<Object?, Object?>>()
          .map(TestSessionInfo.fromMap)
          .toList(),
    );
  }
}

/// Metadatos de una sesión persistida (para la lista de la pantalla
/// de pruebas).
class TestSessionInfo {
  const TestSessionInfo({
    required this.sessionId,
    this.testRunId,
    this.startedAtWallMs,
    required this.sizeBytes,
    this.verdict,
  });

  final String sessionId;
  final String? testRunId;
  final int? startedAtWallMs;
  final int sizeBytes;

  /// Veredicto si se conoce; `null` si no.
  final String? verdict;

  static TestSessionInfo fromMap(Map<Object?, Object?> map) {
    return TestSessionInfo(
      sessionId: map['sessionId'] as String? ?? '',
      testRunId: map['testRunId'] as String?,
      startedAtWallMs: (map['startedAtWallMs'] as num?)?.toInt(),
      sizeBytes: (map['sizeBytes'] as num?)?.toInt() ?? 0,
      verdict: map['verdict'] as String?,
    );
  }
}

/// Estado de almacenamiento de pruebas (tope de 10 sesiones,
/// Revisión 1 §6).
class TestStorageState {
  const TestStorageState({
    required this.sessionCount,
    required this.limit,
    required this.full,
  });

  final int sessionCount;
  final int limit;
  final bool full;

  static TestStorageState fromMap(Map<Object?, Object?> map) {
    return TestStorageState(
      sessionCount: (map['sessionCount'] as num?)?.toInt() ?? 0,
      limit: (map['limit'] as num?)?.toInt() ?? TestContract.maxSessions,
      full: map['full'] as bool? ?? false,
    );
  }
}

/// Entrada de un intento bloqueado de iniciar sesión
/// (`blocked-starts.jsonl`).
class TestBlockedStart {
  const TestBlockedStart({
    required this.testRunId,
    required this.wallMs,
    required this.monotonicMs,
    required this.reason,
  });

  final String testRunId;
  final int wallMs;
  final int monotonicMs;
  final String reason;

  Map<String, Object?> toMap() {
    return <String, Object?>{
      'testRunId': testRunId,
      'wallMs': wallMs,
      'monotonicMs': monotonicMs,
      'reason': reason,
    };
  }
}

/// Intención de pausa persistida (`armed-pause.json`, versión 1;
/// Revisión 1 §2.3). De un solo uso y con caducidad de 10 minutos.
class ArmedPauseIntent {
  const ArmedPauseIntent({
    required this.version,
    required this.testRunId,
    required this.sessionId,
    required this.point,
    required this.armedAtWallMs,
    required this.armedAtMonotonicMs,
    required this.expiresAtWallMs,
  });

  final int version;
  final String testRunId;
  final String sessionId;
  final PausePoint point;
  final int armedAtWallMs;
  final int armedAtMonotonicMs;
  final int expiresAtWallMs;

  static ArmedPauseIntent fromMap(Map<Object?, Object?> map) {
    return ArmedPauseIntent(
      version: (map['version'] as num?)?.toInt() ?? 0,
      testRunId: map['testRunId'] as String? ?? '',
      sessionId: map['sessionId'] as String? ?? '',
      point: PausePoint.fromWireName(map['point'] as String?) ?? PausePoint.p1,
      armedAtWallMs: (map['armedAtWallMs'] as num?)?.toInt() ?? 0,
      armedAtMonotonicMs: (map['armedAtMonotonicMs'] as num?)?.toInt() ?? 0,
      expiresAtWallMs: (map['expiresAtWallMs'] as num?)?.toInt() ?? 0,
    );
  }
}

/// Resultado de exportar un informe (JSON + `.txt`).
class TestExportResult {
  const TestExportResult({
    required this.sessionId,
    required this.jsonPath,
    required this.txtPath,
    this.exported = true,
  });

  final String sessionId;
  final String jsonPath;
  final String txtPath;
  final bool exported;

  static TestExportResult fromMap(Map<Object?, Object?> map) {
    return TestExportResult(
      sessionId: map['sessionId'] as String? ?? '',
      jsonPath: map['jsonPath'] as String? ?? '',
      txtPath: map['txtPath'] as String? ?? '',
      exported: map['exported'] as bool? ?? true,
    );
  }
}

/// Tamaño del `.tmp` activo (`null` sin grabación activa).
class TestStagingSample {
  const TestStagingSample({this.tmpSizeBytes, required this.active});

  final int? tmpSizeBytes;
  final bool active;

  static TestStagingSample fromMap(Map<Object?, Object?> map) {
    return TestStagingSample(
      tmpSizeBytes: (map['tmpSizeBytes'] as num?)?.toInt(),
      active: map['active'] as bool? ?? false,
    );
  }
}

/// Una entrada viva del registro (sin token: solo diagnóstico).
class TestRegistryEntry {
  const TestRegistryEntry({
    required this.id,
    required this.owner,
    required this.acquiredAtMs,
    required this.ageMs,
  });

  final String id;
  final String owner;
  final int acquiredAtMs;
  final int ageMs;

  static TestRegistryEntry fromMap(Map<Object?, Object?> map) {
    return TestRegistryEntry(
      id: map['id'] as String? ?? '',
      owner: map['owner'] as String? ?? '',
      acquiredAtMs: (map['acquiredAtMs'] as num?)?.toInt() ?? 0,
      ageMs: (map['ageMs'] as num?)?.toInt() ?? 0,
    );
  }
}

/// Instantánea completa del registro: entradas vivas + contadores de
/// rechazo por motivo. Solo diagnóstico/observabilidad.
class TestRegistrySnapshot {
  const TestRegistrySnapshot({
    required this.available,
    this.reason,
    this.activeClaims = const <TestRegistryEntry>[],
    this.reservedGroups = const <TestRegistryEntry>[],
    this.claimRejectedActive = 0,
    this.claimRejectedReserved = 0,
    this.reserveRejectedReserved = 0,
    this.reserveRejectedActive = 0,
    this.foreignReleaseRejected = 0,
  });

  final bool available;
  final String? reason;
  final List<TestRegistryEntry> activeClaims;
  final List<TestRegistryEntry> reservedGroups;
  final int claimRejectedActive;
  final int claimRejectedReserved;
  final int reserveRejectedReserved;
  final int reserveRejectedActive;
  final int foreignReleaseRejected;

  static TestRegistrySnapshot fromMap(Map<Object?, Object?> map) {
    List<TestRegistryEntry> entries(String key) {
      final List<Object?> raw =
          (map[key] as List?)?.cast<Object?>() ?? <Object?>[];
      return raw
          .whereType<Map<Object?, Object?>>()
          .map(TestRegistryEntry.fromMap)
          .toList();
    }

    return TestRegistrySnapshot(
      available: map['available'] as bool? ?? false,
      reason: map['reason'] as String?,
      activeClaims: entries('activeClaims'),
      reservedGroups: entries('reservedGroups'),
      claimRejectedActive: (map['claimRejectedActive'] as num?)?.toInt() ?? 0,
      claimRejectedReserved:
          (map['claimRejectedReserved'] as num?)?.toInt() ?? 0,
      reserveRejectedReserved:
          (map['reserveRejectedReserved'] as num?)?.toInt() ?? 0,
      reserveRejectedActive:
          (map['reserveRejectedActive'] as num?)?.toInt() ?? 0,
      foreignReleaseRejected:
          (map['foreignReleaseRejected'] as num?)?.toInt() ?? 0,
    );
  }
}

// ------------------------------------------------------------------
// Veredicto e informe (espejo Dart de `TestReportBuilder` en Kotlin)
// ------------------------------------------------------------------

/// Veredicto de sesión/campaña (fail-closed: la ausencia de evidencia
/// nunca se lee como evidencia de ausencia).
enum TestVerdict {
  passed,
  failed,
  inconclusive,
  incomplete;

  String get wireName => name.toUpperCase();

  static TestVerdict fromWireName(String? wire) {
    for (final TestVerdict verdict in TestVerdict.values) {
      if (verdict.wireName == wire) {
        return verdict;
      }
    }
    return TestVerdict.incomplete;
  }
}

enum TestCriterionStatus {
  passed,
  failed,
  inconclusive,
  notEvaluable;

  String get wireName => switch (this) {
    TestCriterionStatus.passed => 'PASSED',
    TestCriterionStatus.failed => 'FAILED',
    TestCriterionStatus.inconclusive => 'INCONCLUSIVE',
    TestCriterionStatus.notEvaluable => 'NOT_EVALUABLE',
  };
}

class TestCriterion {
  const TestCriterion({
    required this.name,
    required this.status,
    this.evidence = const <String>[],
    this.note,
  });

  final String name;
  final TestCriterionStatus status;
  final List<String> evidence;
  final String? note;

  Map<String, Object?> toMap() {
    return <String, Object?>{
      'name': name,
      'status': status.wireName,
      'evidence': evidence,
      'note': note,
    };
  }
}

/// Motivos `INCOMPLETE` de la pausa (Revisión 2 §2.5 + Revisión 3
/// §2.5) y motivos de reserva del informe.
abstract final class TestIncompleteReasons {
  static const String intentNotConsumed = 'intent_not_consumed';
  static const String intentConsumedWithoutPause =
      'intent_consumed_without_pause';
  static const String intentOutcomeUnknown = 'intent_outcome_unknown';
  static const String pausedThenInterrupted = 'paused_then_interrupted';
  static const String pauseCancelled = 'pause_cancelled';
  static const String intentConsumeFailed = 'intent_consume_failed';
  static const String intentExpired = 'intent_expired';
  static const String storageFull = 'storage_full';
  static const String noPauseExecuted = 'no_pause_executed';
  static const String sessionInterrupted = 'session_interrupted';
  static const String noOverlap = 'no_overlap';
  static const String pauseTimeoutWithoutRecordingStart =
      'pause_timeout_without_recording_start';
}

/// Motivo `INCOMPLETE` por el patrón exacto de eventos (espejo de
/// `TestReportBuilder.incompleteReason` en Kotlin), o `null` si ningún
/// patrón aplica. `armedIntent` es la intención que sigue en disco al
/// construir el informe (`null` si ya no existe).
String? pauseConsumptionIncompleteReason(
  List<TestEvent> events, {
  ArmedPauseIntent? armedIntent,
  List<TestBlockedStart> blockedStarts = const <TestBlockedStart>[],
  String? testRunId,
}) {
  bool has(TestEventType type) =>
      events.any((TestEvent e) => e.event == type);
  if (events.isEmpty &&
      blockedStarts.any(
        (TestBlockedStart b) =>
            b.testRunId == testRunId &&
            b.reason == TestContract.testStorageFull,
      )) {
    return TestIncompleteReasons.storageFull;
  }
  // El fallo de consumo y la caducidad son más específicos que «sigue
  // armada»: se comprueban antes.
  if (has(TestEventType.armedPauseConsumeFailed)) {
    return TestIncompleteReasons.intentConsumeFailed;
  }
  if (has(TestEventType.armedPauseExpired)) {
    return TestIncompleteReasons.intentExpired;
  }
  if (armedIntent != null &&
      !has(TestEventType.intentConsumed) &&
      !has(TestEventType.pauseEntered)) {
    return TestIncompleteReasons.intentNotConsumed;
  }
  if (has(TestEventType.intentConsumed) && !has(TestEventType.pauseEntered)) {
    return TestIncompleteReasons.intentConsumedWithoutPause;
  }
  if (has(TestEventType.pauseArmed) &&
      armedIntent == null &&
      !has(TestEventType.intentConsumed) &&
      !has(TestEventType.armedPauseExpired) &&
      // Sin SESSION_ENDED: si la sesión terminó normalmente, la
      // intención pudo borrarse en el fin de sesión (disarm), así que
      // su ausencia no significa que el resultado sea desconocido.
      !has(TestEventType.sessionEnded)) {
    return TestIncompleteReasons.intentOutcomeUnknown;
  }
  if (has(TestEventType.pauseEntered) && has(TestEventType.pauseCancelled)) {
    return TestIncompleteReasons.pauseCancelled;
  }
  if (has(TestEventType.pauseEntered) &&
      has(TestEventType.processInterrupted) &&
      !has(TestEventType.pauseReleased) &&
      !has(TestEventType.pauseTimeout) &&
      !has(TestEventType.pauseCancelled)) {
    return TestIncompleteReasons.pausedThenInterrupted;
  }
  return null;
}

/// Banderas del incidente seg008 (diseño base §8; espejo de
/// `TestReportBuilder.detectSampleFlags` en Kotlin).
///
/// `DIVERGENCE_SUSPECTED`: dos muestras `RECORDING_SAMPLE`
/// consecutivas separadas ≥ 5 s sin avance de `segmentDurationMs` ni
/// del tamaño del `.tmp` con estado `recording`.
/// `STALLED_TMP`: dos muestras con el mismo tamaño separadas ≥ 10 s.
/// Son banderas para el análisis, no un veredicto (son inferidas).
List<String> detectSampleFlags(List<TestEvent> events) {
  final List<TestEvent> samples = events
      .where((TestEvent e) => e.event == TestEventType.recordingSample)
      .toList();
  final List<String> flags = <String>[];
  for (int i = 1; i < samples.length; i++) {
    final TestEvent prev = samples[i - 1];
    final TestEvent curr = samples[i];
    final int gap = curr.monotonicMs - prev.monotonicMs;
    final int? prevDuration =
        (prev.detail['segmentDurationMs'] as num?)?.toInt();
    final int? currDuration =
        (curr.detail['segmentDurationMs'] as num?)?.toInt();
    final int? prevSize = (prev.detail['tmpSizeBytes'] as num?)?.toInt();
    final int? currSize = (curr.detail['tmpSizeBytes'] as num?)?.toInt();
    final String? state = curr.detail['dartState'] as String?;
    if (gap >= 5000 &&
        prevDuration != null &&
        currDuration != null &&
        prevSize != null &&
        currSize != null &&
        currDuration <= prevDuration &&
        currSize <= prevSize &&
        state == 'recording') {
      if (!flags.contains('DIVERGENCE_SUSPECTED')) {
        flags.add('DIVERGENCE_SUSPECTED');
      }
    }
    if (gap >= 10000 && prevSize != null && currSize != null && currSize == prevSize) {
      if (!flags.contains('STALLED_TMP')) {
        flags.add('STALLED_TMP');
      }
    }
  }
  return flags;
}

/// Evaluación completa de una sesión (veredicto + motivo +
/// criterios + banderas), sin resumen de reconciliación: C1/C2
/// quedan `not_evaluable` (el constructor no inventa resúmenes).
class TestReportEvaluation {
  const TestReportEvaluation({
    required this.verdict,
    required this.reason,
    required this.criteria,
    required this.flags,
  });

  final TestVerdict verdict;
  final String? reason;
  final List<TestCriterion> criteria;
  final List<String> flags;
}

TestReportEvaluation evaluateTestReport(
  List<TestEvent> events, {
  ArmedPauseIntent? armedIntent,
  List<TestBlockedStart> blockedStarts = const <TestBlockedStart>[],
  String? testRunId,
}) {
  const List<TestCriterion> criteria = <TestCriterion>[
    TestCriterion(
      name: 'C1',
      status: TestCriterionStatus.notEvaluable,
      note: 'Sin resumen de reconciliación para evaluar.',
    ),
    TestCriterion(
      name: 'C2',
      status: TestCriterionStatus.notEvaluable,
      note: 'Sin resumen de reconciliación para evaluar.',
    ),
  ];
  final String? incomplete = pauseConsumptionIncompleteReason(
    events,
    armedIntent: armedIntent,
    blockedStarts: blockedStarts,
    testRunId: testRunId,
  );
  final TestVerdict verdict;
  final String? reason;
  if (incomplete != null) {
    verdict = TestVerdict.incomplete;
    reason = incomplete;
  } else if (criteria.any(
    (TestCriterion c) => c.status == TestCriterionStatus.failed,
  )) {
    verdict = TestVerdict.failed;
    reason = null;
  } else {
    final bool pauseEntered = events.any(
      (TestEvent e) => e.event == TestEventType.pauseEntered,
    );
    if (!pauseEntered) {
      verdict = TestVerdict.incomplete;
      reason = TestIncompleteReasons.noPauseExecuted;
    } else if (criteria.any(
          (TestCriterion c) => c.status == TestCriterionStatus.passed,
        ) &&
        criteria.every(
          (TestCriterion c) =>
              c.status != TestCriterionStatus.notEvaluable &&
              c.status != TestCriterionStatus.inconclusive,
        )) {
      verdict = TestVerdict.passed;
      reason = null;
    } else {
      verdict = TestVerdict.inconclusive;
      final bool timeout = events.any(
        (TestEvent e) => e.event == TestEventType.pauseTimeout,
      );
      final bool started = events.any(
        (TestEvent e) => e.event == TestEventType.recordingStarted,
      );
      if (timeout && !started) {
        reason = TestIncompleteReasons.pauseTimeoutWithoutRecordingStart;
      } else if (!pauseEntered) {
        reason = TestIncompleteReasons.noPauseExecuted;
      } else {
        reason = TestIncompleteReasons.noOverlap;
      }
    }
  }
  return TestReportEvaluation(
    verdict: verdict,
    reason: reason,
    criteria: criteria,
    flags: detectSampleFlags(events),
  );
}

/// Informe de una sesión (JSON + resumen `.txt`), espejo de
/// `TestReportBuilder.assemble`/`buildTextReport` en Kotlin: mismas
/// claves JSON y mismo formato de texto, para que el JSON y el `.txt`
/// nunca discrepen (prueba 2d).
class TestReport {
  TestReport({
    required this.sessionId,
    required this.testRunId,
    required this.events,
    this.appVersion = 'unknown',
    this.armedIntent,
    this.blockedStarts = const <TestBlockedStart>[],
  }) : evaluation = evaluateTestReport(
         events,
         armedIntent: armedIntent,
         blockedStarts: blockedStarts,
         testRunId: testRunId,
       ),
       recordings = events
           .map((TestEvent e) => e.recordingId)
           .whereType<String>()
           .toSet()
           .toList();

  final String? sessionId;
  final String? testRunId;
  final List<TestEvent> events;
  final String appVersion;
  final ArmedPauseIntent? armedIntent;
  final List<TestBlockedStart> blockedStarts;
  final TestReportEvaluation evaluation;
  final List<String> recordings;

  TestVerdict get verdict => evaluation.verdict;
  String? get reason => evaluation.reason;
  List<TestCriterion> get criteria => evaluation.criteria;
  List<String> get flags => evaluation.flags;

  Map<String, Object?> toJsonMap() {
    final int? startedAt = events.isEmpty
        ? null
        : events.map((TestEvent e) => e.wallMs).reduce((a, b) => a < b ? a : b);
    final Iterable<TestEvent> ended = events.where(
      (TestEvent e) => e.event == TestEventType.sessionEnded,
    );
    final int? endedAt = ended.isEmpty
        ? null
        : ended.map((TestEvent e) => e.wallMs).reduce((a, b) => a > b ? a : b);
    final List<String> errors = events
        .where(
          (TestEvent e) =>
              e.result == TestEventResult.failed ||
              e.event == TestEventType.error,
        )
        .map((TestEvent e) => e.errorCode ?? e.event.wireName)
        .toSet()
        .toList();
    final List<String> warnings = <String>[
      if (events.any(
        (TestEvent e) => e.origin == TestEventOrigin.inferred,
      ))
        'contiene eventos inferidos (marca «inferido»): nunca aprueban',
    ];
    return <String, Object?>{
      'sessionId': sessionId,
      'testRunId': testRunId,
      'startedAtWallMs': startedAt,
      'endedAtWallMs': endedAt,
      'appVersion': appVersion,
      'testMode': true,
      'verdict': verdict.wireName,
      'reason': reason,
      'criteria': criteria.map((TestCriterion c) => c.toMap()).toList(),
      'flags': flags,
      'events': events.map((TestEvent e) => e.toMap()).toList(),
      'recordings': recordings,
      'errors': errors,
      'warnings': warnings,
      'blockedStarts': blockedStarts.map((b) => b.toMap()).toList(),
    };
  }

  String toJson() => jsonEncode(toJsonMap());

  String toText() {
    final StringBuffer out = StringBuffer();
    out.writeln('INFORME DE PRUEBAS TRANSMITI2');
    out.writeln('Sesión: ${sessionId ?? '-'}');
    out.writeln('Campaña (testRunId): ${testRunId ?? '-'}');
    out.writeln('VEREDICTO: ${verdict.wireName}');
    if (reason != null) {
      out.writeln('Motivo: $reason');
    }
    out.writeln();
    out.writeln('Criterios:');
    if (criteria.isEmpty) {
      out.writeln('- (sin criterios evaluados)');
    } else {
      for (final TestCriterion c in criteria) {
        out.writeln(
          '- ${c.name}: ${c.status.wireName}${c.note == null ? '' : ' — ${c.note}'}',
        );
        for (final String line in c.evidence) {
          out.writeln('    evidencia: $line');
        }
      }
    }
    if (flags.isNotEmpty) {
      out.writeln();
      out.writeln(
        'Banderas: ${flags.join(', ')} (inferido: solo para análisis)',
      );
    }
    final List<String> errors = events
        .where(
          (TestEvent e) =>
              e.result == TestEventResult.failed ||
              e.event == TestEventType.error,
        )
        .map((TestEvent e) => e.errorCode ?? e.event.wireName)
        .toSet()
        .toList();
    if (errors.isNotEmpty) {
      out.writeln();
      out.writeln('Errores: ${errors.join(', ')}');
    }
    if (events.any(
      (TestEvent e) => e.origin == TestEventOrigin.inferred,
    )) {
      out.writeln();
      out.writeln(
        'Advertencia: contiene eventos inferidos (marca «inferido»): nunca aprueban',
      );
    }
    if (blockedStarts.isNotEmpty) {
      out.writeln();
      out.writeln('Inicios bloqueados: ${blockedStarts.length}');
      for (final TestBlockedStart b in blockedStarts) {
        out.writeln('- ${b.testRunId} (${b.reason})');
      }
    }
    out.writeln();
    out.writeln(
      'Grabaciones: ${recordings.isEmpty ? '-' : recordings.join(', ')}',
    );
    out.writeln();
    out.writeln('Cronología:');
    for (final TestEvent e in events) {
      final String inferred = e.origin == TestEventOrigin.inferred
          ? ' [inferido]'
          : '';
      final String rec = e.recordingId == null ? '' : ' ${e.recordingId}';
      out.writeln(
        '${_formatWall(e.wallMs)} ${e.event.wireName}$rec ${e.result.wireName}$inferred',
      );
    }
    return out.toString();
  }

  static String _formatWall(int wallMs) {
    final DateTime d = DateTime.fromMillisecondsSinceEpoch(
      wallMs,
    ).toLocal();
    String two(int n) => n.toString().padLeft(2, '0');
    return '${d.year}-${two(d.month)}-${two(d.day)} '
        '${two(d.hour)}:${two(d.minute)}:${two(d.second)}';
  }
}

/// Exportador de informes inyectable (Revisión 2 §3.1): en el
/// harness exporta a un directorio; en el dispositivo, el adaptador
/// MediaStore es el mismo informe ya verificado (la exportación
/// nativa vive en el canal `exportTestReport`). La verificación
/// física de MediaStore queda para FASE 3/4.
abstract class TestReportExporter {
  Future<TestExportResult> exportReport(TestReport report);
}

/// Exporta el informe a un directorio (`exportRoot`, inyectado e
/// independiente de la raíz de datos; Revisión 3 §3.2): escribe solo
/// ahí, nunca dentro del árbol de datos.
class DirectoryTestReportExporter implements TestReportExporter {
  DirectoryTestReportExporter(this.exportRoot);

  final Directory exportRoot;

  @override
  Future<TestExportResult> exportReport(TestReport report) async {
    final String sessionId = report.sessionId ?? 'sin-sesion';
    await exportRoot.create(recursive: true);
    final File jsonFile = File(
      '${exportRoot.path}/$sessionId-report.json',
    );
    final File txtFile = File('${exportRoot.path}/$sessionId-report.txt');
    await jsonFile.writeAsString(report.toJson(), encoding: utf8);
    await txtFile.writeAsString(report.toText(), encoding: utf8);
    return TestExportResult(
      sessionId: sessionId,
      jsonPath: jsonFile.path,
      txtPath: txtFile.path,
    );
  }
}
