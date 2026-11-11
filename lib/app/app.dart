import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_router.dart';
import 'package:transmiti2/app/app_routes.dart';
import 'package:transmiti2/app/theme/app_theme.dart';
import 'package:transmiti2/core/app_info.dart';

/// Raíz de la aplicación: tema, rutas y pantalla inicial.
class Transmiti2App extends StatelessWidget {
  const Transmiti2App({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: AppInfo.name,
      debugShowCheckedModeBanner: false,
      theme: AppTheme.dark(),
      initialRoute: AppRoutes.home,
      onGenerateRoute: AppRouter.onGenerateRoute,
    );
  }
}
