import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_routes.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/core/app_info.dart';
import 'package:transmiti2/features/home/presentation/widgets/brand_header.dart';
import 'package:transmiti2/features/home/presentation/widgets/menu_card.dart';

/// Pantalla inicial: identidad de TRANSMITI2 y accesos principales.
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  void _open(BuildContext context, String route) {
    Navigator.of(context).pushNamed(route);
  }

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: LayoutBuilder(
              builder: (BuildContext context, BoxConstraints constraints) {
                final int columns = constraints.maxWidth >= 600 ? 4 : 2;
                return ListView(
                  padding: const EdgeInsets.fromLTRB(20, 24, 20, 24),
                  children: <Widget>[
                    const BrandHeader(),
                    const SizedBox(height: 28),
                    FilledButton.icon(
                      onPressed: () => _open(context, AppRoutes.broadcast),
                      icon: const Icon(Icons.sensors, size: 28),
                      label: const Text('INICIAR TRANSMISIÓN'),
                    ),
                    const SizedBox(height: 28),
                    Text(
                      'HERRAMIENTAS DEL PARTIDO',
                      style: text.labelMedium?.copyWith(
                        color: AppColors.textMuted,
                        letterSpacing: 1.4,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 12),
                    GridView(
                      shrinkWrap: true,
                      physics: const NeverScrollableScrollPhysics(),
                      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                        crossAxisCount: columns,
                        mainAxisSpacing: 12,
                        crossAxisSpacing: 12,
                        mainAxisExtent: 160,
                      ),
                      children: <Widget>[
                        MenuCard(
                          icon: Icons.sports_score,
                          title: 'Marcador',
                          subtitle: 'Goles de ambos equipos',
                          onTap: () => _open(context, AppRoutes.scoreboard),
                        ),
                        MenuCard(
                          icon: Icons.timer,
                          title: 'Cronómetro',
                          subtitle: 'Tiempo del partido',
                          onTap: () => _open(context, AppRoutes.matchClock),
                        ),
                        MenuCard(
                          icon: Icons.bar_chart,
                          title: 'Estadísticas',
                          subtitle: 'Datos del encuentro',
                          onTap: () => _open(context, AppRoutes.stats),
                        ),
                        MenuCard(
                          icon: Icons.replay,
                          title: 'Repeticiones',
                          subtitle: 'Clips de jugadas',
                          onTap: () => _open(context, AppRoutes.replays),
                        ),
                      ],
                    ),
                    const SizedBox(height: 24),
                    Center(
                      child: Text(
                        'Versión ${AppInfo.version} · Etapa 1',
                        style: text.bodySmall?.copyWith(
                          color: AppColors.textMuted,
                        ),
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      ),
    );
  }
}
