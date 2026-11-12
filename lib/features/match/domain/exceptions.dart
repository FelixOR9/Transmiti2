/// Datos inválidos: [errors] relaciona cada campo con su mensaje.
class ValidationException implements Exception {
  const ValidationException(this.errors);

  final Map<String, String> errors;

  @override
  String toString() => 'ValidationException: $errors';
}

/// Ya existe un jugador con ese número en el mismo equipo.
class DuplicateNumberException implements Exception {
  const DuplicateNumberException(this.number);

  final int number;

  @override
  String toString() => 'DuplicateNumberException: $number';
}
