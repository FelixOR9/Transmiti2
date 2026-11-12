import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite/sqflite.dart';
import 'package:transmiti2/core/db/app_database.dart';
import 'package:transmiti2/core/storage/image_store.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/data/repositories.dart';
import 'package:transmiti2/features/match/data/sqlite_repositories.dart';

/// Servicios compartidos de la app: almacenamiento y partido actual.
class AppServices {
  AppServices({
    required this.teams,
    required this.players,
    required this.sponsors,
    required this.matches,
    required this.images,
    required this.session,
  });

  final TeamRepository teams;
  final PlayerRepository players;
  final SponsorRepository sponsors;
  final MatchRepository matches;
  final ImageStore images;
  final MatchSession session;

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
    return AppServices(
      teams: teams,
      players: players,
      sponsors: sponsors,
      matches: matches,
      images: images,
      session: session,
    );
  }
}
