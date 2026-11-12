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

/// Migraciones por versión de destino: la clave N contiene las sentencias que
/// llevan la base de la versión N-1 a la N. Vacía mientras la versión sea 1.
const Map<int, List<String>> kMigrations = <int, List<String>>{};
