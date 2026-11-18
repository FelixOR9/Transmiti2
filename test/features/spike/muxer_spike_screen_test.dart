// SPIKE 3.1.2 — TEMPORAL
// Timeout de biblioteca (60 s para todo el archivo: hay E/S real de
// disco): `Timeout` solo puede anotar la biblioteca, no `main()`
// (`invalid_annotation_target` si se pone sobre una función).
@Timeout(Duration(seconds: 60))
library;

import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/features/spike/muxer_spike_screen.dart';
import 'package:transmiti2/features/spike/muxer_spike_service.dart';
import 'package:transmiti2/features/spike/spike_results_store.dart';

import '../../support/test_services.dart';

/// Servicio del spike falso, solo para pruebas: devuelve datos fijos
/// sin tocar el canal nativo.
class FakeSpikeService implements MuxerSpikeService {
  SpikeException? probeError;
  EncoderProbeResult probeResult = const EncoderProbeResult(
    encoderName: 'c2.qti.avc.encoder',
    profiles: <int>[66],
    cbrSupported: true,
    supports720pForMediaCodec: true,
    profileIdc: 66,
  );
  List<SpikeFileInfo> files = const <SpikeFileInfo>[];
  int verifyCalls = 0;

  @override
  Future<SpikePreviewInfo> startPreview() async {
    return const SpikePreviewInfo(textureId: 1, width: 1280, height: 720);
  }

  @override
  Future<void> stopPreview() async {}

  @override
  Future<EncoderProbeResult> probeEncoder() async {
    if (probeError != null) {
      throw probeError!;
    }
    return probeResult;
  }

  @override
  Future<SpikeRunResult> recordWithMuxer({
    required String muxer,
    required int durationMs,
  }) async {
    return SpikeRunResult(muxer: muxer, fileName: 'spike_${muxer}_1.mp4');
  }

  @override
  Future<SpikeRunResult> interruptWithMuxer({
    required String muxer,
    required int durationMs,
  }) async {
    return SpikeRunResult(
      muxer: muxer,
      interrupted: true,
      fileName: 'spike_${muxer}_1.tmp.mp4',
    );
  }

  @override
  Future<SpikeRunResult> verifyFile(String fileName) async {
    verifyCalls++;
    return SpikeRunResult(
      muxer: 'verify',
      fileName: fileName,
      durationMs: 10000,
      durationTargetMs: 10000,
      width: 1280,
      height: 720,
      sampleCount: 300,
      keyframeCount: 6,
      firstSampleKeyframe: true,
      ptsMonotonic: true,
      moovPresent: true,
      extractorOpens: true,
      exoplayerReady: true,
      exoplayerDurationMs: 10000,
      profileIdc: 66,
    );
  }

  @override
  Future<List<SpikeFileInfo>> listSpikeFiles() async => files;

  @override
  Future<int> deleteSpikeFiles() async => files.length;
}

/// Deja completar el trabajo asíncrono real (E/S de disco, canal) y
/// redibuja. No usar `pumpAndSettle` con esta pantalla: la carga
/// inicial es E/S real, que `pumpAndSettle` no espera (devuelve antes
/// de que lleguen los resultados), y mientras la pantalla está ocupada
/// muestra un `LinearProgressIndicator` animado que nunca se asienta.
/// La E/S real de disco (guardar/cargar el JSON) tampoco avanza si se
/// espera directamente en el cuerpo del test: necesita una ventana de
/// tiempo real, que es lo que abre `runAsync`.
Future<void> settleAsync(WidgetTester tester) async {
  await tester.runAsync(() async {
    await Future<void>.delayed(const Duration(milliseconds: 500));
  });
  await tester.pump(const Duration(milliseconds: 300));
  await tester.pump();
}

