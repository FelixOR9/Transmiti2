import 'package:transmiti2/features/match/domain/team.dart';

enum MatchStatus { scheduled, live, finished, cancelled }

/// Límites de los datos del partido.
abstract final class MatchRules {
  static const int minHalfMinutes = 1;
  static const int maxHalfMinutes = 90;
  static const int minBreakMinutes = 0;
  static const int maxBreakMinutes = 60;
  static const int maxTeamNameLength = 40;
  static const int maxTournamentLength = 60;

  /// Errores de las duraciones, por campo; vacío si son válidas.
  static Map<String, String> validateDurations({
    required int halfMinutes,
    required int breakMinutes,
  }) {
    final Map<String, String> errors = <String, String>{};
    if (halfMinutes < minHalfMinutes || halfMinutes > maxHalfMinutes) {
      errors['halfMinutes'] =
          'Cada tiempo debe durar entre $minHalfMinutes y $maxHalfMinutes minutos.';
    }
    if (breakMinutes < minBreakMinutes || breakMinutes > maxBreakMinutes) {
      errors['breakMinutes'] =
          'El descanso debe durar entre $minBreakMinutes y $maxBreakMinutes minutos.';
    }
    return errors;
  }
}

/// Partido guardado.
class SoccerMatch {
  const SoccerMatch({
    required this.id,
    required this.homeTeamId,
    required this.awayTeamId,
    this.tournament,
    required this.scheduledAtMs,
    required this.halfMinutes,
    required this.breakMinutes,
    required this.status,
    required this.createdAtMs,
  });

  final int id;
  final int homeTeamId;
  final int awayTeamId;
  final String? tournament;
  final int scheduledAtMs;
  final int halfMinutes;
  final int breakMinutes;
  final MatchStatus status;
  final int createdAtMs;

  SoccerMatch copyWith({
    int? homeTeamId,
    int? awayTeamId,
    String? tournament,
    bool clearTournament = false,
    int? scheduledAtMs,
    int? halfMinutes,
    int? breakMinutes,
    MatchStatus? status,
  }) {
    return SoccerMatch(
      id: id,
      homeTeamId: homeTeamId ?? this.homeTeamId,
      awayTeamId: awayTeamId ?? this.awayTeamId,
      tournament: clearTournament ? null : (tournament ?? this.tournament),
      scheduledAtMs: scheduledAtMs ?? this.scheduledAtMs,
      halfMinutes: halfMinutes ?? this.halfMinutes,
      breakMinutes: breakMinutes ?? this.breakMinutes,
      status: status ?? this.status,
      createdAtMs: createdAtMs,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is SoccerMatch &&
      other.id == id &&
      other.homeTeamId == homeTeamId &&
      other.awayTeamId == awayTeamId &&
      other.tournament == tournament &&
      other.scheduledAtMs == scheduledAtMs &&
      other.halfMinutes == halfMinutes &&
      other.breakMinutes == breakMinutes &&
      other.status == status &&
      other.createdAtMs == createdAtMs;

  @override
  int get hashCode => Object.hash(
    id,
    homeTeamId,
    awayTeamId,
    tournament,
    scheduledAtMs,
    halfMinutes,
    breakMinutes,
    status,
    createdAtMs,
  );
}

/// Datos del formulario de preparación del partido.
class MatchSetup {
  const MatchSetup({
    required this.homeName,
    required this.homeColor,
    this.homeCrestPath,
    required this.awayName,
    required this.awayColor,
    this.awayCrestPath,
    this.tournament,
    required this.scheduledAtMs,
    required this.halfMinutes,
    required this.breakMinutes,
    this.sponsorIds = const <int>[],
  });

  final String homeName;
  final int homeColor;
  final String? homeCrestPath;
  final String awayName;
  final int awayColor;
  final String? awayCrestPath;
  final String? tournament;
  final int scheduledAtMs;
  final int halfMinutes;
  final int breakMinutes;
  final List<int> sponsorIds;

  /// Torneo sin espacios sobrantes; nulo si quedó vacío.
  String? get cleanTournament {
    final String? t = tournament?.trim();
    return (t == null || t.isEmpty) ? null : t;
  }

  /// Errores por campo; vacío si los datos son válidos.
  Map<String, String> validate() {
    final Map<String, String> errors = <String, String>{};
    final String home = Team.cleanName(homeName);
    final String away = Team.cleanName(awayName);

    if (home.isEmpty) {
      errors['homeName'] = 'Escribe el nombre del equipo local.';
    } else if (home.length > MatchRules.maxTeamNameLength) {
      errors['homeName'] = 'Máximo ${MatchRules.maxTeamNameLength} caracteres.';
    }
    if (away.isEmpty) {
      errors['awayName'] = 'Escribe el nombre del equipo visitante.';
    } else if (away.length > MatchRules.maxTeamNameLength) {
      errors['awayName'] = 'Máximo ${MatchRules.maxTeamNameLength} caracteres.';
    }
    if (home.isNotEmpty &&
        away.isNotEmpty &&
        Team.keyOf(home) == Team.keyOf(away)) {
      errors['awayName'] = 'Los equipos deben tener nombres distintos.';
    }
    if (homeColor == awayColor) {
      errors['awayColor'] = 'Elige un color distinto al del equipo local.';
    }
    if ((cleanTournament ?? '').length > MatchRules.maxTournamentLength) {
      errors['tournament'] =
          'Máximo ${MatchRules.maxTournamentLength} caracteres.';
    }
    if (scheduledAtMs <= 0) {
      errors['date'] = 'Elige la fecha del partido.';
    }
    errors.addAll(
      MatchRules.validateDurations(
        halfMinutes: halfMinutes,
        breakMinutes: breakMinutes,
      ),
    );
    return errors;
  }
}
