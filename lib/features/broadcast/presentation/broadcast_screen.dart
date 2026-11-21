import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/streaming/application/permission_controller.dart';
import 'package:transmiti2/features/streaming/application/preview_controller.dart';
import 'package:transmiti2/features/streaming/application/recording_controller.dart';
import 'package:transmiti2/features/streaming/application/streaming_controller.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';
import 'package:transmiti2/features/streaming/domain/stream_destination.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';
import 'package:transmiti2/shared/widgets/app_input.dart';
import 'package:transmiti2/shared/widgets/section_label.dart';

/// Estudio de transmisión (subetapas 3.1.0 y 3.1.1).
///
/// Lo que hace: guardar el destino manual de Facebook Live (URL RTMPS +
/// clave) y elegir el destino activo. La clave se guarda cifrada en el
/// teléfono y nunca se muestra ni se guarda en la base.
///
/// Permisos (3.1.0): no se pide ningún permiso al entrar. Al tocar
/// "Vista previa" se pide la cámara con un motivo en la app antes del
/// diálogo del sistema. Si se deniega, se puede reintentar; si se deniega
/// permanentemente, se ofrece abrir los ajustes de la aplicación.
///
/// Vista previa (3.1.1): con el permiso concedido, "Vista previa" abre
/// la cámara trasera real con Camera2 y muestra su imagen en vivo con el
/// widget `Texture` (sin deformar: relación de aspecto y rotación según
/// la orientación del sensor). Al salir de la pantalla, al ir a segundo
/// plano o al girar el teléfono, la cámara se cierra o se reinicia.
///
/// Grabación local (Fase B, 3.1.2; audio en 3.1.3): con la vista previa
/// activa, "GRABAR" graba el partido en MP4 en el teléfono (segmentos
/// de ~60 s) y "DETENER GRABACIÓN" lo para. Al tocar «GRABAR» se pide
/// el micrófono: concedido → la grabación lleva audio AAC; denegado →
/// video-only y el chip muestra «SOLO VIDEO» (la denegación no impide
/// grabar). Mientras se graba, la vista previa no se para ni se
/// reinicia (ni al salir de la pantalla: la grabación continúa por el
/// servicio en primer plano y la UI se re-sincroniza al volver).
///
/// Lo que NO hace todavía: transmisión (Fase 3.2). Ese botón aparece
/// deshabilitado con la fase en la que llega.
class BroadcastScreen extends StatefulWidget {
  const BroadcastScreen({super.key});

  @override
  State<BroadcastScreen> createState() => _BroadcastScreenState();
}

class _BroadcastScreenState extends State<BroadcastScreen> {
  final TextEditingController _labelController = TextEditingController();
  final TextEditingController _urlController = TextEditingController();
  final TextEditingController _keyController = TextEditingController();

  List<StreamDestination> _destinations = const <StreamDestination>[];
  String? _errorCode;
  bool _saving = false;
  bool _loading = true;
  bool _didLoad = false;
  late PermissionController _permissions;
  late PreviewController _preview;
  late RecordingController _recording;