/// Espera hasta que [condition] sea verdadera, dando tiempo real entre
/// intentos. Para trabajos con varias operaciones de disco seguidas
/// (verificar todos los archivos, una persistencia por archivo) un
/// solo `settleAsync` puede no bastar.
Future<void> waitForCondition(
  WidgetTester tester,
  bool Function() condition, {
  int maxTries = 30,
}) async {
  for (int i = 0; i < maxTries; i++) {
    await settleAsync(tester);
    if (condition()) {
      return;
    }
  }
}

/// Lee el JSON persistido hasta que [check] sea verdadera o se agote
/// el tiempo. La escritura en disco de la pantalla corre en segundo
/// plano y su finalización no coincide con un solo `settleAsync`: una
/// sola lectura puede ver el archivo anterior.
Future<SpikeResultsData> waitForPersisted(
  WidgetTester tester,
  SpikeResultsStore store,
  bool Function(SpikeResultsData) check, {
  int maxTries = 30,
}) async {
  SpikeResultsData data = const SpikeResultsData();
  for (int i = 0; i < maxTries; i++) {
    final SpikeResultsData? loaded =
        await tester.runAsync(() => store.load());
    data = loaded ?? const SpikeResultsData();
    if (check(data)) {
      return data;
    }
    await settleAsync(tester);
  }
  return data;
}

Future<void> pumpSpike(
  WidgetTester tester,
  FakeSpikeService service,
  SpikeResultsStore store,
) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.reset);
  final AppServices services = createTestServices();
  await tester.pumpWidget(
    MaterialApp(
      home: AppScope(
        services: services,
        child: MuxerSpikeScreen(service: service, resultsStore: store),
      ),
    ),
  );
  // La carga inicial (leer el JSON + listar archivos) es E/S real:
  // cada operación necesita su propia ventana de tiempo real, así que
  // un solo `settleAsync` no basta.
  await settleAsync(tester);
  await settleAsync(tester);
  await settleAsync(tester);
}

