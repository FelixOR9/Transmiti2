import 'dart:async';

import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/testing/application/test_controller.dart';
import 'package:transmiti2/features/testing/domain/test_contract.dart';

/// Pantalla de pruebas `/pruebas` (diseño base §4.F + Revisiones 1–3).
///
/// Solo existe en builds de pruebas: con `testMode == false` muestra
/// únicamente «No disponible en esta compilación», sin ningún control.
/// En builds de pruebas muestra la banda «MODO PRUEBA» y: estado de
/// instrumentación y sesión/campaña, eventos recientes con marca
/// «inferido», armar pausa P0/P1/P2 + reanudar con estado visible,
/// estado de reconciliación y `recordingId` activo, reservas y
/// conflictos (snapshot del registro), diagnósticos persistidos con
/// veredicto, exportar informe y aviso de almacenamiento lleno con
/// lista de sesiones y diálogos de confirmación de borrado.
///
/// No toca `BroadcastScreen` ni ninguna pantalla de producción.
class TestScreen extends StatefulWidget {
  const TestScreen({super.key});

  @override
  State<TestScreen> createState() => _TestScreenState();
}

class _TestScreenState extends State<TestScreen> {
  TestController? _controller;
  bool _attached = false;
  bool _testMode = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final services = AppScope.of(context);
    _testMode = services.testMode;
    final TestController controller = services.testController;
    if (_controller != controller) {
      if (_attached) {
        _controller?.detach();
        _attached = false;
      }
      _controller = controller;
      controller.attach();
      _attached = true;
      unawaited(controller.refreshTestMode());
    }
  }

  @override
  void dispose() {
    if (_attached) {
      _controller?.detach();
    }
    super.dispose();
  }

  Future<void> _run(
    Future<void> Function() action, {
    String okMessage = '',
  }) async {
    try {
      await action();
      if (okMessage.isNotEmpty && mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(okMessage)));
      }
    } on TestException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(e.message ?? e.code)),
        );
      }
    }
  }

  Future<void> _confirmDelete(TestSessionInfo session) async {
    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: const Text('Eliminar sesión'),
          content: Text(
            'Se eliminará la sesión ${session.sessionId} '
            '(campaña ${session.testRunId ?? '-'}, '
            '${_formatBytes(session.sizeBytes)}).\n\n'
            'Esta acción no se puede deshacer.',
          ),
          actions: <Widget>[
            TextButton(
              key: const Key('test-delete-cancel'),
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text('Cancelar'),
            ),
            TextButton(
              key: const Key('test-delete-confirm'),
              onPressed: () => Navigator.of(context).pop(true),
              child: const Text('Eliminar'),
            ),
          ],
        );
      },
    );
    if (confirmed == true) {
      await _run(
        () => _controller!.deleteSession(
          session.sessionId,
          confirmed: true,
        ),
        okMessage: 'Sesión eliminada.',
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final TestController? controller = _controller;
    if (!_testMode || controller == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Pruebas de diagnóstico')),
        body: const SafeArea(
          child: Center(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: Text(
                'No disponible en esta compilación',
                textAlign: TextAlign.center,
              ),
            ),
          ),
        ),
      );
    }
    return Scaffold(
      appBar: AppBar(title: const Text('Pruebas de diagnóstico')),
      body: SafeArea(
        child: ListenableBuilder(
          listenable: controller,
          builder: (BuildContext context, Widget? child) {
            return ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
              children: <Widget>[
                _TestModeBanner(),
                const SizedBox(height: 12),
                if (controller.lastError != null)
                  _ErrorBanner(error: controller.lastError!),
                _Section(
                  title: 'Sesión',
                  child: _SessionSection(
                    controller: controller,
                    onStart: () => _run(
                      () => controller.startSession(),
                      okMessage: 'Sesión iniciada.',
                    ),
                    onNewCampaign: () => _run(
                      () => controller.startNewCampaign(),
                      okMessage: 'Nueva campaña iniciada.',
                    ),
                    onEnd: () => _run(
                      () => controller.endSession(),
                      okMessage: 'Sesión terminada.',
                    ),
                  ),
                ),
                _Section(
                  title: 'Almacenamiento',
                  child: _StorageSection(
                    controller: controller,
                    onDelete: _confirmDelete,
                    onExport: (TestSessionInfo session) => _run(
                      () => controller.exportReport(session.sessionId),
                      okMessage: 'Informe exportado.',
                    ),
                  ),
                ),
                _Section(
                  title: 'Pausas de reconciliación',
                  child: _PauseSection(
                    controller: controller,
                    onArm: (PausePoint point) => _run(
                      () => controller.armPause(point),
                      okMessage: 'Pausa ${point.wireName} armada.',
                    ),
                    onResume: (PausePoint point) => _run(
                      () => controller.resumePause(point),
                      okMessage: 'Pausa ${point.wireName} reanudada.',
                    ),
                    onResumeAll: () => _run(
                      () => controller.resumeAllPauses(),
                      okMessage: 'Pausas reanudadas.',
                    ),
                  ),
                ),
                _Section(
                  title: 'Grabación y registro',
                  child: _RecordingSection(
                    controller: controller,
                    onRefreshRegistry: () => _run(
                      () => controller.refreshRegistrySnapshot(),
                    ),
                  ),
                ),
                _Section(
                  title: 'Eventos recientes',
                  child: _EventsSection(controller: controller),
                ),
                const SizedBox(height: 16),
                OutlinedButton(
                  key: const Key('test-export-all'),
                  onPressed: () => _run(
                    () => controller.exportAllReports(),
                    okMessage: 'Informes exportados.',
                  ),
                  child: const Text('EXPORTAR TODOS LOS INFORMES'),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

String _formatBytes(int bytes) {
  if (bytes < 1024) {
    return '$bytes B';
  }
  if (bytes < 1024 * 1024) {
    return '${(bytes / 1024).toStringAsFixed(1)} KB';
  }
  return '${(bytes / (1024 * 1024)).toStringAsFixed(2)} MB';
}

String _formatDate(int? wallMs) {
  if (wallMs == null) {
    return '-';
  }
  final DateTime d = DateTime.fromMillisecondsSinceEpoch(wallMs).toLocal();
  String two(int n) => n.toString().padLeft(2, '0');
  return '${d.year}-${two(d.month)}-${two(d.day)} '
      '${two(d.hour)}:${two(d.minute)}';
}

class _TestModeBanner extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(
      key: const Key('test-mode-banner'),
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 12),
      decoration: BoxDecoration(
        color: AppColors.primary,
        borderRadius: BorderRadius.circular(12),
      ),
      child: const Text(
        'MODO PRUEBA',
        textAlign: TextAlign.center,
        style: TextStyle(fontWeight: FontWeight.w800, letterSpacing: 2),
      ),
    );
  }
}

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.error});

  final TestException error;

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const Key('test-error-banner'),
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(
        color: AppColors.live.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.live),
      ),
      child: Text(
        '${error.code}${error.message == null ? '' : ': ${error.message}'}',
      ),
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(
              title,
              style: text.titleMedium?.copyWith(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 12),
            child,
          ],
        ),
      ),
    );
  }
}

