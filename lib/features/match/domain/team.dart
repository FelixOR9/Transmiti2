/// Equipo. El color se guarda como valor ARGB para no depender de Flutter.
class Team {
  const Team({
    required this.id,
    required this.name,
    required this.colorValue,
    this.crestPath,
  });

  final int id;
  final String name;
  final int colorValue;

  /// Ruta relativa del escudo dentro del almacén de imágenes.
  final String? crestPath;

  /// Nombre sin espacios sobrantes.
  static String cleanName(String raw) =>
      raw.trim().replaceAll(RegExp(r'\s+'), ' ');

  /// Clave para reconocer el mismo equipo sin importar mayúsculas.
  static String keyOf(String raw) => cleanName(raw).toLowerCase();

  Team copyWith({
    String? name,
    int? colorValue,
    String? crestPath,
    bool clearCrest = false,
  }) {
    return Team(
      id: id,
      name: name ?? this.name,
      colorValue: colorValue ?? this.colorValue,
      crestPath: clearCrest ? null : (crestPath ?? this.crestPath),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is Team &&
      other.id == id &&
      other.name == name &&
      other.colorValue == colorValue &&
      other.crestPath == crestPath;

  @override
  int get hashCode => Object.hash(id, name, colorValue, crestPath);

  @override
  String toString() => 'Team($id, $name)';
}
