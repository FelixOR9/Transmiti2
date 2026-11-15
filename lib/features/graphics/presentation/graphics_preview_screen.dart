import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_routes.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/graphics/application/overlay_data_builder.dart';
import 'package:transmiti2/features/graphics/domain/overlay_data.dart';
import 'package:transmiti2/features/graphics/presentation/broadcast_overlay.dart';
import 'package:transmiti2/features/live/presentation/widgets/live_clock.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';
import 'package:transmiti2/shared/widgets/app_button.dart';
import 'package:transmiti2/shared/widgets/section_label.dart';

/// Vista previa de la composición gráfica 16:9 con los datos reales del
/// partido. Se actualiza sola con el marcador y el cronómetro.
///
/// Todavía no se integra con la señal de video: esa parte llega en la etapa
/// de transmisión.
class GraphicsPreviewScreen extends StatefulWidget {
  const GraphicsPreviewScreen({super.key});

  @override
  State<GraphicsPreviewScreen> createState() => _GraphicsPreviewScreenState();
}

class _GraphicsPreviewScreenState extends State<GraphicsPreviewScreen> {
  final Set<SponsorZone> _active = SponsorZone.values.toSet();

  static const Map<SponsorZone, String> _zoneNames = <SponsorZone, String>{
    SponsorZone.lowerStrip: 'Franja inferior',
    SponsorZone.sidePanel: 'Lateral',
    SponsorZone.mainSponsor: 'Patrocinador principal',
  };

  void _toggle(SponsorZone zone, bool selected) {
    setState(() {
      if (selected) {
        _active.add(zone);
      } else {
        _active.remove(zone);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final MatchSession session = AppScope.of(context).session;
    final Duration? tick = AppScope.of(context).clockTick;
    final TextTheme text = Theme.of(context).textTheme;
    return ListenableBuilder(
      listenable: session,
      builder: (BuildContext context, Widget? child) {
        final OverlayData? first = buildOverlayData(session);
        return Scaffold(
          appBar: AppBar(title: const Text('Gráficos')),
          body: SafeArea(
            child: first == null
                ? _emptyState(context)
                : Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 900),
                      child: ListView(
                        padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
                        children: <Widget>[
                          Text(
                            'Así se verá la superposición sobre la transmisión (formato 16:9). Gira el teléfono en horizontal para verla más grande.',
                            style: text.bodyMedium?.copyWith(
                              color: AppColors.textMuted,
                            ),
                          ),
                          const SizedBox(height: 16),
                          ClipRRect(
                            borderRadius: BorderRadius.circular(16),
                            child: DecoratedBox(
                              decoration: const BoxDecoration(
                                gradient: LinearGradient(
                                  begin: Alignment.topCenter,
                                  end: Alignment.bottomCenter,
                                  colors: <Color>[
                                    Color(0xFF14532D),
                                    Color(0xFF052E16),
                                  ],
                                ),
                              ),
                              child: LiveClock(
                                session: session,
                                tick: tick,
                                builder:
                                    (BuildContext context, ClockReading _) {
                                      final OverlayData data =
                                          buildOverlayData(session) ?? first;
                                      return BroadcastOverlay(
                                        key: const Key('broadcast-overlay'),
                                        data: data,
                                        zones: SponsorZones.assign(
                                          data.sponsors,
                                          _active,
                                        ),
                                      );
                                    },
                              ),
                            ),
                          ),
                          const SizedBox(height: 20),
                          const SectionLabel('ZONAS DE PATROCINADORES'),
                          const SizedBox(height: 12),
                          Wrap(
                            spacing: 8,
                            runSpacing: 8,
                            children: <Widget>[
                              for (final SponsorZone zone in SponsorZone.values)
                                FilterChip(
                                  key: Key('zone-${zone.name}'),
                                  label: Text(_zoneNames[zone]!),
                                  selected: _active.contains(zone),
                                  showCheckmark: false,
                                  onSelected: (bool v) => _toggle(zone, v),
                                  selectedColor: AppColors.primary,
                                  backgroundColor: AppColors.surfaceHigh,
                                  side: const BorderSide(
                                    color: AppColors.outlineStrong,
                                  ),
                                  labelStyle: TextStyle(
                                    color: _active.contains(zone)
                                        ? AppColors.onPrimary
                                        : AppColors.textPrimary,
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                            ],
                          ),
                          const SizedBox(height: 16),
                          Text(
                            first.sponsors.isEmpty
                                ? 'Este partido no tiene patrocinadores elegidos. Elígelos en Preparar partido para verlos aquí.'
                                : '${first.sponsors.length} patrocinador(es) elegido(s) para este partido.',
                            key: const Key('overlay-sponsor-count'),
                            style: text.bodyMedium?.copyWith(
                              color: AppColors.textMuted,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
          ),
        );
      },
    );
  }

  Widget _emptyState(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Text(
              'Primero prepara un partido para ver los gráficos.',
              key: const Key('graphics-empty'),
              textAlign: TextAlign.center,
              style: text.titleMedium?.copyWith(color: AppColors.textMuted),
            ),
            const SizedBox(height: 24),
            AppButton.primary(
              label: 'PREPARAR PARTIDO',
              onPressed: () =>
                  Navigator.of(context).pushNamed(AppRoutes.matchSetup),
            ),
          ],
        ),
      ),
    );
  }
}
