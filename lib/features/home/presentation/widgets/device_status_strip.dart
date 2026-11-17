import 'dart:async';

import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/streaming/application/permission_controller.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';

/// Franja con el estado de cámara, micrófono y red.
///
/// Subetapa 3.1.0: cámara y micrófono muestran su estado real de permiso
/// (consulta de solo lectura, sin mostrar diálogos). Los estados se
/// vuelven a consultar al regresar a la app. La red sigue
/// "Sin comprobar" hasta que haya transmisión.
class DeviceStatusStrip extends StatefulWidget {
  const DeviceStatusStrip({super.key});

  @override
  State<DeviceStatusStrip> createState() => _DeviceStatusStripState();
}

class _DeviceStatusStripState extends State<DeviceStatusStrip> {
  PermissionController? _permissions;

  static const String _notChecked = 'Sin comprobar';

  static String _label(PermissionStatus status) {
    switch (status) {
      case PermissionStatus.granted:
        return 'Concedida';
      case PermissionStatus.denied:
        return 'Denegada';
      case PermissionStatus.permanentlyDenied:
        return 'Bloqueada';
      case PermissionStatus.notRequested:
        return 'No pedida';
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_permissions == null) {
      _permissions = AppScope.of(context).streaming.permissions;
      _permissions!.attach();
      unawaited(_permissions!.refresh());
    }
  }

  @override
  void dispose() {
    _permissions?.detach();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final PermissionController permissions = _permissions!;
    return ListenableBuilder(
      listenable: permissions,
      builder: (BuildContext context, Widget? child) {
        final PermissionStates states = permissions.states;
        return Container(
          padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 8),
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: AppColors.outline),
          ),
          child: Row(
            children: <Widget>[
              Expanded(
                child: _StatusItem(
                  label: 'CÁMARA',
                  value: _label(states.camera),
                ),
              ),
              Expanded(
                child: _StatusItem(
                  label: 'MICRÓFONO',
                  value: _label(states.microphone),
                ),
              ),
              const Expanded(
                child: _StatusItem(label: 'RED', value: _notChecked),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _StatusItem extends StatelessWidget {
  const _StatusItem({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          FittedBox(
            fit: BoxFit.scaleDown,
            child: Text(
              label,
              maxLines: 1,
              style: text.labelMedium?.copyWith(
                color: AppColors.textMuted,
                letterSpacing: 1.4,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          const SizedBox(height: 4),
          FittedBox(
            fit: BoxFit.scaleDown,
            child: Text(
              value,
              maxLines: 1,
              style: text.titleSmall?.copyWith(
                color: AppColors.textPrimary,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
