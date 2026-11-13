import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';

OutlineInputBorder _border(Color color, [double width = 1.5]) {
  return OutlineInputBorder(
    borderRadius: BorderRadius.circular(14),
    borderSide: BorderSide(color: color, width: width),
  );
}

/// Estilo de los campos de texto de TRANSMITI2.
InputDecoration appInputDecoration({
  required String label,
  String? hint,
  String? errorText,
}) {
  return InputDecoration(
    labelText: label,
    hintText: hint,
    errorText: errorText,
    filled: true,
    fillColor: AppColors.surfaceHigh,
    labelStyle: const TextStyle(color: AppColors.textMuted),
    hintStyle: const TextStyle(color: AppColors.textMuted),
    contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
    border: _border(AppColors.outlineStrong),
    enabledBorder: _border(AppColors.outlineStrong),
    focusedBorder: _border(AppColors.primary, 2),
    errorBorder: _border(AppColors.live),
    focusedErrorBorder: _border(AppColors.live, 2),
    disabledBorder: _border(AppColors.outline),
  );
}
