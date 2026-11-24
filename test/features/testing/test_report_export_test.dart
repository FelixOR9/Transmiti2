import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/testing/domain/test_contract.dart';

TestEvent _event(
  TestEventType type, {
  int monotonicMs = 0,
  String? recordingId,
  TestEventOrigin origin = TestEventOrigin.observed,
  Map<String, Object?> detail = const <String, Object?>{},
}) {
  return TestEvent(
    sessionId: 'sess_20261123_120000_01',
    testRunId: 'run_20261123_120000_ab12',
    recordingId: recordingId,
    event: type,
    monotonicMs: monotonicMs,
    wallMs: 1764000000000 + monotonicMs,
    origin: origin,
    detail: detail,
  );
}

void main() {
  group('test nº 10 (parte Dart): exportación', () {
    test('reportIsValidJsonWithVerdict', () {
      final TestReport report = TestReport(
        sessionId: 'sess_20261123_120000_01',
        testRunId: 'run_20261123_120000_ab12',
        events: <TestEvent>[
          _event(TestEventType.sessionStarted, monotonicMs: 0),
          _event(
            TestEventType.recordingStarted,
            monotonicMs: 1000,
            recordingId: 'rec_20261123_120000_ab12',
          ),
          _event(
            TestEventType.recordingStopped,
            monotonicMs: 11000,
            recordingId: 'rec_20261123_120000_ab12',
          ),
          _event(TestEventType.sessionEnded, monotonicMs: 12000),
        ],
      );
      final Map<String, Object?> decoded =
          jsonDecode(report.toJson()) as Map<String, Object?>;
      expect(decoded['sessionId'], 'sess_20261123_120000_01');
      expect(decoded['testRunId'], 'run_20261123_120000_ab12');
      expect(decoded['verdict'], isNotNull);
      expect(decoded['events'], isA<List>());
      expect((decoded['events'] as List).length, 4);
      expect(decoded['criteria'], isA<List>());
      expect(decoded['recordings'], contains('rec_20261123_120000_ab12'));
      // El resumen `.txt` contiene sesión, veredicto y una línea por
      // criterio.
      final String text = report.toText();
      expect(text, contains('sess_20261123_120000_01'));
      expect(text, contains('VEREDICTO: ${report.verdict.wireName}'));
      expect(text, contains('- C1:'));
      expect(text, contains('- C2:'));
    });

    test('inconclusiveIsMarkedExplicitly', () {
      // Pausa ejecutada pero sin solapamiento: INCONCLUSIVE con
      // motivo, nunca aprobado.
      final TestReport report = TestReport(
        sessionId: 'sess_20261123_120000_01',
        testRunId: 'run_20261123_120000_ab12',
        events: <TestEvent>[
          _event(TestEventType.sessionStarted, monotonicMs: 0),
          _event(
            TestEventType.pauseEntered,
            monotonicMs: 1000,
            detail: <String, Object?>{
              'point': 'P1',
              'runId': 'run-1',
            },
          ),
          _event(
            TestEventType.pauseReleased,
            monotonicMs: 2000,
            detail: <String, Object?>{'point': 'P1'},
          ),
          _event(TestEventType.sessionEnded, monotonicMs: 3000),
        ],
      );
      expect(report.verdict, TestVerdict.inconclusive);
      expect(report.reason, TestIncompleteReasons.noOverlap);
      expect(report.toText(), contains('VEREDICTO: INCONCLUSIVE'));
      expect(report.toText(), contains('Motivo: no_overlap'));
    });
  });

  group('prueba 1g: pauseConsumptionVerdicts', () {
    test('[INTENT_CONSUMED, sin PAUSE_ENTERED] → INCOMPLETE + '
        'intent_consumed_without_pause', () {
      final TestReport report = TestReport(
        sessionId: 'sess_20261123_120000_01',
        testRunId: 'run_20261123_120000_ab12',
        events: <TestEvent>[
          _event(TestEventType.sessionStarted, monotonicMs: 0),
          _event(TestEventType.pauseArmed, monotonicMs: 100),
          _event(TestEventType.intentConsumed, monotonicMs: 200),
          _event(TestEventType.sessionEnded, monotonicMs: 300),
        ],
      );
      expect(report.verdict, TestVerdict.incomplete);
      expect(
        report.reason,
        TestIncompleteReasons.intentConsumedWithoutPause,
      );
    });

    test('[PAUSE_ENTERED, PAUSE_TIMEOUT, RECORDING_STARTED posterior al '
        'timeout] → INCONCLUSIVE', () {
      final TestReport report = TestReport(
        sessionId: 'sess_20261123_120000_01',
        testRunId: 'run_20261123_120000_ab12',
        events: <TestEvent>[
          _event(TestEventType.sessionStarted, monotonicMs: 0),
          _event(
            TestEventType.pauseEntered,
            monotonicMs: 1000,
            detail: <String, Object?>{
              'point': 'P1',
              'runId': 'run-1',
            },
          ),
          _event(
            TestEventType.pauseTimeout,
            monotonicMs: 2000,
            detail: <String, Object?>{'point': 'P1'},
          ),
          _event(
            TestEventType.recordingStarted,
            monotonicMs: 3000,
            recordingId: 'rec_20261123_120000_ab12',
          ),
          _event(TestEventType.sessionEnded, monotonicMs: 4000),
        ],
      );
      expect(report.verdict, TestVerdict.inconclusive);
      // La grabación empezó, pero fuera de la ventana de pausa: sin
      // solapamiento (no aprobado).
      expect(report.reason, TestIncompleteReasons.noOverlap);
    });

    test('[PAUSE_ENTERED, PAUSE_CANCELLED] → INCOMPLETE + '
        'pause_cancelled', () {
      final TestReport report = TestReport(
        sessionId: 'sess_20261123_120000_01',
        testRunId: 'run_20261123_120000_ab12',
        events: <TestEvent>[
          _event(TestEventType.sessionStarted, monotonicMs: 0),
          _event(
            TestEventType.pauseEntered,
            monotonicMs: 1000,
            detail: <String, Object?>{
              'point': 'P1',
              'runId': 'run-1',
            },
          ),
          _event(TestEventType.pauseCancelled, monotonicMs: 2000),
          _event(TestEventType.sessionEnded, monotonicMs: 3000),
        ],
      );
      expect(report.verdict, TestVerdict.incomplete);
      expect(report.reason, TestIncompleteReasons.pauseCancelled);
    });

    test('intención que sigue armada → INCOMPLETE + intent_not_consumed',
        () {
      const ArmedPauseIntent intent = ArmedPauseIntent(
        version: 1,
        testRunId: 'run_20261123_120000_ab12',
        sessionId: 'sess_20261123_120000_01',
        point: PausePoint.p1,
        armedAtWallMs: 1764000000000,
        armedAtMonotonicMs: 1000,
        expiresAtWallMs: 1764000600000,
      );
      final TestReport report = TestReport(
        sessionId: 'sess_20261123_120000_01',
        testRunId: 'run_20261123_120000_ab12',
        armedIntent: intent,
        events: <TestEvent>[
          _event(TestEventType.sessionStarted, monotonicMs: 0),
          _event(TestEventType.pauseArmed, monotonicMs: 100),
        ],
      );
      expect(report.verdict, TestVerdict.incomplete);
      expect(report.reason, TestIncompleteReasons.intentNotConsumed);
    });

    test('PAUSE_ARMED sin intención ni consumo ni cierre → INCOMPLETE + '
        'intent_outcome_unknown', () {
      final TestReport report = TestReport(
        sessionId: 'sess_20261123_120000_01',
        testRunId: 'run_20261123_120000_ab12',
        events: <TestEvent>[
          _event(TestEventType.sessionStarted, monotonicMs: 0),
          _event(TestEventType.pauseArmed, monotonicMs: 100),
        ],
      );
      expect(report.verdict, TestVerdict.incomplete);
      expect(report.reason, TestIncompleteReasons.intentOutcomeUnknown);
    });

    test('PAUSE_ENTERED + PROCESS_INTERRUPTED sin liberación → '
        'INCOMPLETE + paused_then_interrupted', () {
      final TestReport report = TestReport(
        sessionId: 'sess_20261123_120000_01',
        testRunId: 'run_20261123_120000_ab12',
        events: <TestEvent>[
          _event(TestEventType.sessionStarted, monotonicMs: 0),
          _event(
            TestEventType.pauseEntered,
            monotonicMs: 1000,
            detail: <String, Object?>{
              'point': 'P1',
              'runId': 'run-1',
            },
          ),
          _event(TestEventType.processInterrupted, monotonicMs: 2000),
        ],
      );
      expect(report.verdict, TestVerdict.incomplete);
      expect(report.reason, TestIncompleteReasons.pausedThenInterrupted);
    });

    test('sin pausa ejecutada ni intentada → INCOMPLETE + '
        'no_pause_executed', () {
      final TestReport report = TestReport(
        sessionId: 'sess_20261123_120000_01',
        testRunId: 'run_20261123_120000_ab12',
        events: <TestEvent>[
          _event(TestEventType.sessionStarted, monotonicMs: 0),
          _event(TestEventType.sessionEnded, monotonicMs: 1000),
        ],
      );
      expect(report.verdict, TestVerdict.incomplete);
      expect(report.reason, TestIncompleteReasons.noPauseExecuted);
    });

    test('lo inferido nunca aprueba: se marca en el informe', () {
      final TestReport report = TestReport(
        sessionId: 'sess_20261123_120000_01',
        testRunId: 'run_20261123_120000_ab12',
        events: <TestEvent>[
          _event(TestEventType.sessionStarted, monotonicMs: 0),
          _event(
            TestEventType.reconciliationFinished,
            monotonicMs: 1000,
            origin: TestEventOrigin.inferred,
          ),
          _event(TestEventType.sessionEnded, monotonicMs: 2000),
        ],
      );
      expect(report.toText(), contains('[inferido]'));
      expect(
        report.toJsonMap()['warnings'],
        contains(
          'contiene eventos inferidos (marca «inferido»): nunca aprueban',
        ),
      );
    });
  });

  group('prueba 2d: exportedJsonAndSummaryAgree', () {
    test('el JSON y el .txt muestran el mismo veredicto y motivo; los '
        'criterios quedan no evaluables, no fallidos', () async {
      final TestReport report = TestReport(
        sessionId: 'sess_20261123_120000_01',
        testRunId: 'run_20261123_120000_ab12',
        events: <TestEvent>[
          _event(TestEventType.sessionStarted, monotonicMs: 0),
          _event(TestEventType.pauseArmed, monotonicMs: 100),
          _event(TestEventType.intentConsumed, monotonicMs: 200),
          _event(TestEventType.sessionEnded, monotonicMs: 300),
        ],
      );
      expect(report.verdict, TestVerdict.incomplete);
      expect(
        report.reason,
        TestIncompleteReasons.intentConsumedWithoutPause,
      );

      // Exportación a un exportRoot separado de la raíz de datos
      // (Revisión 3 §3.2): el exportador escribe solo ahí.
      final Directory exportRoot = await Directory.systemTemp.createTemp(
        'transmiti2-test-export-',
      );
      addTearDown(() async {
        if (await exportRoot.exists()) {
          await exportRoot.delete(recursive: true);
        }
      });
      final DirectoryTestReportExporter exporter =
          DirectoryTestReportExporter(exportRoot);
      final TestExportResult exported = await exporter.exportReport(report);
      expect(exported.sessionId, 'sess_20261123_120000_01');

      final Map<String, Object?> decoded =
          jsonDecode(await File(exported.jsonPath).readAsString())
              as Map<String, Object?>;
      final String text = await File(exported.txtPath).readAsString();
      expect(decoded['verdict'], 'INCOMPLETE');
      expect(decoded['reason'], 'intent_consumed_without_pause');
      expect(text, contains('VEREDICTO: INCOMPLETE'));
      expect(text, contains('Motivo: intent_consumed_without_pause'));
      // Criterios no evaluables (sin resumen de reconciliación), no
      // fallidos.
      final List<Object?> criteria = decoded['criteria'] as List<Object?>;
      expect(criteria.length, 2);
      for (final Object? c in criteria) {
        expect(
          (c as Map<String, Object?>)['status'],
          'NOT_EVALUABLE',
        );
      }
      expect(text, contains('- C1: NOT_EVALUABLE'));
      expect(text, contains('- C2: NOT_EVALUABLE'));
      expect(text, isNot(contains('FAILED')));
      // Los archivos existen en exportRoot y solo ahí.
      expect(exported.jsonPath, startsWith(exportRoot.path));
      expect(exported.txtPath, startsWith(exportRoot.path));
    });
  });
}