void main() {
  group('MuxerSpikeScreen: persistencia de resultados', () {
    late Directory tempDir;

    setUp(() async {
      tempDir = await Directory.systemTemp.createTemp('spike_screen_test_');
    });

    tearDown(() async {
      if (await tempDir.exists()) {
        await tempDir.delete(recursive: true);
      }
    });

    SpikeResultsStore store() {
      return SpikeResultsStore(
        File('${tempDir.path}/spike_results.json'),
      );
    }

    const SpikeFileInfo classicFile = SpikeFileInfo(
      name: 'spike_classic_1.mp4',
      sizeBytes: 3145728,
      lastModifiedMs: 1,
    );
    const SpikeFileInfo compatFile = SpikeFileInfo(
      name: 'spike_compat_1.mp4',
      sizeBytes: 3355443,
      lastModifiedMs: 2,
    );

    testWidgets('carga la sonda y las pasadas guardadas al abrir', (
      WidgetTester tester,
    ) async {
      final SpikeResultsStore saved = store();
      // E/S real de disco: necesita ventana de tiempo real.
      await tester.runAsync(
        () => saved.save(
          probe: const EncoderProbeResult(
            encoderName: 'c2.qti.avc.encoder',
            profiles: <int>[66],
            profileIdc: 66,
          ),
          runs: const <SpikeRunResult>[
            SpikeRunResult(
              muxer: 'verify',
              fileName: 'spike_classic_1.mp4',
              moovPresent: true,
              extractorOpens: true,
              exoplayerReady: true,
            ),
          ],
        ),
      );
      final FakeSpikeService service = FakeSpikeService()
        ..files = const <SpikeFileInfo>[
          classicFile,
          compatFile,
          // El JSON de resultados se lista en el nativo pero la
          // pantalla no lo muestra como archivo del spike.
          SpikeFileInfo(
            name: 'spike_results.json',
            sizeBytes: 512,
            lastModifiedMs: 3,
          ),
        ];
      await pumpSpike(tester, service, saved);

      await waitForCondition(
        tester,
        () =>
            find.textContaining('c2.qti.avc.encoder').evaluate().isNotEmpty,
      );
      expect(find.textContaining('c2.qti.avc.encoder'), findsOneWidget);
      expect(find.text('PASADAS'), findsOneWidget);
      expect(find.textContaining('verificación'), findsOneWidget);
      expect(find.text('spike_results.json'), findsNothing);
      expect(find.text('spike_classic_1.mp4'), findsOneWidget);
    });

    testWidgets('tocar un archivo añade su verificación a las pasadas', (
      WidgetTester tester,
    ) async {
      final FakeSpikeService service = FakeSpikeService()
        ..files = const <SpikeFileInfo>[classicFile];
      final SpikeResultsStore saved = store();
      await pumpSpike(tester, service, saved);
      expect(find.textContaining('verificación'), findsNothing);

      await tester.ensureVisible(find.text('spike_classic_1.mp4'));
      await tester.tap(find.text('spike_classic_1.mp4'));
      await settleAsync(tester);
      expect(find.text('CERRAR'), findsOneWidget);
      await tester.tap(find.text('CERRAR'));
      await settleAsync(tester);

      expect(find.textContaining('verificación'), findsOneWidget);
      expect(service.verifyCalls, 1);
      final SpikeResultsData persisted = await waitForPersisted(
        tester,
        saved,
        (SpikeResultsData d) => d.runs.length == 1,
      );
      expect(persisted.runs.length, 1);
      expect(persisted.runs.first.muxer, 'verify');
      expect(persisted.runs.first.fileName, 'spike_classic_1.mp4');
      expect(persisted.runs.first.moovPresent, isTrue);
    });

    testWidgets(
      'VERIFICAR ARCHIVOS EXISTENTES añade todas las verificaciones',
      (WidgetTester tester) async {
        final FakeSpikeService service = FakeSpikeService()
          ..files = const <SpikeFileInfo>[classicFile, compatFile];
        final SpikeResultsStore saved = store();
        await pumpSpike(tester, service, saved);

        await tester.ensureVisible(
          find.text('VERIFICAR ARCHIVOS EXISTENTES'),
        );
        await tester.tap(find.text('VERIFICAR ARCHIVOS EXISTENTES'));
        // Dos archivos, una persistencia por archivo: esperar a que
        // aparezcan las dos verificaciones en lugar de suponer un
        // tiempo fijo.
        await waitForCondition(
          tester,
          () =>
              find.textContaining('verificación').evaluate().length == 2,
        );

        expect(find.textContaining('verificación'), findsNWidgets(2));
        expect(service.verifyCalls, 2);
        final SpikeResultsData persisted = await waitForPersisted(
          tester,
          saved,
          (SpikeResultsData d) => d.runs.length == 2,
        );
        expect(persisted.runs.length, 2);
        expect(
          persisted.runs.map((SpikeRunResult r) => r.fileName).toSet(),
          <String>{'spike_classic_1.mp4', 'spike_compat_1.mp4'},
        );
      },
    );

    testWidgets('la sonda fallida queda registrada con su error', (
      WidgetTester tester,
    ) async {
      final FakeSpikeService service = FakeSpikeService()
        ..probeError = const SpikeException(
          'encoder_error',
          'Sin codificador.',
        );
      final SpikeResultsStore saved = store();
      await pumpSpike(tester, service, saved);

      await tester.ensureVisible(find.text('SONDEAR CODIFICADOR'));
      await tester.tap(find.text('SONDEAR CODIFICADOR'));
      await waitForCondition(
        tester,
        () =>
            find.textContaining('Sin codificador.').evaluate().isNotEmpty,
      );

      expect(find.textContaining('Sin codificador.'), findsNWidgets(2));
      final SpikeResultsData persisted = await waitForPersisted(
        tester,
        saved,
        (SpikeResultsData d) => d.probe?.encoderError != null,
      );
      expect(
        persisted.probe?.encoderError,
        'encoder_error: Sin codificador.',
      );
    });
  });
}
