import 'dart:io';

import 'package:image_picker/image_picker.dart';

/// Abre la galería del teléfono para elegir una imagen.
abstract class GalleryPicker {
  /// Devuelve el archivo elegido, o nulo si el usuario cancela.
  Future<File?> pickImage();
}

/// Galería real del teléfono (selector del sistema: no pide permisos).
class ImagePickerGallery implements GalleryPicker {
  ImagePickerGallery({ImagePicker? picker}) : _picker = picker ?? ImagePicker();

  final ImagePicker _picker;

  @override
  Future<File?> pickImage() async {
    final XFile? picked = await _picker.pickImage(
      source: ImageSource.gallery,
      maxWidth: 1024,
      maxHeight: 1024,
    );
    return picked == null ? null : File(picked.path);
  }
}
