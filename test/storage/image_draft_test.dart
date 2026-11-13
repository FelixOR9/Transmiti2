import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/core/storage/image_draft.dart';
import 'package:transmiti2/core/storage/image_store.dart';

const List<int> pngBytes = <int>[
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, //
  0x49, 0x48, 0x44, 0x52,
];

void main() {
  late Directory temp;
  late ImageStore store;
  late File source;
  Set<String> inUse = <String>{};

  setUp(() async {
    temp = await Directory.systemTemp.createTemp('transmiti2_draft_');
    store = ImageStore(Directory('${temp.path}/app_images'));
    source = File('${temp.path}/foto.png');
    await source.writeAsBytes(pngBytes);
    inUse = <String>{};
  });

  tearDown(() async {
    await temp.delete(recursive: true);
  });

  ImageDraft draft({String? initial}) => ImageDraft(
        store: store,
        kind: ImageKind.crest,
        initial: initial,
        isInUse: (String path) async => inUse.contains(path),
      );

  /// Deja una imagen ya guardada, como si estuviera en la base de datos.
  Future<String> savedImage() async {
    final String path = await store.save(source, ImageKind.crest);
    inUse.add(path);
    return path;
  }

  test('cargar una imagen la deja como actual y la guarda en el almacén',
      () async {
    final ImageDraft d = draft();
    expect(d.hasImage, isFalse);
    await d.replaceWith(source);
    expect(d.hasImage, isTrue);
    expect(d.changed, isTrue);
    expect(await store.exists(d.current), isTrue);
  });

  test('guardar conserva la imagen nueva', () async {
    final ImageDraft d = draft();
    await d.replaceWith(source);
    final String path = d.current!;
    inUse.add(path);
    await d.commit();
    expect(d.committed, path);
    expect(d.changed, isFalse);
    expect(await store.exists(path), isTrue);
  });

  test(
      'reemplazar: al guardar se borra la anterior y las intermedias, no la nueva',
      () async {
    final String old = await savedImage();
    final ImageDraft d = draft(initial: old);

    await d.replaceWith(source);
    final String intermediate = d.current!;
    await d.replaceWith(source);
    final String latest = d.current!;

    // Antes de guardar no se borra nada: se puede cancelar sin perder la original.
    expect(await store.exists(old), isTrue);

    inUse
      ..remove(old)
      ..add(latest);
    await d.commit();
    expect(await store.exists(old), isFalse);
    expect(await store.exists(intermediate), isFalse);
    expect(await store.exists(latest), isTrue);
  });

  test('quitar: al guardar se borra el archivo si nadie más lo usa', () async {
    final String old = await savedImage();
    final ImageDraft d = draft(initial: old);
    d.clear();
    expect(d.hasImage, isFalse);
    expect(await store.exists(old), isTrue);

    inUse.remove(old);
    await d.commit();
    expect(await store.exists(old), isFalse);
    expect(d.committed, isNull);
  });

  test('nunca se borra un archivo que otro equipo o patrocinador sigue usando',
      () async {
    final String shared = await savedImage();
    final ImageDraft d = draft(initial: shared);
    await d.replaceWith(source);
    // 'shared' sigue en uso (no se quitó de inUse).
    await d.commit();
    expect(await store.exists(shared), isTrue);
  });

  test(
      'descartar borra lo cargado en esta sesión y conserva la imagen guardada',
      () async {
    final String old = await savedImage();
    final ImageDraft d = draft(initial: old);
    await d.replaceWith(source);
    final String temporary = d.current!;

    await d.discard();
    expect(d.current, old);
    expect(await store.exists(temporary), isFalse);
    expect(await store.exists(old), isTrue);
  });

  test('descartar después de guardar no borra nada', () async {
    final ImageDraft d = draft();
    await d.replaceWith(source);
    final String path = d.current!;
    inUse.add(path);
    await d.commit();
    await d.discard();
    expect(d.current, path);
    expect(await store.exists(path), isTrue);
  });

  test('adopt toma como punto de partida una imagen ya guardada', () async {
    final String existing = await savedImage();
    final ImageDraft d = draft();
    d.adopt(existing);
    expect(d.current, existing);
    expect(d.changed, isFalse);
    await d.discard();
    expect(await store.exists(existing), isTrue);
  });

  test('un archivo no válido lanza error y no cambia la imagen actual',
      () async {
    final File bad = File('${temp.path}/texto.png');
    await bad.writeAsString('esto no es una imagen');
    final ImageDraft d = draft();
    await expectLater(
      d.replaceWith(bad),
      throwsA(isA<ImageStorageException>()),
    );
    expect(d.hasImage, isFalse);
  });
}
