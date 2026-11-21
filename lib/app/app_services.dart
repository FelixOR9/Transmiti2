import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';
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
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';

/// Resultado de `AppServices.resolveRecordingsDir` (subetapa 0,
/// 3.1.4, grupo 0-f): la ruta canónica más cómo se llegó a ella.
/// `attempts` es el intento en el que se resolvió (1 en el caso
/// normal; 3 si hubo dos fallos temporales antes); `durationMs` es
/// la duración medida de la resolución completa, en milisegundos.
/// Ambos existen para que el diagnóstico del teléfono los muestre
/// (F1/F8): sin ellos no hay forma de confirmar en el S24 Ultra que
/// la ruta se resolvió en el primer intento ni cuánto tardó.
class RecordingsDirResolution {
  const RecordingsDirResolution({
    required this.path,
    required this.attempts,
    required this.durationMs,
  });

  /// Ruta canónica (`<filesDir>/recordings/`).
  final String path;

  /// Intento en el que se resolvió (1..3).
  final int attempts;

  /// Duración medida de la resolución completa, en milisegundos.
  final int durationMs;
}

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

  /// Resuelve la ruta canónica de grabaciones por el canal nativo
  /// (`<filesDir>/recordings/`), con reintentos, timeout y
  /// clasificación (subetapa 0, 3.1.4, grupo 0-b).
  ///
  /// SEDE ÚNICA (ambigüedad A5 del plan): aquí viven el timeout por
  /// intento (`Future.timeout`, 3 s), los reintentos (3 intentos en
  /// total, esperas 250/500 ms, solo para fallos temporales), la
  /// comprobación de contrato (respuesta nula/vacía) y la
  /// CONSTRUCCIÓN de `recordings_channel_unavailable` y
  /// `recordings_channel_contract_error` (ningún código Kotlin los
  /// emite; R-E5). `MethodChannelStreamingService.getRecordingsDir()`
  /// es solo el `invokeMethod` desnudo, sin timeout ni valor por
  /// defecto.
  ///
  /// Clasificación por código de excepción, no por `stage`:
  /// - `PlatformException`/`StreamingException` con código
  ///   `recordings_dir_unavailable` → duro inmediato, sin reintento.
  /// - Respuesta `null`/`""` (o solo espacios) → duro inmediato
  ///   `recordings_channel_contract_error` (`stage=null_response` |
  ///   `empty_response`, `attempts` = intento en el que se detectó),
  ///   sin reintento y sin registrar la ruta (en particular, nunca
  ///   se registra `""`).
  /// - `MissingPluginException` / otra `PlatformException` /
  ///   `TimeoutException` / `recordings_channel_timeout` nativo →
  ///   temporal, consume un intento; agotados los 3 →
  ///   `recordings_channel_unavailable` con `stage` = última clase
  ///   de fallo del puente (`missing_plugin` | `platform_error` |
  ///   `timeout`), `attempts=3`, `lastErrorCode` y `exceptionClass`.
  ///   Si gana el timeout Dart, `lastErrorCode` es `null` (revisión
  ///   4 §3.10 F3); si gana el timeout nativo, es
  ///   `recordings_channel_timeout`.
  ///
  /// `attemptTimeout` y `retryDelays` existen para las pruebas (los
  /// valores de producción son los de arriba).
  static Future<RecordingsDirResolution> resolveRecordingsDir(
    StreamingService service, {
    Duration attemptTimeout = const Duration(seconds: 3),
    List<Duration> retryDelays = const <Duration>[
      Duration(milliseconds: 250),
      Duration(milliseconds: 500),
    ],
  }) async {
    final Stopwatch stopwatch = Stopwatch()..start();
    final int maxAttempts = 1 + retryDelays.length;
    String? lastStage;
    String? lastErrorCode;
    String? lastExceptionClass;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        final String? path = await service
            .getRecordingsDir(attempt: attempt)
            .timeout(attemptTimeout);
        if (path == null || path.trim().isEmpty) {
          throw StreamingException(
            StreamContract.recordingsChannelContractError,
            'La respuesta del sistema de grabaciones no es válida.',
            <String, Object?>{
              'stage': path == null ? 'null_response' : 'empty_response',
              'attempts': attempt,
              'exceptionClass': null,
            },
          );
        }
        return RecordingsDirResolution(
          path: path,
          attempts: attempt,
          durationMs: stopwatch.elapsedMilliseconds,
        );
      } on StreamingException catch (e) {
        // Duro inmediato: fallo de directorio o error de contrato.
        // El de contrato se lanza arriba, dentro de este mismo `try`:
        // si no se re-lanzara aquí, el `catch` lo trataría como
        // temporal y lo reintentaría.
        if (e.code == StreamContract.recordingsDirUnavailable ||
            e.code == StreamContract.recordingsChannelContractError) {
          rethrow;
        }
        if (e.code == StreamContract.recordingsChannelTimeout) {
          lastStage = 'timeout';
          lastErrorCode = e.code;
        } else {
          lastStage = 'platform_error';
          lastErrorCode = e.code;
        }
        lastExceptionClass =
            e.details?['exceptionClass'] as String? ?? 'PlatformException';
      } on MissingPluginException {
        lastStage = 'missing_plugin';
        lastErrorCode = null;
        lastExceptionClass = 'MissingPluginException';
      } on PlatformException catch (e) {
        // Un servicio que propague la `PlatformException` sin
        // convertir: mismo tratamiento que el error de canal.
        lastStage = 'platform_error';
        lastErrorCode = e.code;
        lastExceptionClass = 'PlatformException';
      } on TimeoutException {
        lastStage = 'timeout';
        lastErrorCode = null;
        lastExceptionClass = 'TimeoutException';
      }
      if (attempt < maxAttempts) {
        await Future<void>.delayed(retryDelays[attempt - 1]);
      }
    }
    throw StreamingException(
      StreamContract.recordingsChannelUnavailable,
      'No se pudo contactar con el sistema de grabaciones del teléfono.',
      <String, Object?>{
        'stage': lastStage,
        'attempts': maxAttempts,
        'lastErrorCode': lastErrorCode,
        'exceptionClass': lastExceptionClass,
      },
    );
  }

  /// Resuelve la ruta canónica, abre la base SQLite y la carpeta de
  /// imágenes del teléfono y recupera el partido guardado. Todo
  /// funciona sin conexión a internet.
  ///
  /// Orden (subetapa 0, grupo 0-b; ambigüedad A2 del plan): la ruta
  /// canónica se resuelve ANTES de abrir la BD. Si la resolución
  /// falla, la BD no se abre en este intento y el REINTENTAR manual
  /// empieza limpio; y si un intento posterior reabre la misma ruta,
  /// `sqflite` devuelve la misma instancia (`singleInstance` por
  /// defecto), así que el reintento es idempotente.
  ///
  /// `streamingService` y `recordingsDirPathOverride` son costuras
  /// de inyección para las pruebas (servicio falso / ruta simulada);
  /// `recordingsDirAttemptTimeout` y `recordingsDirRetryDelays`
  /// acortan los tiempos del wrapper solo en pruebas.
  static Future<AppServices> create({
    StreamingService? streamingService,
    String? recordingsDirPathOverride,
    Duration? recordingsDirAttemptTimeout,
    List<Duration>? recordingsDirRetryDelays,
  }) async {
    final StreamingService service =
        streamingService ?? MethodChannelStreamingService();
    // Ruta canónica + cómo se resolvió (intento y duración medida,
    // grupo 0-f): el diagnóstico del teléfono (F1/F8) los muestra.
    // Con `recordingsDirPathOverride` (solo pruebas) no hay
    // resolución real: intento 1 y duración 0.
    final RecordingsDirResolution recordingsDir =
        recordingsDirPathOverride != null
            ? RecordingsDirResolution(
                path: recordingsDirPathOverride,
                attempts: 1,
                durationMs: 0,
              )
            : await resolveRecordingsDir(
                service,
                attemptTimeout:
                    recordingsDirAttemptTimeout ?? const Duration(seconds: 3),
                retryDelays: recordingsDirRetryDelays ??
                    const <Duration>[
                      Duration(milliseconds: 250),
                      Duration(milliseconds: 500),
                    ],
              );
    final String recordingsDirPath = recordingsDir.path;
    final String dbFolder = await getDatabasesPath();
    final Database db = await AppDatabase.open(
      path: p.join(dbFolder, 'transmiti2.db'),
    );
    final Directory docs = await getApplicationDocumentsDirectory();
    final ImageStore images = ImageStore(
      Directory(p.join(docs.path, 'images')),
    );
    // La carpeta `<docs>/recordings` ya no se crea ni se usa: la ruta
    // canónica es `<filesDir>/recordings/` (nativa) y la decide el
    // canal. La carpeta abandonada no se borra (vacía; solo se deja
    // de crear/usar) y las `file_path` ya registradas no se
    // reescriben. La carpeta de imágenes se mantiene.
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
        recordingsDirPath: recordingsDirPath,
      ),
      service: service,
      recordingsDirPath: recordingsDir.path,
      recordingsDirAttempts: recordingsDir.attempts,
      recordingsDirResolutionMs: recordingsDir.durationMs,
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
