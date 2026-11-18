// SPIKE 3.1.2 — TEMPORAL
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/spike/muxer_spike_service.dart';

Map<Object?, Object?> _fullRunMap() {
  return <Object?, Object?>{
    'muxer': 'classic',
    'interrupted': false,
    'fileName': 'spike_classic_1.mp4',
    'filePath': '/x/spike_classic_1.mp4',
    'sizeBytes': 3145728,
    'durationTargetMs': 10000,
    'durationMs': 10033,
    'width': 1280,
    'height': 720,
    'fpsMeasured': 29.9,
    'sampleCount': 300,
    'keyframeCount': 6,
    'firstSampleKeyframe': true,
    'ptsMonotonic': true,
    'moovPresent': true,
    'extractorOpens': true,
    'exoplayerReady': true,
    'exoplayerDurationMs': 10033,
    'maxWriteLatencyMs': 12,
    'avgBitrateBps': 2506000,
    'profileIdc': 66,
    'cttsPresent': false,
    'cttsAllZero': null,
    'readableDurationMs': 10033,
    'lossMs': null,
    'encoderName': 'c2.qti.avc.encoder',
    'templateUsed': 'TEMPLATE_RECORD',
    'configWidth': 1280,
    'configHeight': 720,
    'configFps': 30,
    'samplesBeforeMuxer': 0,
    'muxerError': null,
    'eosTimeout': false,
  };
}

