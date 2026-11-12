import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';

/// Equipos. Un equipo se reconoce por su nombre (sin importar mayúsculas).
abstract class TeamRepository {
  Future<Team?> byId(int id);
  Future<List<Team>> all();

  /// Crea el equipo o, si ya existe uno con ese nombre, actualiza su color y
  /// su escudo. Un [crestPath] nulo significa "sin escudo".
  Future<Team> saveByName({
    required String name,
    required int colorValue,
    String? crestPath,
  });
}

/// Jugadores. Lanzan [ValidationException] con datos inválidos y
/// [DuplicateNumberException] si el número ya existe en el equipo.
abstract class PlayerRepository {
  Future<List<Player>> byTeam(int teamId);
  Future<Player> add({
    required int teamId,
    required String name,
    required int number,
  });
  Future<Player> update(Player player);
  Future<void> delete(int id);
}

/// Patrocinadores (sin límite de cantidad).
abstract class SponsorRepository {
  Future<List<Sponsor>> all();
  Future<Sponsor> add({required String name, String? logoPath});
  Future<Sponsor> update(Sponsor sponsor);
  Future<void> delete(int id);
}

/// Partidos, su cronómetro, sus patrocinadores y su historial de eventos.
abstract class MatchRepository {
  /// Último partido no cancelado (programado, en juego o terminado).
  Future<SoccerMatch?> current();

  Future<SoccerMatch> create({
    required int homeTeamId,
    required int awayTeamId,
    String? tournament,
    required int scheduledAtMs,
    required int halfMinutes,
    required int breakMinutes,
  });

  Future<void> update(SoccerMatch match);
  Future<void> setStatus(int matchId, MatchStatus status);

  /// Patrocinadores elegidos para el partido, en el orden en que se eligieron.
  Future<List<int>> sponsorIds(int matchId);
  Future<void> setSponsors(int matchId, List<int> sponsorIds);

  Future<MatchClockState> clock(int matchId);

  /// Guarda de una sola vez (en una transacción) el nuevo estado del
  /// cronómetro, el nuevo estado del partido y el evento asociado.
  /// Devuelve el evento ya guardado, si había uno.
  Future<MatchEvent?> commitClock({
    required int matchId,
    required MatchClockState clock,
    required int nowMs,
    MatchStatus? status,
    MatchEvent? event,
  });

  Future<List<MatchEvent>> events(int matchId);
  Future<MatchEvent> addEvent(MatchEvent event);

  /// Guarda el gol corregido y añade el evento de corrección, juntos.
  /// Devuelve el evento de corrección ya guardado.
  Future<MatchEvent> correctGoal({
    required MatchEvent updatedGoal,
    required MatchEvent correction,
  });
}
