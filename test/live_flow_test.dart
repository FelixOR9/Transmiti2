import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/app/app.dart';
import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/features/graphics/presentation/graphics_preview_screen.dart';
import 'package:transmiti2/features/history/presentation/events_history_screen.dart';
import 'package:transmiti2/features/home/presentation/widgets/access_button.dart';
import 'package:transmiti2/features/live/presentation/live_match_screen.dart';
import 'package:transmiti2/features/live/presentation/widgets/goal_dialog.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/player.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';
import 'package:transmiti2/features/stats/presentation/stats_screen.dart';
import 'package:transmiti2/shared/widgets/app_bottom_nav.dart';
import 'package:transmiti2/shared/widgets/stored_image.dart';

import 'support/test_services.dart';

/// Pruebas de interfaz de la entrega 2C: control del partido en vivo.
/// Todo pasa por MatchSession y los repositorios (en memoria, sin disco).
const int minute = 60000;
const int second = 1000;
const int t0 = 1800000000000;

class Env {
  Env({Duration? clockTick, Duration tapCooldown = Duration.zero}) {
    services = createTestServices(
      nowMs: () => now,
      clockTick: clockTick,
      tapCooldown: tapCooldown,
    );
  }

  int now = t0;
  late final AppServices services;
  MatchSession get session => services.session;
}

Future<void> openApp(WidgetTester tester, AppServices services) async {
  tester.view.physicalSize = const Size(1080, 9000);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(Transmiti2App(services: services));
  await tester.pumpAndSettle();
}

Finder byKey(String key) => find.byKey(Key(key));

Future<void> tapKey(WidgetTester tester, String key) async {
  await tester.tap(byKey(key));
  await tester.pumpAndSettle();
}

