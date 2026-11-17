/// Estructura de la base de datos SQLite (versión 1).
///
/// - Las imágenes NO se guardan aquí: solo su ruta relativa (crest_path,
///   logo_path) dentro del almacén de imágenes de la app.
/// - El marcador no se duplica: se calcula con los goles de match_events.
const List<String> kSchemaV1 = <String>[
  '''
CREATE TABLE teams (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  name_key TEXT NOT NULL UNIQUE,
  color INTEGER NOT NULL,
  crest_path TEXT,
  created_at_ms INTEGER NOT NULL
)''',
  '''
CREATE TABLE players (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  team_id INTEGER NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  number INTEGER NOT NULL,
  created_at_ms INTEGER NOT NULL,
  UNIQUE (team_id, number)
)''',
  '''
CREATE TABLE sponsors (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  logo_path TEXT,
  created_at_ms INTEGER NOT NULL
)''',
  '''
CREATE TABLE matches (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  home_team_id INTEGER NOT NULL REFERENCES teams(id),
  away_team_id INTEGER NOT NULL REFERENCES teams(id),
  tournament TEXT,
  scheduled_at_ms INTEGER NOT NULL,
  half_minutes INTEGER NOT NULL,
  break_minutes INTEGER NOT NULL,
  status TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL
)''',
  '''
CREATE TABLE match_sponsors (
  match_id INTEGER NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  sponsor_id INTEGER NOT NULL REFERENCES sponsors(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  PRIMARY KEY (match_id, sponsor_id)
)''',
  '''
CREATE TABLE match_clock (
  match_id INTEGER PRIMARY KEY REFERENCES matches(id) ON DELETE CASCADE,
  phase TEXT NOT NULL,
  period INTEGER NOT NULL,
  running INTEGER NOT NULL,
  period_elapsed_ms INTEGER NOT NULL,
  resumed_at_ms INTEGER,
  first_half_ms INTEGER,
  break_started_at_ms INTEGER,
  updated_at_ms INTEGER NOT NULL
)''',
  '''
CREATE TABLE match_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  match_id INTEGER NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  type TEXT NOT NULL,
  team_id INTEGER REFERENCES teams(id),
  player_id INTEGER REFERENCES players(id) ON DELETE SET NULL,
  period INTEGER NOT NULL,
  minute INTEGER NOT NULL,
  added INTEGER NOT NULL DEFAULT 0,
  clock_ms INTEGER NOT NULL,
  ref_event_id INTEGER REFERENCES match_events(id) ON DELETE SET NULL,
  voided INTEGER NOT NULL DEFAULT 0,
  note TEXT,
  created_at_ms INTEGER NOT NULL
)''',
  'CREATE INDEX idx_players_team ON players (team_id)',
  'CREATE INDEX idx_match_events_match ON match_events (match_id, id)',
];

/// Migración de la versión 1 a la 2 (Fase 3.0, infraestructura de
/// transmisión). Solo añade tablas; no modifica ni borra ninguna tabla v1.
///
/// - `stream_destinations`: destinos de transmisión guardados por el usuario
///   (manual RTMPS ahora; `facebook_graph` reservado para más adelante).
///   La clave de transmisión NO se guarda aquí: solo `has_key` indica si hay
///   una clave cifrada en el almacén seguro de Android.
/// - `stream_sessions`: cada intento de transmisión (en 3.0 no se crea
///   ninguna; la tabla existe para las fases 3.1 en adelante).
/// - `recordings`: partidos grabados en MP4 en el teléfono (en 3.0 no se
///   crea ningún archivo; la tabla existe para las fases 3.1 en adelante).
const List<String> kMigrationV2 = <String>[
  '''
CREATE TABLE stream_destinations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL CHECK (kind IN ('manual', 'facebook_graph')),
  label TEXT NOT NULL,
  ingest_url TEXT NOT NULL,
  page_id TEXT,
  page_name TEXT,
  has_key INTEGER NOT NULL DEFAULT 0,
  is_active INTEGER NOT NULL DEFAULT 0,
  expires_at_ms INTEGER,
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL
)''',
  '''
CREATE TABLE stream_sessions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  destination_id INTEGER REFERENCES stream_destinations(id)
    ON DELETE SET NULL,
  match_id INTEGER REFERENCES matches(id) ON DELETE SET NULL,
  platform TEXT NOT NULL DEFAULT 'facebook',
  status TEXT NOT NULL CHECK (
    status IN ('preparing', 'live', 'reconnecting', 'ended', 'failed')),
  started_at_ms INTEGER,
  ended_at_ms INTEGER,
  disconnect_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at_ms INTEGER NOT NULL
)''',
  '''
CREATE TABLE recordings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  match_id INTEGER REFERENCES matches(id) ON DELETE SET NULL,
  stream_session_id INTEGER REFERENCES stream_sessions(id)
    ON DELETE SET NULL,
  file_path TEXT NOT NULL UNIQUE,
  file_name TEXT NOT NULL,
  resolution TEXT NOT NULL DEFAULT '720p',
  fps INTEGER NOT NULL DEFAULT 30,
  video_bitrate_bps INTEGER NOT NULL DEFAULT 2500000,
  duration_ms INTEGER,
  size_bytes INTEGER,
  status TEXT NOT NULL CHECK (
    status IN ('recording', 'finalizing', 'ready', 'corrupt', 'deleted')),
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL
)''',
  'CREATE INDEX idx_stream_destinations_active '
      'ON stream_destinations (is_active)',
  'CREATE INDEX idx_stream_sessions_destination '
      'ON stream_sessions (destination_id)',
  'CREATE INDEX idx_recordings_match ON recordings (match_id)',
];

/// Migraciones por versión de destino: la clave N contiene las sentencias que
/// llevan la base de la versión N-1 a la N.
const Map<int, List<String>> kMigrations = <int, List<String>>{
  2: kMigrationV2,
};
