import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';

/// Pide confirmación antes de una acción que no se puede deshacer.
/// Devuelve verdadero solo si el usuario confirma.
Future<bool> confirmAction(
  BuildContext context, {
  required String title,
  required String message,
  required String confirmLabel,
}) async {
  final bool? result = await showDialog<bool>(
    context: context,
    builder: (BuildContext dialogContext) {
      return AlertDialog(
        title: Text(title),
        content: Text(message),
        actions: <Widget>[
          TextButton(
            key: const Key('confirm-cancel'),
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('CANCELAR'),
          ),
          FilledButton(
            key: const Key('confirm-ok'),
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.live,
              foregroundColor: Colors.white,
            ),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(confirmLabel),
          ),
        ],
      );
    },
  );
  return result ?? false;
}
