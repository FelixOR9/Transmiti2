import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/features/graphics/application/overlay_data_builder.dart';
import 'package:transmiti2/features/graphics/domain/overlay_data.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match_clock/domain/match_clock.dart';

import '../support/in_memory_repositories.dart';
import '../support/test_services.dart';

const int minute = 60000;
const int t0 = 1800000000000;

OverlaySponsor sp(int id) => OverlaySponsor(id: id, name: 'S$id');

void main() {
  late InMemoryTeamRepository teams;
  late InMemoryPlayerRepository players;
  late InMemorySponsorRepository sponsors;
  late InMemoryMatchRepository matches;
  late MatchSession session;
  int now = t0;

  setUp(() {
    now = t0;
    teams = InMemoryTeamRepository();
    players = InMemoryPlayerRepository();
    sponsors = InMemorySponsorRepository();
    matches = InMemoryMatchRepository(nowMs: () => now);
    session = MatchSession(
      matches: matches,
      teams: teams,
      players: players,
      sponsors: sponsors,
      nowMs: () => now,
    );
  });

  group('buildOverlayData', () {
    test('sin partido no hay composición', () async {
      await session.load();
      expect(buildOverlayData(session), isNull);
    });

    test('refleja equipos, competición, marcador, reloj y patrocinadores', () async {
      final Sponsor alfa = await sponsors.add(
        name: 'Alfa',
        logoPath: 'logos/a.png',
      );
      final Sponsor beta = await sponsors.add(name: 'Beta');
      await session.load();
      await session.saveSetup(
        sampleSetup(
          tournament: 'Liga local',
          sponsorIds: <int>[beta.id, alfa.id],
        ),
      );
      await session.startMatch();
      now = t0 + 12 * minute + 34000;
      await session.addGoal(teamId: session.detail!.home.id);

      final OverlayData data = buildOverlayData(session)!;
      expect(data.homeName, 'Rojo');
      expect(data.awayName, 'Azul');
      expect(data.competition, 'Liga local');
      expect(data.homeGoals, 1);
      expect(data.awayGoals, 0);
      expect(data.scoreText, '1 - 0');
      expect(data.clockText, '12:34');
      expect(data.addedText, isNull);
      expect(data.periodLabel, 'PRIMER TIEMPO');
      expect(data.running, isTrue);
      expect(data.sponsors.map((OverlaySponsor s) => s.name), <String>[
        'Beta',
        'Alfa',
      ]);
      expect(data.sponsors.last.logoPath, 'logos/a.png');
    });

    test('los cambios del marcador y del reloj se reflejan en la composición', () async {
      await session.load();
      await session.saveSetup(sampleSetup());
      await session.startMatch();

      now = t0 + 5 * minute;
      OverlayData data = buildOverlayData(session)!;
      expect(data.scoreText, '0 - 0');
      expect(data.clockText, '05:00');

      await session.addGoal(teamId: session.detail!.away.id);
      now = t0 + 9 * minute;
      data = buildOverlayData(session)!;
      expect(data.scoreText, '0 - 1');
      expect(data.clockText, '09:00');

      await session.voidGoal(_lastGoalId(session));
      data = buildOverlayData(session)!;
      expect(data.scoreText, '0 - 0');
    });

    test('muestra el tiempo añadido y el periodo', () async {
      await session.load();
      await session.saveSetup(sampleSetup());
      await session.startMatch();
      now = t0 + 47 * minute;
      OverlayData data = buildOverlayData(session)!;
      expect(data.clockText, '45:00');
      expect(data.addedText, '+02:00');

      await session.endFirstHalf();
      data = buildOverlayData(session)!;
      expect(data.periodLabel, 'DESCANSO');
      expect(data.running, isFalse);

      await session.startSecondHalf();
      data = buildOverlayData(session)!;
      expect(data.periodLabel, 'SEGUNDO TIEMPO');

      await session.endMatch();
      data = buildOverlayData(session)!;
      expect(data.periodLabel, 'FINAL');
    });

    test('periodLabelFor cubre todas las fases', () {
      expect(periodLabelFor(MatchPhase.notStarted), 'POR COMENZAR');
      expect(periodLabelFor(MatchPhase.firstHalf), 'PRIMER TIEMPO');
      expect(periodLabelFor(MatchPhase.halfTime), 'DESCANSO');
      expect(periodLabelFor(MatchPhase.secondHalf), 'SEGUNDO TIEMPO');
      expect(periodLabelFor(MatchPhase.fullTime), 'FINAL');
    });
  });

  group('SponsorZones.assign', () {
    final List<OverlaySponsor> three = <OverlaySponsor>[sp(1), sp(2), sp(3)];

    test('con todas las zonas: el primero es el principal y el resto va a la franja y al lateral', () {
      final SponsorZones z = SponsorZones.assign(
        three,
        SponsorZone.values.toSet(),
      );
      expect(z.main, sp(1));
      expect(z.strip, <OverlaySponsor>[sp(2), sp(3)]);
      expect(z.side, <OverlaySponsor>[sp(2), sp(3)]);
    });

    test('sin zona principal todos van a las otras zonas', () {
      final SponsorZones z = SponsorZones.assign(three, <SponsorZone>{
        SponsorZone.lowerStrip,
      });
      expect(z.main, isNull);
      expect(z.strip, three);
      expect(z.side, isEmpty);
    });

    test('solo la zona principal', () {
      final SponsorZones z = SponsorZones.assign(three, <SponsorZone>{
        SponsorZone.mainSponsor,
      });
      expect(z.main, sp(1));
      expect(z.strip, isEmpty);
      expect(z.side, isEmpty);
    });

    test('sin patrocinadores todas las zonas quedan vacías', () {
      final SponsorZones z = SponsorZones.assign(
        <OverlaySponsor>[],
        SponsorZone.values.toSet(),
      );
      expect(z.main, isNull);
      expect(z.strip, isEmpty);
      expect(z.side, isEmpty);
    });

    test('sin zonas activas no se muestra nada y no hay límite de patrocinadores', () {
      final List<OverlaySponsor> many = <OverlaySponsor>[
        for (int i = 1; i <= 40; i++) sp(i),
      ];
      expect(SponsorZones.assign(many, <SponsorZone>{}).strip, isEmpty);
      final SponsorZones z = SponsorZones.assign(many, <SponsorZone>{
        SponsorZone.lowerStrip,
      });
      expect(z.strip.length, 40);
    });
  });
}

int _lastGoalId(MatchSession s) =>
    s.events.lastWhere((MatchEvent e) => e.isActiveGoal).id;
