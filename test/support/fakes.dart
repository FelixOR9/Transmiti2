import 'dart:io';

import 'package:transmiti2/core/storage/gallery_picker.dart';
import 'package:transmiti2/core/storage/image_store.dart';

/// Galería falsa: "elige" siempre el mismo archivo sin abrir ninguna pantalla.
class FakeGalleryPicker implements GalleryPicker {
  int pickCount = 0;

  /// Si es verdadero, el usuario cancela la selección.
  bool cancel = false;

  @override
  Future<File?> pickImage() async {
    pickCount++;
    return cancel ? null : File('/fake/galeria/foto.png');
  }
}

/// Almacén falso para pruebas de interfaz: no toca el disco. Reparte rutas
/// distintas y recuerda qué se guardó y qué se borró.
class FakeImageStore extends ImageStore {
  FakeImageStore() : super(Directory('/fake/imagenes'));

  final List<String> saved = <String>[];
  final Set<String> deleted = <String>{};
  int _next = 0;

  /// Ruta que no existe en el almacén, para forzar un error.
  bool failNextSave = false;

  @override
  Future<String> save(File source, ImageKind kind) async {
    if (failNextSave) {
      failNextSave = false;
      throw const ImageStorageException(
        'Formato no admitido. Usa una imagen PNG, JPG o JPEG.',
      );
    }
    final String path = '${kind.folder}/falsa_${_next++}.png';
    saved.add(path);
    return path;
  }

  @override
  File file(String relativePath) => File('/fake/imagenes/$relativePath');

  @override
  Future<bool> exists(String? relativePath) async =>
      relativePath != null && !deleted.contains(relativePath);

  @override
  Future<void> delete(String? relativePath) async {
    if (relativePath != null) {
      deleted.add(relativePath);
    }
  }
}
