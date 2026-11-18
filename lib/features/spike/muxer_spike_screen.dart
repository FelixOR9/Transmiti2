// SPIKE 3.1.2 — TEMPORAL
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/spike/muxer_spike_service.dart';
import 'package:transmiti2/features/streaming/application/permission_controller.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';
import 'package:transmiti2/shared/widgets/section_label.dart';

/// Pantalla debug TEMPORAL del spike del muxer (plan 3.1.2 §12.2).
///
/// Compara `MediaMuxer` clásico frente a `MediaMuxerCompat` de Media3
/// con el codificador real y la configuración objetivo real, por el
/// camino real de Camera2 (vista previa + superficie del encoder).
///
/// TEMPORAL — se elimina antes de cerrar 3.1.2: esta pantalla, su
/// ruta, su botón en el Estudio, el servicio y todo el código nativo
/// del spike. Los archivos del spike viven en
/// `<documents>/recordings/spike/` y no se registran en SQLite ni se
/// muestran como grabaciones.
///
/// Uso: sondear el codificador, grabar 10 s × 2 por muxer, interrumpir
/// 5 s × 3 por muxer, copiar los resultados y borrar los archivos.
/// Ningún resultado se presenta como definitivo hasta repetirse.
// SPIKE 3.1.2 — TEMPORAL
class MuxerSpikeScreen extends StatefulWidget {
  const MuxerSpikeScreen({super.key, MuxerSpikeService? service})
    : _service = service ?? const _DefaultSpikeService();

  final MuxerSpikeService _service;

  @override
  State<MuxerSpikeScreen> createState() => _MuxerSpikeScreenState();
}

/// Servicio por defecto (canal propio del spike). La inyección solo
/// existe para las pruebas.
// SPIKE 3.1.2 — TEMPORAL
class _DefaultSpikeService implements MuxerSpikeService {
  const _DefaultSpikeService();

  static final MethodChannelMuxerSpikeService _delegate =
      MethodChannelMuxerSpikeService();

  @override
  Future<SpikePreviewInfo> startPreview() => _delegate.startPreview();

  @override
  Future<void> stopPreview() => _delegate.stopPreview();

  @override
  Future<EncoderProbeResult> probeEncoder() => _delegate.probeEncoder();

  @override
  Future<SpikeRunResult> recordWithMuxer({
    required String muxer,
    required int durationMs,
  }) => _delegate.recordWithMuxer(muxer: muxer, durationMs: durationMs);

  @override
  Future<SpikeRunResult> interruptWithMuxer({
    required String muxer,
    required int durationMs,
  }) => _delegate.interruptWithMuxer(muxer: muxer, durationMs: durationMs);

  @override
  Future<SpikeRunResult> verifyFile(String fileName) =>
      _delegate.verifyFile(fileName);

  @override
  Future<List<SpikeFileInfo>> listSpikeFiles() =>
      _delegate.listSpikeFiles();

  @override
  Future<int> deleteSpikeFiles() => _delegate.deleteSpikeFiles();
}

// SPIKE 3.1.2 — TEMPORAL
class _MuxerSpikeScreenState extends State<MuxerSpikeScreen> {
  static const int _normalDurationMs = 10000;
  static const int _normalRepetitions = 2;
  static const int _interruptDurationMs = 5000;
  static const int _interruptRepetitions = 3;

  MuxerSpikeService get _service => widget._service;

