import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite/sqflite.dart';
import 'package:transmiti2/core/db/app_database.dart';
import 'package:transmiti2/core/storage/gallery_picker.dart';
import 'package:transmiti2/core/storage/image_store.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/data/repositories.dart';
import 'package:transmiti2/features/match/data/sqlite_repositories.dart';
import 'package:transmiti2/features/match/domain/sponsor.dart';
import 'package:transmiti2/features/match/domain/team.dart';
import 'package:transmiti2/features/streaming/application/streaming_controller.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/data/recording_repository.dart';
import 'package:transmiti2/features/streaming/data/stream_destination_repository.dart';

/// Servicios compartidos de la app: almacenamiento y partido actual.
class AppServices {
  AppServices({
    required this.teams,
    required this.players,
    required this.sponsors,
    required this.matches,
    required this.images,
    required this.gallery,
    required this.session,
    required this.streaming,
    this.clockTick = const Duration(milliseconds: 250),
    this.tapCooldown = const Duration(milliseconds: 700),
  });

  final TeamRepository teams;
  final PlayerRepository players;
  final SponsorRepository sponsors;
  final MatchRepository matches;
  final ImageStore images;
  final GalleryPicker gallery;
  final MatchSession session;

  /// Destinos de transmisión y claves (Fase 3.0: solo infraestructura y
  /// destino manual; sin cámara, transmisión ni grabación todavía).
  final StreamingController streaming;

  /// Cada cuánto se repinta el reloj en pantalla. El tiempo siempre se calcula
  /// con la hora real, así que esto solo afecta a la fluidez visual. Nulo
  /// desactiva el repintado periódico (se usa en las pruebas).
  final Duration? clockTick;

  /// Tiempo durante el cual se ignoran toques repetidos tras una acción
  /// (evita que un doble toque registre dos goles).
  final Duration tapCooldown;

  /// Indica si algún equipo o patrocinador usa todavía esa imagen. Se usa
  /// antes de borrar un archivo para no perder imágenes que siguen en uso.
  Future<bool> isImageInUse(String path) async {
    for (final Team team in await teams.all()) {
      if (team.crestPath == path) {
        return true;
      }
    }
    for (final Sponsor sponsor in await sponsors.all()) {
      if (sponsor.logoPath == path) {
        return true;
      }
    }
    return false;
  }

  /// Abre la base SQLite y la carpeta de imágenes del teléfono y recupera el
  /// partido guardado. Todo funciona sin conexión a internet.
  static Future<AppServices> create() async {
    final String dbFolder = await getDatabasesPath();
    final Database db = await AppDatabase.open(
      path: p.join(dbFolder, 'transmiti2.db'),
    );
    final Directory docs = await getApplicationDocumentsDirectory();
    final ImageStore images = ImageStore(
      Directory(p.join(docs.path, 'images')),
    );
    // Carpeta de grabaciones locales (Fase 3.0: se crea vacía; los archivos
    // MP4 llegan en la Fase 3.2).
    final Directory recordings = Directory(p.join(docs.path, 'recordings'));
    if (!await recordings.exists()) {
      await recordings.create(recursive: true);
    }

    final TeamRepository teams = SqliteTeamRepository(db);
    final PlayerRepository players = SqlitePlayerRepository(db);
    final SponsorRepository sponsors = SqliteSponsorRepository(db);
    final MatchRepository matches = SqliteMatchRepository(db);
    final MatchSession session = MatchSession(
      matches: matches,
      teams: teams,
      players: players,
      sponsors: sponsors,
    );
    await session.load();
    final StreamingController streaming = StreamingController(
      destinations: SqliteStreamDestinationRepository(db),
      recordings: SqliteRecordingRepository(
        db,
        recordingsDirPath: recordings.path,
      ),
      service: MethodChannelStreamingService(),
    );
    return AppServices(
      teams: teams,
      players: players,
      sponsors: sponsors,
      matches: matches,
      images: images,
      gallery: ImagePickerGallery(),
      session: session,
      streaming: streaming,
    );
  }
}