Future<void> typeKey(WidgetTester tester, String key, String text) async {
  await tester.enterText(byKey(key), text);
  await tester.pump();
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

String textOf(WidgetTester tester, String key) =>
    tester.widget<Text>(byKey(key)).data!;

/// El botón de relleno que hay dentro del widget con esa llave.
FilledButton filledIn(WidgetTester tester, String key) => tester.widget<FilledButton>(
  find.descendant(of: byKey(key), matching: find.byType(FilledButton)),
);

/// Simula que Android devuelve la app al primer plano (por ejemplo tras
/// bloquear el teléfono): el reloj debe mostrar el tiempo real transcurrido.
Future<void> resumeApp(WidgetTester tester) async {
  tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
  tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
  await tester.pump();
}

/// Deja un partido preparado, con jugadores y patrocinadores opcionales.
Future<void> prepareMatch(
  WidgetTester tester,
  Env env, {
  String? tournament,
  bool crests = false,
  bool roster = false,
  List<int> sponsorIds = const <int>[],
}) async {
  await tester.runAsync(() async {
    await env.session.saveSetup(
      MatchSetup(
        homeName: 'Rojo',
        homeColor: 0xFFE53935,
        homeCrestPath: crests ? 'crests/rojo.png' : null,
        awayName: 'Azul',
        awayColor: 0xFF1E88E5,
        awayCrestPath: crests ? 'crests/azul.png' : null,
        tournament: tournament,
        scheduledAtMs: DateTime(2026, 11, 15).millisecondsSinceEpoch,
        halfMinutes: 45,
        breakMinutes: 15,
        sponsorIds: sponsorIds,
      ),
    );
    if (roster) {
      final Team home = env.session.detail!.home;
      final Team away = env.session.detail!.away;
      await env.services.players.add(teamId: home.id, name: 'Luis', number: 9);
      await env.services.players.add(teamId: away.id, name: 'Pedro', number: 7);
      await env.session.load();
    }
  });
}

Future<void> openLive(WidgetTester tester) async {
  await tapNav(tester, 'Partido');
  await tapKey(tester, 'open-live');
  expect(find.byType(LiveMatchScreen), findsOneWidget);
}

/// Registra un gol con el diálogo de la pantalla.
Future<void> scoreGoal(
  WidgetTester tester,
  String button,
  String minuteText, {
  String? playerKey,
}) async {
  await tapKey(tester, button);
  await typeKey(tester, 'goal-minute', minuteText);
  if (playerKey != null) {
    await tapKey(tester, playerKey);
  }
  await tapKey(tester, 'goal-confirm');
}

void main() {
  group('1 y 2. Abrir un partido preparado, escudos y nombres', () {
    testWidgets('muestra escudos, nombres, marcador, cronómetro y estado', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env, crests: true);
      await openApp(tester, env.services);
      await openLive(tester);

      expect(textOf(tester, 'live-name-1'), 'Rojo');
      expect(textOf(tester, 'live-name-2'), 'Azul');
      expect(tester.widget<StoredImage>(byKey('live-crest-1')).path, 'crests/rojo.png');
      expect(tester.widget<StoredImage>(byKey('live-crest-2')).path, 'crests/azul.png');
      expect(textOf(tester, 'live-score'), '0 - 0');
      expect(textOf(tester, 'live-clock'), '00:00');
      expect(textOf(tester, 'live-status'), 'POR COMENZAR');
      expect(byKey('open-history'), findsOneWidget);
    });

    testWidgets('sin partido preparado ofrece prepararlo', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await openApp(tester, env.services);
      await tester.tap(
        find.descendant(
          of: find.byType(AccessButton),
          matching: find.text('MARCADOR'),
        ),
      );
      await tester.pumpAndSettle();
      expect(byKey('live-prepare'), findsOneWidget);
      expect(byKey('main-action'), findsNothing);
    });

    testWidgets('MARCADOR y CRONÓMETRO del inicio abren el control del partido', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      for (final String label in <String>['MARCADOR', 'CRONÓMETRO']) {
        await tester.tap(
          find.descendant(
            of: find.byType(AccessButton),
            matching: find.text(label),
          ),
        );
        await tester.pumpAndSettle();
        expect(byKey('live-score'), findsOneWidget);
        expect(byKey('main-action'), findsOneWidget);
        await tester.pageBack();
        await tester.pumpAndSettle();
      }
    });
  });

  group('3 al 6. Cronómetro', () {
    testWidgets('iniciar el partido pone a correr el primer tiempo', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);

      await tapKey(tester, 'main-action');
      expect(textOf(tester, 'live-status'), 'PRIMER TIEMPO');
      expect(
        find.descendant(of: byKey('main-action'), matching: find.text('PAUSAR')),
        findsOneWidget,
      );
      expect(env.session.clock.running, isTrue);
      expect(env.session.detail!.match.status, MatchStatus.live);
      expect(env.session.events.single.type, MatchEventType.periodStart);
    });

    testWidgets('pausar congela el tiempo y reanudar continúa', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');

      env.now = t0 + 10 * minute;
      await resumeApp(tester);
      expect(textOf(tester, 'live-clock'), '10:00');

      await tapKey(tester, 'main-action'); // PAUSAR
      expect(textOf(tester, 'live-status'), 'PRIMER TIEMPO · EN PAUSA');
      expect(
        find.descendant(of: byKey('main-action'), matching: find.text('REANUDAR')),
        findsOneWidget,
      );

      env.now = t0 + 40 * minute; // 30 minutos en pausa
      await resumeApp(tester);
      expect(textOf(tester, 'live-clock'), '10:00');

      await tapKey(tester, 'main-action'); // REANUDAR
      env.now = t0 + 45 * minute;
      await resumeApp(tester);
      expect(textOf(tester, 'live-clock'), '15:00');
      expect(textOf(tester, 'live-status'), 'PRIMER TIEMPO');
    });

    testWidgets('con el repintado periódico el reloj avanza con la hora real', (
      WidgetTester tester,
    ) async {
      final Env env = Env(clockTick: const Duration(milliseconds: 100));
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);

      await tester.tap(byKey('main-action'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(textOf(tester, 'live-clock'), '00:00');

      env.now = t0 + 12 * minute + 34 * second;
      await tester.pump(const Duration(milliseconds: 150));
      expect(textOf(tester, 'live-clock'), '12:34');
    });

    testWidgets('al bloquear el teléfono y volver, el tiempo se recupera', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');

      // El teléfono se bloquea 20 minutos: no hay ningún repintado.
      env.now = t0 + 20 * minute;
      expect(textOf(tester, 'live-clock'), '00:00'); // pantalla sin actualizar
      await resumeApp(tester);
      expect(textOf(tester, 'live-clock'), '20:00');
    });

    testWidgets('el cronómetro no se pierde al cambiar de pantalla', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');

      await tapKey(tester, 'open-history');
      env.now = t0 + 8 * minute;
      await tester.pageBack();
      await tester.pumpAndSettle();
      await resumeApp(tester);
      expect(textOf(tester, 'live-clock'), '08:00');
    });

    testWidgets('el tiempo añadido se muestra pasada la duración del tiempo', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');

      env.now = t0 + 47 * minute + 30 * second;
      await resumeApp(tester);
      expect(textOf(tester, 'live-clock'), '45:00');
      expect(textOf(tester, 'live-added'), '+02:30');
    });
  });

  group('7 y 8. Goles y correcciones', () {
    testWidgets('registra goles de ambos equipos con el escudo y el resultado', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env, crests: true);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');

      await scoreGoal(tester, 'goal-home', '10');
      expect(textOf(tester, 'live-score'), '1 - 0');
      await scoreGoal(tester, 'goal-away', '25');
      expect(textOf(tester, 'live-score'), '1 - 1');

      final List<MatchEvent> goals = env.session.events
          .where((MatchEvent e) => e.isActiveGoal)
          .toList();
      expect(goals.map((MatchEvent g) => g.minute), <int>[10, 25]);
      expect(goals.map((MatchEvent g) => g.teamId), <int?>[
        env.session.detail!.home.id,
        env.session.detail!.away.id,
      ]);
      expect(find.text('Gol de Rojo'), findsOneWidget);
      expect(find.text('Gol de Azul'), findsOneWidget);
    });

    testWidgets('los botones de gol están desactivados antes de empezar', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);

      expect(filledIn(tester, 'goal-home').onPressed, isNull);
      expect(filledIn(tester, 'goal-away').onPressed, isNull);
      expect(byKey('goals-disabled-hint'), findsOneWidget);

      await tapKey(tester, 'main-action');
      expect(filledIn(tester, 'goal-home').onPressed, isNotNull);
      expect(byKey('goals-disabled-hint'), findsNothing);
    });

    testWidgets('el diálogo propone el minuto que marca el cronómetro', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');

      env.now = t0 + 12 * minute + 30 * second;
      await tapKey(tester, 'goal-home');
      expect(tester.widget<TextField>(byKey('goal-minute')).controller!.text, '13');
      expect(find.text('Gol de Rojo'), findsOneWidget); // título del diálogo
    });

    testWidgets('un gol puede asociarse a un jugador registrado', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env, roster: true);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');

      final Player luis = env.session.playersOf(env.session.detail!.home.id).single;
      await scoreGoal(tester, 'goal-home', '33', playerKey: 'goal-player-${luis.id}');
      expect(find.text('#9 Luis'), findsOneWidget);
      expect(env.session.events.last.playerId, luis.id);
    });

    testWidgets('sin jugadores registrados el gol se guarda igual', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');
      await tapKey(tester, 'goal-home');
      expect(find.text('Este equipo no tiene jugadores registrados.'), findsOneWidget);
      await tapKey(tester, 'goal-confirm');
      expect(find.text('Sin jugador asignado'), findsOneWidget);
    });

    testWidgets('un minuto no válido muestra el error y no guarda el gol', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');

      await scoreGoal(tester, 'goal-home', 'abc');
      expect(
        find.text('Escribe un minuto válido, por ejemplo 23 o 45+2.'),
        findsOneWidget,
      );
      expect(env.session.score, const ScoreSummary(0, 0));
      expect(byKey('goal-confirm'), findsOneWidget); // el diálogo sigue abierto
    });

    testWidgets('un doble toque en REGISTRAR GOL guarda un solo gol', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');

      await tapKey(tester, 'goal-home');
      await typeKey(tester, 'goal-minute', '10');
      await tester.tap(byKey('goal-confirm'));
      await tester.tap(byKey('goal-confirm'));
      await tester.pumpAndSettle();

      expect(env.session.score, const ScoreSummary(1, 0));
      expect(
        env.session.events.where((MatchEvent e) => e.type == MatchEventType.goal).length,
        1,
      );
    });

    testWidgets('un doble toque en GOL abre un solo diálogo', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');

      await tester.tap(byKey('goal-home'));
      await tester.tap(byKey('goal-home'));
      await tester.pumpAndSettle();
      expect(find.byType(GoalDialog), findsOneWidget);
    });

    testWidgets('tras registrar un gol, un toque accidental inmediato se ignora', (
      WidgetTester tester,
    ) async {
      final Env env = Env(tapCooldown: const Duration(seconds: 30));
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      // Primera acción de la pantalla: iniciar (ocupa el período de espera).
      await tester.runAsync(() => env.session.startMatch());
      await tester.pumpAndSettle();

      await tapKey(tester, 'goal-home');
      expect(find.byType(GoalDialog), findsOneWidget);
      await tapKey(tester, 'goal-confirm');
      expect(env.session.score, const ScoreSummary(1, 0));

      await tapKey(tester, 'goal-home'); // dentro del tiempo de espera
      expect(find.byType(GoalDialog), findsNothing);
      expect(env.session.score, const ScoreSummary(1, 0));
    });

    testWidgets('corregir un gol cambia minuto y equipo y deja constancia', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');
      await scoreGoal(tester, 'goal-home', '10');
      final int goalId = env.session.events.last.id;
      expect(textOf(tester, 'live-score'), '1 - 0');

      await tapKey(tester, 'edit-goal-$goalId');
      expect(find.text('Corregir gol'), findsOneWidget);
      await typeKey(tester, 'goal-minute', '12');
      await tapKey(tester, 'goal-team-away');
      await tapKey(tester, 'goal-confirm');

      expect(textOf(tester, 'live-score'), '0 - 1');
      expect(find.text('Gol de Azul'), findsOneWidget);
      expect(find.text("12'"), findsOneWidget);
      expect(env.session.events.last.type, MatchEventType.goalCorrection);
      expect(env.session.events.last.note, 'edited');
    });

    testWidgets('anular un gol pide confirmación y conserva el historial', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');
      await scoreGoal(tester, 'goal-home', '10');
      final int goalId = env.session.events.last.id;

      await tapKey(tester, 'void-goal-$goalId');
      expect(find.textContaining('¿Anular el gol de Rojo'), findsOneWidget);
      await tapKey(tester, 'confirm-cancel');
      expect(textOf(tester, 'live-score'), '1 - 0');

      await tapKey(tester, 'void-goal-$goalId');
      await tapKey(tester, 'confirm-ok');
      expect(textOf(tester, 'live-score'), '0 - 0');
      expect(byKey('no-goals'), findsOneWidget);
      expect(env.session.events.last.note, 'voided');
      expect(
        env.session.events.firstWhere((MatchEvent e) => e.id == goalId).voided,
        isTrue,
      );
    });
  });

  group('10 al 12. Tiempos y final del partido', () {
    testWidgets('primer tiempo, descanso y segundo tiempo', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');
      env.now = t0 + 46 * minute;

      await tapKey(tester, 'end-first-half');
      expect(find.text('Finalizar primer tiempo'), findsOneWidget);
      await tapKey(tester, 'confirm-cancel');
      expect(textOf(tester, 'live-status'), 'PRIMER TIEMPO');

      await tapKey(tester, 'end-first-half');
      await tapKey(tester, 'confirm-ok');
      expect(textOf(tester, 'live-status'), 'DESCANSO');
      expect(textOf(tester, 'break-clock'), 'Descanso: 00:00 de 15:00');
      expect(
        find.descendant(
          of: byKey('main-action'),
          matching: find.text('INICIAR SEGUNDO TIEMPO'),
        ),
        findsOneWidget,
      );

      env.now = t0 + 49 * minute + 12 * second;
      await resumeApp(tester);
      expect(textOf(tester, 'break-clock'), 'Descanso: 03:12 de 15:00');

      await tapKey(tester, 'main-action');
      expect(textOf(tester, 'live-status'), 'SEGUNDO TIEMPO');
      expect(textOf(tester, 'live-clock'), '45:00');
      env.now = t0 + 59 * minute + 12 * second;
      await resumeApp(tester);
      expect(textOf(tester, 'live-clock'), '55:00');
    });

    testWidgets('finalizar el partido pide confirmación y lo cierra', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'main-action');
      await scoreGoal(tester, 'goal-home', '10');

      await tapKey(tester, 'end-match');
      expect(find.textContaining('¿Finalizar el partido ahora?'), findsOneWidget);
      await tapKey(tester, 'confirm-cancel');
      expect(env.session.clock.phase, MatchPhase.firstHalf);

      env.now = t0 + 50 * minute;
      await tapKey(tester, 'end-match');
      await tapKey(tester, 'confirm-ok');
      expect(textOf(tester, 'live-status'), 'FINAL');
      expect(env.session.detail!.match.status, MatchStatus.finished);
      expect(byKey('main-action'), findsNothing); // ya no hay controles de juego
      expect(byKey('goal-home'), findsNothing);
      expect(textOf(tester, 'match-finished'), 'El partido terminó 1 - 0.');
      expect(byKey('prepare-another'), findsOneWidget);
      // Las correcciones de goles siguen disponibles.
      expect(find.byTooltip('Corregir gol'), findsOneWidget);
    });
  });

  group('9. Historial de eventos', () {
    testWidgets('sin eventos explica que aparecerán al empezar', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'open-history');
      expect(find.byType(EventsHistoryScreen), findsOneWidget);
      expect(byKey('history-empty'), findsOneWidget);
    });

    testWidgets('muestra todos los eventos en orden cronológico', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env, roster: true);
      final Team home = env.session.detail!.home;
      final Player luis = env.session.playersOf(home.id).single;
      await tester.runAsync(() async {
        await env.session.startMatch();
        env.now = t0 + 10 * minute;
        await env.session.addGoal(teamId: home.id, playerId: luis.id);
        env.now = t0 + 20 * minute;
        await env.session.addGoal(teamId: env.session.detail!.away.id);
        await env.session.voidGoal(env.session.events.last.id);
        env.now = t0 + 46 * minute;
        await env.session.endFirstHalf();
        env.now = t0 + 61 * minute;
        await env.session.startSecondHalf();
        env.now = t0 + 107 * minute;
        await env.session.endMatch();
      });
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'open-history');

      for (final String title in <String>[
        'Inicio del primer tiempo',
        'Gol de Rojo',
        'Gol anulado de Azul',
        'Corrección: gol anulado',
        'Fin del primer tiempo · Descanso',
        'Inicio del segundo tiempo',
        'Final del partido',
      ]) {
        expect(find.text(title), findsOneWidget, reason: title);
      }
      expect(find.text('#9 Luis'), findsOneWidget);
      expect(find.text('Resultado: 1 - 0'), findsOneWidget);

      final List<double> positions = env.session.events
          .map((MatchEvent e) => tester.getTopLeft(byKey('event-${e.id}')).dy)
          .toList();
      final List<double> sorted = List<double>.of(positions)..sort();
      expect(positions, sorted);
      expect(env.session.events.length, 7);
    });

    testWidgets('el historial se actualiza al registrar un gol', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await tester.runAsync(() => env.session.startMatch());
      await openApp(tester, env.services);
      await openLive(tester);
      await tapKey(tester, 'open-history');
      expect(find.text('Gol de Rojo'), findsNothing);

      await tester.runAsync(
        () => env.session.addGoal(teamId: env.session.detail!.home.id),
      );
      await tester.pumpAndSettle();
      expect(find.text('Gol de Rojo'), findsOneWidget);
    });
  });

  group('13. Estadísticas', () {
    Future<void> playMatch(WidgetTester tester, Env env) async {
      final Team home = env.session.detail!.home;
      final Team away = env.session.detail!.away;
      final Player luis = env.session.playersOf(home.id).single;
      await tester.runAsync(() async {
        await env.session.startMatch();
        env.now = t0 + 5 * minute;
        await env.session.addGoal(teamId: home.id, playerId: luis.id);
        await env.session.addGoal(teamId: home.id, playerId: luis.id);
        await env.session.addGoal(teamId: away.id);
        await env.session.addGoal(teamId: home.id);
        await env.session.voidGoal(env.session.events.last.id);
        env.now = t0 + 46 * minute;
        await env.session.endFirstHalf();
        env.now = t0 + 61 * minute;
        await env.session.startSecondHalf();
        env.now = t0 + 107 * minute;
        await env.session.endMatch();
      });
    }

    testWidgets('calcula solo lo registrado', (WidgetTester tester) async {
      final Env env = Env();
      await prepareMatch(tester, env, roster: true);
      await playMatch(tester, env);
      await openApp(tester, env.services);
      await tapNav(tester, 'Partido');
      await tapKey(tester, 'tab-stats');

      expect(find.byType(StatsScreen), findsOneWidget);
      expect(find.text('RESULTADO FINAL'), findsOneWidget);
      expect(textOf(tester, 'stats-score'), '2 - 1');
      expect(textOf(tester, 'stats-goals-home'), '2');
      expect(textOf(tester, 'stats-goals-away'), '1');
      expect(textOf(tester, 'stats-goals-valid'), '3');
      expect(textOf(tester, 'stats-goals-voided'), '1');
      expect(textOf(tester, 'stats-corrections'), '1');
      expect(textOf(tester, 'stats-events-total'), '9');
      final Player luis = env.session.playersOf(env.session.detail!.home.id).single;
      expect(textOf(tester, 'stats-scorer-${luis.id}'), '2');
      expect(find.text('#9 Luis · Rojo'), findsOneWidget);
      expect(textOf(tester, 'stats-no-player'), '1');
    });

    testWidgets('sin goles no inventa goleadores', (WidgetTester tester) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await tester.runAsync(() => env.session.startMatch());
      await openApp(tester, env.services);
      await tapNav(tester, 'Partido');
      await tapKey(tester, 'tab-stats');

      expect(find.text('RESULTADO ACTUAL'), findsOneWidget);
      expect(textOf(tester, 'stats-score'), '0 - 0');
      expect(byKey('stats-no-scorers'), findsOneWidget);
      expect(byKey('stats-no-player'), findsNothing);
      expect(textOf(tester, 'stats-events-total'), '1');
    });

    testWidgets('sin partido preparado lo indica', (WidgetTester tester) async {
      final Env env = Env();
      await openApp(tester, env.services);
      await tester.tap(
        find.descendant(
          of: find.byType(AccessButton),
          matching: find.text('ESTADÍSTICAS'),
        ),
      );
      await tester.pumpAndSettle();
      expect(byKey('stats-empty'), findsOneWidget);
    });
  });

  group('16. Composición gráfica 16:9', () {
    Future<void> prepareWithSponsors(WidgetTester tester, Env env) async {
      await tester.runAsync(() async {
        await env.services.sponsors.add(name: 'Alfa', logoPath: 'logos/a.png');
        await env.services.sponsors.add(name: 'Beta', logoPath: 'logos/b.png');
        await env.services.sponsors.add(name: 'Gama');
      });
      await prepareMatch(
        tester,
        env,
        tournament: 'Liga local',
        crests: true,
        sponsorIds: <int>[1, 2, 3],
      );
    }

    testWidgets('muestra marcador, reloj, competición y patrocinadores', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareWithSponsors(tester, env);
      await openApp(tester, env.services);
      await tapNav(tester, 'Partido');
      await tapKey(tester, 'tab-graphics');

      expect(find.byType(GraphicsPreviewScreen), findsOneWidget);
      expect(byKey('broadcast-overlay'), findsOneWidget);
      expect(textOf(tester, 'overlay-score'), '0 - 0');
      expect(textOf(tester, 'overlay-clock'), '00:00');
      expect(textOf(tester, 'overlay-competition'), 'LIGA LOCAL');
      expect(textOf(tester, 'overlay-period'), 'POR COMENZAR');
      // El primer patrocinador es el principal; los demás van a franja y lateral.
      expect(byKey('overlay-main-1'), findsOneWidget);
      expect(byKey('overlay-strip-2'), findsOneWidget);
      expect(byKey('overlay-strip-3'), findsOneWidget);
      expect(byKey('overlay-side-2'), findsOneWidget);
      expect(byKey('overlay-side-3'), findsOneWidget);
      expect(find.text('3 patrocinador(es) elegido(s) para este partido.'), findsOneWidget);
    });

    testWidgets('los cambios del marcador y del cronómetro se reflejan', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareWithSponsors(tester, env);
      await openApp(tester, env.services);
      await tapNav(tester, 'Partido');
      await tapKey(tester, 'tab-graphics');

      await tester.runAsync(() => env.session.startMatch());
      await tester.pumpAndSettle();
      env.now = t0 + 12 * minute + 34 * second;
      await tester.runAsync(
        () => env.session.addGoal(teamId: env.session.detail!.home.id),
      );
      await tester.pumpAndSettle();
      expect(textOf(tester, 'overlay-score'), '1 - 0');
      expect(textOf(tester, 'overlay-clock'), '12:34');
      expect(textOf(tester, 'overlay-period'), 'PRIMER TIEMPO');

      await tester.runAsync(
        () => env.session.voidGoal(env.session.events.last.id),
      );
      await tester.pumpAndSettle();
      expect(textOf(tester, 'overlay-score'), '0 - 0');

      env.now = t0 + 47 * minute;
      await resumeApp(tester);
      expect(textOf(tester, 'overlay-clock'), '45:00');
      expect(textOf(tester, 'overlay-added'), '+02:00');
    });

    testWidgets('las zonas de patrocinadores se pueden activar y desactivar', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareWithSponsors(tester, env);
      await openApp(tester, env.services);
      await tapNav(tester, 'Partido');
      await tapKey(tester, 'tab-graphics');

      await tapKey(tester, 'zone-lowerStrip');
      expect(byKey('overlay-strip-2'), findsNothing);
      expect(byKey('overlay-side-2'), findsOneWidget);

      await tapKey(tester, 'zone-mainSponsor');
      expect(byKey('overlay-main-1'), findsNothing);
      expect(byKey('overlay-side-1'), findsOneWidget); // ahora entra el primero

      await tapKey(tester, 'zone-sidePanel');
      expect(byKey('overlay-side-1'), findsNothing);
    });

    testWidgets('sin patrocinadores lo indica y no dibuja zonas', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await openApp(tester, env.services);
      await tapNav(tester, 'Partido');
      await tapKey(tester, 'tab-graphics');
      expect(byKey('broadcast-overlay'), findsOneWidget);
      expect(
        textOf(tester, 'overlay-sponsor-count'),
        'Este partido no tiene patrocinadores elegidos. Elígelos en Preparar partido para verlos aquí.',
      );
    });

    testWidgets('sin partido preparado ofrece prepararlo', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await openApp(tester, env.services);
      await tester.runAsync(() => env.session.load());
      // El acceso a gráficos está en la pestaña Partido: se abre la ruta directa.
      final NavigatorState navigator = tester.state(find.byType(Navigator));
      navigator.pushNamed('/graficos');
      await tester.pumpAndSettle();
      expect(byKey('graphics-empty'), findsOneWidget);
    });
  });

  group('14 y 15. Persistencia y uso sin conexión', () {
    testWidgets('al cerrar y volver a abrir la app se recupera todo', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env, roster: true, crests: true);
      final Team home = env.session.detail!.home;
      final Player luis = env.session.playersOf(home.id).single;
      await tester.runAsync(() async {
        await env.session.startMatch();
        env.now = t0 + 10 * minute;
        await env.session.addGoal(teamId: home.id, playerId: luis.id);
        env.now = t0 + 20 * minute;
        await env.session.addGoal(teamId: env.session.detail!.away.id);
        await env.session.addGoal(teamId: home.id);
      });

      // "Cierra" la app: otra sesión y otra interfaz sobre los mismos datos.
      env.now = t0 + 27 * minute;
      final MatchSession reopened = MatchSession(
        matches: env.services.matches,
        teams: env.services.teams,
        players: env.services.players,
        sponsors: env.services.sponsors,
        nowMs: () => env.now,
      );
      await tester.runAsync(() => reopened.load());
      final AppServices services = AppServices(
        teams: env.services.teams,
        players: env.services.players,
        sponsors: env.services.sponsors,
        matches: env.services.matches,
        images: env.services.images,
        gallery: env.services.gallery,
        session: reopened,
        clockTick: null,
        tapCooldown: Duration.zero,
      );
      await openApp(tester, services);
      await openLive(tester);

      expect(textOf(tester, 'live-score'), '2 - 1');
      expect(textOf(tester, 'live-clock'), '27:00'); // siguió contando
      expect(textOf(tester, 'live-status'), 'PRIMER TIEMPO');
      expect(tester.widget<StoredImage>(byKey('live-crest-1')).path, 'crests/rojo.png');
      expect(find.text('#9 Luis'), findsOneWidget);
      expect(reopened.events.length, env.session.events.length);
    });

    testWidgets('un partido terminado se recupera con su resultado', (
      WidgetTester tester,
    ) async {
      final Env env = Env();
      await prepareMatch(tester, env);
      await tester.runAsync(() async {
        await env.session.startMatch();
        await env.session.addGoal(teamId: env.session.detail!.home.id);
        env.now = t0 + 50 * minute;
        await env.session.endMatch();
      });
      await openApp(tester, env.services);
      await openLive(tester);
      expect(textOf(tester, 'live-status'), 'FINAL');
      expect(textOf(tester, 'live-score'), '1 - 0');
    });
  });
}
