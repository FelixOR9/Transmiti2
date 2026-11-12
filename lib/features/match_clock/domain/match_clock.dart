import 'package:transmiti2/core/formatting.dart';

enum MatchPhase { notStarted, firstHalf, halfTime, secondHalf, fullTime }

/// Estado del cronómetro del partido.
///
/// No cuenta con temporizadores: guarda marcas de tiempo del reloj del
/// teléfono ([resumedAtMs]) y el tiempo acumulado antes de la última
/// reanudación. Así el tiempo transcurrido se calcula siempre a partir de la
/// hora actual y no se pierde si la pantalla se apaga, la app se minimiza o
/// el sistema la cierra: basta guardar este estado y leerlo al volver.
class MatchClockState {
  const MatchClockState({
    this.phase = MatchPhase.notStarted,
    this.period = 0,
    this.running = false,
    this.periodElapsedMs = 0,
    this.resumedAtMs,
    this.firstHalfMs,
    this.breakStartedAtMs,
  });

  static const MatchClockState initial = MatchClockState();

  final MatchPhase phase;

  /// 0 sin empezar, 1 primer tiempo, 2 segundo tiempo.
  final int period;
  final bool running;

  /// Tiempo acumulado del periodo actual hasta la última pausa.
  final int periodElapsedMs;

  /// Hora (ms desde 1970) de la última reanudación; nula si está en pausa.
  final int? resumedAtMs;
  final int? firstHalfMs;
  final int? breakStartedAtMs;

  bool get isLive =>
      phase == MatchPhase.firstHalf || phase == MatchPhase.secondHalf;
  bool get canStart => phase == MatchPhase.notStarted;
  bool get canPause => isLive && running;
  bool get canResume => isLive && !running;
  bool get canEndFirstHalf => phase == MatchPhase.firstHalf;
  bool get canStartSecondHalf => phase == MatchPhase.halfTime;
  bool get canEndMatch =>
      phase == MatchPhase.firstHalf ||
      phase == MatchPhase.halfTime ||
      phase == MatchPhase.secondHalf;

  /// Tiempo transcurrido del periodo actual a la hora [nowMs].
  int elapsedAt(int nowMs) {
    if (!running || resumedAtMs == null) {
      return periodElapsedMs;
    }
    final int delta = nowMs - resumedAtMs!;
    return periodElapsedMs + (delta > 0 ? delta : 0);
  }

  MatchClockState start(int nowMs) {
    _require(canStart, 'iniciar el partido');
    return MatchClockState(
      phase: MatchPhase.firstHalf,
      period: 1,
      running: true,
      resumedAtMs: nowMs,
    );
  }

  MatchClockState pause(int nowMs) {
    _require(canPause, 'pausar el cronómetro');
    return MatchClockState(
      phase: phase,
      period: period,
      running: false,
      periodElapsedMs: elapsedAt(nowMs),
      firstHalfMs: firstHalfMs,
    );
  }

  MatchClockState resume(int nowMs) {
    _require(canResume, 'reanudar el cronómetro');
    return MatchClockState(
      phase: phase,
      period: period,
      running: true,
      periodElapsedMs: periodElapsedMs,
      resumedAtMs: nowMs,
      firstHalfMs: firstHalfMs,
    );
  }

  MatchClockState endFirstHalf(int nowMs) {
    _require(canEndFirstHalf, 'finalizar el primer tiempo');
    final int elapsed = elapsedAt(nowMs);
    return MatchClockState(
      phase: MatchPhase.halfTime,
      period: 1,
      periodElapsedMs: elapsed,
      firstHalfMs: elapsed,
      breakStartedAtMs: nowMs,
    );
  }

  MatchClockState startSecondHalf(int nowMs) {
    _require(canStartSecondHalf, 'iniciar el segundo tiempo');
    return MatchClockState(
      phase: MatchPhase.secondHalf,
      period: 2,
      running: true,
      resumedAtMs: nowMs,
      firstHalfMs: firstHalfMs,
    );
  }

