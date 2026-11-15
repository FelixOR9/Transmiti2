import 'package:transmiti2/features/graphics/domain/overlay_data.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/match_detail.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';

/// Texto del periodo para mostrar en pantalla y en la composición gráfica.
String periodLabelFor(MatchPhase phase) {
  switch (phase) {
    case MatchPhase.notStarted:
      return 'POR COMENZAR';
    case MatchPhase.firstHalf:
      return 'PRIMER TIEMPO';
    case MatchPhase.halfTime:
      return 'DESCANSO';
    case MatchPhase.secondHalf:
      return 'SEGUNDO TIEMPO';
    case MatchPhase.fullTime:
      return 'FINAL';
  }
}

/// Construye los datos de la composición gráfica a partir del partido actual.
/// Devuelve nulo si todavía no hay partido preparado.
OverlayData? buildOverlayData(MatchSession session, {int? nowMs}) {
  final MatchDetail? detail = session.detail;
  if (detail == null) {
    return null;
  }
  final ScoreSummary score = session.score;
  final ClockReading reading = session.reading(nowMs);
  return OverlayData(
    homeName: detail.home.name,
    awayName: detail.away.name,
    homeColor: detail.home.colorValue,
    awayColor: detail.away.colorValue,
    homeCrestPath: detail.home.crestPath,
    awayCrestPath: detail.away.crestPath,
    homeGoals: score.home,
    awayGoals: score.away,
    clockText: reading.mainText,
    addedText: reading.addedText,
    periodLabel: periodLabelFor(session.clock.phase),
    running: session.clock.running,
    competition: detail.match.tournament,
    sponsors: <OverlaySponsor>[
      for (final Sponsor s in detail.sponsors)
        OverlaySponsor(id: s.id, name: s.name, logoPath: s.logoPath),
    ],
  );
}
