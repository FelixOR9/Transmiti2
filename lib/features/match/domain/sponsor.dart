/// Patrocinador con su logotipo opcional.
class Sponsor {
  const Sponsor({required this.id, required this.name, this.logoPath});

  final int id;
  final String name;

  /// Ruta relativa del logotipo dentro del almacén de imágenes.
  final String? logoPath;

  Sponsor copyWith({String? name, String? logoPath, bool clearLogo = false}) {
    return Sponsor(
      id: id,
      name: name ?? this.name,
      logoPath: clearLogo ? null : (logoPath ?? this.logoPath),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is Sponsor &&
      other.id == id &&
      other.name == name &&
      other.logoPath == logoPath;

  @override
  int get hashCode => Object.hash(id, name, logoPath);

  @override
  String toString() => 'Sponsor($id, $name)';
}

/// Reglas de validación de los patrocinadores.
abstract final class SponsorRules {
  static const int maxNameLength = 50;

  static Map<String, String> validate({required String name}) {
    final String clean = name.trim();
    if (clean.isEmpty) {
      return <String, String>{'name': 'Escribe el nombre del patrocinador.'};
    }
    if (clean.length > maxNameLength) {
      return <String, String>{'name': 'Máximo $maxNameLength caracteres.'};
    }
    return <String, String>{};
  }
}
