// SPIKE 3.1.2 — TEMPORAL
import 'package:flutter/services.dart';

/// Error del spike del muxer. El código nunca lleva datos sensibles.
// SPIKE 3.1.2 — TEMPORAL
class SpikeException implements Exception {
  const SpikeException(this.code, [this.message]);

  final String code;
  final String? message;

  @override
  String toString() => 'SpikeException($code)';
}

/// Información de la vista previa en vivo del spike (para `Texture`).
// SPIKE 3.1.2 — TEMPORAL
class SpikePreviewInfo {
  const SpikePreviewInfo({
    required this.textureId,
    required this.width,
    required this.height,
    this.rotationDegrees = 90,
    this.fps = 30,
  });

  final int textureId;
  final int width;
  final int height;
  final int rotationDegrees;
  final int fps;

  factory SpikePreviewInfo.fromMap(Map<Object?, Object?> map) {
    return SpikePreviewInfo(
      textureId: (map['textureId'] as num?)?.toInt() ?? -1,
      width: (map['width'] as num?)?.toInt() ?? 0,
      height: (map['height'] as num?)?.toInt() ?? 0,
      rotationDegrees: (map['rotationDegrees'] as num?)?.toInt() ?? 90,
      fps: (map['fps'] as num?)?.toInt() ?? 30,
    );
  }
}

/// Resultado de la sonda del codificador (`probeEncoder`, sin grabar).
// SPIKE 3.1.2 — TEMPORAL
class EncoderProbeResult {
  const EncoderProbeResult({
    this.encoderName,
    this.profiles = const <int>[],
    this.levels = const <int>[],
    this.cbrSupported = false,
    this.supports720pForMediaCodec = false,
    this.outputProfile,
    this.outputLevel,
    this.hasCsd0 = false,
    this.hasCsd1 = false,
    this.profileIdc,
    this.levelIdc,
    this.encoderError,
  });

  final String? encoderName;
  final List<int> profiles;
  final List<int> levels;
  final bool cbrSupported;
  final bool supports720pForMediaCodec;
  final int? outputProfile;
  final int? outputLevel;
  final bool hasCsd0;
  final bool hasCsd1;

  /// `profile_idc` del SPS (`csd-0`): 66 = Baseline, 77 = Main,
  /// 100 = High. Es la confirmación real del perfil.
  final int? profileIdc;
  final int? levelIdc;

  /// Error de la sonda (sin codificador, sin formato de salida...).
  final String? encoderError;

  factory EncoderProbeResult.fromMap(Map<Object?, Object?> map) {
    List<int> intList(Object? value) {
      final List<Object?>? list = value as List<Object?>?;
      if (list == null) {
        return const <int>[];
      }
      return list.map((Object? e) => (e as num).toInt()).toList();
    }

    return EncoderProbeResult(
      encoderName: map['encoderName'] as String?,
      profiles: intList(map['profiles']),
      levels: intList(map['levels']),
      cbrSupported: map['cbrSupported'] as bool? ?? false,
      supports720pForMediaCodec:
          map['supports720pForMediaCodec'] as bool? ?? false,
      outputProfile: (map['outputProfile'] as num?)?.toInt(),
      outputLevel: (map['outputLevel'] as num?)?.toInt(),
      hasCsd0: map['hasCsd0'] as bool? ?? false,
      hasCsd1: map['hasCsd1'] as bool? ?? false,
      profileIdc: (map['profileIdc'] as num?)?.toInt(),
      levelIdc: (map['levelIdc'] as num?)?.toInt(),
      encoderError: map['encoderError'] as String?,
    );
  }

  /// Nombre legible del perfil según `profile_idc`.
  String get profileLabel {
    return switch (profileIdc) {
      66 => 'Baseline (66)',
      77 => 'Main (77)',
      100 => 'High (100)',
      final int id => 'profile_idc $id',
      null => '—',
    };
  }
}

