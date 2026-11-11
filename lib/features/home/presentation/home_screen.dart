import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_routes.dart';
import 'package:transmiti2/features/home/presentation/widgets/access_button.dart';
import 'package:transmiti2/features/home/presentation/widgets/brand_header.dart';
import 'package:transmiti2/features/home/presentation/widgets/device_status_strip.dart';
import 'package:transmiti2/features/home/presentation/widgets/next_match_card.dart';
import 'package:transmiti2/shared/widgets/section_label.dart';

/// Pantalla de inicio: próximo partido, accesos y estado del equipo.
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.onOpenReplays});

  /// Cambia a la pestaña de repeticiones (la decide la estructura principal).
  final VoidCallback onOpenReplays;

  void _open(BuildContext context, String route) {
    Navigator.of(context).pushNamed(route);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: LayoutBuilder(
              builder: (BuildContext context, BoxConstraints constraints) {
                final int columns = constraints.maxWidth >= 600 ? 4 : 2;
                return ListView(
                  padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
                  children: <Widget>[
                    const BrandHeader(),
                    const SizedBox(height: 20),
                    NextMatchCard(
                      onPrepare: () => _open(context, AppRoutes.matchSetup),
                      onOpenStudio: () => _open(context, AppRoutes.broadcast),
                    ),
                    const SizedBox(height: 28),
                    const SectionLabel('ACCESOS'),
                    const SizedBox(height: 12),
                    GridView(
                      shrinkWrap: true,
                      physics: const NeverScrollableScrollPhysics(),
                      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                        crossAxisCount: columns,
                        mainAxisSpacing: 12,
                        crossAxisSpacing: 12,
                        mainAxisExtent: 56,
                      ),
                      children: <Widget>[
                        AccessButton(
                          label: 'MARCADOR',
                          onTap: () => _open(context, AppRoutes.scoreboard),
                        ),
                        AccessButton(
                          label: 'CRONÓMETRO',
                          onTap: () => _open(context, AppRoutes.matchClock),
                        ),
                        AccessButton(
                          label: 'ESTADÍSTICAS',
                          onTap: () => _open(context, AppRoutes.stats),
                        ),
                        AccessButton(
                          label: 'REPETICIONES',
                          onTap: onOpenReplays,
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),
                    const DeviceStatusStrip(),
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
