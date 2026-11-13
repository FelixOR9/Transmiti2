import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/app/app.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/features/match/presentation/match_screen.dart';
import 'package:transmiti2/features/match/presentation/match_setup_screen.dart';
import 'package:transmiti2/features/match/presentation/players_screen.dart';
import 'package:transmiti2/features/sponsors/presentation/sponsors_screen.dart';
import 'package:transmiti2/shared/widgets/app_bottom_nav.dart';
import 'package:transmiti2/shared/widgets/color_palette.dart';
import 'package:transmiti2/shared/widgets/stored_image.dart';

import 'support/fakes.dart';
import 'support/test_services.dart';

/// Pruebas de interfaz de la entrega 2B: cada formulario guarda de verdad en
/// los repositorios y en el almacén de imágenes (en memoria, sin disco).
class Harness {
  Harness()
    : gallery = FakeGalleryPicker(),
      store = FakeImageStore() {
    services = createTestServices(gallery: gallery, images: store);
  }

  final FakeGalleryPicker gallery;
  final FakeImageStore store;
  late final AppServices services;
}

/// Pantalla muy alta para que todo el formulario esté construido sin desplazar.
Future<void> openApp(WidgetTester tester, Harness h) async {
  tester.view.physicalSize = const Size(1080, 9000);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(Transmiti2App(services: h.services));
  await tester.pumpAndSettle();
}

Finder byKey(String key) => find.byKey(Key(key));

Future<void> tapKey(WidgetTester tester, String key) async {
  await tester.tap(byKey(key));
  await tester.pumpAndSettle();
}

Future<void> tapText(WidgetTester tester, String text) async {
  await tester.tap(find.text(text));
  await tester.pumpAndSettle();
}

