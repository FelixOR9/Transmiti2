import 'dart:io';

import 'package:path/path.dart' as p;

/// Tipos de imagen que se guardan, cada uno en su carpeta.
enum ImageKind {
  crest('crests'),
  logo('logos');

  const ImageKind(this.folder);

  final String folder;
}

/// La imagen no es válida o no se pudo guardar.
class ImageStorageException implements Exception {
  const ImageStorageException(this.message);

  final String message;

  @override
  String toString() => 'ImageStorageException: $message';
}

/// Almacén persistente de escudos y logotipos.
///
/// Copia la imagen elegida a una carpeta propia de la app (sobrevive a cerrar
/// la app y no depende de la galería) y devuelve su ruta relativa, que es lo
/// único que se guarda en SQLite.
class ImageStore {
  ImageStore(this.root, {this.maxBytes = defaultMaxBytes});

  static const int defaultMaxBytes = 8 * 1024 * 1024;

  final Directory root;
  final int maxBytes;
  int _counter = 0;

  /// Detecta el formato real por los primeros bytes (no por el nombre).
  /// Devuelve 'png', 'jpg' o nulo si no es un formato admitido.
  static String? detectExtension(List<int> header) {
    if (header.length >= 8 &&
        header[0] == 0x89 &&
        header[1] == 0x50 &&
        header[2] == 0x4E &&
        header[3] == 0x47 &&
        header[4] == 0x0D &&
        header[5] == 0x0A &&
        header[6] == 0x1A &&
        header[7] == 0x0A) {
      return 'png';
    }
    if (header.length >= 3 &&
        header[0] == 0xFF &&
        header[1] == 0xD8 &&
        header[2] == 0xFF) {
      return 'jpg';
    }
    return null;
  }

  /// Valida y copia [source]. Devuelve la ruta relativa, por ejemplo
  /// `crests/1731360000000000_0.png`.
  Future<String> save(File source, ImageKind kind) async {
    try {
      if (!await source.exists()) {
        throw const ImageStorageException(
          'No se encontró la imagen seleccionada.',
        );
      }
      final int length = await source.length();
      if (length == 0) {
        throw const ImageStorageException('La imagen está vacía.');
      }
      if (length > maxBytes) {
        throw const ImageStorageException('La imagen es demasiado grande.');
      }
      final RandomAccessFile handle = await source.open();
      final List<int> header;
      try {
        header = await handle.read(12);
      } finally {
        await handle.close();
      }
      final String? extension = detectExtension(header);
      if (extension == null) {
        throw const ImageStorageException(
          'Formato no admitido. Usa una imagen PNG, JPG o JPEG.',
        );
      }
      final Directory dir = Directory(p.join(root.path, kind.folder));
      await dir.create(recursive: true);
      final String name =
          '${DateTime.now().microsecondsSinceEpoch}_${_counter++}.$extension';
      await source.copy(p.join(dir.path, name));
      return '${kind.folder}/$name';
    } on FileSystemException catch (e) {
      throw ImageStorageException('No se pudo guardar la imagen: ${e.message}');
    }
  }

  /// Archivo correspondiente a una ruta relativa guardada en la base.
  File file(String relativePath) {
    final String full = p.normalize(p.join(root.path, relativePath));
    if (!p.isWithin(root.path, full)) {
      throw ArgumentError.value(
        relativePath,
        'relativePath',
        'La ruta está fuera del almacén de imágenes.',
      );
    }
    return File(full);
  }

  /// Indica si el archivo existe (la ruta guardada puede quedar huérfana).
  Future<bool> exists(String? relativePath) async {
    if (relativePath == null) {
      return false;
    }
    return file(relativePath).exists();
  }

  /// Borra la imagen; si ya no existe no es un error.
  Future<void> delete(String? relativePath) async {
    if (relativePath == null) {
      return;
    }
    try {
      final File target = file(relativePath);
      if (await target.exists()) {
        await target.delete();
      }
    } on FileSystemException {
      // No se pudo borrar: queda un archivo huérfano, sin consecuencias.
    }
  }
}
