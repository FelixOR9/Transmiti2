/// Destino de transmisión guardado por el usuario.
///
/// La clave de transmisión NO vive aquí ni en SQLite: en la base solo se
/// guarda `hasKey` (si hay una clave cifrada en el almacén seguro de
/// Android). El motor RTMP solo recibe un endpoint con la referencia a la
/// clave, nunca su valor.
enum StreamDestinationKind {
  /// URL RTMPS + clave pegadas a mano desde Facebook Live Producer.
  /// Es el único tipo disponible en la Fase 3.0.
  manual,

  /// Destino creado con Facebook Login + Graph API. Reservado: en la
  /// Fase 3.0 no se crea ninguno y no hay ninguna implementación.
  facebookGraph,
}

/// Un destino de transmisión.
class StreamDestination {
  const StreamDestination({
    required this.id,
    required this.kind,
    required this.label,
    required this.ingestUrl,
    this.pageId,
    this.pageName,
    required this.hasKey,
    required this.isActive,
    this.expiresAtMs,
    required this.createdAtMs,
    required this.updatedAtMs,
  });

  final int id;
  final StreamDestinationKind kind;
  final String label;

  /// URL de ingesta RTMPS (por ejemplo, la `secure_stream_url` de
  /// Facebook Live Producer). Nunca lleva la clave dentro.
  final String ingestUrl;

  /// Solo para destinos `facebookGraph` (reservado en 3.0).
  final String? pageId;
  final String? pageName;

  /// `true` si hay una clave cifrada guardada para este destino.
  final bool hasKey;

  /// Solo un destino puede estar activo a la vez.
  final bool isActive;

  /// Solo para destinos `facebookGraph` (reservado en 3.0).
  final int? expiresAtMs;

  final int createdAtMs;
  final int updatedAtMs;

  /// La URL de ingesta debe ser RTMPS. Se rechazan `rtmp://`, `http://`
  /// y `https://`: la transmisión no debe ir sin cifrar.
  static bool isValidIngestUrl(String url) {
    if (!url.startsWith('rtmps://')) {
      return false;
    }
    final Uri? uri = Uri.tryParse(url);
    return uri != null && uri.hasAuthority && uri.host.isNotEmpty;
  }

  StreamDestination copyWith({
    String? label,
    String? ingestUrl,
    bool? hasKey,
    bool? isActive,
    int? updatedAtMs,
  }) {
    return StreamDestination(
      id: id,
      kind: kind,
      label: label ?? this.label,
      ingestUrl: ingestUrl ?? this.ingestUrl,
      pageId: pageId,
      pageName: pageName,
      hasKey: hasKey ?? this.hasKey,
      isActive: isActive ?? this.isActive,
      expiresAtMs: expiresAtMs,
      createdAtMs: createdAtMs,
      updatedAtMs: updatedAtMs ?? this.updatedAtMs,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is StreamDestination &&
      other.id == id &&
      other.kind == kind &&
      other.label == label &&
      other.ingestUrl == ingestUrl &&
      other.pageId == pageId &&
      other.pageName == pageName &&
      other.hasKey == hasKey &&
      other.isActive == isActive &&
      other.expiresAtMs == expiresAtMs;

  @override
  int get hashCode => Object.hash(
    id,
    kind,
    label,
    ingestUrl,
    pageId,
    pageName,
    hasKey,
    isActive,
    expiresAtMs,
  );
}