/// Resultado de una pasada del spike: grabación normal, interrupción o
/// verificación de un archivo (`verifyFile` devuelve lo mismo con
/// `muxer == 'verify'`). Todos los valores son medidos, nunca
/// solicitados: lo desconocido es `null` y se muestra como «—».
// SPIKE 3.1.2 — TEMPORAL
class SpikeRunResult {
  const SpikeRunResult({
    this.muxer = '',
    this.interrupted = false,
    this.fileName = '',
    this.filePath = '',
    this.sizeBytes = 0,
    this.durationTargetMs = 0,
    this.durationMs,
    this.width,
    this.height,
    this.fpsMeasured,
    this.sampleCount,
    this.keyframeCount,
    this.firstSampleKeyframe,
    this.ptsMonotonic,
    this.moovPresent = false,
    this.extractorOpens = false,
    this.exoplayerReady = false,
    this.exoplayerDurationMs,
    this.maxWriteLatencyMs,
    this.avgBitrateBps,
    this.profileIdc,
    this.cttsPresent,
    this.cttsAllZero,
    this.readableDurationMs,
    this.lossMs,
    this.encoderName,
    this.templateUsed,
    this.configWidth,
    this.configHeight,
    this.configFps,
    this.samplesBeforeMuxer,
    this.muxerError,
    this.eosTimeout = false,
  });

  /// `classic`, `compat` o `verify`.
  final String muxer;
  final bool interrupted;
  final String fileName;
  final String filePath;
  final int sizeBytes;
  final int durationTargetMs;

  /// Duración medida por `MediaExtractor` (ms).
  final int? durationMs;
  final int? width;
  final int? height;

  /// FPS medido (muestras / duración).
  final double? fpsMeasured;
  final int? sampleCount;
  final int? keyframeCount;
  final bool? firstSampleKeyframe;
  final bool? ptsMonotonic;
  final bool moovPresent;
  final bool extractorOpens;
  final bool exoplayerReady;
  final int? exoplayerDurationMs;

  /// Latencia máxima de `writeSampleData` (ms).
  final int? maxWriteLatencyMs;

  /// Bitrate medio medido (bps).
  final int? avgBitrateBps;
  final int? profileIdc;
  final bool? cttsPresent;
  final bool? cttsAllZero;

  /// Duración legible tras una interrupción (ms).
  final int? readableDurationMs;

  /// Pérdida medida por interrupción: objetivo − legible (ms).
  final int? lossMs;
  final String? encoderName;
  final String? templateUsed;
  final int? configWidth;
  final int? configHeight;
  final int? configFps;
  final int? samplesBeforeMuxer;
  final String? muxerError;
  final bool eosTimeout;

  factory SpikeRunResult.fromMap(Map<Object?, Object?> map) {
    return SpikeRunResult(
      muxer: map['muxer'] as String? ?? '',
      interrupted: map['interrupted'] as bool? ?? false,
      fileName: map['fileName'] as String? ?? '',
      filePath: map['filePath'] as String? ?? '',
      sizeBytes: (map['sizeBytes'] as num?)?.toInt() ?? 0,
      durationTargetMs: (map['durationTargetMs'] as num?)?.toInt() ?? 0,
      durationMs: (map['durationMs'] as num?)?.toInt(),
      width: (map['width'] as num?)?.toInt(),
      height: (map['height'] as num?)?.toInt(),
      fpsMeasured: (map['fpsMeasured'] as num?)?.toDouble(),
      sampleCount: (map['sampleCount'] as num?)?.toInt(),
      keyframeCount: (map['keyframeCount'] as num?)?.toInt(),
      firstSampleKeyframe: map['firstSampleKeyframe'] as bool?,
      ptsMonotonic: map['ptsMonotonic'] as bool?,
      moovPresent: map['moovPresent'] as bool? ?? false,
      extractorOpens: map['extractorOpens'] as bool? ?? false,
      exoplayerReady: map['exoplayerReady'] as bool? ?? false,
      exoplayerDurationMs:
          (map['exoplayerDurationMs'] as num?)?.toInt(),
      maxWriteLatencyMs: (map['maxWriteLatencyMs'] as num?)?.toInt(),
      avgBitrateBps: (map['avgBitrateBps'] as num?)?.toInt(),
      profileIdc: (map['profileIdc'] as num?)?.toInt(),
      cttsPresent: map['cttsPresent'] as bool?,
      cttsAllZero: map['cttsAllZero'] as bool?,
      readableDurationMs: (map['readableDurationMs'] as num?)?.toInt(),
      lossMs: (map['lossMs'] as num?)?.toInt(),
      encoderName: map['encoderName'] as String?,
      templateUsed: map['templateUsed'] as String?,
      configWidth: (map['configWidth'] as num?)?.toInt(),
      configHeight: (map['configHeight'] as num?)?.toInt(),
      configFps: (map['configFps'] as num?)?.toInt(),
      samplesBeforeMuxer: (map['samplesBeforeMuxer'] as num?)?.toInt(),
      muxerError: map['muxerError'] as String?,
      eosTimeout: map['eosTimeout'] as bool? ?? false,
    );
  }

