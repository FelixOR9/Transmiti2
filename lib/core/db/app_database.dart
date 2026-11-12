import 'package:sqflite/sqflite.dart';
import 'package:transmiti2/core/db/schema.dart';

/// Apertura de la base de datos con creación y migraciones.
abstract final class AppDatabase {
  static const int version = 1;

  /// Abre (o crea) la base en [path].
  ///
  /// [factory], [targetVersion] y [extraMigrations] existen para las pruebas:
  /// en la app se usan los valores por defecto.
  static Future<Database> open({
    required String path,
    DatabaseFactory? factory,
    int targetVersion = version,
    Map<int, List<String>> extraMigrations = const <int, List<String>>{},
  }) {
    final DatabaseFactory dbFactory = factory ?? databaseFactory;
    final Map<int, List<String>> migrations = <int, List<String>>{
      ...kMigrations,
      ...extraMigrations,
    };

    Future<void> migrate(Database db, int from, int to) async {
      for (int v = from + 1; v <= to; v++) {
        final List<String>? statements = migrations[v];
        if (statements == null) {
          throw StateError('Falta la migración de la base a la versión $v.');
        }
        for (final String sql in statements) {
          await db.execute(sql);
        }
      }
    }

    return dbFactory.openDatabase(
      path,
      options: OpenDatabaseOptions(
        version: targetVersion,
        onConfigure: (Database db) async {
          await db.execute('PRAGMA foreign_keys = ON');
        },
        onCreate: (Database db, int newVersion) async {
          for (final String sql in kSchemaV1) {
            await db.execute(sql);
          }
          await migrate(db, 1, newVersion);
        },
        onUpgrade: (Database db, int oldVersion, int newVersion) async {
          await migrate(db, oldVersion, newVersion);
        },
      ),
    );
  }
}
