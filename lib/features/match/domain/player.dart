/// Jugador de un equipo.
class Player {
  const Player({
    required this.id,
    required this.teamId,
    required this.name,
    required this.number,
  });

  final int id;
  final int teamId;
  final String name;
  final int number;

  Player copyWith({String? name, int? number}) {
    return Player(
      id: id,
      teamId: teamId,
      name: name ?? this.name,
      number: number ?? this.number,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is Player &&
      other.id == id &&
      other.teamId == teamId &&
      other.name == name &&
      other.number == number;

  @override
  int get hashCode => Object.hash(id, teamId, name, number);

  @override
  String toString() => 'Player($id, #$number $name)';
}

/// Reglas de validación de los jugadores.
abstract final class PlayerRules {
  static const int maxNameLength = 40;
  static const int minNumber = 1;
  static const int maxNumber = 99;

  /// Devuelve los errores por campo ('name', 'number'); vacío si todo es válido.
  static Map<String, String> validate({
    required String name,
    required int? number,
  }) {
    final Map<String, String> errors = <String, String>{};
    final String clean = name.trim();
    if (clean.isEmpty) {
      errors['name'] = 'Escribe el nombre del jugador.';
    } else if (clean.length > maxNameLength) {
      errors['name'] = 'Máximo $maxNameLength caracteres.';
    }
    if (number == null) {
      errors['number'] = 'Escribe el número de camiseta.';
    } else if (number < minNumber || number > maxNumber) {
      errors['number'] = 'El número debe estar entre $minNumber y $maxNumber.';
    }
    return errors;
  }
}