  late PermissionController _permissions;
  bool _didInit = false;
  SpikePreviewInfo? _preview;
  EncoderProbeResult? _probe;
  List<SpikeRunResult> _runs = const <SpikeRunResult>[];
  List<SpikeFileInfo> _files = const <SpikeFileInfo>[];
  String? _busy;
  String? _error;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_didInit) {
      _didInit = true;
      _permissions = AppScope.of(context).streaming.permissions;
      _permissions.attach();
      unawaited(_permissions.refresh());
      unawaited(_maybeStartPreview());
      unawaited(_refreshFiles());
    }
  }

  @override
  void dispose() {
    // La vista previa del spike es propia: se cierra al salir.
    unawaited(_service.stopPreview());
    _permissions.detach();
    super.dispose();
  }

  Future<void> _maybeStartPreview() async {
    if (_permissions.states.camera == PermissionStatus.granted) {
      await _startPreview();
    }
  }

  Future<void> _startPreview() async {
    try {
      final SpikePreviewInfo info = await _service.startPreview();
      if (!mounted) {
        return;
      }
      setState(() {
        _preview = info;
        _error = null;
      });
    } on SpikeException catch (e) {
      if (!mounted) {
        return;
      }
      setState(() => _error = _errorText(e));
    }
  }

  Future<void> _requestCamera() async {
    try {
      final PermissionStatus result =
          await _permissions.request(PermissionKind.camera);
      if (!mounted) {
        return;
      }
      if (result == PermissionStatus.granted) {
        await _startPreview();
      } else if (result == PermissionStatus.permanentlyDenied) {
        setState(
          () => _error =
              'Permiso de cámara bloqueado en los ajustes del sistema.',
        );
      }
    } on StreamingException catch (e) {
      if (!mounted) {
        return;
      }
      setState(() => _error = 'No se pudo pedir el permiso: ${e.code}');
    }
  }

  Future<void> _probeEncoder() async {
    if (_busy != null) {
      return;
    }
    setState(() {
      _busy = 'Sondeando el codificador…';
      _error = null;
    });
    try {
      final EncoderProbeResult result = await _service.probeEncoder();
      if (!mounted) {
        return;
      }
      setState(() => _probe = result);
    } on SpikeException catch (e) {
      if (mounted) {
        setState(() => _error = _errorText(e));
      }
    } finally {
      if (mounted) {
        setState(() => _busy = null);
      }
    }
  }

  Future<void> _runBatch({
    required String label,
    required Future<SpikeRunResult> Function(String muxer) run,
    required String muxer,
    required int repetitions,
  }) async {
    if (_busy != null) {
      return;
    }
    setState(() {
      _busy = label;
      _error = null;
    });
    try {
      for (int i = 0; i < repetitions; i++) {
        final SpikeRunResult result = await run(muxer);
        if (!mounted) {
          return;
        }
        setState(() => _runs = <SpikeRunResult>[..._runs, result]);
      }
      await _refreshFiles();
    } on SpikeException catch (e) {
      if (mounted) {
        setState(() => _error = _errorText(e));
      }
    } finally {
      if (mounted) {
        setState(() => _busy = null);
      }
    }
  }

  Future<void> _refreshFiles() async {
    try {
      final List<SpikeFileInfo> files = await _service.listSpikeFiles();
      if (!mounted) {
        return;
      }
      setState(() => _files = files);
    } on SpikeException catch (e) {
      if (mounted) {
        setState(() => _error = _errorText(e));
      }
    }
  }

  Future<void> _copyResults() async {
    final String text = formatSpikeCopyText(
      probe: _probe,
      runs: _runs,
      files: _files,
    );
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Resultados copiados al portapapeles.')),
    );
  }

  Future<void> _deleteFiles() async {
    final bool? confirm = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) {
        return AlertDialog(
          title: const Text('Eliminar archivos del spike'),
          content: Text(
            'Se borran los ${_files.length} archivos de '
            '<documents>/recordings/spike/. Los resultados ya medidos '
            'se conservan en esta pantalla.',
          ),
          actions: <Widget>[
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('CANCELAR'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('ELIMINAR'),
            ),
          ],
        );
      },
    );
    if (confirm != true) {
      return;
    }
    try {
      final int deleted = await _service.deleteSpikeFiles();
      if (!mounted) {
        return;
      }
      await _refreshFiles();
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Eliminados $deleted archivos del spike.')),
      );
    } on SpikeException catch (e) {
      if (mounted) {
        setState(() => _error = _errorText(e));
      }
    }
  }

  Future<void> _verifyFile(SpikeFileInfo file) async {
    try {
      final SpikeRunResult result = await _service.verifyFile(file.name);
      if (!mounted) {
        return;
      }
      await showDialog<void>(
        context: context,
        builder: (BuildContext dialogContext) {
          return AlertDialog(
            title: Text(file.name),
            content: Text(_verifySummary(result)),
            actions: <Widget>[
              FilledButton(
                onPressed: () => Navigator.of(dialogContext).pop(),
                child: const Text('CERRAR'),
              ),
            ],
          );
        },
      );
    } on SpikeException catch (e) {
      if (mounted) {
        setState(() => _error = _errorText(e));
      }
    }
  }

  String _verifySummary(SpikeRunResult result) {
    return 'Extractor abre: ${spikeCheck(result.extractorOpens)}\n'
        'moov: ${spikeCheck(result.moovPresent)}\n'
        'Duración: ${spikeSecondsLabel(result.durationMs)}\n'
        'Resolución: ${result.width ?? '—'}x${result.height ?? '—'}\n'
        'Muestras: ${result.sampleCount ?? '—'}\n'
        'Keyframes: ${result.keyframeCount ?? '—'}\n'
        'Primer keyframe: ${spikeCheck(result.firstSampleKeyframe)}\n'
        'PTS monótonos: ${spikeCheck(result.ptsMonotonic)}\n'
        'ExoPlayer: ${spikeCheck(result.exoplayerReady)} '
        '(${spikeSecondsLabel(result.exoplayerDurationMs)})\n'
        'Perfil: ${result.profileLabel}\n'
        'ctts: ${spikeCheck(result.cttsPresent)} '
        'offsets 0: ${spikeCheck(result.cttsAllZero)}';
  }

  String _errorText(SpikeException e) {
    return e.message == null ? e.code : '${e.code}: ${e.message}';
  }

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final bool busy = _busy != null;
    return Scaffold(
      appBar: AppBar(title: const Text('SPIKE MUXER — TEMPORAL')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              Card(
                color: AppColors.live,
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Text(
                    'TEMPORAL — se elimina antes de cerrar 3.1.2.\n'
                    'Compara MediaMuxer clásico con MediaMuxerCompat '
                    'con el codificador real (720p, 30 fps, 2,5 Mbps). '
                    'Los archivos quedan en recordings/spike/ y no se '
                    'registran como grabaciones.',
                    style: text.bodyMedium?.copyWith(color: Colors.white),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              const SectionLabel('VISTA PREVIA EN VIVO'),
              const SizedBox(height: 8),
              if (_preview != null)
                _previewArea(_preview!)
              else if (_permissions.states.camera !=
                  PermissionStatus.granted)
                Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: <Widget>[
                    Text(
                      'El spike necesita la cámara para grabar con el '
                      'camino real de Camera2.',
                      style: text.bodyMedium?.copyWith(
                        color: AppColors.textMuted,
                      ),
                    ),
                    const SizedBox(height: 12),
                    AppButton.primary(
                      label: 'PEDIR PERMISO DE CÁMARA',
                      onPressed: _requestCamera,
                    ),
                    if (_permissions.states.camera ==
                        PermissionStatus.permanentlyDenied) ...<Widget>[
                      const SizedBox(height: 12),
                      AppButton.primary(
                        label: 'ABRIR AJUSTES',
                        onPressed: () =>
                            _permissions.openAppSettings(),
                      ),
                    ],
                  ],
                )
              else
                Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: <Widget>[
                    Text(
                      'La vista previa no está activa.',
                      style: text.bodyMedium?.copyWith(
                        color: AppColors.textMuted,
                      ),
                    ),
                    const SizedBox(height: 12),
                    AppButton.primary(
                      label: 'INICIAR VISTA PREVIA',
                      onPressed: busy ? () {} : _startPreview,
                    ),
                  ],
                ),
              if (_error != null) ...<Widget>[
                const SizedBox(height: 12),
                Text(
                  _error!,
                  style: text.bodyMedium?.copyWith(color: AppColors.live),
                ),
              ],
              if (busy) ...<Widget>[
                const SizedBox(height: 12),
                Text(_busy!, style: text.bodyMedium),
                const SizedBox(height: 8),
                const LinearProgressIndicator(),
              ],
              const SizedBox(height: 24),
              const SectionLabel('SONDA DEL CODIFICADOR'),
              const SizedBox(height: 8),
              Text(
                'Sin grabar: codificador H.264, perfiles, 720p para '
                'MediaCodec, CBR y perfil real del SPS.',
                style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
              ),
              const SizedBox(height: 12),
              AppButton.primary(
                label: 'SONDEAR CODIFICADOR',
                onPressed: busy ? () {} : _probeEncoder,
              ),
              if (_probe != null) ...<Widget>[
                const SizedBox(height: 12),
                SelectableText(_probeSummary(_probe!), style: text.bodyMedium),
              ],
              const SizedBox(height: 24),
              const SectionLabel('GRABACIÓN NORMAL — 10 s × 2'),
              const SizedBox(height: 8),
              Text(
                'Graba con cada muxer, con EOS y stop() correctos. La '
                'vista previa sigue visible durante la grabación.',
                style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
              ),
              const SizedBox(height: 12),
              AppButton.primary(
                label: 'GRABAR 10 s × 2 — CLÁSICO',
                onPressed: busy
                    ? () {}
                    : () => _runBatch(
                          label: 'Grabando con el muxer clásico…',
                          run: (String muxer) => _service.recordWithMuxer(
                            muxer: muxer,
                            durationMs: _normalDurationMs,
                          ),
                          muxer: 'classic',
                          repetitions: _normalRepetitions,
                        ),
              ),
              const SizedBox(height: 12),
              AppButton.primary(
                label: 'GRABAR 10 s × 2 — COMPAT',
                onPressed: busy
                    ? () {}
                    : () => _runBatch(
                          label: 'Grabando con MediaMuxerCompat…',
                          run: (String muxer) => _service.recordWithMuxer(
                            muxer: muxer,
                            durationMs: _normalDurationMs,
                          ),
                          muxer: 'compat',
                          repetitions: _normalRepetitions,
                        ),
              ),
              const SizedBox(height: 24),
              const SectionLabel('INTERRUPCIÓN — 5 s × 3'),
              const SizedBox(height: 8),
              Text(
                'Abandona el muxer sin stop(): simula una muerte del '
                'proceso. Se mide cuánto queda legible.',
                style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
              ),
              const SizedBox(height: 12),
              AppButton.primary(
                label: 'INTERRUMPIR 5 s × 3 — CLÁSICO',
                onPressed: busy
                    ? () {}
                    : () => _runBatch(
                          label: 'Interrumpiendo el muxer clásico…',
                          run: (String muxer) => _service.interruptWithMuxer(
                            muxer: muxer,
                            durationMs: _interruptDurationMs,
                          ),
                          muxer: 'classic',
                          repetitions: _interruptRepetitions,
                        ),
              ),
              const SizedBox(height: 12),
              AppButton.primary(
                label: 'INTERRUMPIR 5 s × 3 — COMPAT',
                onPressed: busy
                    ? () {}
                    : () => _runBatch(
                          label: 'Interrumpiendo MediaMuxerCompat…',
                          run: (String muxer) => _service.interruptWithMuxer(
                            muxer: muxer,
                            durationMs: _interruptDurationMs,
                          ),
                          muxer: 'compat',
                          repetitions: _interruptRepetitions,
                        ),
              ),
              if (_runs.isNotEmpty) ...<Widget>[
                const SizedBox(height: 24),
                const SectionLabel('RESULTADOS POR MUXER'),
                const SizedBox(height: 8),
                _resultsTable(text),
                const SizedBox(height: 12),
                const SectionLabel('PASADAS'),
                const SizedBox(height: 8),
                ..._runs.map(
                  (SpikeRunResult run) => Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: SelectableText(
                      _runSummary(run),
                      style: text.bodyMedium,
                    ),
                  ),
                ),
              ],
              const SizedBox(height: 24),
              const SectionLabel('ARCHIVOS DEL SPIKE'),
              const SizedBox(height: 8),
              if (_files.isEmpty)
                Text(
                  'Sin archivos en recordings/spike/.',
                  style: text.bodyMedium?.copyWith(
                    color: AppColors.textMuted,
                  ),
                )
              else
                ..._files.map(
                  (SpikeFileInfo file) => Card(
                    color: AppColors.surface,
                    child: ListTile(
                      title: Text(file.name),
                      subtitle: Text(spikeBytesLabel(file.sizeBytes)),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => _verifyFile(file),
                    ),
                  ),
                ),
              const SizedBox(height: 16),
              AppButton.primary(
                label: 'COPIAR RESULTADOS',
                onPressed: busy ? () {} : _copyResults,
              ),
              const SizedBox(height: 12),
              AppButton.primary(
                label: 'ELIMINAR ARCHIVOS DEL SPIKE',
                onPressed: busy || _files.isEmpty ? () {} : _deleteFiles,
              ),
              const SizedBox(height: 16),
              Text(
                'Variante manual: inicia una interrupción, saca la app '
                'de Recientes a mitad de grabación (muerte real del '
                'proceso), reabre y toca el archivo para verificarlo.',
                style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _probeSummary(EncoderProbeResult probe) {
    return 'Codificador: ${probe.encoderName ?? '—'}\n'
        'Perfiles anunciados: ${probe.profiles}\n'
        'Niveles anunciados: ${probe.levels}\n'
        'CBR soportado: ${spikeCheck(probe.cbrSupported)}\n'
        '720p en getOutputSizes(MediaCodec): '
        '${spikeCheck(probe.supports720pForMediaCodec)}\n'
        'Perfil de salida: ${probe.outputProfile ?? '—'} / '
        'nivel: ${probe.outputLevel ?? '—'}\n'
        'csd-0: ${spikeCheck(probe.hasCsd0)} / '
        'csd-1: ${spikeCheck(probe.hasCsd1)}\n'
        'Perfil real (SPS): ${probe.profileLabel}'
        '${probe.encoderError == null ? '' : '\nError: ${probe.encoderError}'}';
  }

  String _runSummary(SpikeRunResult run) {
    return '${run.muxer} / ${run.kindLabel} / ${run.fileName}: '
        '${spikeSecondsLabel(run.durationMs)} de '
        '${spikeSecondsLabel(run.durationTargetMs)}, '
        '${run.sampleCount ?? '—'} muestras, '
        'moov ${spikeCheck(run.moovPresent)}, '
        'ExoPlayer ${spikeCheck(run.exoplayerReady)}'
        '${run.lossMs == null ? '' : ', pérdida ${spikeSecondsLabel(run.lossMs)}'}';
  }

  Widget _resultsTable(TextTheme text) {
    final List<SpikeTableRow> rows = spikeComparisonRows(_runs);
    final TextStyle? cellStyle = text.bodyMedium;
    final TextStyle? headerStyle = text.labelMedium?.copyWith(
      color: AppColors.textMuted,
    );
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Table(
        columnWidths: const <int, TableColumnWidth>{
          0: FixedColumnWidth(170),
          1: FixedColumnWidth(150),
          2: FixedColumnWidth(150),
        },
        border: TableBorder.all(color: AppColors.surface),
        children: <TableRow>[
          TableRow(
            children: <Widget>[
              _tableCell('MEDIDA', headerStyle),
              _tableCell('CLÁSICO', headerStyle),
              _tableCell('COMPAT', headerStyle),
            ],
          ),
          ...rows.map(
            (SpikeTableRow row) => TableRow(
              children: <Widget>[
                _tableCell(row.label, cellStyle),
                _tableCell(row.classic, cellStyle),
                _tableCell(row.compat, cellStyle),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _tableCell(String value, TextStyle? style) {
    return Padding(
      padding: const EdgeInsets.all(8),
      child: Text(value, style: style),
    );
  }

  /// Imagen real de la cámara, sin deformar (mismo patrón que el
  /// Estudio: relación de aspecto de la imagen ya rotada y `RotatedBox`
  /// con la rotación del motor).
  Widget _previewArea(SpikePreviewInfo info) {
    final int rotation = info.rotationDegrees;
    final bool swapped = rotation == 90 || rotation == 270;
    final double aspect = swapped
        ? info.height / info.width
        : info.width / info.height;
    return ClipRRect(
      borderRadius: BorderRadius.circular(16),
      child: AspectRatio(
        aspectRatio: aspect,
        child: RotatedBox(
          quarterTurns: rotation ~/ 90,
          child: Texture(textureId: info.textureId),
        ),
      ),
    );
  }
}
