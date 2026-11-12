/// Minuto de un gol: minuto base y minutos añadidos (por ejemplo 45+2).
class GoalMinute {
  const GoalMinute(this.minute, [this.added = 0]);

  final int minute;
  final int added;

  static const int maxMinute = 150;

  /// Lee textos como "23", "23'" o "45+2". Devuelve nulo si no es válido.
  static GoalMinute? tryParse(String raw) {
    final String text = raw.trim().replaceAll("'", '').replaceAll('’', '');
    final RegExpMatch? match = RegExp(r'^(\d{1,3})(?:\s*\+\s*(\d{1,2}))?$')
        .firstMatch(text);
    if (match == null) {
      return null;
    }
    final int minute = int.parse(match.group(1)!);
    final int added = match.group(2) == null ? 0 : int.parse(match.group(2)!);
    if (minute < 1 || minute > maxMinute) {
      return null;
    }
    return GoalMinute(minute, added);
  }

  /// Minuto que corresponde a [elapsedMs] transcurridos dentro del tiempo
  /// [period] (1 o 2). Pasada la duración reglamentaria cuenta como añadido.
  static GoalMinute fromElapsed({
    required int period,
    required int elapsedMs,
    required int halfMinutes,
  }) {
    final int halfMs = halfMinutes * 60000;
    final int base = period >= 2 ? halfMinutes : 0;
    final int e = elapsedMs < 0 ? 0 : elapsedMs;
    if (e < halfMs) {
      return GoalMinute(base + e ~/ 60000 + 1);
    }
    return GoalMinute(base + halfMinutes, (e - halfMs) ~/ 60000 + 1);
  }

  /// Texto para mostrar, por ejemplo 23' o 45+2'.
  String get label => added > 0 ? "$minute+$added'" : "$minute'";

  @override
  bool operator ==(Object other) =>
      other is GoalMinute && other.minute == minute && other.added == added;

  @override
  int get hashCode => Object.hash(minute, added);

  @override
  String toString() => 'GoalMinute($label)';
}
