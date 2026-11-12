import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match/domain/team.dart';

/// Partido junto con sus equipos y los patrocinadores elegidos (en orden).
class MatchDetail {
  const MatchDetail({
    required this.match,
    required this.home,
    required this.away,
    required this.sponsors,
  });

  final SoccerMatch match;
  final Team home;
  final Team away;
  final List<Sponsor> sponsors;

  Team? teamById(int? id) {
    if (id == home.id) {
      return home;
    }
    if (id == away.id) {
      return away;
    }
    return null;
  }
}
