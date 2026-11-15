/// Evita acciones repetidas por un doble toque accidental.
///
/// Ignora una acción mientras otra sigue en curso y durante un instante
/// después de que termina ([cooldown]). Así, dos toques seguidos en el botón
/// de gol no pueden registrar dos goles.
class ActionGuard {
  ActionGuard({
    this.cooldown = const Duration(milliseconds: 700),
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  final Duration cooldown;
  final DateTime Function() _now;
  bool _busy = false;
  DateTime? _finishedAt;

  /// Verdadero mientras una acción está en curso.
  bool get busy => _busy;

  /// Ejecuta [action] si no hay otra en curso ni terminó hace menos de
  /// [cooldown]. Devuelve verdadero si se ejecutó y falso si se ignoró.
  Future<bool> run(Future<void> Function() action) async {
    if (_busy) {
      return false;
    }
    final DateTime? last = _finishedAt;
    if (last != null && _now().difference(last) < cooldown) {
      return false;
    }
    _busy = true;
    try {
      await action();
    } finally {
      _busy = false;
      _finishedAt = _now();
    }
    return true;
  }
}