  String get kindLabel {
    if (muxer == 'verify') {
      return 'verificación';
    }
    return interrupted ? 'interrupción' : 'normal';
  }

  String get profileLabel {
    return switch (profileIdc) {
      66 => 'Baseline (66)',
      77 => 'Main (77)',
      100 => 'High (100)',
      final int id => 'profile_idc $id',
      null => '—',
    };
  }
}

/// Archivo del spike (listado de `<documents>/recordings/spike/`).
// SPIKE 3.1.2 — TEMPORAL
class SpikeFileInfo {
  const SpikeFileInfo({
    required this.name,
    required this.sizeBytes,
    required this.lastModifiedMs,
  });

  final String name;
  final int sizeBytes;
  final int lastModifiedMs;

  factory SpikeFileInfo.fromMap(Map<Object?, Object?> map) {
    return SpikeFileInfo(
      name: map['name'] as String? ?? '',
      sizeBytes: (map['sizeBytes'] as num?)?.toInt() ?? 0,
      lastModifiedMs: (map['lastModifiedMs'] as num?)?.toInt() ?? 0,
    );
  }
}

/// Marca de comprobación para la tabla: `✓` sí, `✗` no, `—` sin dato.
String spikeCheck(bool? value) {
  return switch (value) {
    true => '✓',
    false => '✗',
    null => '—',
  };
}

/// Milisegundos como segundos con un decimal (`10,0 s` usa punto:
/// `10.0 s`), o «—» sin dato.
String spikeSecondsLabel(int? ms) {
  if (ms == null) {
    return '—';
  }
  return '${(ms / 1000).toStringAsFixed(1)} s';
}

