import 'package:flutter/material.dart';
import 'package:transmiti2/app/app.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/app/theme/app_theme.dart';
import 'package:transmiti2/core/app_info.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';

/// Texto de la pantalla de error de arranque (U64).
class StartupErrorText {
  const StartupErrorText({required this.title, required this.detail});

  /// Mensaje específico del error (nunca presupone disco: un fallo
  /// de canal muestra su propio mensaje).
  final String title;

  /// Código + `stage` (+ `attempts` en canal-no-disponible).
  final String detail;
}

/// Formatea el error de arranque para la pantalla (función pura y
/// testeable, U64). El título fijo de disco anterior presuponía un
/// fallo de disco para cualquier error; ahora cada código de la
/// subetapa 0 tiene su propio mensaje, y lo desconocido conserva el
/// título genérico.
StartupErrorText startupErrorText(Object error) {
  if (error is StreamingException) {
    final Map<String, Object?>? details = error.details;
    final String? stage = details?['stage'] as String?;
    final Object? attempts = details?['attempts'];
    final String detail = <String>[
      error.code,
      if (stage != null) 'stage: $stage',
      if (attempts != null) 'intentos: $attempts',
    ].join(' · ');
    final String title;
    switch (error.code) {
      case StreamContract.recordingsDirUnavailable:
        title = 'No se pudo preparar la carpeta de grabaciones.';
      case StreamContract.recordingsChannelUnavailable:
        title =
            'No se pudo contactar con el sistema de grabaciones del teléfono.';
      case StreamContract.recordingsChannelContractError:
        title = 'La respuesta del sistema de grabaciones no es válida.';
      case StreamContract.storageCheckUnavailable:
        title = 'No se pudo comprobar el espacio disponible para grabar.';
      default:
        title = 'No se pudo abrir el almacenamiento del teléfono.';
    }
    return StartupErrorText(title: title, detail: detail);
  }
  return StartupErrorText(
    title: 'No se pudo abrir el almacenamiento del teléfono.',
    detail: '$error',
  );
}

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
                      // Estado «Preparando almacenamiento…» durante
                      // la resolución de la ruta (distinto del
                      // spinner genérico): es el primer paso de
                      // `AppServices.create()` y el único que puede
                      // tardar segundos (reintentos/timeouts).
                      : const Column(
                          mainAxisSize: MainAxisSize.min,
                          children: <Widget>[
                            CircularProgressIndicator(
                              color: AppColors.primary,
                            ),
                            SizedBox(height: 16),
                            Text(
                              'Preparando almacenamiento…',
                              textAlign: TextAlign.center,
                            ),
                          ],
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
    final StartupErrorText shown = startupErrorText(error);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        const Icon(Icons.error_outline, size: 56, color: AppColors.live),
        const SizedBox(height: 16),
        Text(
          shown.title,
          textAlign: TextAlign.center,
          style: text.titleMedium?.copyWith(color: AppColors.textPrimary),
        ),
        const SizedBox(height: 8),
        Text(
          shown.detail,
          textAlign: TextAlign.center,
          style: text.bodySmall?.copyWith(color: AppColors.textMuted),
        ),
        const SizedBox(height: 24),
        AppButton.primary(label: 'REINTENTAR', onPressed: onRetry),
      ],
    );
  }
}
