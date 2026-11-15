import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/features/graphics/domain/overlay_data.dart';

const Color _panel = Color(0xE6101722);
const Color _score = Color(0xFF1B2640);
const Color _accent = Color(0xFF23D18B);
const Color _ink = Color(0xFF0A0F1C);
const List<FontFeature> _tabular = <FontFeature>[FontFeature.tabularFigures()];

/// Composición gráfica 16:9 para superponer en una transmisión: marcador,
/// cronómetro, escudos, nombres, competición y patrocinadores.
///
/// Está diseñada sobre un lienzo de 1280 x 720 y todo se escala con el ancho
/// disponible, así que se ve igual de bien en la vista previa del teléfono
/// que cuando más adelante se dibuje a tamaño de video. El fondo es
/// transparente: solo se dibujan los elementos gráficos.
class BroadcastOverlay extends StatelessWidget {
  const BroadcastOverlay({super.key, required this.data, required this.zones});

  final OverlayData data;
  final SponsorZones zones;

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: 16 / 9,
      child: LayoutBuilder(
        builder: (BuildContext context, BoxConstraints box) {
          final double s = box.maxWidth / 1280;
          return DefaultTextStyle.merge(
            // Altura de línea fija: la composición no depende del tema de la app.
            style: const TextStyle(height: 1.0),
            child: Stack(
              clipBehavior: Clip.hardEdge,
              children: <Widget>[
                Positioned(
                  left: 40 * s,
                  top: 28 * s,
                  child: _Scorebug(data: data, s: s),
                ),
                if (zones.main != null)
                  Positioned(
                    right: 40 * s,
                    top: 28 * s,
                    width: 240 * s,
                    height: 120 * s,
                    child: _MainSponsor(sponsor: zones.main!, s: s),
                  ),
                if (zones.side.isNotEmpty)
                  Positioned(
                    right: 40 * s,
                    top: (zones.main != null ? 168 : 28) * s,
                    bottom: (zones.strip.isNotEmpty ? 124 : 40) * s,
                    width: 150 * s,
                    child: _SideSponsors(sponsors: zones.side, s: s),
                  ),
                if (zones.strip.isNotEmpty)
                  Positioned(
                    left: 0,
                    right: 0,
                    bottom: 0,
                    height: 92 * s,
                    child: _LowerStrip(sponsors: zones.strip, s: s),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _Scorebug extends StatelessWidget {
  const _Scorebug({required this.data, required this.s});

  final OverlayData data;
  final double s;

  TextStyle get _name => TextStyle(
    color: Colors.white,
    fontSize: 28 * s,
    fontWeight: FontWeight.w800,
  );

  Widget _teamName(String name) {
    return Center(
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: 230 * s),
        child: Text(
          name,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: _name,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final String? competition = data.competition;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        if (competition != null)
          Padding(
            padding: EdgeInsets.only(bottom: 6 * s),
            child: Text(
              competition.toUpperCase(),
              key: const Key('overlay-competition'),
              style: TextStyle(
                color: Colors.white,
                fontSize: 18 * s,
                fontWeight: FontWeight.w700,
                letterSpacing: 1.5 * s,
                shadows: <Shadow>[
                  Shadow(blurRadius: 4 * s, color: Colors.black),
                ],
              ),
            ),
          ),
        Container(
          height: 76 * s,
          clipBehavior: Clip.antiAlias,
          decoration: BoxDecoration(
            color: _panel,
            borderRadius: BorderRadius.circular(12 * s),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              Container(width: 10 * s, color: Color(data.homeColor)),
              SizedBox(width: 10 * s),
              Center(
                child: _OverlayCrest(path: data.homeCrestPath, size: 56 * s),
              ),
              SizedBox(width: 12 * s),
              _teamName(data.homeName),
              SizedBox(width: 16 * s),
              Container(
                padding: EdgeInsets.symmetric(horizontal: 20 * s),
                color: _score,
                alignment: Alignment.center,
                child: Text(
                  data.scoreText,
                  key: const Key('overlay-score'),
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 42 * s,
                    fontWeight: FontWeight.w900,
                    fontFeatures: _tabular,
                  ),
                ),
              ),
              SizedBox(width: 16 * s),
              _teamName(data.awayName),
              SizedBox(width: 12 * s),
              Center(
                child: _OverlayCrest(path: data.awayCrestPath, size: 56 * s),
              ),
              SizedBox(width: 10 * s),
              Container(width: 10 * s, color: Color(data.awayColor)),
              Container(
                padding: EdgeInsets.symmetric(horizontal: 18 * s),
                color: _accent,
                alignment: Alignment.center,
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: <Widget>[
                      Text(
                        data.clockText,
                        key: const Key('overlay-clock'),
                        style: TextStyle(
                          color: _ink,
                          fontSize: 32 * s,
                          fontWeight: FontWeight.w900,
                          fontFeatures: _tabular,
                        ),
                      ),
                      if (data.addedText != null)
                        Text(
                          data.addedText!,
                          key: const Key('overlay-added'),
                          style: TextStyle(
                            color: _ink,
                            fontSize: 15 * s,
                            fontWeight: FontWeight.w800,
                            fontFeatures: _tabular,
                          ),
                        ),
                      Text(
                        data.periodLabel,
                        key: const Key('overlay-period'),
                        style: TextStyle(
                          color: _ink,
                          fontSize: 12 * s,
                          fontWeight: FontWeight.w800,
                          letterSpacing: 1 * s,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _OverlayCrest extends StatelessWidget {
  const _OverlayCrest({required this.path, required this.size});

  final String? path;
  final double size;

  @override
  Widget build(BuildContext context) {
    return _OverlayImage(
      path: path,
      width: size,
      height: size,
      fallback: Icon(
        Icons.shield_outlined,
        size: size * 0.8,
        color: Colors.white54,
      ),
    );
  }
}

/// Imagen del almacén sin marco ni fondo; si falta, muestra [fallback].
class _OverlayImage extends StatelessWidget {
  const _OverlayImage({
    required this.path,
    required this.width,
    required this.height,
    required this.fallback,
  });

  final String? path;
  final double width;
  final double height;
  final Widget fallback;

  @override
  Widget build(BuildContext context) {
    final String? relative = path;
    Widget child = fallback;
    if (relative != null) {
      try {
        child = Image.file(
          AppScope.of(context).images.file(relative),
          fit: BoxFit.contain,
          gaplessPlayback: true,
          errorBuilder: (
            BuildContext context,
            Object error,
            StackTrace? stackTrace,
          ) => fallback,
        );
      } on ArgumentError {
        child = fallback;
      }
    }
    return SizedBox(
      width: width,
      height: height,
      child: Center(child: child),
    );
  }
}

class _SponsorLogo extends StatelessWidget {
  const _SponsorLogo({
    super.key,
    required this.sponsor,
    required this.width,
    required this.height,
    required this.s,
  });

  final OverlaySponsor sponsor;
  final double width;
  final double height;
  final double s;

  @override
  Widget build(BuildContext context) {
    return _OverlayImage(
      path: sponsor.logoPath,
      width: width,
      height: height,
      fallback: Center(
        child: Text(
          sponsor.name,
          maxLines: 2,
          textAlign: TextAlign.center,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            color: Colors.white,
            fontSize: 20 * s,
            fontWeight: FontWeight.w700,
          ),
        ),
      ),
    );
  }
}

class _MainSponsor extends StatelessWidget {
  const _MainSponsor({required this.sponsor, required this.s});

  final OverlaySponsor sponsor;
  final double s;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: EdgeInsets.all(10 * s),
      decoration: BoxDecoration(
        color: _panel,
        borderRadius: BorderRadius.circular(12 * s),
      ),
      child: Column(
        children: <Widget>[
          Text(
            'PATROCINADOR PRINCIPAL',
            style: TextStyle(
              color: Colors.white70,
              fontSize: 12 * s,
              fontWeight: FontWeight.w700,
              letterSpacing: 1 * s,
            ),
          ),
          SizedBox(height: 6 * s),
          Expanded(
            child: Center(
              child: _SponsorLogo(
                key: Key('overlay-main-${sponsor.id}'),
                sponsor: sponsor,
                width: 200 * s,
                height: 70 * s,
                s: s,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SideSponsors extends StatelessWidget {
  const _SideSponsors({required this.sponsors, required this.s});

  final List<OverlaySponsor> sponsors;
  final double s;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: EdgeInsets.all(8 * s),
      decoration: BoxDecoration(
        color: _panel,
        borderRadius: BorderRadius.circular(12 * s),
      ),
      child: FittedBox(
        fit: BoxFit.scaleDown,
        alignment: Alignment.topCenter,
        child: Column(
          children: <Widget>[
            for (final OverlaySponsor sponsor in sponsors)
              Padding(
                padding: EdgeInsets.symmetric(vertical: 6 * s),
                child: _SponsorLogo(
                  key: Key('overlay-side-${sponsor.id}'),
                  sponsor: sponsor,
                  width: 130 * s,
                  height: 70 * s,
                  s: s,
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _LowerStrip extends StatelessWidget {
  const _LowerStrip({required this.sponsors, required this.s});

  final List<OverlaySponsor> sponsors;
  final double s;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: EdgeInsets.symmetric(horizontal: 32 * s, vertical: 10 * s),
      color: const Color(0xCC0A0F1C),
      child: FittedBox(
        fit: BoxFit.scaleDown,
        child: Row(
          children: <Widget>[
            for (final OverlaySponsor sponsor in sponsors)
              Padding(
                padding: EdgeInsets.symmetric(horizontal: 18 * s),
                child: _SponsorLogo(
                  key: Key('overlay-strip-${sponsor.id}'),
                  sponsor: sponsor,
                  width: 160 * s,
                  height: 64 * s,
                  s: s,
                ),
              ),
          ],
        ),
      ),
    );
  }
}