/// Tamaño en KB o MB, o «—» sin dato.
String spikeBytesLabel(int? bytes) {
  if (bytes == null) {
    return '—';
  }
  if (bytes >= 1024 * 1024) {
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
  return '${(bytes / 1024).toStringAsFixed(0)} KB';
}

/// Una fila de la tabla de comparación por muxer.
// SPIKE 3.1.2 — TEMPORAL
class SpikeTableRow {
  const SpikeTableRow(this.label, this.classic, this.compat);

  final String label;
  final String classic;
  final String compat;
}

SpikeRunResult? _latestRun(
  List<SpikeRunResult> runs,
  String muxer, {
  required bool interrupted,
}) {
  for (int i = runs.length - 1; i >= 0; i--) {
    final SpikeRunResult run = runs[i];
    if (run.muxer == muxer && run.interrupted == interrupted) {
      return run;
    }
  }
  return null;
}

/// Filas de la tabla de comparación (última grabación normal e
/// interrupción por muxer). Puro: se prueba sin dispositivo.
List<SpikeTableRow> spikeComparisonRows(List<SpikeRunResult> runs) {
  final SpikeRunResult? classic = _latestRun(
    runs,
    'classic',
    interrupted: false,
  );
  final SpikeRunResult? compat = _latestRun(
    runs,
    'compat',
    interrupted: false,
  );
  final SpikeRunResult? classicInt = _latestRun(
    runs,
    'classic',
    interrupted: true,
  );
  final SpikeRunResult? compatInt = _latestRun(
    runs,
    'compat',
    interrupted: true,
  );
  String value(
    SpikeRunResult? run,
    String Function(SpikeRunResult run) format,
  ) {
    return run == null ? '—' : format(run);
  }

  String resolution(SpikeRunResult run) {
    if (run.width == null || run.height == null) {
      return '—';
    }
    return '${run.width}x${run.height}';
  }

  return <SpikeTableRow>[
    SpikeTableRow(
      'Archivo',
      value(classic, (SpikeRunResult r) => r.fileName),
      value(compat, (SpikeRunResult r) => r.fileName),
    ),
    SpikeTableRow(
      'Tamaño',
      value(classic, (SpikeRunResult r) => spikeBytesLabel(r.sizeBytes)),
      value(compat, (SpikeRunResult r) => spikeBytesLabel(r.sizeBytes)),
    ),
    SpikeTableRow(
      'Duración medida',
      value(classic, (SpikeRunResult r) => spikeSecondsLabel(r.durationMs)),
      value(compat, (SpikeRunResult r) => spikeSecondsLabel(r.durationMs)),
    ),
    SpikeTableRow(
      'Resolución',
      value(classic, resolution),
      value(compat, resolution),
    ),
    SpikeTableRow(
      'FPS medido',
      value(
        classic,
        (SpikeRunResult r) =>
            r.fpsMeasured == null ? '—' : r.fpsMeasured!.toStringAsFixed(1),
      ),
      value(
        compat,
        (SpikeRunResult r) =>
            r.fpsMeasured == null ? '—' : r.fpsMeasured!.toStringAsFixed(1),
      ),
    ),
    SpikeTableRow(
      'Muestras',
      value(
        classic,
        (SpikeRunResult r) => r.sampleCount?.toString() ?? '—',
      ),
      value(
        compat,
        (SpikeRunResult r) => r.sampleCount?.toString() ?? '—',
      ),
    ),
    SpikeTableRow(
      'Keyframes',
      value(
        classic,
        (SpikeRunResult r) => r.keyframeCount?.toString() ?? '—',
      ),
      value(
        compat,
        (SpikeRunResult r) => r.keyframeCount?.toString() ?? '—',
      ),
    ),
    SpikeTableRow(
      'Primer sample keyframe',
      value(classic, (SpikeRunResult r) => spikeCheck(r.firstSampleKeyframe)),
      value(compat, (SpikeRunResult r) => spikeCheck(r.firstSampleKeyframe)),
    ),
    SpikeTableRow(
      'PTS monótonos',
      value(classic, (SpikeRunResult r) => spikeCheck(r.ptsMonotonic)),
      value(compat, (SpikeRunResult r) => spikeCheck(r.ptsMonotonic)),
    ),
    SpikeTableRow(
      'moov presente',
      value(classic, (SpikeRunResult r) => spikeCheck(r.moovPresent)),
      value(compat, (SpikeRunResult r) => spikeCheck(r.moovPresent)),
    ),
    SpikeTableRow(
      'ExoPlayer listo',
      value(classic, (SpikeRunResult r) => spikeCheck(r.exoplayerReady)),
      value(compat, (SpikeRunResult r) => spikeCheck(r.exoplayerReady)),
    ),
    SpikeTableRow(
      'Duración ExoPlayer',
      value(
        classic,
        (SpikeRunResult r) => spikeSecondsLabel(r.exoplayerDurationMs),
      ),
      value(
        compat,
        (SpikeRunResult r) => spikeSecondsLabel(r.exoplayerDurationMs),
      ),
    ),
    SpikeTableRow(
      'Latencia máx writeSampleData',
      value(
        classic,
        (SpikeRunResult r) =>
            r.maxWriteLatencyMs == null ? '—' : '${r.maxWriteLatencyMs} ms',
      ),
      value(
        compat,
        (SpikeRunResult r) =>
            r.maxWriteLatencyMs == null ? '—' : '${r.maxWriteLatencyMs} ms',
      ),
    ),
    SpikeTableRow(
      'Bitrate medio',
      value(
        classic,
        (SpikeRunResult r) => r.avgBitrateBps == null
            ? '—'
            : '${(r.avgBitrateBps! / 1000).toStringAsFixed(0)} kbps',
      ),
      value(
        compat,
        (SpikeRunResult r) => r.avgBitrateBps == null
            ? '—'
            : '${(r.avgBitrateBps! / 1000).toStringAsFixed(0)} kbps',
      ),
    ),
    SpikeTableRow(
      'Perfil (SPS)',
      value(classic, (SpikeRunResult r) => r.profileLabel),
      value(compat, (SpikeRunResult r) => r.profileLabel),
    ),
    SpikeTableRow(
      'ctts presente',
      value(classic, (SpikeRunResult r) => spikeCheck(r.cttsPresent)),
      value(compat, (SpikeRunResult r) => spikeCheck(r.cttsPresent)),
    ),
    SpikeTableRow(
      'ctts offsets a 0',
      value(classic, (SpikeRunResult r) => spikeCheck(r.cttsAllZero)),
      value(compat, (SpikeRunResult r) => spikeCheck(r.cttsAllZero)),
    ),
    SpikeTableRow(
      'Duración legible tras interrupción',
      value(
        classicInt,
        (SpikeRunResult r) => spikeSecondsLabel(r.readableDurationMs),
      ),
      value(
        compatInt,
        (SpikeRunResult r) => spikeSecondsLabel(r.readableDurationMs),
      ),
    ),
    SpikeTableRow(
      'Pérdida por interrupción',
      value(classicInt, (SpikeRunResult r) => spikeSecondsLabel(r.lossMs)),
      value(compatInt, (SpikeRunResult r) => spikeSecondsLabel(r.lossMs)),
    ),
  ];
}

/// Texto completo de resultados para «COPIAR RESULTADOS»: sonda,
/// todas las pasadas y archivos. Puro: se prueba sin dispositivo.
String formatSpikeCopyText({
  required EncoderProbeResult? probe,
  required List<SpikeRunResult> runs,
  required List<SpikeFileInfo> files,
}) {
  final StringBuffer out = StringBuffer();
  out.writeln('SPIKE MUXER 3.1.2 — RESULTADOS');
  out.writeln('Configuración objetivo: 1280x720, 30 fps, H.264, 2,5 Mbps');
  out.writeln();
  out.writeln('SONDA DEL CODIFICADOR');
  if (probe == null) {
    out.writeln('Sin sonda.');
  } else {
    out.writeln('Codificador: ${probe.encoderName ?? '—'}');
    out.writeln('Perfiles anunciados: ${probe.profiles}');
    out.writeln('Niveles anunciados: ${probe.levels}');
    out.writeln('CBR soportado: ${spikeCheck(probe.cbrSupported)}');
    out.writeln(
      '720p en getOutputSizes(MediaCodec): '
      '${spikeCheck(probe.supports720pForMediaCodec)}',
    );
    out.writeln(
      'Perfil de salida: ${probe.outputProfile ?? '—'} / '
      'nivel: ${probe.outputLevel ?? '—'}',
    );
    out.writeln('csd-0: ${spikeCheck(probe.hasCsd0)} / '
        'csd-1: ${spikeCheck(probe.hasCsd1)}');
    out.writeln('Perfil real (SPS): ${probe.profileLabel}');
    if (probe.encoderError != null) {
      out.writeln('Error de sonda: ${probe.encoderError}');
    }
  }
  out.writeln();
  out.writeln('PASADAS (${runs.length})');
  if (runs.isEmpty) {
    out.writeln('Sin pasadas.');
  }
  for (final SpikeRunResult run in runs) {
    out.writeln(
      '- ${run.muxer} / ${run.kindLabel} / ${run.fileName}: '
      'duración ${spikeSecondsLabel(run.durationMs)} '
      '(objetivo ${spikeSecondsLabel(run.durationTargetMs)}), '
      '${run.width ?? '—'}x${run.height ?? '—'}, '
      'FPS ${run.fpsMeasured?.toStringAsFixed(1) ?? '—'}, '
      'muestras ${run.sampleCount ?? '—'}, '
      'keyframes ${run.keyframeCount ?? '—'}, '
      'primer keyframe ${spikeCheck(run.firstSampleKeyframe)}, '
      'PTS ${spikeCheck(run.ptsMonotonic)}, '
      'moov ${spikeCheck(run.moovPresent)}, '
      'ExoPlayer ${spikeCheck(run.exoplayerReady)} '
      '(${spikeSecondsLabel(run.exoplayerDurationMs)}), '
      'latencia máx ${run.maxWriteLatencyMs ?? '—'} ms, '
      'bitrate ${run.avgBitrateBps ?? '—'} bps, '
      'perfil ${run.profileLabel}, '
      'ctts ${spikeCheck(run.cttsPresent)} '
      'offsets 0 ${spikeCheck(run.cttsAllZero)}, '
      'pérdida ${run.lossMs == null ? '—' : spikeSecondsLabel(run.lossMs)}, '
      'tamaño ${spikeBytesLabel(run.sizeBytes)}',
    );
    if (run.muxerError != null) {
      out.writeln('  Error: ${run.muxerError}');
    }
    if (run.eosTimeout) {
      out.writeln('  Aviso: timeout al drenar EOS (5 s).');
    }
  }
  out.writeln();
  out.writeln('ARCHIVOS (${files.length})');
  if (files.isEmpty) {
    out.writeln('Sin archivos.');
  }
  for (final SpikeFileInfo file in files) {
    out.writeln('- ${file.name} (${spikeBytesLabel(file.sizeBytes)})');
  }
  return out.toString();
}

/// Servicio del canal del spike (`com.transmiti2.transmiti2/spike`).
// SPIKE 3.1.2 — TEMPORAL
abstract class MuxerSpikeService {
  Future<SpikePreviewInfo> startPreview();
  Future<void> stopPreview();
  Future<EncoderProbeResult> probeEncoder();
  Future<SpikeRunResult> recordWithMuxer({
    required String muxer,
    required int durationMs,
  });
  Future<SpikeRunResult> interruptWithMuxer({
    required String muxer,
    required int durationMs,
  });
  Future<SpikeRunResult> verifyFile(String fileName);
  Future<List<SpikeFileInfo>> listSpikeFiles();
  Future<int> deleteSpikeFiles();
}

// SPIKE 3.1.2 — TEMPORAL
class MethodChannelMuxerSpikeService implements MuxerSpikeService {
  MethodChannelMuxerSpikeService({MethodChannel? channel})
    : _channel =
          channel ?? const MethodChannel('com.transmiti2.transmiti2/spike');

  final MethodChannel _channel;

  SpikeException _fromPlatform(PlatformException e) {
    return SpikeException(e.code, e.message);
  }

  @override
  Future<SpikePreviewInfo> startPreview() async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>('startPreview');
      if (result == null) {
        throw const SpikeException('spike_error');
      }
      return SpikePreviewInfo.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<void> stopPreview() async {
    try {
      await _channel.invokeMethod<void>('stopPreview');
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<EncoderProbeResult> probeEncoder() async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>('probeEncoder');
      if (result == null) {
        throw const SpikeException('spike_error');
      }
      return EncoderProbeResult.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  Future<SpikeRunResult> _run(
    String method, {
    required String muxer,
    required int durationMs,
  }) async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>(method, <String, Object>{
            'muxer': muxer,
            'durationMs': durationMs,
          });
      if (result == null) {
        throw const SpikeException('spike_error');
      }
      return SpikeRunResult.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<SpikeRunResult> recordWithMuxer({
    required String muxer,
    required int durationMs,
  }) {
    return _run('recordWithMuxer', muxer: muxer, durationMs: durationMs);
  }

  @override
  Future<SpikeRunResult> interruptWithMuxer({
    required String muxer,
    required int durationMs,
  }) {
    return _run(
      'interruptWithMuxer',
      muxer: muxer,
      durationMs: durationMs,
    );
  }

  @override
  Future<SpikeRunResult> verifyFile(String fileName) async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>('verifyFile', <String, Object>{
            'fileName': fileName,
          });
      if (result == null) {
        throw const SpikeException('spike_error');
      }
      return SpikeRunResult.fromMap(result);
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<List<SpikeFileInfo>> listSpikeFiles() async {
    try {
      final List<Object?>? result = await _channel.invokeMethod<List<Object?>>(
        'listSpikeFiles',
      );
      if (result == null) {
        return const <SpikeFileInfo>[];
      }
      return result
          .map(
            (Object? e) =>
                SpikeFileInfo.fromMap(Map<Object?, Object?>.from(e as Map)),
          )
          .toList();
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }

  @override
  Future<int> deleteSpikeFiles() async {
    try {
      final Map<Object?, Object?>? result = await _channel
          .invokeMapMethod<String, Object?>('deleteSpikeFiles');
      if (result == null) {
        return 0;
      }
      return (result['deleted'] as num?)?.toInt() ?? 0;
    } on PlatformException catch (e) {
      throw _fromPlatform(e);
    }
  }
}
