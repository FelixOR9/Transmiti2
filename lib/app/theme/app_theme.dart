import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';

/// Tema visual de la aplicación (oscuro, pensado para usarse al aire libre).
abstract final class AppTheme {
  /// Fuente condensada del sistema Android. Si el teléfono no la tiene,
  /// Flutter usa la fuente normal sin ningún error.
  static const String fontFamily = 'sans-serif-condensed';

  static ThemeData dark() {
    const ColorScheme scheme = ColorScheme.dark(
      primary: AppColors.primary,
      onPrimary: AppColors.onPrimary,
      secondary: AppColors.live,
      onSecondary: Colors.white,
      surface: AppColors.surface,
      onSurface: AppColors.textPrimary,
      error: AppColors.live,
      onError: Colors.white,
    );

    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      fontFamily: fontFamily,
      scaffoldBackgroundColor: AppColors.background,
      appBarTheme: const AppBarTheme(
        backgroundColor: AppColors.background,
        foregroundColor: AppColors.textPrimary,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
      ),
    );
  }
}
