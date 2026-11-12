import 'package:flutter/material.dart';
import 'package:transmiti2/app/app.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/app/theme/app_theme.dart';
import 'package:transmiti2/core/app_info.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';

/// Abre el almacenamiento local antes de mostrar la app y, si falla, muestra
/// un mensaje con la opción de reintentar en lugar de cerrarse.
class Transmiti2Bootstrap extends StatefulWidget {
  const Transmiti2Bootstrap({super.key});

  @override
  State<Transmiti2Bootstrap> createState() => _Transmiti2BootstrapState();
}

class _Transmiti2BootstrapState extends State<Transmiti2Bootstrap> {
  late Future<AppServices> _services = AppServices.create();

  void _retry() {
    setState(() {
      _services = AppServices.create();
    });
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<AppServices>(
      future: _services,
      builder: (BuildContext context, AsyncSnapshot<AppServices> snapshot) {
        if (snapshot.hasData) {
          return Transmiti2App(services: snapshot.data!);
        }
        return MaterialApp(
          title: AppInfo.name,
          debugShowCheckedModeBanner: false,
          theme: AppTheme.dark(),
          home: Scaffold(
            body: SafeArea(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: snapshot.hasError
                      ? _StartupError(error: snapshot.error!, onRetry: _retry)
                      : const CircularProgressIndicator(
                          color: AppColors.primary,
                        ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

class _StartupError extends StatelessWidget {
  const _StartupError({required this.error, required this.onRetry});

  final Object error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        const Icon(Icons.error_outline, size: 56, color: AppColors.live),
        const SizedBox(height: 16),
        Text(
          'No se pudo abrir el almacenamiento del teléfono.',
          textAlign: TextAlign.center,
          style: text.titleMedium?.copyWith(color: AppColors.textPrimary),
        ),
        const SizedBox(height: 8),
        Text(
          '$error',
          textAlign: TextAlign.center,
          style: text.bodySmall?.copyWith(color: AppColors.textMuted),
        ),
        const SizedBox(height: 24),
        AppButton.primary(label: 'REINTENTAR', onPressed: onRetry),
      ],
    );
  }
}
