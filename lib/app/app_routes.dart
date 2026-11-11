/// Nombres de las rutas de navegación de la aplicación.
///
/// Las pestañas de la barra inferior (Inicio, Partido, Repeticiones, Más)
/// viven dentro de [home]; el resto son pantallas que se abren encima.
abstract final class AppRoutes {
  static const String home = '/';
  static const String broadcast = '/estudio';
  static const String matchSetup = '/partido/preparar';
  static const String scoreboard = '/marcador';
  static const String matchClock = '/cronometro';
  static const String stats = '/estadisticas';
}
