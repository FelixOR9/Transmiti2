/// Patrocinador tal como se dibuja en la composición gráfica.
class OverlaySponsor {
  const OverlaySponsor({required this.id, required this.name, this.logoPath});

  final int id;
  final String name;
  final String? logoPath;

  @override
  bool operator ==(Object other) =>
      other is OverlaySponsor &&
      other.id == id &&
      other.name == name &&
      other.logoPath == logoPath;

  @override
  int get hashCode => Object.hash(id, name, logoPath);
}

/// Todo lo que muestra la composición gráfica 16:9. Es un valor inmutable:
/// cada vez que cambia el marcador o el reloj se construye uno nuevo y la
/// composición se vuelve a dibujar.
class OverlayData {
  const OverlayData({
    required this.homeName,
    required this.awayName,
    required this.homeColor,
    required this.awayColor,
    this.homeCrestPath,
    this.awayCrestPath,
    required this.homeGoals,
    required this.awayGoals,
    required this.clockText,
    this.addedText,
    required this.periodLabel,
    required this.running,
    this.competition,
    this.sponsors = const <OverlaySponsor>[],
  });

  final String homeName;
  final String awayName;
  final int homeColor;
  final int awayColor;
  final String? homeCrestPath;
  final String? awayCrestPath;
  final int homeGoals;
  final int awayGoals;

  /// Reloj principal (MM:SS) y tiempo añadido (+MM:SS), si lo hay.
  final String clockText;
  final String? addedText;

  /// Por ejemplo "PRIMER TIEMPO" o "DESCANSO".
  final String periodLabel;
  final bool running;
  final String? competition;
  final List<OverlaySponsor> sponsors;

  String get scoreText => '$homeGoals - $awayGoals';
}

/// Zonas de la composición donde se pueden mostrar patrocinadores.
enum SponsorZone { lowerStrip, sidePanel, mainSponsor }

/// Reparto de los patrocinadores del partido entre las zonas activas.
///
/// - Si la zona principal está activa, recibe el primer patrocinador.
/// - La franja inferior y el panel lateral muestran los demás (o todos, si
///   la zona principal no está activa), en el orden en que se eligieron.
class SponsorZones {
  const SponsorZones({
    this.main,
    this.strip = const <OverlaySponsor>[],
    this.side = const <OverlaySponsor>[],
  });

  factory SponsorZones.assign(
    List<OverlaySponsor> sponsors,
    Set<SponsorZone> active,
  ) {
    final bool useMain =
        active.contains(SponsorZone.mainSponsor) && sponsors.isNotEmpty;
    final OverlaySponsor? main = useMain ? sponsors.first : null;
    final List<OverlaySponsor> rest = useMain
        ? sponsors.sublist(1)
        : List<OverlaySponsor>.of(sponsors);
    return SponsorZones(
      main: main,
      strip: active.contains(SponsorZone.lowerStrip)
          ? rest
          : const <OverlaySponsor>[],
      side: active.contains(SponsorZone.sidePanel)
          ? rest
          : const <OverlaySponsor>[],
    );
  }

  final OverlaySponsor? main;
  final List<OverlaySponsor> strip;
  final List<OverlaySponsor> side;
}
