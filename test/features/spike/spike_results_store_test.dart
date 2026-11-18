// SPIKE 3.1.2 — TEMPORAL
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/spike/muxer_spike_service.dart';
import 'package:transmiti2/features/spike/spike_results_store.dart';

void main() {
  group('SpikeResultsStore', () {
    late Directory tempDir;

    setUp(() async {
      tempDir = await Directory.systemTemp.createTemp(
        'spike_results_test_',
      );
    });

    tearDown(() async {
      if (await tempDir.exists()) {
        await tempDir.delete(recursive: true);
      }
    });

    File resultsFile() =>
        File('${tempDir.path}/spike_results.json');

    SpikeRunResult sampleRun(String fileName) {
      return SpikeRunResult(
        muxer: 'classic',
        fileName: fileName,
        sizeBytes: 3145728,
        durationTargetMs: 10000,
        durationMs: 10033,
        width: 1280,
        height: 720,
        fpsMeasured: 29.9,
        sampleCount: 300,
        keyframeCount: 6,
        firstSampleKeyframe: true,
        ptsMonotonic: true,
        moovPresent: true,
        extractorOpens: true,
        exoplayerReady: true,
        profileIdc: 66,
      );
    }

    const EncoderProbeResult sampleProbe = EncoderProbeResult(
      encoderName: 'c2.qti.avc.encoder',
      profiles: <int>[66, 77],
      levels: <int>[512],
      cbrSupported: true,
      supports720pForMediaCodec: true,
      outputProfile: 66,
      hasCsd0: true,
      hasCsd1: true,
      profileIdc: 66,
    );

    test('guardar y cargar sonda y pasadas', () async {
      final SpikeResultsStore store = SpikeResultsStore(resultsFile());
      await store.save(
        probe: sampleProbe,
        runs: <SpikeRunResult>[
          sampleRun('spike_classic_1.mp4'),
          sampleRun('spike_compat_1.mp4'),
        ],
      );
      final SpikeResultsData loaded = await store.load();
      expect(loaded.probe?.encoderName, 'c2.qti.avc.encoder');
      expect(loaded.probe?.profiles, <int>[66, 77]);
      expect(loaded.probe?.profileIdc, 66);
      expect(loaded.runs.length, 2);
      expect(loaded.runs.first.fileName, 'spike_classic_1.mp4');
      expect(loaded.runs.first.durationMs, 10033);
      expect(loaded.runs.first.fpsMeasured, 29.9);
      expect(loaded.runs.first.profileLabel, 'Baseline (66)');
      expect(loaded.runs.last.fileName, 'spike_compat_1.mp4');
    });

    test('el JSON es legible y lleva version 1', () async {
      final SpikeResultsStore store = SpikeResultsStore(resultsFile());
      await store.save(probe: sampleProbe, runs: const <SpikeRunResult>[]);
      final Map<String, dynamic> json =
          jsonDecode(await resultsFile().readAsString())
              as Map<String, dynamic>;
      expect(json['version'], 1);
      expect(
        (json['probe'] as Map<String, dynamic>)['encoderName'],
        'c2.qti.avc.encoder',
      );
      expect(json['runs'], isEmpty);
    });

    test('sin archivo devuelve datos vacíos', () async {
      final SpikeResultsData loaded = await SpikeResultsStore(
        resultsFile(),
      ).load();
      expect(loaded.probe, isNull);
      expect(loaded.runs, isEmpty);
    });

    test('JSON roto devuelve datos vacíos sin lanzar', () async {
      await resultsFile().writeAsString('{no es json');
      final SpikeResultsData loaded = await SpikeResultsStore(
        resultsFile(),
      ).load();
      expect(loaded.probe, isNull);
      expect(loaded.runs, isEmpty);
    });

    test('versión distinta devuelve datos vacíos', () async {
      await resultsFile().writeAsString(
        jsonEncode(<String, Object?>{
          'version': 99,
          'probe': sampleProbe.toMap(),
          'runs': <Object?>[],
        }),
      );
      final SpikeResultsData loaded = await SpikeResultsStore(
        resultsFile(),
      ).load();
      expect(loaded.probe, isNull);
      expect(loaded.runs, isEmpty);
    });

    test('guardar crea el directorio si no existe', () async {
      final SpikeResultsStore store = SpikeResultsStore(
        File('${tempDir.path}/a/b/spike_results.json'),
      );
      await store.save(probe: null, runs: const <SpikeRunResult>[]);
      expect(await store.load(), isNotNull);
      expect((await store.load()).runs, isEmpty);
    });

    test('isResultsFileName excluye el JSON y su temporal', () {
      expect(
        SpikeResultsStore.isResultsFileName('spike_results.json'),
        isTrue,
      );
      expect(
        SpikeResultsStore.isResultsFileName('spike_results.json.tmp'),
        isTrue,
      );
      expect(
        SpikeResultsStore.isResultsFileName('spike_classic_1.mp4'),
        isFalse,
      );
    });
  });
}