Future<void> tapNav(WidgetTester tester, String label) async {
  await tester.tap(
    find.descendant(
      of: find.byType(AppBottomNav),
      matching: find.text(label),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> typeKey(WidgetTester tester, String key, String text) async {
  await tester.enterText(byKey(key), text);
  await tester.pump();
}

Future<void> openSetup(WidgetTester tester) async {
  await tapText(tester, 'PREPARAR PARTIDO');
  expect(find.byType(MatchSetupScreen), findsOneWidget);
}

/// Guarda un partido de ejemplo directamente en la sesión.
Future<void> saveSampleMatch(
  WidgetTester tester,
  Harness h, {
  List<int> sponsorIds = const <int>[],
}) async {
  await tester.runAsync(
    () => h.services.session.saveSetup(sampleSetup(sponsorIds: sponsorIds)),
  );
}

void main() {
  group('1. Crear un partido y guardar los equipos', () {
    testWidgets('guarda los nombres de ambos equipos y los muestra en inicio', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);

      await typeKey(tester, 'home-name', 'Rojo');
      await typeKey(tester, 'away-name', 'Azul');
      await typeKey(tester, 'tournament', 'Liga local');
      await tapKey(tester, 'save-match');

      expect(find.text('Partido guardado.'), findsOneWidget);
      final session = h.services.session;
      expect(session.detail!.home.name, 'Rojo');
      expect(session.detail!.away.name, 'Azul');
      expect(session.detail!.match.tournament, 'Liga local');
      expect(session.detail!.match.halfMinutes, 45);
      expect(session.detail!.match.breakMinutes, 15);

      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.text('Rojo'), findsOneWidget);
      expect(find.text('Azul'), findsOneWidget);
      expect(find.text('Sin partido configurado'), findsNothing);
    });

    testWidgets('sin nombres no guarda y marca los errores', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);
      await tapKey(tester, 'save-match');

      expect(find.text('Escribe el nombre del equipo local.'), findsOneWidget);
      expect(
        find.text('Escribe el nombre del equipo visitante.'),
        findsOneWidget,
      );
      expect(h.services.session.hasMatch, isFalse);
    });

    testWidgets('dos equipos con el mismo nombre no se aceptan', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);
      await typeKey(tester, 'home-name', 'Tigres');
      await typeKey(tester, 'away-name', ' tigres ');
      await tapKey(tester, 'save-match');

      expect(
        find.text('Los equipos deben tener nombres distintos.'),
        findsOneWidget,
      );
      expect(h.services.session.hasMatch, isFalse);
    });

    testWidgets('los dos equipos no pueden tener el mismo color', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);
      await typeKey(tester, 'home-name', 'Rojo');
      await typeKey(tester, 'away-name', 'Azul');
      await tapKey(tester, 'away-color-0'); // mismo color que el local
      await tapKey(tester, 'save-match');

      expect(
        find.text('Elige un color distinto al del equipo local.'),
        findsOneWidget,
      );
      expect(h.services.session.hasMatch, isFalse);
    });

    testWidgets('el color elegido de cada equipo se guarda', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);
      await typeKey(tester, 'home-name', 'Rojo');
      await typeKey(tester, 'away-name', 'Azul');
      await tapKey(tester, 'home-color-3');
      await tapKey(tester, 'away-color-6');
      await tapKey(tester, 'save-match');

      expect(h.services.session.detail!.home.colorValue, kTeamColors[3]);
      expect(h.services.session.detail!.away.colorValue, kTeamColors[6]);
    });

    testWidgets('los botones de duración cambian y se guardan', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);
      await typeKey(tester, 'home-name', 'Rojo');
      await typeKey(tester, 'away-name', 'Azul');

      await tapKey(tester, 'half-minus5');
      await tapKey(tester, 'half-minus1');
      await tapKey(tester, 'break-plus5');
      expect(find.text('39 min'), findsOneWidget);
      expect(find.text('20 min'), findsOneWidget);

      await tapKey(tester, 'save-match');
      expect(h.services.session.detail!.match.halfMinutes, 39);
      expect(h.services.session.detail!.match.breakMinutes, 20);
    });

    testWidgets('al volver a abrir el formulario se cargan los datos guardados', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await tester.runAsync(
        () => h.services.session.saveSetup(
          sampleSetup(tournament: 'Liga local', halfMinutes: 30),
        ),
      );
      await openApp(tester, h);
      await openSetup(tester);

      expect(tester.widget<TextField>(byKey('home-name')).controller!.text, 'Rojo');
      expect(tester.widget<TextField>(byKey('away-name')).controller!.text, 'Azul');
      expect(
        tester.widget<TextField>(byKey('tournament')).controller!.text,
        'Liga local',
      );
      expect(find.text('30 min'), findsOneWidget);
      expect(byKey('new-match'), findsOneWidget);
    });

    testWidgets('con el partido iniciado se bloquean equipos y duraciones', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await saveSampleMatch(tester, h);
      await tester.runAsync(() => h.services.session.startMatch());
      await openApp(tester, h);
      await openSetup(tester);

      expect(tester.widget<TextField>(byKey('home-name')).enabled, isFalse);
      expect(
        tester.widget<OutlinedButton>(byKey('half-plus1')).onPressed,
        isNull,
      );
    });

    testWidgets('nuevo partido cancela el actual y empieza uno vacío', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await saveSampleMatch(tester, h);
      final int firstId = h.services.session.detail!.match.id;
      await openApp(tester, h);
      await openSetup(tester);

      await tapKey(tester, 'new-match');
      await tapKey(tester, 'confirm-ok');
      expect(tester.widget<TextField>(byKey('home-name')).controller!.text, isEmpty);

      await typeKey(tester, 'home-name', 'Verde');
      await typeKey(tester, 'away-name', 'Negro');
      await tapKey(tester, 'save-match');

      expect(h.services.session.detail!.match.id, isNot(firstId));
      expect(h.services.session.detail!.home.name, 'Verde');
      expect(h.services.session.detail!.match.status, MatchStatus.scheduled);
    });
  });

  group('2. Escudos desde la galería', () {
    testWidgets('cargar los escudos de ambos equipos los guarda en el partido', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);
      await typeKey(tester, 'home-name', 'Rojo');
      await typeKey(tester, 'away-name', 'Azul');

      await tapKey(tester, 'home-crest-pick');
      await tapKey(tester, 'away-crest-pick');
      expect(h.gallery.pickCount, 2);
      expect(h.store.saved.length, 2);
      expect(byKey('home-crest-remove'), findsOneWidget);
      expect(find.text('REEMPLAZAR'), findsNWidgets(2));

      await tapKey(tester, 'save-match');
      final detail = h.services.session.detail!;
      expect(detail.home.crestPath, h.store.saved[0]);
      expect(detail.away.crestPath, h.store.saved[1]);
      expect(h.store.deleted, isEmpty);
    });

    testWidgets('reemplazar un escudo borra el anterior al guardar', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);
      await typeKey(tester, 'home-name', 'Rojo');
      await typeKey(tester, 'away-name', 'Azul');
      await tapKey(tester, 'home-crest-pick');
      await tapKey(tester, 'save-match');
      final String first = h.services.session.detail!.home.crestPath!;

      await tapKey(tester, 'home-crest-pick'); // REEMPLAZAR
      expect(h.store.deleted.contains(first), isFalse); // aún no se borra
      await tapKey(tester, 'save-match');

      final String second = h.services.session.detail!.home.crestPath!;
      expect(second, isNot(first));
      expect(h.store.deleted.contains(first), isTrue);
      expect(h.store.deleted.contains(second), isFalse);
    });

    testWidgets('quitar un escudo lo elimina al guardar', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);
      await typeKey(tester, 'home-name', 'Rojo');
      await typeKey(tester, 'away-name', 'Azul');
      await tapKey(tester, 'home-crest-pick');
      await tapKey(tester, 'save-match');
      final String path = h.services.session.detail!.home.crestPath!;

      await tapKey(tester, 'home-crest-remove');
      expect(byKey('home-crest-remove'), findsNothing);
      await tapKey(tester, 'save-match');

      expect(h.services.session.detail!.home.crestPath, isNull);
      expect(h.store.deleted.contains(path), isTrue);
    });

    testWidgets('salir sin guardar borra el escudo recién cargado', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);
      await tapKey(tester, 'home-crest-pick');
      final String path = h.store.saved.single;

      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(h.store.deleted.contains(path), isTrue);
      expect(h.services.session.hasMatch, isFalse);
    });

    testWidgets('un archivo no válido muestra el mensaje y no cambia nada', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);
      h.store.failNextSave = true;
      await tapKey(tester, 'home-crest-pick');

      expect(
        find.text('Formato no admitido. Usa una imagen PNG, JPG o JPEG.'),
        findsOneWidget,
      );
      expect(byKey('home-crest-remove'), findsNothing);
    });

    testWidgets('cancelar la galería no cambia nada', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      h.gallery.cancel = true;
      await openApp(tester, h);
      await openSetup(tester);
      await tapKey(tester, 'home-crest-pick');

      expect(h.gallery.pickCount, 1);
      expect(h.store.saved, isEmpty);
      expect(byKey('home-crest-remove'), findsNothing);
    });

    testWidgets('un equipo ya registrado conserva su escudo y su color', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await tester.runAsync(
        () => h.services.teams.saveByName(
          name: 'Rojo',
          colorValue: kTeamColors[1],
          crestPath: 'crests/previo.png',
        ),
      );
      await openApp(tester, h);
      await openSetup(tester);
      await typeKey(tester, 'home-name', 'rojo');
      await typeKey(tester, 'away-name', 'Azul');
      expect(byKey('home-crest-remove'), findsOneWidget);

      await tapKey(tester, 'save-match');
      final home = h.services.session.detail!.home;
      expect(home.crestPath, 'crests/previo.png');
      expect(home.colorValue, kTeamColors[1]);
      expect(h.store.deleted, isEmpty);
    });

    testWidgets('la pestaña Partido muestra escudos, nombres y resultado', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);
      await typeKey(tester, 'home-name', 'Rojo');
      await typeKey(tester, 'away-name', 'Azul');
      await tapKey(tester, 'home-crest-pick');
      await tapKey(tester, 'save-match');
      await tester.pageBack();
      await tester.pumpAndSettle();

      await tapNav(tester, 'Partido');
      expect(find.byType(MatchScreen), findsOneWidget);
      expect(find.text('Rojo'), findsOneWidget);
      expect(find.text('Azul'), findsOneWidget);
      expect(find.text('0 - 0'), findsOneWidget);
      expect(byKey('match-crest-1'), findsOneWidget);
      expect(byKey('match-crest-2'), findsOneWidget);
      expect(
        tester.widget<StoredImage>(byKey('match-crest-1')).path,
        h.store.saved.single,
      );
    });
  });

  group('3. Jugadores y números de camiseta', () {
    Future<void> openHomePlayers(WidgetTester tester, Harness h) async {
      await saveSampleMatch(tester, h);
      await openApp(tester, h);
      await tapNav(tester, 'Partido');
      await tapKey(tester, 'tab-players-home');
      expect(find.byType(PlayersScreen), findsOneWidget);
    }

    Future<void> addPlayer(
      WidgetTester tester,
      String name,
      String number,
    ) async {
      await tapKey(tester, 'add-player');
      await typeKey(tester, 'player-name', name);
      await typeKey(tester, 'player-number', number);
      await tapKey(tester, 'player-save');
    }

    testWidgets('registra jugadores con su número y los guarda', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openHomePlayers(tester, h);
      expect(find.textContaining('Todavía no hay jugadores'), findsOneWidget);

      await addPlayer(tester, 'Luis', '9');
      await addPlayer(tester, 'Ana', '10');

      expect(find.text('Luis'), findsOneWidget);
      expect(find.text('Ana'), findsOneWidget);
      final home = h.services.session.detail!.home;
      final List<Player> saved = await tester.runAsync(
        () => h.services.players.byTeam(home.id),
      ) as List<Player>;
      expect(saved.map((Player p) => p.name), <String>['Luis', 'Ana']);
      expect(saved.map((Player p) => p.number), <int>[9, 10]);
      // La sesión también conoce la plantilla.
      expect(h.services.session.playersOf(home.id).length, 2);
    });

    testWidgets('no permite repetir un número dentro del mismo equipo', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openHomePlayers(tester, h);
      await addPlayer(tester, 'Luis', '9');

      await addPlayer(tester, 'Pedro', '9');
      expect(
        find.text('Ese número ya lo usa otro jugador de este equipo.'),
        findsOneWidget,
      );
      // El diálogo sigue abierto y Pedro todavía no está en la lista.
      expect(byKey('player-save'), findsOneWidget);
      expect(byKey('player-2'), findsNothing);

      await typeKey(tester, 'player-number', '11');
      await tapKey(tester, 'player-save');
      expect(find.text('Pedro'), findsOneWidget);
    });

    testWidgets('valida el nombre y el número', (WidgetTester tester) async {
      final Harness h = Harness();
      await openHomePlayers(tester, h);
      await tapKey(tester, 'add-player');
      await tapKey(tester, 'player-save');

      expect(find.text('Escribe el nombre del jugador.'), findsOneWidget);
      expect(find.text('Escribe el número de camiseta.'), findsOneWidget);

      await typeKey(tester, 'player-name', 'Luis');
      await typeKey(tester, 'player-number', '0');
      await tapKey(tester, 'player-save');
      expect(
        find.text('El número debe estar entre 1 y 99.'),
        findsOneWidget,
      );
    });

    testWidgets('edita y elimina jugadores', (WidgetTester tester) async {
      final Harness h = Harness();
      await openHomePlayers(tester, h);
      await addPlayer(tester, 'Luis', '9');

      await tapKey(tester, 'player-1');
      await typeKey(tester, 'player-name', 'Luis Pérez');
      await typeKey(tester, 'player-number', '7');
      await tapKey(tester, 'player-save');
      expect(find.text('Luis Pérez'), findsOneWidget);
      expect(find.text('7'), findsOneWidget);
      expect(find.text('Luis'), findsNothing);

      await tapKey(tester, 'delete-player-1');
      expect(find.textContaining('¿Eliminar a Luis Pérez'), findsOneWidget);
      await tapKey(tester, 'confirm-ok');
      expect(find.text('Luis Pérez'), findsNothing);
      expect(
        h.services.session.playersOf(h.services.session.detail!.home.id),
        isEmpty,
      );
    });

    testWidgets('cancelar la eliminación conserva al jugador', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openHomePlayers(tester, h);
      await addPlayer(tester, 'Luis', '9');
      await tapKey(tester, 'delete-player-1');
      await tapKey(tester, 'confirm-cancel');
      expect(find.text('Luis'), findsOneWidget);
    });

    testWidgets('desde el formulario del partido también se abren los jugadores', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await saveSampleMatch(tester, h);
      await openApp(tester, h);
      await openSetup(tester);
      await tapKey(tester, 'players-away');
      expect(find.byType(PlayersScreen), findsOneWidget);
      expect(find.text('Jugadores · Azul'), findsOneWidget);
    });

    testWidgets('sin partido guardado el formulario explica que hay que guardar', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);
      expect(byKey('players-home'), findsNothing);
      expect(find.textContaining('Guarda el partido'), findsOneWidget);
    });
  });

  group('4. Patrocinadores y logotipos', () {
    Future<void> openSponsors(WidgetTester tester, Harness h) async {
      await openApp(tester, h);
      await tapNav(tester, 'Más');
      await tapKey(tester, 'more-sponsors');
      expect(find.byType(SponsorsScreen), findsOneWidget);
    }

    testWidgets('registra un patrocinador con su logotipo', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openSponsors(tester, h);
      expect(find.textContaining('Todavía no hay patrocinadores'), findsOneWidget);

      await tapKey(tester, 'add-sponsor');
      await typeKey(tester, 'sponsor-name', 'Casa Pepe');
      await tapKey(tester, 'sponsor-logo-pick');
      await tapKey(tester, 'sponsor-save');

      expect(find.text('Casa Pepe'), findsOneWidget);
      final List<Sponsor> all = await tester.runAsync(
        () => h.services.sponsors.all(),
      ) as List<Sponsor>;
      expect(all.single.name, 'Casa Pepe');
      expect(all.single.logoPath, h.store.saved.single);
    });

    testWidgets('el nombre es obligatorio', (WidgetTester tester) async {
      final Harness h = Harness();
      await openSponsors(tester, h);
      await tapKey(tester, 'add-sponsor');
      await tapKey(tester, 'sponsor-save');
      expect(find.text('Escribe el nombre del patrocinador.'), findsOneWidget);
    });

    testWidgets('edita el nombre y reemplaza el logotipo', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openSponsors(tester, h);
      await tapKey(tester, 'add-sponsor');
      await typeKey(tester, 'sponsor-name', 'Casa Pepe');
      await tapKey(tester, 'sponsor-logo-pick');
      await tapKey(tester, 'sponsor-save');
      final String first = h.store.saved.single;

      await tapKey(tester, 'sponsor-1');
      await typeKey(tester, 'sponsor-name', 'Casa Pepe Plus');
      await tapKey(tester, 'sponsor-logo-pick'); // REEMPLAZAR
      expect(h.store.deleted.contains(first), isFalse);
      await tapKey(tester, 'sponsor-save');

      expect(find.text('Casa Pepe Plus'), findsOneWidget);
      final List<Sponsor> all = await tester.runAsync(
        () => h.services.sponsors.all(),
      ) as List<Sponsor>;
      expect(all.single.logoPath, isNot(first));
      expect(h.store.deleted.contains(first), isTrue);
    });

    testWidgets('quitar el logotipo lo elimina', (WidgetTester tester) async {
      final Harness h = Harness();
      await openSponsors(tester, h);
      await tapKey(tester, 'add-sponsor');
      await typeKey(tester, 'sponsor-name', 'Casa Pepe');
      await tapKey(tester, 'sponsor-logo-pick');
      await tapKey(tester, 'sponsor-save');
      final String logo = h.store.saved.single;

      await tapKey(tester, 'sponsor-1');
      await tapKey(tester, 'sponsor-logo-remove');
      await tapKey(tester, 'sponsor-save');

      final List<Sponsor> all = await tester.runAsync(
        () => h.services.sponsors.all(),
      ) as List<Sponsor>;
      expect(all.single.logoPath, isNull);
      expect(h.store.deleted.contains(logo), isTrue);
    });

    testWidgets('cancelar el diálogo borra el logotipo recién cargado', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openSponsors(tester, h);
      await tapKey(tester, 'add-sponsor');
      await tapKey(tester, 'sponsor-logo-pick');
      final String logo = h.store.saved.single;
      await tapKey(tester, 'sponsor-cancel');

      expect(h.store.deleted.contains(logo), isTrue);
      expect(find.textContaining('Todavía no hay patrocinadores'), findsOneWidget);
    });

    testWidgets('elimina un patrocinador y su logotipo con confirmación', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openSponsors(tester, h);
      await tapKey(tester, 'add-sponsor');
      await typeKey(tester, 'sponsor-name', 'Casa Pepe');
      await tapKey(tester, 'sponsor-logo-pick');
      await tapKey(tester, 'sponsor-save');
      final String logo = h.store.saved.single;

      await tapKey(tester, 'delete-sponsor-1');
      await tapKey(tester, 'confirm-cancel');
      expect(find.text('Casa Pepe'), findsOneWidget);

      await tapKey(tester, 'delete-sponsor-1');
      await tapKey(tester, 'confirm-ok');
      expect(find.text('Casa Pepe'), findsNothing);
      expect(h.store.deleted.contains(logo), isTrue);
    });

    testWidgets('permite registrar muchos patrocinadores sin límite', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await tester.runAsync(() async {
        for (int i = 0; i < 25; i++) {
          await h.services.sponsors.add(name: 'Marca $i');
        }
      });
      final List<Sponsor> all = await tester.runAsync(
        () => h.services.sponsors.all(),
      ) as List<Sponsor>;
      expect(all.length, 25);
    });
  });

  group('5. Asociar patrocinadores al partido', () {
    testWidgets('elige varios patrocinadores y se guardan en orden', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await tester.runAsync(() async {
        await h.services.sponsors.add(name: 'Alfa', logoPath: 'logos/a.png');
        await h.services.sponsors.add(name: 'Beta');
        await h.services.sponsors.add(name: 'Gama');
      });
      await openApp(tester, h);
      await openSetup(tester);
      await typeKey(tester, 'home-name', 'Rojo');
      await typeKey(tester, 'away-name', 'Azul');

      await tapKey(tester, 'sponsor-option-3'); // Gama primero
      await tapKey(tester, 'sponsor-option-1'); // luego Alfa
      await tapKey(tester, 'sponsor-option-2'); // luego Beta
      await tapKey(tester, 'sponsor-option-2'); // y se quita Beta
      await tapKey(tester, 'save-match');

      final List<String> names = h.services.session.detail!.sponsors
          .map((Sponsor s) => s.name)
          .toList();
      expect(names, <String>['Gama', 'Alfa']);
    });

    testWidgets('la pestaña Partido muestra los patrocinadores elegidos', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await tester.runAsync(() async {
        await h.services.sponsors.add(name: 'Alfa', logoPath: 'logos/a.png');
      });
      await saveSampleMatch(tester, h, sponsorIds: <int>[1]);
      await openApp(tester, h);
      await tapNav(tester, 'Partido');
      expect(byKey('match-sponsor-1'), findsOneWidget);
    });

    testWidgets('administrar patrocinadores desde el formulario actualiza la lista', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await openApp(tester, h);
      await openSetup(tester);
      expect(
        find.text('Todavía no hay patrocinadores registrados.'),
        findsOneWidget,
      );

      await tapKey(tester, 'manage-sponsors');
      await tapKey(tester, 'add-sponsor');
      await typeKey(tester, 'sponsor-name', 'Casa Pepe');
      await tapKey(tester, 'sponsor-save');
      await tester.pageBack();
      await tester.pumpAndSettle();

      expect(byKey('sponsor-option-1'), findsOneWidget);
      expect(find.text('Casa Pepe'), findsOneWidget);
    });

    testWidgets('al eliminar un patrocinador desaparece del partido', (
      WidgetTester tester,
    ) async {
      final Harness h = Harness();
      await tester.runAsync(() async {
        await h.services.sponsors.add(name: 'Alfa');
      });
      await saveSampleMatch(tester, h, sponsorIds: <int>[1]);
      expect(h.services.session.detail!.sponsors.length, 1);

      await openApp(tester, h);
      await tapNav(tester, 'Más');
      await tapKey(tester, 'more-sponsors');
      await tapKey(tester, 'delete-sponsor-1');
      await tapKey(tester, 'confirm-ok');

      expect(h.services.session.detail!.sponsors, isEmpty);
    });
  });

  test('los equipos del formulario usan colores de la paleta distintos', () {
    expect(kTeamColors.toSet().length, kTeamColors.length);
    expect(Team.keyOf(' Rojo '), 'rojo');
  });
}
