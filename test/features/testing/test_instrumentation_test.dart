import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';
import 'package:transmiti2/features/testing/application/test_controller.dart';
import 'package:transmiti2/features/testing/domain/test_contract.dart';

import '../../support/fake_streaming.dart';
import '../../support/fake_testing.dart';

TestEvent _sample({
  required int monotonicMs,
  required int segmentDurationMs,
  required int tmpSizeBytes,
  String dartState = 'recording',
}) {
  return TestEvent(
    sessionId: 'sess_1',
    testRunId: 'run_1',
    recordingId: 'rec_20261123_120000_ab12',
    event: TestEventType.recordingSample,
    monotonicMs: monotonicMs,
    wallMs: 1764000000000 + monotonicMs,
    detail: <String, Object?>{
      'segmentDurationMs': segmentDurationMs,
      'tmpSizeBytes': tmpSizeBytes,
      'dartState': dartState,
    },
  );
}

TestController _controller(
  FakeTestService service,
  FakeStreamingService streaming, {
  Duration sampleInterval = const Duration(seconds: 5),
}) {
  return TestController(
    testService: service,
    streamingService: streaming,
    testMode: service.testMode,
    sampleInterval: sampleInterval,
  );
}

void main() {
  group('contrato del canal de pruebas (espejo de TestChannelHandler)', () {
    test('el canal y los nombres de método son estables', () {
      expect(
        TestContract.methodChannel,
        'com.transmiti2.transmiti2/streaming/testing',
      );
      expect(TestContract.getTestMode, 'getTestMode');
      expect(TestContract.startTestRun, 'startTestRun');
      expect(TestContract.startTestSession, 'startTestSession');
      expect(TestContract.endTestSession, 'endTestSession');
      expect(TestContract.getTestEvents, 'getTestEvents');
      expect(TestContract.getTestRuns, 'getTestRuns');
      expect(TestContract.getTestSessions, 'getTestSessions');
      expect(TestContract.getStorageState, 'getStorageState');
      expect(TestContract.deleteTestSession, 'deleteTestSession');
      expect(TestContract.exportTestReport, 'exportTestReport');
      expect(TestContract.exportTestSessions, 'exportTestSessions');
      expect(TestContract.armReconciliationPause, 'armReconciliationPause');
      expect(TestContract.resumeReconciliation, 'resumeReconciliation');
      expect(TestContract.getPauseState, 'getPauseState');
      expect(TestContract.getRegistrySnapshot, 'getRegistrySnapshot');
      expect(TestContract.sampleActiveStagingSize, 'sampleActiveStagingSize');
    });

    test('los códigos de error son distintos', () {
      final List<String> codes = <String>[
        TestContract.testModeUnavailable,
        TestContract.testStorageFull,
        TestContract.testNoActiveSession,
        TestContract.confirmationRequired,
        TestContract.testPathOutsideRoot,
        TestContract.sessionNotFound,
        TestContract.testNotAvailable,
        TestContract.testError,
      ];
      expect(codes.toSet().length, codes.length);
      expect(TestContract.testModeUnavailable, 'test_mode_unavailable');
      expect(TestContract.testStorageFull, 'test_storage_full');
      expect(TestContract.confirmationRequired, 'confirmation_required');
    });

    test('los tipos de evento usan los nombres de cable Kotlin', () {
      expect(TestEventType.sessionStarted.wireName, 'SESSION_STARTED');
      expect(TestEventType.recordingSample.wireName, 'RECORDING_SAMPLE');
      expect(
        TestEventType.processInterrupted.wireName,
        'PROCESS_INTERRUPTED',
      );
      expect(
        TestEventType.recordingStartRequested.wireName,
        'RECORDING_START_REQUESTED',
      );
      expect(TestEventType.fromWireName('PAUSE_ENTERED'), TestEventType.pauseEntered);
      expect(TestEventType.fromWireName('DESCONOCIDO'), isNull);
    });

    test('TestEvent.fromMap reconstruye el evento y descarta el corrupto', () {
      final TestEvent? event = TestEvent.fromMap(<Object?, Object?>{
        'sessionId': 'sess_1',
        'testRunId': 'run_1',
        'recordingId': 'rec_1',
        'operation': '',
        'event': 'RECORDING_STARTED',
        'monotonicMs': 1000,
        'wallMs': 1764000001000,
        'result': 'ok',
        'errorCode': null,
        'durationMs': null,
        'detail': <Object?, Object?>{'point': 'P1'},
        'origin': 'inferred',
      });
      expect(event, isNotNull);
      expect(event!.recordingId, 'rec_1');
      expect(event.event, TestEventType.recordingStarted);
      expect(event.origin, TestEventOrigin.inferred);
      expect(event.detail['point'], 'P1');
      expect(event.toMap()['event'], 'RECORDING_STARTED');
      // Sin sessionId o con tipo desconocido: corrupto.
      expect(
        TestEvent.fromMap(<Object?, Object?>{'testRunId': 'run_1'}),
        isNull,
      );
      expect(
        TestEvent.fromMap(<Object?, Object?>{
          'sessionId': 'sess_1',
          'testRunId': 'run_1',
          'event': 'NO_EXISTE',
          'monotonicMs': 1,
          'wallMs': 1,
        }),
        isNull,
      );
    });
  });

  group('test nº 1 (parte Dart): instrumentación desactivada', () {
    test('disabled controller records no events and exposes no test entry', () async {
      final FakeTestService service = FakeTestService(testMode: false);
      final TestController controller = _controller(
        service,
        FakeStreamingService(),
      );
      controller.attach();
      await controller.refreshAll();
      expect(controller.testMode, isFalse);
      expect(controller.events, isEmpty);
      expect(controller.sessions, isEmpty);
      expect(controller.samplerActive, isFalse);
      await expectLater(
        controller.startSession(),
        throwsA(
          isA<TestException>().having(
            (TestException e) => e.code,
            'code',
            TestContract.testModeUnavailable,
          ),
        ),
      );
      expect(service.startTestSessionCalls, 0);
      expect(controller.events, isEmpty);
      controller.detach();
      controller.dispose();
    });
  });

  group('test nº 3 (parte Dart): identificadores', () {
    test('events carry the right recordingId', () async {
      final FakeTestService service = FakeTestService();
      final TestController controller = _controller(
        service,
        FakeStreamingService(),
      );
      final TestSession session = await controller.startSession();
      service.events.addAll(<TestEvent>[
        TestEvent(
          sessionId: session.sessionId,
          testRunId: session.testRunId,
          recordingId: 'rec_20261123_120000_aaaa',
          event: TestEventType.recordingStarted,
          monotonicMs: 1000,
          wallMs: 1764000001000,
        ),
        TestEvent(
          sessionId: session.sessionId,
          testRunId: session.testRunId,
          recordingId: 'rec_20261123_120000_bbbb',
          event: TestEventType.recordingStarted,
          monotonicMs: 2000,
          wallMs: 1764000002000,
        ),
      ]);
      await controller.refreshEvents();
      expect(controller.events.length, 3); // SESSION_STARTED + 2
      expect(
        controller.events.map((TestEvent e) => e.recordingId).toList(),
        containsAll(<String?>[
          'rec_20261123_120000_aaaa',
          'rec_20261123_120000_bbbb',
        ]),
      );
      // Ningún evento lleva el recordingId de otra grabación.
      expect(
        controller.events
            .where(
              (TestEvent e) => e.recordingId == 'rec_20261123_120000_cccc',
            )
            .toList(),
        isEmpty,
      );
      controller.dispose();
    });

    test('twoSessionsNeverShareEvents', () async {
      final FakeTestService service = FakeTestService();
      final TestController controller = _controller(
        service,
        FakeStreamingService(),
      );
      final TestSession first = await controller.startSession();
      service.events.add(
        TestEvent(
          sessionId: first.sessionId,
          testRunId: first.testRunId,
          recordingId: 'rec_primera',
          event: TestEventType.recordingStarted,
          monotonicMs: 1000,
          wallMs: 1764000001000,
        ),
      );
      await controller.endSession();
      final TestSession second = await controller.startSession();
      expect(second.sessionId, isNot(first.sessionId));
      service.events.add(
        TestEvent(
          sessionId: second.sessionId,
          testRunId: second.testRunId,
          recordingId: 'rec_segunda',
          event: TestEventType.recordingStarted,
          monotonicMs: 2000,
          wallMs: 1764000002000,
        ),
      );
      final List<TestEvent> firstEvents = await service.getTestEvents(
        sessionId: first.sessionId,
      );
      final List<TestEvent> secondEvents = await service.getTestEvents(
        sessionId: second.sessionId,
      );
      expect(
        firstEvents.map((TestEvent e) => e.recordingId),
        contains('rec_primera'),
      );
      expect(
        firstEvents.map((TestEvent e) => e.recordingId),
        isNot(contains('rec_segunda')),
      );
      expect(
        secondEvents.map((TestEvent e) => e.recordingId),
        contains('rec_segunda'),
      );
      expect(
        secondEvents.map((TestEvent e) => e.recordingId),
        isNot(contains('rec_primera')),
      );
      controller.dispose();
    });
  });

  group('test nº 9 (parte Dart): persistencia', () {
    test('persisted sessions list reads back', () async {
      final FakeTestService service = FakeTestService();
      final TestController controller = _controller(
        service,
        FakeStreamingService(),
      );
      final TestSession first = await controller.startSession();
      await controller.endSession();
      final TestSession second = await controller.continueCampaign(
        first.testRunId,
      );
      await controller.endSession();
      expect(second.testRunId, first.testRunId);
      expect(second.sessionId, isNot(first.sessionId));
      await controller.refreshSessions();
      await controller.refreshRuns();
      expect(controller.sessions.length, 2);
      expect(
        controller.sessions.map((TestSessionInfo s) => s.sessionId),
        containsAll(<String>[first.sessionId, second.sessionId]),
      );
      // Una campaña con dos sesiones (continuación, Revisión 1 §2.6).
      expect(controller.runs.length, 1);
      expect(controller.runs.first.testRunId, first.testRunId);
      expect(controller.runs.first.sessionCount, 2);
      controller.dispose();
    });
  });

  group('muestreador (diseño base §8)', () {
    test('sampler runs only with an active session and recording', () async {
      final FakeTestService service = FakeTestService();
      final FakeStreamingService streaming = FakeStreamingService();
      final TestController controller = _controller(
        service,
        streaming,
        sampleInterval: const Duration(milliseconds: 50),
      );
      controller.attach();
      // Grabación sin sesión: no muestrea.
      streaming.recordingStatus = const RecordingStatusSnapshot(
        state: 'recording',
        recordingId: 'rec_20261123_120000_ab12',
        segmentDurationMs: 1000,
      );
      streaming.emitEvent(<String, Object?>{
        'type': StreamContract.eventRecordingStarted,
        'recordingId': 'rec_20261123_120000_ab12',
      });
      await Future<void>.delayed(const Duration(milliseconds: 150));
      expect(controller.samplerActive, isFalse);
      expect(controller.samples, isEmpty);

      // Sesión activa + grabación: muestrea cada 50 ms.
      await controller.startSession();
      streaming.emitEvent(<String, Object?>{
        'type': StreamContract.eventRecordingStarted,
        'recordingId': 'rec_20261123_120000_ab12',
      });
      await Future<void>.delayed(const Duration(milliseconds: 200));
      expect(controller.samplerActive, isTrue);
      expect(controller.samples, isNotEmpty);
      expect(
        controller.samples.first.event,
        TestEventType.recordingSample,
      );
      expect(
        controller.samples.first.detail['dartState'],
        'recording',
      );

      // Fin de sesión: el muestreador se para.
      await controller.endSession();
      expect(controller.samplerActive, isFalse);
      final int count = controller.samples.length;
      await Future<void>.delayed(const Duration(milliseconds: 150));
      expect(controller.samples.length, count);
      controller.detach();
      controller.dispose();
    });

    test('divergenceFlaggedWhenNativeStalls', () {
      // Contador Dart avanzando, nativo parado: dos muestras con
      // ≥ 5 s sin avance de duración ni de tamaño del `.tmp`.
      final List<String> flags = detectSampleFlags(<TestEvent>[
        _sample(monotonicMs: 0, segmentDurationMs: 1000, tmpSizeBytes: 1000),
        _sample(
          monotonicMs: 5000,
          segmentDurationMs: 1000,
          tmpSizeBytes: 1000,
        ),
      ]);
      expect(flags, contains('DIVERGENCE_SUSPECTED'));
      expect(flags, isNot(contains('STALLED_TMP')));
    });

    test('stalled tmp is flagged after 10 s without growth', () {
      final List<String> flags = detectSampleFlags(<TestEvent>[
        _sample(monotonicMs: 0, segmentDurationMs: 1000, tmpSizeBytes: 5000),
        _sample(
          monotonicMs: 5000,
          segmentDurationMs: 2000,
          tmpSizeBytes: 5000,
        ),
        _sample(
          monotonicMs: 15000,
          segmentDurationMs: 3000,
          tmpSizeBytes: 5000,
        ),
      ]);
      // La duración avanza: no hay divergencia; el tamaño no crece
      // en 10 s: STALLED_TMP.
      expect(flags, isNot(contains('DIVERGENCE_SUSPECTED')));
      expect(flags, contains('STALLED_TMP'));
    });

    test('advancing samples raise no flags', () {
      final List<String> flags = detectSampleFlags(<TestEvent>[
        _sample(monotonicMs: 0, segmentDurationMs: 1000, tmpSizeBytes: 1000),
        _sample(
          monotonicMs: 5000,
          segmentDurationMs: 6000,
          tmpSizeBytes: 2000,
        ),
        _sample(
          monotonicMs: 10000,
          segmentDurationMs: 11000,
          tmpSizeBytes: 3000,
        ),
      ]);
      expect(flags, isEmpty);
    });
  });

  group('campaña y almacenamiento', () {
    test('startNewCampaign generates a fresh testRunId', () async {
      final FakeTestService service = FakeTestService();
      final TestController controller = _controller(
        service,
        FakeStreamingService(),
      );
      final TestSession session = await controller.startNewCampaign();
      expect(session.testRunId, startsWith('run_'));
      expect(controller.activeTestRunId, session.testRunId);
      controller.dispose();
    });

    test('eleventh start is rejected with test_storage_full', () async {
      final FakeTestService service = FakeTestService();
      service.fillToLimit();
      final TestController controller = _controller(
        service,
        FakeStreamingService(),
      );
      await controller.refreshStorageState();
      expect(controller.storageState.full, isTrue);
      expect(controller.storageState.sessionCount, 10);
      await expectLater(
        controller.startSession(),
        throwsA(
          isA<TestException>().having(
            (TestException e) => e.code,
            'code',
            TestContract.testStorageFull,
          ),
        ),
      );
      controller.dispose();
    });

    test('delete without confirmation throws and calls nothing', () async {
      final FakeTestService service = FakeTestService();
      final TestController controller = _controller(
        service,
        FakeStreamingService(),
      );
      final TestSession session = await controller.startSession();
      await expectLater(
        controller.deleteSession(session.sessionId, confirmed: false),
        throwsA(
          isA<TestException>().having(
            (TestException e) => e.code,
            'code',
            TestContract.confirmationRequired,
          ),
        ),
      );
      expect(service.deleteCalls, isEmpty);
      await controller.refreshSessions();
      expect(controller.sessions.length, 1);
      controller.dispose();
    });
  });
}