class _SessionSection extends StatelessWidget {
  const _SessionSection({
    required this.controller,
    required this.onStart,
    required this.onNewCampaign,
    required this.onEnd,
  });

  final TestController controller;
  final VoidCallback onStart;
  final VoidCallback onNewCampaign;
  final VoidCallback onEnd;

  @override
  Widget build(BuildContext context) {
    final TestSession? session = controller.activeSession;
    final TestReportEvaluation evaluation = controller.evaluateActiveSession();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Text(
          session == null
              ? 'Sin sesión activa'
              : 'Sesión: ${session.sessionId}',
        ),
        if (session != null) ...<Widget>[
          Text('Campaña: ${session.testRunId}'),
          if (session.interruptedPrevious.isNotEmpty)
            Text(
              'Sesiones interrumpidas detectadas: '
              '${session.interruptedPrevious.length}',
            ),
        ] else if (controller.activeTestRunId != null)
          Text('Última campaña: ${controller.activeTestRunId}'),
        const SizedBox(height: 8),
        Text(
          'Veredicto local: ${evaluation.verdict.wireName}'
          '${evaluation.reason == null ? '' : ' (${evaluation.reason})'}',
        ),
        if (controller.sampleFlags.isNotEmpty)
          Text('Banderas: ${controller.sampleFlags.join(', ')}'),
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: <Widget>[
            FilledButton(
              key: const Key('test-start-session'),
              onPressed: session == null && !controller.isBusy
                  ? onStart
                  : null,
              child: const Text('Iniciar sesión'),
            ),
            OutlinedButton(
              key: const Key('test-new-campaign'),
              onPressed: session == null && !controller.isBusy
                  ? onNewCampaign
                  : null,
              child: const Text('Nueva campaña'),
            ),
            OutlinedButton(
              key: const Key('test-end-session'),
              onPressed: session != null && !controller.isBusy
                  ? onEnd
                  : null,
              child: const Text('Terminar sesión'),
            ),
          ],
        ),
      ],
    );
  }
}

