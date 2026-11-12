import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_routes.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/core/formatting.dart';
import 'package:transmiti2/features/home/presentation/widgets/access_button.dart';
import 'package:transmiti2/features/home/presentation/widgets/brand_header.dart';
import 'package:transmiti2/features/home/presentation/widgets/device_status_strip.dart';
import 'package:transmiti2/features/home/presentation/widgets/next_match_card.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/match_detail.dart';
import 'package:transmiti2/shared/widgets/section_label.dart';

/// Pantalla de inicio: próximo partido, accesos y estado del equipo.
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.onOpenReplays});

  /// Cambia a la pestaña de repeticiones (la decide la estructura principal).
  final VoidCallback onOpenReplays;

  void _open(BuildContext context, String route) {
    Navigator.of(context).pushNamed(route);
  }

  /// Línea de detalles del partido: fecha y torneo (si lo hay).
  String _details(MatchDetail d) {
    final String? tournament = d.match.tournament;
    return <String>[
      formatDate(d.match.scheduledAtMs),
      if (tournament != null) tournament,
    ].join(' · ');
  }

  @override
  Widget build(BuildContext context) {
    final MatchSession session = AppScope.of(context).session;
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
                    ListenableBuilder(
                      listenable: session,
                      builder: (BuildContext context, Widget? child) {
                        final MatchDetail? d = session.detail;
                        return NextMatchCard(
                          homeName: d?.home.name,
                          awayName: d?.away.name,
                          details: d == null ? null : _details(d),
                          onPrepare: () => _open(context, AppRoutes.matchSetup),
                          onOpenStudio: () =>
                              _open(context, AppRoutes.broadcast),
                        );
                      },
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
