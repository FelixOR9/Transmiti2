// SPIKE 3.1.2 — TEMPORAL
import 'dart:convert';
import 'dart:io';

import 'package:transmiti2/features/spike/muxer_spike_service.dart';

/// Sonda y pasadas del spike leídas de `spike_results.json`.
// SPIKE 3.1.2 — TEMPORAL
class SpikeResultsData {
  const SpikeResultsData({
    this.probe,
    this.runs = const <SpikeRunResult>[],
  });

  final EncoderProbeResult? probe;
  final List<SpikeRunResult> runs;
}

/// Persistencia de los resultados del spike en disco.
///
/// Los resultados viven en `<documents>/recordings/spike/` junto a los
/// archivos, en `spike_results.json` (versionado, `version: 1`), y se
/// escriben tras cada repetición, tras la sonda y tras cada
/// verificación. Sin persistencia, salir de la pantalla o que la app
/// muera (la variante manual del spike) borraba todas las pasadas y la
/// sonda acumuladas, mientras los archivos se volvían a listar desde
/// el disco: el informe copiado salía con 0 pasadas.
///
/// El archivo JSON no es un archivo del spike: `listSpikeFiles()` del
/// nativo lo lista como cualquier otro, así que la pantalla lo excluye
/// con [isResultsFileName]. El borrado nativo (`deleteSpikeFiles`)
/// también lo borra; la pantalla vuelve a persistir los resultados
/// tras borrar, para que no se pierdan con los archivos.
///
/// La escritura es atómica (temporal + renombrado) para no dejar un
/// JSON a medias si la app muere a mitad de escritura. Una lectura
/// con formato desconocido, versión distinta o JSON roto devuelve
/// datos vacíos en vez de romper la pantalla: los resultados se
/// pueden volver a medir, la pantalla no.
//
// SPIKE 3.1.2 — TEMPORAL
class SpikeResultsStore {
  SpikeResultsStore(this.file);

  /// Nombre del archivo de resultados dentro de `recordings/spike/`.
  static const String fileName = 'spike_results.json';

  /// Versión del formato JSON. Al cambiar el formato se sube y la
  /// lectura de versiones antiguas devuelve datos vacíos.
  static const int currentVersion = 1;

  /// Verdadero si [name] es el archivo de resultados o su temporal
  /// de escritura: no es un archivo multimedia del spike.
  static bool isResultsFileName(String name) {
    return name == fileName || name == '$fileName.tmp';
  }

  final File file;

  Future<SpikeResultsData> load() async {
    try {
      if (!await file.exists()) {
        return const SpikeResultsData();
      }
      final Object? decoded = jsonDecode(await file.readAsString());
      if (decoded is! Map<String, dynamic>) {
        return const SpikeResultsData();
      }
      if (decoded['version'] != currentVersion) {
        return const SpikeResultsData();
      }
      final Map<String, dynamic>? probeMap =
          decoded['probe'] as Map<String, dynamic>?;
      final List<dynamic>? runsList = decoded['runs'] as List<dynamic>?;
      return SpikeResultsData(
        probe: probeMap == null
            ? null
            : EncoderProbeResult.fromMap(probeMap),
        runs:
            runsList
                ?.map(
                  (Object? e) =>
                      SpikeRunResult.fromMap(e as Map<String, dynamic>),
                )
                .toList() ??
            const <SpikeRunResult>[],
      );
    } catch (_) {
      return const SpikeResultsData();
    }
  }

  Future<void> save({
    required EncoderProbeResult? probe,
    required List<SpikeRunResult> runs,
  }) async {
    final Map<String, Object?> json = <String, Object?>{
      'version': currentVersion,
      'probe': probe?.toMap(),
      'runs': runs.map((SpikeRunResult run) => run.toMap()).toList(),
    };
    await file.parent.create(recursive: true);
    final File tmp = File('${file.path}.tmp');
    await tmp.writeAsString(jsonEncode(json), flush: true);
    await tmp.rename(file.path);
  }
}