  StreamingController get _streaming => AppScope.of(context).streaming;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // AppScope solo se puede leer cuando las dependencias están listas,
    // no en initState.
    if (!_didLoad) {
      _didLoad = true;
      _refresh();
      _permissions = _streaming.permissions;
      _permissions.attach();
      unawaited(_permissions.refresh());
      _preview = _streaming.preview;
      _preview.attach();
      _recording = _streaming.recording;
      _recording.attach();
    }
  }

  @override
  void dispose() {
    _recording.detach();
    _preview.detach();
    // Al salir de la pantalla la cámara se cierra: no se mantiene
    // abierta innecesariamente. Con grabación activa la parada es un
    // no-op (la grabación continúa por el FGS; plan §6.4).
    unawaited(_preview.stop());
    _permissions.detach();
    _labelController.dispose();
    _urlController.dispose();
    _keyController.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    final List<StreamDestination> destinations =
        await _streaming.listDestinations();
    if (!mounted) {
      return;
    }
    setState(() {
      _destinations = destinations;
      _loading = false;
    });
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _errorCode = null;
    });
    try {
      await _streaming.saveManualDestination(
        label: _labelController.text,
        ingestUrl: _urlController.text,
        key: _keyController.text,
      );
      _labelController.clear();
      _urlController.clear();
      _keyController.clear();
      await _refresh();
    } on StreamingException catch (e) {
      setState(() => _errorCode = e.code);
    } on ArgumentError {
      setState(() => _errorCode = 'invalid_input');
    } finally {
      if (mounted) {
        setState(() => _saving = false);
      }
    }
  }

  Future<void> _delete(StreamDestination destination) async {
    await _streaming.deleteDestination(destination.id);
    await _refresh();
  }

  Future<void> _setActive(StreamDestination destination) async {
    await _streaming.setActiveDestination(destination.id);
    await _refresh();
  }

  /// Botón "Vista previa": si la cámara está activa, la detiene; si no,
  /// comprueba el permiso y la abre.
  void _onPreviewTap() {
    if (_preview.state == PreviewState.active ||
        _preview.state == PreviewState.recovering) {
      unawaited(_preview.stop());
      return;
    }
    unawaited(_startPreviewFlow());
  }

  /// Flujo de "Vista previa": si el permiso está concedido se abre la
  /// cámara directamente; si no, motivo en la app, diálogo del sistema y,
  /// al conceder, se abre la cámara. Nunca se pide el permiso al entrar
  /// ni se muestra una cámara ficticia.
  Future<void> _startPreviewFlow() async {
    final PermissionStatus current = _permissions.states.camera;
    if (current == PermissionStatus.granted) {
      await _preview.start();
      return;
    }
    if (current == PermissionStatus.permanentlyDenied) {
      final bool? open = await _showSettingsDialog();
      if (open == true && mounted) {
        await _permissions.openAppSettings();
      }
      return;
    }
    final bool? allow = await _showRationaleDialog();
    if (allow != true || !mounted) {
      return;
    }
    try {
      final PermissionStatus result =
          await _permissions.request(PermissionKind.camera);
      if (!mounted) {
        return;
      }
      if (result == PermissionStatus.granted) {
        await _preview.start();
      } else if (result == PermissionStatus.permanentlyDenied) {
        final bool? open = await _showSettingsDialog();
        if (open == true && mounted) {
          await _permissions.openAppSettings();
        }
      }
    } on StreamingException catch (e) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('No se pudo pedir el permiso: ${e.code}')),
      );
    }
  }

  /// Copia el diagnóstico del último intento de vista previa al
  /// portapapeles (Fase A), para poder pegarlo sin ordenador ni logcat.
  Future<void> _copyDiagnostics() async {
    final String? text = _preview.diagnosticText;
    if (text == null) {
      return;
    }
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Diagnóstico copiado al portapapeles.'),
      ),
    );
  }

  /// Copia el diagnóstico de la grabación al portapapeles (Fase B,
  /// 3.1.2), igual que el de la vista previa.
  Future<void> _copyRecordingDiagnostics() async {
    final String? text = _recording.diagnosticText;
    if (text == null) {
      return;
    }
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Diagnóstico de grabación copiado.'),
      ),
    );
  }

  /// Botón "GRABAR" / "DETENER GRABACIÓN" (Fase B, 3.1.2). Solo con la
  /// vista previa activa. La grabación se asocia al partido actual, si
  /// lo hay. Confirmaciones con `SnackBar` al iniciar y al detener.
  Future<void> _onRecordTap() async {
    if (_recording.isRecordingActive) {
      await _recording.stop();
      if (!mounted) {
        return;
      }
      final RecordingStoppedInfo? stopped = _recording.lastStopped;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            stopped == null
                ? 'Grabación detenida.'
                : 'Grabación detenida: ${stopped.segmentCount} segmento(s), '
                    'duración total ${_formatDuration(stopped.totalDurationMs)}.',
          ),
        ),
      );
      return;
    }
    final int? matchId = AppScope.of(context).session.detail?.match.id;
    await _recording.start(matchId: matchId);
    if (!mounted) {
      return;
    }
    if (_recording.state == RecordingState.recording ||
        _recording.state == RecordingState.starting) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Grabación iniciada.')),
      );
    } else if (_recording.state == RecordingState.error) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(_recordingErrorText())),
      );
    }
  }

  /// Error de grabación en lenguaje normal + código, para mostrarlo en
  /// la pantalla (plan §10.1).
  String _recordingErrorText() {
    final String code = _recording.errorCode ?? 'error';
    final String plain = switch (code) {
      StreamContract.storageFull => 'Sin espacio para grabar.',
      StreamContract.storageCheckUnavailable =>
        'No se pudo comprobar el espacio disponible para grabar.',
      StreamContract.recordingsDirUnavailable =>
        'No se pudo preparar la carpeta de grabaciones.',
      StreamContract.encoderError =>
        'El codificador no aceptó la configuración.',
      StreamContract.muxerError =>
        'No se pudo escribir el archivo de video.',
      StreamContract.cameraDisconnected =>
        'La cámara se desconectó durante la grabación.',
      StreamContract.cameraUnavailable =>
        'Inicia la vista previa antes de grabar.',
      StreamContract.permissionDenied => 'Permiso de cámara denegado.',
      _ => 'No se pudo grabar.',
    };
    return '$plain ($code)';
  }

  /// Duración en `MM:SS` para las confirmaciones y el indicador.
  String _formatDuration(int ms) {
    final int totalSeconds = ms ~/ 1000;
    final int minutes = totalSeconds ~/ 60;
    final int seconds = totalSeconds % 60;
    return '${minutes.toString().padLeft(2, '0')}:'
        '${seconds.toString().padLeft(2, '0')}';
  }

  /// Motivo en la app, antes del diálogo del sistema.
  Future<bool?> _showRationaleDialog() {
    return showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) {
        return AlertDialog(
          title: const Text('Permiso de cámara'),
          content: const Text(
            'TRANSMITI2 usa la cámara trasera para mostrar la vista '
            'previa del partido. El permiso solo se usa cuando abres la '
            'vista previa.',
          ),
          actions: <Widget>[
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('CANCELAR'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('PERMITIR CÁMARA'),
            ),
          ],
        );
      },
    );
  }

  /// Denegación permanente: el diálogo del sistema ya no haría nada;
  /// se ofrece abrir los ajustes de la aplicación.
  Future<bool?> _showSettingsDialog() {
    return showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) {
        return AlertDialog(
          title: const Text('Permiso bloqueado'),
          content: const Text(
            'El permiso de cámara está denegado de forma permanente. '
            'Ábrelo en los ajustes de la aplicación para usar la vista '
            'previa.',
          ),
          actions: <Widget>[
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('CANCELAR'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('ABRIR AJUSTES'),
            ),
          ],
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Estudio de transmisión')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              const SectionLabel('DESTINO DE FACEBOOK LIVE'),
              const SizedBox(height: 8),
              Text(
                'Pega la URL RTMPS y la clave desde Facebook Live Producer. '
                'La clave se guarda cifrada en el teléfono y nunca se muestra.',
                style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _labelController,
                decoration: appInputDecoration(
                  label: 'Nombre del destino',
                  hint: 'Facebook Live',
                ),
                textInputAction: TextInputAction.next,
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _urlController,
                decoration: appInputDecoration(
                  label: 'URL RTMPS',
                  hint: 'rtmps://live-api-s.facebook.com:443/rtmp/',
                ),
                keyboardType: TextInputType.url,
                autocorrect: false,
                textInputAction: TextInputAction.next,
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _keyController,
                decoration: appInputDecoration(
                  label: 'Clave de transmisión',
                  hint: 'Pégala aquí, no se mostrará',
                ),
                obscureText: true,
                enableSuggestions: false,
                autocorrect: false,
                textInputAction: TextInputAction.done,
                onSubmitted: (_) => _save(),
              ),
              if (_errorCode != null) ...<Widget>[
                const SizedBox(height: 12),
                Text(
                  'No se pudo guardar el destino: $_errorCode',
                  style: text.bodyMedium?.copyWith(color: AppColors.live),
                ),
              ],
              const SizedBox(height: 16),
              AppButton.primary(
                label: _saving ? 'GUARDANDO…' : 'GUARDAR DESTINO',
                onPressed: _saving ? () {} : _save,
              ),
              const SizedBox(height: 24),
              const SectionLabel('DESTINOS GUARDADOS'),
              const SizedBox(height: 8),
              if (_loading)
                const Center(child: CircularProgressIndicator())
              else if (_destinations.isEmpty)
                Text(
                  'Todavía no hay destinos guardados.',
                  style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
                )
              else
                ..._destinations.map(_destinationTile),
              const SizedBox(height: 24),
              ListenableBuilder(
                listenable: _permissions,
                builder: (BuildContext context, Widget? child) {
                  return ListenableBuilder(
                    listenable: _preview,
                    builder: (BuildContext context, Widget? child) {
                      return ListenableBuilder(
                        listenable: _recording,
                        builder: (BuildContext context, Widget? child) {
                          return _videoSection(text);
                        },
                      );
                    },
                  );
                },
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _videoSection(TextTheme text) {
    final PreviewState state = _preview.state;
    final PermissionStatus camera = _permissions.states.camera;
    final bool requesting = _permissions.isRequesting;
    final String explanation;
    switch (state) {
      case PreviewState.active:
        explanation = 'Vista previa activa: imagen real de la cámara trasera.';
      case PreviewState.preparing:
        explanation = 'Preparando la cámara…';
      case PreviewState.recovering:
        explanation = 'Recuperando la vista previa…';
      case PreviewState.unavailable:
        explanation =
            'Cámara no disponible en este dispositivo.';
      case PreviewState.permissionDenied:
        explanation =
            'El permiso de cámara está denegado. Puedes volver a '
            'intentarlo para abrir la vista previa.';
      case PreviewState.error:
        explanation =
            'No se pudo iniciar la vista previa'
            '${_preview.errorCode != null ? ': ${_preview.errorCode}' : '.'}';
      case PreviewState.closed:
        switch (camera) {
          case PermissionStatus.granted:
            explanation =
                'Cámara concedida. Toca «Vista previa» para ver la '
                'imagen real de la cámara trasera.';
          case PermissionStatus.denied:
            explanation =
                'El permiso de cámara está denegado. Puedes volver a '
                'intentarlo.';
          case PermissionStatus.permanentlyDenied:
            explanation =
                'El permiso de cámara está bloqueado en los ajustes del '
                'sistema. Ábrelo desde los ajustes de la aplicación para '
                'usar la vista previa.';
          case PermissionStatus.notRequested:
            explanation =
                'La vista previa usa la cámara trasera del teléfono. El '
                'permiso se pide solo cuando tocas «Vista previa».';
        }
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        const SectionLabel('VIDEO'),
        const SizedBox(height: 8),
        Text(
          explanation,
          style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
        ),
        const SizedBox(height: 12),
        if (state == PreviewState.active ||
            state == PreviewState.recovering)
          ...<Widget>[
            _previewArea(_preview.info!),
            const SizedBox(height: 12),
            // Con grabación activa la vista previa no se detiene
            // (plan §6.4): el botón queda deshabilitado mientras se
            // graba, igual que en recuperación.
            AppButton.primary(
              label: _recording.isRecordingActive
                  ? 'GRABANDO…'
                  : state == PreviewState.recovering
                  ? 'RECUPERANDO…'
                  : 'DETENER VISTA PREVIA',
              onPressed:
                  _recording.isRecordingActive ||
                      state == PreviewState.recovering
                  ? () {}
                  : _onPreviewTap,
            ),
          ]
        else if (state == PreviewState.preparing)
          const Center(child: CircularProgressIndicator())
        else if (state == PreviewState.unavailable ||
            state == PreviewState.error)
          AppButton.primary(
            label: 'REINTENTAR',
            onPressed: _onPreviewTap,
          )
        else
          AppButton.primary(
            label: requesting ? 'PIDIENDO PERMISO…' : 'VISTA PREVIA',
            onPressed: requesting ? () {} : _onPreviewTap,
          ),
        // Bloque de diagnóstico (Fase A): tras un intento de preview,
        // en error y también con la preview activa (configuración
        // elegida). No interfiere con el resto de la pantalla.
        if (_preview.diagnosticText != null) ...<Widget>[
          const SizedBox(height: 12),
          _diagnosticBlock(text),
        ],
        const SizedBox(height: 12),
        const _PhaseButton(
          label: 'TRANSMITIR',
          phase: 'Fase 3.2',
        ),
        const SizedBox(height: 12),
        // Grabación local (Fase B, 3.1.2): botón real; solo activo con
        // la vista previa activa (plan §6.2 y §10.1).
        _recordButton(text),
        // Indicador de grabación activa: punto rojo + «GRABANDO» +
        // tiempo transcurrido + segmento actual (plan §10.1).
        if (_recording.state == RecordingState.recording ||
            _recording.state == RecordingState.rotating) ...<Widget>[
          const SizedBox(height: 12),
          _recordingIndicator(text),
        ],
        // Error de grabación en lenguaje normal + código.
        if (_recording.state == RecordingState.error) ...<Widget>[
          const SizedBox(height: 12),
          Text(
            _recordingErrorText(),
            style: text.bodyMedium?.copyWith(color: AppColors.live),
          ),
        ],
        // Tarjeta de diagnóstico de grabación (Fase B, 3.1.2), análoga
        // a la de vista previa, con botón de copiar.
        if (_recording.diagnosticText != null) ...<Widget>[
          const SizedBox(height: 12),
          _recordingDiagnosticBlock(text),
        ],
        const SizedBox(height: 12),
      ],
    );
  }

  /// Botón de grabación (Fase B, 3.1.2): «GRABAR» con la vista previa
  /// activa, «DETENER GRABACIÓN» mientras se graba. Deshabilitado sin
  /// vista previa activa (con el motivo debajo).
  Widget _recordButton(TextTheme text) {
    final RecordingState recState = _recording.state;
    final bool previewActive = _preview.state == PreviewState.active;
    final bool recording =
        recState == RecordingState.recording ||
        recState == RecordingState.rotating;
    final bool busy =
        recState == RecordingState.starting ||
        recState == RecordingState.stopping;
    final String label;
    if (recording) {
      label = 'DETENER GRABACIÓN';
    } else if (recState == RecordingState.starting) {
      label = 'INICIANDO…';
    } else if (recState == RecordingState.stopping) {
      label = 'DETENIENDO…';
    } else {
      label = 'GRABAR';
    }
    final bool enabled = previewActive && !busy;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        FilledButton(
          onPressed: enabled ? _onRecordTap : null,
          style: FilledButton.styleFrom(
            backgroundColor: recording ? AppColors.live : null,
            foregroundColor: recording ? Colors.white : null,
            minimumSize: const Size.fromHeight(60),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
          ),
          child: Text(label),
        ),
        if (!previewActive && !recording) ...<Widget>[
          const SizedBox(height: 4),
          Text(
            'Activa la vista previa para grabar.',
            textAlign: TextAlign.center,
            style: text.bodySmall?.copyWith(color: AppColors.textMuted),
          ),
        ],
      ],
    );
  }

  /// Indicador de grabación activa (Fase B, 3.1.2): punto rojo +
  /// «GRABANDO» + tiempo transcurrido + segmento actual. `Wrap` para
  /// que quepa en pantallas estrechas (360 px) sin desbordar. El
  /// número de segmento es el del controlador (1-based, índice
  /// nativo; fix duraciones 3.1.2, paso 3: antes era
  /// `last.index + 1` de la lista parcial de Dart).
  /// 3.1.3: el chip «CON AUDIO» / «SOLO VIDEO» refleja el estado
  /// REAL de la grabación (del evento `recording_started` / del
  /// snapshot), no el permiso: si el audio se pierde en caliente,
  /// el chip pasa a «SOLO VIDEO» sin detener la grabación.
  Widget _recordingIndicator(TextTheme text) {
    final int segmentNumber = _recording.currentSegmentDisplay ?? 1;
    return Wrap(
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: 12,
      runSpacing: 4,
      children: <Widget>[
        Row(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Container(
              width: 12,
              height: 12,
              decoration: const BoxDecoration(
                color: AppColors.live,
                shape: BoxShape.circle,
              ),
            ),
            const SizedBox(width: 8),
            Text(
              'GRABANDO',
              style: text.labelLarge?.copyWith(color: AppColors.live),
            ),
          ],
        ),
        _audioChip(text),
        Text(
          _formatDuration(_recording.elapsedMs),
          style: text.bodyMedium,
        ),
        Text(
          'Segmento $segmentNumber · '
          '${_formatDuration(_recording.segmentDurationMs)}',
          style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
        ),
      ],
    );
  }

  /// Chip de audio (3.1.3, plan §8.2): «CON AUDIO» solo mientras el
  /// audio está activo; «SOLO VIDEO» en modo video-only o tras una
  /// pérdida en caliente. Píldora con borde, mismo estilo compacto
  /// que el resto de la pantalla (sin botones nuevos).
  Widget _audioChip(TextTheme text) {
    final bool withAudio =
        _recording.audioEnabled && _recording.audioActive;
    final String label = withAudio ? 'CON AUDIO' : 'SOLO VIDEO';
    final Color color =
        withAudio ? AppColors.primary : AppColors.textMuted;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        border: Border.all(color: color),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: text.labelSmall?.copyWith(color: color),
      ),
    );
  }

  /// Bloque de diagnóstico de la grabación (Fase B, 3.1.2), análogo al
  /// de la vista previa: estado, segmento, código y mensaje técnico,
  /// con botón para copiar el texto al portapapeles.
  Widget _recordingDiagnosticBlock(TextTheme text) {
    final String diagnostic = _recording.diagnosticText!;
    return Card(
      color: AppColors.surface,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Text(
              'DIAGNÓSTICO DE GRABACIÓN',
              style: text.labelMedium?.copyWith(
                color: AppColors.textMuted,
              ),
            ),
            const SizedBox(height: 8),
            SelectableText(
              diagnostic,
              style: text.bodyMedium,
            ),
            const SizedBox(height: 8),
            OutlinedButton(
              onPressed: _copyRecordingDiagnostics,
              child: const Text('COPIAR DIAGNÓSTICO'),
            ),
          ],
        ),
      ),
    );
  }

  /// Bloque de diagnóstico de la vista previa (Fase A): etapa
  /// alcanzada, código y mensaje técnico del error, cámaras detectadas,
  /// cámara y configuración elegidas y última etapa con error. Con
  /// botón para copiar el texto al portapapeles.
  Widget _diagnosticBlock(TextTheme text) {
    final String diagnostic = _preview.diagnosticText!;
    return Card(
      color: AppColors.surface,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Text(
              'DIAGNÓSTICO DE VISTA PREVIA',
              style: text.labelMedium?.copyWith(
                color: AppColors.textMuted,
              ),
            ),
            const SizedBox(height: 8),
            SelectableText(
              diagnostic,
              style: text.bodyMedium,
            ),
            const SizedBox(height: 8),
            OutlinedButton(
              onPressed: _copyDiagnostics,
              child: const Text('COPIAR DIAGNÓSTICO'),
            ),
          ],
        ),
      ),
    );
  }

  /// Imagen real de la cámara, sin deformar: la relación de aspecto es
  /// la de la imagen ya rotada y `RotatedBox` aplica la rotación que el
  /// motor calculó a partir de la orientación del sensor y de la
  /// pantalla. El motor no rota los píxeles (el backend `ImageReader`
  /// no gestiona la rotación): la rotación es solo de presentación.
  Widget _previewArea(PreviewInfo info) {
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

  Widget _destinationTile(StreamDestination destination) {
    return Card(
      color: AppColors.surface,
      child: ListTile(
        title: Text(destination.label),
        subtitle: Text(
          destination.hasKey
              ? 'Clave guardada · ${destination.ingestUrl}'
              : 'Sin clave guardada · ${destination.ingestUrl}',
        ),
        leading: Radio<int>(
          value: destination.id,
          groupValue: _destinations
              .where((StreamDestination d) => d.isActive)
              .map((StreamDestination d) => d.id)
              .firstOrNull,
          onChanged: (_) => _setActive(destination),
        ),
        trailing: IconButton(
          icon: const Icon(Icons.delete_outline),
          tooltip: 'Eliminar destino',
          onPressed: () => _delete(destination),
        ),
      ),
    );
  }
}

/// Botón de video todavía no disponible: deshabilitado de verdad, con la
/// fase en la que llega escrita debajo. No es un botón muerto: no se puede
/// tocar y explica cuándo estará.
class _PhaseButton extends StatelessWidget {
  const _PhaseButton({required this.label, required this.phase});

  final String label;
  final String phase;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        FilledButton(
          onPressed: null,
          style: FilledButton.styleFrom(
            minimumSize: const Size.fromHeight(60),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
          ),
          child: Text(label),
        ),
        const SizedBox(height: 4),
        Text(
          'Disponible en la $phase',
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
            color: AppColors.textMuted,
          ),
        ),
      ],
    );
  }
}
