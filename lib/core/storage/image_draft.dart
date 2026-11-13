import 'dart:io';

import 'package:transmiti2/core/storage/image_store.dart';

/// Imagen en edición dentro de un formulario (escudo o logotipo).
///
/// Mientras el usuario edita, las imágenes nuevas ya se copian al almacén,
/// pero las anteriores NO se borran hasta que el formulario se guarda bien.
/// Así, cancelar nunca pierde una imagen guardada, y guardar no deja
/// archivos sobrantes.
///
/// [isInUse] protege los archivos: nada que siga referenciado por un equipo
/// o patrocinador se borra, aunque este formulario ya no lo use.
class ImageDraft {
  ImageDraft({
    required this.store,
    required this.kind,
    String? initial,
    this.isInUse,
  })  : _committed = initial,
        current = initial;

  final ImageStore store;
  final ImageKind kind;
  final Future<bool> Function(String path)? isInUse;

  String? _committed;
  final List<String> _created = <String>[];

  /// Ruta relativa que se guardará (nula = sin imagen).
  String? current;

  /// Ruta que está guardada actualmente en la base de datos.
  String? get committed => _committed;

  bool get hasImage => current != null;
  bool get changed => current != _committed;

  /// Parte de una imagen que ya estaba guardada (por ejemplo, la de un
  /// equipo reconocido por su nombre).
  void adopt(String? path) {
    _committed = path;
    current = path;
  }

  /// Copia [source] al almacén y la deja como imagen actual.
  /// Lanza [ImageStorageException] si no es una imagen válida.
  Future<void> replaceWith(File source) async {
    final String path = await store.save(source, kind);
    _created.add(path);
    current = path;
  }

  /// Quita la imagen actual (el archivo se borra al guardar).
  void clear() {
    current = null;
  }

  /// El formulario se guardó: borra lo que ya no se usa.
  Future<void> commit() async {
    final List<String> candidates = <String>[
      for (final String p in _created)
        if (p != current) p,
      if (_committed != null && _committed != current) _committed!,
    ];
    _created.clear();
    _committed = current;
    await _deleteUnused(candidates);
  }

  /// El formulario se cerró sin guardar: borra lo cargado en esta sesión y
  /// vuelve a la imagen guardada.
  Future<void> discard() async {
    final List<String> created = List<String>.of(_created);
    _created.clear();
    current = _committed;
    await _deleteUnused(created);
  }

  Future<void> _deleteUnused(List<String> paths) async {
    for (final String path in paths) {
      final Future<bool> Function(String path)? check = isInUse;
      if (check != null && await check(path)) {
        continue;
      }
      await store.delete(path);
    }
  }
}
