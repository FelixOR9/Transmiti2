import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/streaming/application/streaming_controller.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/domain/stream_destination.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';
import 'package:transmiti2/shared/widgets/app_input.dart';
import 'package:transmiti2/shared/widgets/section_label.dart';

/// Estudio de transmisión (Fase 3.0).
///
/// Lo que hace en esta fase: guardar el destino manual de Facebook Live
/// (URL RTMPS + clave) y elegir el destino activo. La clave se guarda
/// cifrada en el teléfono y nunca se muestra ni se guarda en la base.
///
/// Lo que NO hace todavía: vista previa (Fase 3.1), transmisión ni
/// grabación (Fase 3.2). Esos botones aparecen deshabilitados con la fase
/// en la que llegan. En esta fase no se pide cámara ni micrófono.
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

  StreamingController get _streaming => AppScope.of(context).streaming;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // AppScope solo se puede leer cuando las dependencias están listas,
    // no en initState.
    if (!_didLoad) {
      _didLoad = true;
      _refresh();
    }
  }

  @override
  void dispose() {
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
              const SectionLabel('VIDEO'),
              const SizedBox(height: 8),
              Text(
                'En esta fase no se pide cámara ni micrófono. Se pedirán '
                'en la Fase 3.1, cuando vayas a usar la vista previa.',
                style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
              ),
              const SizedBox(height: 12),
              const _PhaseButton(
                label: 'VISTA PREVIA',
                phase: 'Fase 3.1',
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
          ),
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
