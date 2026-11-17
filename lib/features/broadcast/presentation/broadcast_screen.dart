import 'dart:async';

import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/streaming/application/permission_controller.dart';
import 'package:transmiti2/features/streaming/application/preview_controller.dart';
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
/// permanentemente, se ofrece abrir los ajustes de la aplicación. El
/// micrófono no se pide todavía: su camino queda construido para la
/// subetapa 3.1.3.
///
/// Vista previa (3.1.1): con el permiso concedido, "Vista previa" abre
/// la cámara trasera real con Camera2 y muestra su imagen en vivo con el
/// widget `Texture` (sin deformar: relación de aspecto y rotación según
/// la orientación del sensor). Al salir de la pantalla, al ir a segundo
/// plano o al girar el teléfono, la cámara se cierra o se reinicia.
///
/// Lo que NO hace todavía: transmisión ni grabación (Fase 3.2). Esos
/// botones aparecen deshabilitados con la fase en la que llegan.
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
    }
  }

  @override
  void dispose() {
    _preview.detach();
    // Al salir de la pantalla la cámara se cierra: no se mantiene
    // abierta innecesariamente.
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
                      return _videoSection(text);
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
            AppButton.primary(
              label: state == PreviewState.recovering
                  ? 'RECUPERANDO…'
                  : 'DETENER VISTA PREVIA',
              onPressed: state == PreviewState.recovering
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
        const SizedBox(height: 12),
        const _PhaseButton(
          label: 'TRANSMITIR',
          phase: 'Fase 3.2',
        ),
        const SizedBox(height: 12),
        const _PhaseButton(
          label: 'GRABAR EN EL TELÉFONO',
          phase: 'Fase 3.2',
        ),
      ],
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
