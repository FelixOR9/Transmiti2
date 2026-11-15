import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';

import 'in_memory_repositories.dart';

/// Repositorio que falla a propósito, para comprobar que un error de
/// almacenamiento nunca deja el marcador, el cronómetro y el historial en
/// estados distintos.
class FlakyMatchRepository extends InMemoryMatchRepository {
  FlakyMatchRepository({super.nowMs});

  bool failEvents = false;
  bool failClock = false;
  bool failCorrection = false;

  @override
  Future<MatchEvent> addEvent(MatchEvent event) async {
    if (failEvents) {
      throw StateError('Disco lleno (simulado)');
    }
    return super.addEvent(event);
  }

  @override
  Future<MatchEvent?> commitClock({
    required int matchId,
    required MatchClockState clock,
    required int nowMs,
    MatchStatus? status,
    MatchEvent? event,
  }) async {
    if (failClock) {
      throw StateError('Disco lleno (simulado)');
    }
    return super.commitClock(
      matchId: matchId,
      clock: clock,
      nowMs: nowMs,
      status: status,
      event: event,
    );
  }

  @override
  Future<MatchEvent> correctGoal({
    required MatchEvent updatedGoal,
    required MatchEvent correction,
  }) async {
    if (failCorrection) {
      throw StateError('Disco lleno (simulado)');
    }
    return super.correctGoal(updatedGoal: updatedGoal, correction: correction);
  }
}
