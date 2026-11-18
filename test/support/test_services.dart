import 'package:transmiti2/app/app_services.dart';
import 'package:transmiti2/core/storage/gallery_picker.dart';
import 'package:transmiti2/core/storage/image_store.dart';
import 'package:transmiti2/core/time.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/soccer_match.dart';
import 'package:transmiti2/features/streaming/application/streaming_controller.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';

import 'fake_streaming.dart';
import 'fakes.dart';
import 'in_memory_repositories.dart';

/// Servicios de prueba: todo en memoria, sin disco ni galería real.
AppServices createTestServices({
  NowMs? nowMs,
  GalleryPicker? gallery,
  ImageStore? images,
  Duration? clockTick,
  Duration tapCooldown = Duration.zero,
  StreamingService? streamingService,
}) {
  final InMemoryTeamRepository teams = InMemoryTeamRepository();
  final InMemoryPlayerRepository players = InMemoryPlayerRepository();
  final InMemorySponsorRepository sponsors = InMemorySponsorRepository();
  final InMemoryMatchRepository matches = InMemoryMatchRepository(nowMs: nowMs);
  return AppServices(
    teams: teams,
    players: players,
    sponsors: sponsors,
    matches: matches,
    images: images ?? FakeImageStore(),
    gallery: gallery ?? FakeGalleryPicker(),
    clockTick: clockTick,
    tapCooldown: tapCooldown,
    session: MatchSession(
      matches: matches,
      teams: teams,
      players: players,
      sponsors: sponsors,
      nowMs: nowMs,
    ),
    streaming: StreamingController(
      destinations: InMemoryStreamDestinationRepository(),
      recordings: InMemoryRecordingRepository(),
      service: streamingService ?? FakeStreamingService(),
      nowMs: nowMs,
    ),
  );
}

/// Formulario válido de ejemplo (Rojo contra Azul, 45 + 15 minutos).
MatchSetup sampleSetup({
  String homeName = 'Rojo',
  String awayName = 'Azul',
  int homeColor = 0xFFE53935,
  int awayColor = 0xFF1E88E5,
  String? tournament,
  int halfMinutes = 45,
  int breakMinutes = 15,
  List<int> sponsorIds = const <int>[],
}) {
  return MatchSetup(
    homeName: homeName,
    homeColor: homeColor,
    awayName: awayName,
    awayColor: awayColor,
    tournament: tournament,
    scheduledAtMs: DateTime(2026, 11, 15).millisecondsSinceEpoch,
    halfMinutes: halfMinutes,
    breakMinutes: breakMinutes,
    sponsorIds: sponsorIds,
  );
}