class _StorageSection extends StatelessWidget {
  const _StorageSection({
    required this.controller,
    required this.onDelete,
    required this.onExport,
  });

  final TestController controller;
  final void Function(TestSessionInfo session) onDelete;
  final void Function(TestSessionInfo session) onExport;

  @override
  Widget build(BuildContext context) {
    final TestStorageState state = controller.storageState;
    final List<TestSessionInfo> sessions = controller.sessions;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        if (state.full)
          Container(
            key: const Key('test-storage-full-banner'),
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            margin: const EdgeInsets.only(bottom: 12),
            decoration: BoxDecoration(
              color: AppColors.live.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: AppColors.live),
            ),
            child: Text(
              'Almacenamiento de pruebas lleno '
              '(${state.sessionCount}/${state.limit} sesiones). '
              'Elimina una sesión para liberar espacio.',
            ),
          ),
        Text(
          'Sesiones guardadas: ${state.sessionCount}/${state.limit}',
        ),
        const SizedBox(height: 8),
        if (sessions.isEmpty)
          const Text('Sin sesiones persistidas.')
        else
          for (final TestSessionInfo session in sessions)
            _SessionRow(
              session: session,
              onDelete: () => onDelete(session),
              onExport: () => onExport(session),
            ),
        if (controller.lastExport != null) ...<Widget>[
          const SizedBox(height: 8),
          Text('Última exportación: ${controller.lastExport!.jsonPath}'),
        ],
      ],
    );
  }
}

class _SessionRow extends StatelessWidget {
  const _SessionRow({
    required this.session,
    required this.onDelete,
    required this.onExport,
  });

  final TestSessionInfo session;
  final VoidCallback onDelete;
  final VoidCallback onExport;

  @override
  Widget build(BuildContext context) {
    return Padding(
      key: Key('test-session-${session.sessionId}'),
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: <Widget>[
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  session.sessionId,
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
                Text(
                  '${_formatDate(session.startedAtWallMs)} · '
                  '${session.testRunId ?? '-'} · '
                  '${_formatBytes(session.sizeBytes)} · '
                  'veredicto: ${session.verdict ?? 'sin veredicto'}',
                ),
              ],
            ),
          ),
          IconButton(
            key: Key('test-export-${session.sessionId}'),
            icon: const Icon(Icons.file_download_outlined),
            tooltip: 'Exportar informe',
            onPressed: onExport,
          ),
          IconButton(
            key: Key('test-delete-${session.sessionId}'),
            icon: const Icon(Icons.delete_outline),
            tooltip: 'Eliminar sesión',
            onPressed: onDelete,
          ),
        ],
      ),
    );
  }
}

class _PauseSection extends StatelessWidget {
  const _PauseSection({
    required this.controller,
    required this.onArm,
    required this.onResume,
    required this.onResumeAll,
  });