  MatchClockState endMatch(int nowMs) {
    _require(canEndMatch, 'finalizar el partido');
    final int elapsed = phase == MatchPhase.halfTime
        ? periodElapsedMs
        : elapsedAt(nowMs);
    return MatchClockState(
      phase: MatchPhase.fullTime,
      period: period,
      periodElapsedMs: elapsed,
      firstHalfMs: firstHalfMs,
    );
  }

  void _require(bool allowed, String action) {
    if (!allowed) {
      throw StateError('No se puede $action en este momento.');
    }
  }

  /// Columnas de la tabla match_clock (sin match_id ni updated_at_ms).
  Map<String, Object?> toMap() => <String, Object?>{
    'phase': phase.name,
    'period': period,
    'running': running ? 1 : 0,
    'period_elapsed_ms': periodElapsedMs,
    'resumed_at_ms': resumedAtMs,
    'first_half_ms': firstHalfMs,
    'break_started_at_ms': breakStartedAtMs,
  };

  factory MatchClockState.fromMap(Map<String, Object?> map) {
    return MatchClockState(
      phase: MatchPhase.values.byName(map['phase']! as String),
      period: map['period']! as int,
      running: (map['running']! as int) == 1,
      periodElapsedMs: map['period_elapsed_ms']! as int,
      resumedAtMs: map['resumed_at_ms'] as int?,
      firstHalfMs: map['first_half_ms'] as int?,
      breakStartedAtMs: map['break_started_at_ms'] as int?,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is MatchClockState &&
      other.phase == phase &&
      other.period == period &&
      other.running == running &&
      other.periodElapsedMs == periodElapsedMs &&
      other.resumedAtMs == resumedAtMs &&
      other.firstHalfMs == firstHalfMs &&
      other.breakStartedAtMs == breakStartedAtMs;

  @override
  int get hashCode => Object.hash(
    phase,
    period,
    running,
    periodElapsedMs,
    resumedAtMs,
    firstHalfMs,
    breakStartedAtMs,
  );

  @override
  String toString() => 'MatchClockState(${phase.name}, running: $running)';
}

/// Lo que debe mostrar el reloj en un instante dado.
class ClockReading {
  const ClockReading({
    required this.phase,
    required this.running,
    required this.period,
    required this.regularMs,
    required this.addedMs,
    this.breakElapsedMs,
  });

  factory ClockReading.of(
    MatchClockState state, {
    required int nowMs,
    required int halfMinutes,
  }) {
    if (state.period == 0) {
      return ClockReading(
        phase: state.phase,
        running: state.running,
        period: 0,
        regularMs: 0,
        addedMs: 0,
      );
    }
    final int halfMs = halfMinutes * 60000;
    final int elapsed = state.elapsedAt(nowMs);
    final int base = state.period >= 2 ? halfMs : 0;
    int? breakMs;
    if (state.phase == MatchPhase.halfTime && state.breakStartedAtMs != null) {
      final int delta = nowMs - state.breakStartedAtMs!;
      breakMs = delta > 0 ? delta : 0;
    }
    return ClockReading(
      phase: state.phase,
      running: state.running,
      period: state.period,
      regularMs: base + (elapsed < halfMs ? elapsed : halfMs),
      addedMs: elapsed > halfMs ? elapsed - halfMs : 0,
      breakElapsedMs: breakMs,
    );
  }

  final MatchPhase phase;
  final bool running;
  final int period;

  /// Tiempo de juego dentro de la duración reglamentaria (acumulado de ambos
  /// tiempos).
  final int regularMs;

  /// Tiempo añadido del periodo actual, pasada su duración reglamentaria.
  final int addedMs;

  /// Tiempo de descanso transcurrido (solo durante el descanso).
  final int? breakElapsedMs;

  /// Tiempo total transcurrido del partido, incluido el añadido.
  int get totalMs => regularMs + addedMs;

  String get mainText => formatClock(regularMs);

  String? get addedText => addedMs > 0 ? '+${formatClock(addedMs)}' : null;
}