void main() {
  group('SpikeRunResult.fromMap', () {
    test('parsea el mapa completo', () {
      final SpikeRunResult run = SpikeRunResult.fromMap(_fullRunMap());
      expect(run.muxer, 'classic');
      expect(run.interrupted, isFalse);
      expect(run.fileName, 'spike_classic_1.mp4');
      expect(run.durationMs, 10033);
      expect(run.width, 1280);
      expect(run.height, 720);
      expect(run.fpsMeasured, 29.9);
      expect(run.sampleCount, 300);
      expect(run.keyframeCount, 6);
      expect(run.firstSampleKeyframe, isTrue);
      expect(run.ptsMonotonic, isTrue);
      expect(run.moovPresent, isTrue);
      expect(run.exoplayerReady, isTrue);
      expect(run.maxWriteLatencyMs, 12);
      expect(run.avgBitrateBps, 2506000);
      expect(run.profileIdc, 66);
      expect(run.profileLabel, 'Baseline (66)');
      expect(run.cttsPresent, isFalse);
      expect(run.cttsAllZero, isNull);
      expect(run.encoderName, 'c2.qti.avc.encoder');
      expect(run.templateUsed, 'TEMPLATE_RECORD');
      expect(run.eosTimeout, isFalse);
    });

    test('valores por defecto con el mapa vacío', () {
      final SpikeRunResult run = SpikeRunResult.fromMap(
        const <Object?, Object?>{},
      );
      expect(run.muxer, '');
      expect(run.interrupted, isFalse);
      expect(run.fileName, '');
      expect(run.sizeBytes, 0);
      expect(run.durationTargetMs, 0);
      expect(run.durationMs, isNull);
      expect(run.width, isNull);
      expect(run.fpsMeasured, isNull);
      expect(run.sampleCount, isNull);
      expect(run.moovPresent, isFalse);
      expect(run.extractorOpens, isFalse);
      expect(run.exoplayerReady, isFalse);
      expect(run.profileLabel, '—');
      expect(run.eosTimeout, isFalse);
    });

    test('perfil Main y High', () {
      expect(
        SpikeRunResult.fromMap(
          const <Object?, Object?>{'profileIdc': 77},
        ).profileLabel,
        'Main (77)',
      );
      expect(
        SpikeRunResult.fromMap(
          const <Object?, Object?>{'profileIdc': 100},
        ).profileLabel,
        'High (100)',
      );
    });
  });

  group('EncoderProbeResult.fromMap', () {
    test('parsea la sonda y los valores por defecto', () {
      final EncoderProbeResult probe = EncoderProbeResult.fromMap(
        <Object?, Object?>{
          'encoderName': 'c2.qti.avc.encoder',
          'profiles': <int>[66, 77],
          'levels': <int>[512],
          'cbrSupported': true,
          'supports720pForMediaCodec': true,
          'outputProfile': 66,
          'outputLevel': 512,
          'hasCsd0': true,
          'hasCsd1': true,
          'profileIdc': 66,
          'levelIdc': 40,
        },
      );
      expect(probe.encoderName, 'c2.qti.avc.encoder');
      expect(probe.profiles, <int>[66, 77]);
      expect(probe.levels, <int>[512]);
      expect(probe.cbrSupported, isTrue);
      expect(probe.supports720pForMediaCodec, isTrue);
      expect(probe.profileLabel, 'Baseline (66)');
      expect(probe.encoderError, isNull);

      final EncoderProbeResult empty = EncoderProbeResult.fromMap(
        const <Object?, Object?>{},
      );
      expect(empty.encoderName, isNull);
      expect(empty.profiles, isEmpty);
      expect(empty.cbrSupported, isFalse);
      expect(empty.profileLabel, '—');
    });
  });

  group('SpikePreviewInfo.fromMap', () {
    test('parsea y valores por defecto', () {
      final SpikePreviewInfo info = SpikePreviewInfo.fromMap(
        <Object?, Object?>{
          'textureId': 7,
          'width': 1280,
          'height': 720,
          'rotationDegrees': 90,
          'fps': 30,
        },
      );
      expect(info.textureId, 7);
      expect(info.rotationDegrees, 90);
      final SpikePreviewInfo empty = SpikePreviewInfo.fromMap(
        const <Object?, Object?>{},
      );
      expect(empty.textureId, -1);
      expect(empty.width, 0);
      expect(empty.rotationDegrees, 90);
      expect(empty.fps, 30);
    });
  });

  group('etiquetas', () {
    test('spikeCheck', () {
      expect(spikeCheck(true), '✓');
      expect(spikeCheck(false), '✗');
      expect(spikeCheck(null), '—');
    });

    test('spikeSecondsLabel', () {
      expect(spikeSecondsLabel(null), '—');
      expect(spikeSecondsLabel(10033), '10.0 s');
      expect(spikeSecondsLabel(500), '0.5 s');
    });

    test('spikeBytesLabel', () {
      expect(spikeBytesLabel(null), '—');
      expect(spikeBytesLabel(3145728), '3.0 MB');
      expect(spikeBytesLabel(2048), '2 KB');
    });
  });

  group('spikeComparisonRows', () {
    test('tabla con la última pasada normal por muxer', () {
      final SpikeRunResult classic = SpikeRunResult.fromMap(_fullRunMap());
      final Map<Object?, Object?> compatMap = _fullRunMap()
        ..['muxer'] = 'compat'
        ..['fileName'] = 'spike_compat_1.mp4'
        ..['fpsMeasured'] = 30.0
        ..['maxWriteLatencyMs'] = 4;
      final SpikeRunResult compat = SpikeRunResult.fromMap(compatMap);
      final List<SpikeTableRow> rows = spikeComparisonRows(
        <SpikeRunResult>[classic, compat],
      );
      SpikeTableRow row(String label) =>
          rows.firstWhere((SpikeTableRow r) => r.label == label);
      expect(row('Archivo').classic, 'spike_classic_1.mp4');
      expect(row('Archivo').compat, 'spike_compat_1.mp4');
      expect(row('FPS medido').classic, '29.9');
      expect(row('FPS medido').compat, '30.0');
      expect(row('Primer sample keyframe').classic, '✓');
      expect(row('moov presente').compat, '✓');
      expect(row('Perfil (SPS)').classic, 'Baseline (66)');
      expect(row('Latencia máx writeSampleData').classic, '12 ms');
      expect(row('Latencia máx writeSampleData').compat, '4 ms');
      // Sin interrupciones todavía: pérdida «—».
      expect(row('Pérdida por interrupción').classic, '—');
      expect(row('Pérdida por interrupción').compat, '—');
    });

    test('pérdida por interrupción de la última pasada interrumpida', () {
      final Map<Object?, Object?> classicInt = _fullRunMap()
        ..['interrupted'] = true
        ..['durationMs'] = 2000
        ..['readableDurationMs'] = 2000
        ..['lossMs'] = 3000
        ..['durationTargetMs'] = 5000;
      final Map<Object?, Object?> compatInt = _fullRunMap()
        ..['muxer'] = 'compat'
        ..['interrupted'] = true
        ..['durationMs'] = null
        ..['readableDurationMs'] = null
        ..['lossMs'] = 5000
        ..['durationTargetMs'] = 5000;
      final List<SpikeTableRow> rows = spikeComparisonRows(
        <SpikeRunResult>[
          SpikeRunResult.fromMap(classicInt),
          SpikeRunResult.fromMap(compatInt),
        ],
      );
      SpikeTableRow row(String label) =>
          rows.firstWhere((SpikeTableRow r) => r.label == label);
      expect(
        row('Duración legible tras interrupción').classic,
        '2.0 s',
      );
      expect(
        row('Duración legible tras interrupción').compat,
        '—',
      );
      expect(row('Pérdida por interrupción').classic, '3.0 s');
      expect(row('Pérdida por interrupción').compat, '5.0 s');
    });

    test('sin pasadas, todo «—»', () {
      final List<SpikeTableRow> rows = spikeComparisonRows(
        const <SpikeRunResult>[],
      );
      expect(rows, isNotEmpty);
      for (final SpikeTableRow row in rows) {
        expect(row.classic, '—');
        expect(row.compat, '—');
      }
    });
  });

  group('formatSpikeCopyText', () {
    test('incluye sonda, pasadas y archivos', () {
      final EncoderProbeResult probe = EncoderProbeResult.fromMap(
        <Object?, Object?>{
          'encoderName': 'c2.qti.avc.encoder',
          'profiles': <int>[66],
          'cbrSupported': true,
          'supports720pForMediaCodec': true,
          'profileIdc': 66,
        },
      );
      final SpikeRunResult run = SpikeRunResult.fromMap(_fullRunMap());
      final String text = formatSpikeCopyText(
        probe: probe,
        runs: <SpikeRunResult>[run],
        files: const <SpikeFileInfo>[
          SpikeFileInfo(
            name: 'spike_classic_1.mp4',
            sizeBytes: 3145728,
            lastModifiedMs: 1,
          ),
        ],
      );
      expect(text, contains('SPIKE MUXER 3.1.2'));
      expect(text, contains('c2.qti.avc.encoder'));
      expect(text, contains('Baseline (66)'));
      expect(text, contains('spike_classic_1.mp4'));
      expect(text, contains('✓'));
      expect(text, contains('3.0 MB'));
    });

    test('sin sonda ni pasadas, no falla', () {
      final String text = formatSpikeCopyText(
        probe: null,
        runs: const <SpikeRunResult>[],
        files: const <SpikeFileInfo>[],
      );
      expect(text, contains('Sin sonda.'));
      expect(text, contains('Sin pasadas.'));
      expect(text, contains('Sin archivos.'));
    });
  });

  group('SpikeRunResult.toMap', () {
    test('toMap reproduce el mapa completo', () {
      final Map<Object?, Object?> original = _fullRunMap();
      final SpikeRunResult run = SpikeRunResult.fromMap(original);
      final Map<String, Object?> serialized = run.toMap();
      for (final MapEntry<Object?, Object?> entry in original.entries) {
        expect(serialized[entry.key as String], entry.value);
      }
      expect(serialized.length, original.length);
    });

    test('toMap y fromMap sobreviven a JSON', () {
      final SpikeRunResult run = SpikeRunResult.fromMap(_fullRunMap());
      final Map<String, dynamic> decoded =
          jsonDecode(jsonEncode(run.toMap())) as Map<String, dynamic>;
      final SpikeRunResult restored = SpikeRunResult.fromMap(decoded);
      expect(restored.muxer, run.muxer);
      expect(restored.fileName, run.fileName);
      expect(restored.durationMs, run.durationMs);
      expect(restored.fpsMeasured, run.fpsMeasured);
      expect(restored.profileIdc, run.profileIdc);
      expect(restored.cttsAllZero, isNull);
    });
  });

  group('EncoderProbeResult.toMap', () {
    test('toMap y fromMap sobreviven a JSON', () {
      final EncoderProbeResult probe = EncoderProbeResult.fromMap(
        <Object?, Object?>{
          'encoderName': 'c2.qti.avc.encoder',
          'profiles': <int>[66, 77],
          'levels': <int>[512],
          'cbrSupported': true,
          'supports720pForMediaCodec': true,
          'outputProfile': 66,
          'outputLevel': 512,
          'hasCsd0': true,
          'hasCsd1': true,
          'profileIdc': 66,
          'levelIdc': 40,
        },
      );
      final Map<String, dynamic> decoded =
          jsonDecode(jsonEncode(probe.toMap())) as Map<String, dynamic>;
      final EncoderProbeResult restored = EncoderProbeResult.fromMap(
        decoded,
      );
      expect(restored.encoderName, 'c2.qti.avc.encoder');
      expect(restored.profiles, <int>[66, 77]);
      expect(restored.levels, <int>[512]);
      expect(restored.cbrSupported, isTrue);
      expect(restored.profileIdc, 66);
      expect(restored.levelIdc, 40);
      expect(restored.encoderError, isNull);
    });
  });

  group('addSpikeVerifyResult', () {
    SpikeRunResult verifyResult(String fileName) {
      return SpikeRunResult(
        muxer: 'verify',
        fileName: fileName,
        moovPresent: true,
        extractorOpens: true,
        exoplayerReady: true,
      );
    }

    test('añade la verificación a la lista vacía', () {
      final List<SpikeRunResult> runs = addSpikeVerifyResult(
        const <SpikeRunResult>[],
        verifyResult('spike_classic_1.mp4'),
      );
      expect(runs.length, 1);
      expect(runs.first.muxer, 'verify');
      expect(runs.first.fileName, 'spike_classic_1.mp4');
    });

    test('verificar dos veces el mismo archivo no duplica', () {
      final List<SpikeRunResult> once = addSpikeVerifyResult(
        const <SpikeRunResult>[],
        verifyResult('spike_classic_1.mp4'),
      );
      final List<SpikeRunResult> twice = addSpikeVerifyResult(
        once,
        verifyResult('spike_classic_1.mp4'),
      );
      expect(twice.length, 1);
      expect(twice.first.fileName, 'spike_classic_1.mp4');
    });

    test('archivos distintos se acumulan y no borra grabaciones', () {
      final SpikeRunResult classic = SpikeRunResult.fromMap(_fullRunMap());
      final List<SpikeRunResult> runs = addSpikeVerifyResult(
        addSpikeVerifyResult(
          <SpikeRunResult>[classic],
          verifyResult('spike_classic_1.mp4'),
        ),
        verifyResult('spike_compat_1.mp4'),
      );
      expect(runs.length, 3);
      expect(runs.first, classic);
      expect(
        runs.where((SpikeRunResult r) => r.muxer == 'verify').length,
        2,
      );
    });
  });

  group('MethodChannelMuxerSpikeService', () {
    TestWidgetsFlutterBinding.ensureInitialized();
    const MethodChannel channel = MethodChannel(
      'com.transmiti2.transmiti2/spike',
    );

    setUp(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall call) async {
            switch (call.method) {
              case 'probeEncoder':
                return <String, Object?>{
                  'encoderName': 'c2.qti.avc.encoder',
                  'profiles': <int>[66],
                  'cbrSupported': true,
                  'supports720pForMediaCodec': true,
                  'profileIdc': 66,
                };
              case 'recordWithMuxer':
                return _fullRunMap();
              case 'listSpikeFiles':
                return <Object?>[
                  <String, Object?>{
                    'name': 'spike_classic_1.mp4',
                    'sizeBytes': 3145728,
                    'lastModifiedMs': 1,
                  },
                ];
              case 'deleteSpikeFiles':
                return <String, Object?>{'deleted': 2};
              case 'startPreview':
                return <String, Object?>{
                  'textureId': 7,
                  'width': 1280,
                  'height': 720,
                  'rotationDegrees': 90,
                  'fps': 30,
                };
              default:
                return null;
            }
          });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test('probeEncoder, recordWithMuxer y listado por el canal', () async {
      final MuxerSpikeService service = MethodChannelMuxerSpikeService();
      final EncoderProbeResult probe = await service.probeEncoder();
      expect(probe.encoderName, 'c2.qti.avc.encoder');
      final SpikeRunResult run = await service.recordWithMuxer(
        muxer: 'classic',
        durationMs: 10000,
      );
      expect(run.fileName, 'spike_classic_1.mp4');
      expect(run.profileIdc, 66);
      final List<SpikeFileInfo> files = await service.listSpikeFiles();
      expect(files.length, 1);
      expect(files.first.name, 'spike_classic_1.mp4');
      expect(await service.deleteSpikeFiles(), 2);
      final SpikePreviewInfo preview = await service.startPreview();
      expect(preview.textureId, 7);
    });

    test('PlatformException se traduce a SpikeException', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall call) async {
            throw PlatformException(
              code: 'encoder_error',
              message: 'Sin codificador.',
            );
          });
      final MuxerSpikeService service = MethodChannelMuxerSpikeService();
      await expectLater(
        service.probeEncoder(),
        throwsA(
          isA<SpikeException>().having(
            (SpikeException e) => e.code,
            'code',
            'encoder_error',
          ),
        ),
      );
    });
  });
}