  final TestController controller;
  final void Function(PausePoint point) onArm;
  final void Function(PausePoint point) onResume;
  final VoidCallback onResumeAll;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        for (final PausePoint point in PausePoint.values)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(
              children: <Widget>[
                Expanded(
                  child: Text(
                    '${point.label}: '
                    '${controller.pauseStates[point]?.label ?? 'sin armar'}',
                  ),
                ),
                OutlinedButton(
                  key: Key('test-arm-${point.wireName}'),
                  onPressed: controller.activeSession == null
                      ? null
                      : () => onArm(point),
                  child: const Text('Armar'),
                ),
                const SizedBox(width: 8),
                OutlinedButton(
                  key: Key('test-resume-${point.wireName}'),
                  onPressed: () => onResume(point),
                  child: const Text('Reanudar'),
                ),
              ],
            ),
          ),
        const SizedBox(height: 8),
        OutlinedButton(
          key: const Key('test-resume-all'),
          onPressed: onResumeAll,
          child: const Text('Reanudar todas'),
        ),
        if (controller.armedIntent != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              'Intención armada: ${controller.armedIntent!.point.wireName} '
              '(caduca ${_formatDate(controller.armedIntent!.expiresAtWallMs)})',
            ),
          ),
      ],
    );
  }
}

class _RecordingSection extends StatelessWidget {
  const _RecordingSection({
    required this.controller,
    required this.onRefreshRegistry,
  });

  final TestController controller;
  final VoidCallback onRefreshRegistry;

  @override
  Widget build(BuildContext context) {
    final TestRegistrySnapshot? snapshot = controller.registrySnapshot;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Text(
          controller.activeRecordingId == null
              ? 'Sin grabación activa'
              : 'Grabación activa: ${controller.activeRecordingId}',
        ),
        Text(
          controller.samplerActive
              ? 'Muestreador activo (cada 5 s, ${controller.samples.length} muestras)'
              : 'Muestreador inactivo',
        ),
        const SizedBox(height: 8),
        if (snapshot == null)
          const Text('Snapshot del registro: sin datos.')
        else if (!snapshot.available)
          Text(
            'Snapshot del registro: no disponible '
            '(${snapshot.reason ?? 'sin sesión de grabación'}).',
          )
        else ...<Widget>[
          Text('Claims activos: ${snapshot.activeClaims.length}'),
          for (final TestRegistryEntry e in snapshot.activeClaims)
            Text('  ${e.id} (${e.owner}, ${e.ageMs} ms)'),
          Text('Grupos reservados: ${snapshot.reservedGroups.length}'),
          for (final TestRegistryEntry e in snapshot.reservedGroups)
            Text('  ${e.id} (${e.owner}, ${e.ageMs} ms)'),
          Text(
            'Rechazos: claim/activo ${snapshot.claimRejectedActive}, '
            'claim/reservado ${snapshot.claimRejectedReserved}, '
            'reserva/reservada ${snapshot.reserveRejectedReserved}, '
            'reserva/activo ${snapshot.reserveRejectedActive}, '
            'liberaciones ajenas ${snapshot.foreignReleaseRejected}.',
          ),
        ],
        const SizedBox(height: 8),
        OutlinedButton(
          key: const Key('test-refresh-registry'),
          onPressed: onRefreshRegistry,
          child: const Text('Actualizar snapshot del registro'),
        ),
      ],
    );
  }
}

class _EventsSection extends StatelessWidget {
  const _EventsSection({required this.controller});

  final TestController controller;

  @override
  Widget build(BuildContext context) {
    final List<TestEvent> events = controller.events;
    final List<TestEvent> recent = events.length > 30
        ? events.sublist(events.length - 30)
        : events;
    if (recent.isEmpty) {
      return const Text('Sin eventos.');
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        for (final TestEvent event in recent.reversed)
          Padding(
            key: Key('test-event-${event.monotonicMs}-${event.event.wireName}'),
            padding: const EdgeInsets.symmetric(vertical: 2),
            child: Text(
              '${event.event.wireName}'
              '${event.recordingId == null ? '' : ' ${event.recordingId}'}'
              '${event.origin == TestEventOrigin.inferred ? ' [inferido]' : ''}',
            ),
          ),
      ],
    );
  }
}
