import 'package:flutter/widgets.dart';
import 'package:transmiti2/app/app_services.dart';

/// Pone los servicios de la app al alcance de todas las pantallas.
class AppScope extends InheritedWidget {
  const AppScope({super.key, required this.services, required super.child});

  final AppServices services;

  static AppServices of(BuildContext context) {
    final AppScope? scope = context
        .dependOnInheritedWidgetOfExactType<AppScope>();
    assert(scope != null, 'Falta AppScope encima en el árbol de widgets.');
    return scope!.services;
  }

  @override
  bool updateShouldNotify(AppScope oldWidget) => services != oldWidget.services;
}
