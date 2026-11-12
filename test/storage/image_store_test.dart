import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/core/storage/image_store.dart';

const List<int> pngBytes = <int>[
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, //
  0x49, 0x48, 0x44, 0x52,
];
const List<int> jpegBytes = <int>[
  0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, //
  0x01, 0x00,
];

void main() {
  late Directory temp;
  late Directory sourceDir;
  late ImageStore store;

  setUp(() async {
    temp = await Directory.systemTemp.createTemp('transmiti2_images_');
    sourceDir = await Directory('${temp.path}/gallery').create();
    store = ImageStore(Directory('${temp.path}/app_images'), maxBytes: 1000);
  });

  tearDown(() async {
    await temp.delete(recursive: true);
  });

  Future<File> source(String name, List<int> bytes) async {
    final File f = File('${sourceDir.path}/$name');
    await f.writeAsBytes(bytes);
    return f;
  }

  group('detectExtension', () {
    test('reconoce PNG y JPEG por sus primeros bytes', () {
      expect(ImageStore.detectExtension(pngBytes), 'png');
      expect(ImageStore.detectExtension(jpegBytes), 'jpg');
    });

    test('rechaza otros formatos', () {
      expect(ImageStore.detectExtension(<int>[0x47, 0x49, 0x46, 0x38]), isNull);
      expect(ImageStore.detectExtension(<int>[]), isNull);
      expect(ImageStore.detectExtension(<int>[0x89, 0x50]), isNull);
    });
  });

  group('save', () {
    test('guarda un PNG de escudo y devuelve su ruta relativa', () async {
      final File src = await source('escudo.png', pngBytes);
      final String path = await store.save(src, ImageKind.crest);
      expect(path.startsWith('crests/'), isTrue);
      expect(path.endsWith('.png'), isTrue);
      final File saved = store.file(path);
      expect(await saved.exists(), isTrue);
      expect(await saved.readAsBytes(), pngBytes);
      expect(await store.exists(path), isTrue);
    });

    test(
      'guarda un JPEG de logotipo aunque el nombre no tenga extensión',
      () async {
        final File src = await source('foto', jpegBytes);
        final String path = await store.save(src, ImageKind.logo);
        expect(path.startsWith('logos/'), isTrue);
        expect(path.endsWith('.jpg'), isTrue);
      },
    );

    test('el formato se decide por el contenido, no por el nombre', () async {
      final File src = await source('falso.png', <int>[1, 2, 3, 4, 5, 6, 7, 8]);
      await expectLater(
        store.save(src, ImageKind.crest),
        throwsA(isA<ImageStorageException>()),
      );
      final File real = await source('real.txt', jpegBytes);
      expect(await store.save(real, ImageKind.crest), endsWith('.jpg'));
    });

    test('rechaza archivos vacíos, demasiado grandes o inexistentes', () async {
      final File empty = await source('vacio.png', <int>[]);
      await expectLater(
        store.save(empty, ImageKind.crest),
        throwsA(isA<ImageStorageException>()),
      );
      final File big = await source('grande.png', <int>[
        ...pngBytes,
        ...List<int>.filled(1000, 0),
      ]);
      await expectLater(
        store.save(big, ImageKind.crest),
        throwsA(isA<ImageStorageException>()),
      );
      await expectLater(
        store.save(File('${sourceDir.path}/no_existe.png'), ImageKind.crest),
        throwsA(isA<ImageStorageException>()),
      );
    });

    test(
      'cada imagen recibe un nombre distinto y el original no se toca',
      () async {
        final File src = await source('escudo.png', pngBytes);
        final String a = await store.save(src, ImageKind.crest);
        final String b = await store.save(src, ImageKind.crest);
        expect(a, isNot(b));
        expect(await src.exists(), isTrue);
      },
    );

    test('la imagen guardada no depende del original', () async {
      final File src = await source('escudo.png', pngBytes);
      final String path = await store.save(src, ImageKind.crest);
      await src.delete();
      expect(await store.exists(path), isTrue);
    });
  });

  group('file, exists y delete', () {
    test('file rechaza rutas fuera del almacén', () {
      expect(() => store.file('../secreto.txt'), throwsA(isA<ArgumentError>()));
      expect(
        () => store.file('crests/../../secreto.txt'),
        throwsA(isA<ArgumentError>()),
      );
    });

    test(
      'delete borra la imagen y tolera rutas nulas o inexistentes',
      () async {
        final File src = await source('escudo.png', pngBytes);
        final String path = await store.save(src, ImageKind.crest);
        await store.delete(path);
        expect(await store.exists(path), isFalse);

        await store.delete(path);
        await store.delete(null);
        expect(await store.exists(null), isFalse);
      },
    );
  });
}
