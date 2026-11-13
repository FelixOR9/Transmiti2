# Arquitectura de TRANSMITI2

## Principios
- Organización por funciones (`features/`): cada función del partido vive en su propia carpeta.
- Dentro de cada función, la interfaz va en `presentation/`. Cuando una función necesite lógica o datos, se añaden `domain/` (reglas) y `data/` (SQLite, red) en esa misma carpeta.
- Lo compartido va en `shared/widgets/` (botones, barra inferior, etc.) y `core/` (datos generales de la app).
- La parte audiovisual (cámara, codificación H.264/AAC, RTMP/RTMPS, buffer circular, clips MP4) irá en Kotlin dentro de `android/app/src/main/kotlin/`, conectada a Flutter por un canal de plataforma. No se implementa en la Etapa 1.
- El marcador y el cronómetro no dependerán de internet: su estado vivirá en el teléfono (SQLite) y la transmisión solo lo "dibujará" sobre el video.
- La transmisión simultánea mediante relay externo se podrá añadir después, porque la transmisión se aislará detrás de una interfaz propia.

## Navegación
- Barra inferior con cuatro pestañas: **Inicio**, **Partido**, **Repeticiones** y **Más** (`features/shell`). Cada pestaña se crea al visitarla y se conserva.
- Desde Inicio se abren, encima de la barra: Preparar partido, Estudio de transmisión, Marcador, Cronómetro y Estadísticas. El acceso REPETICIONES cambia a su pestaña.
- Las rutas están en `app/app_routes.dart` y su traducción a pantallas en `app/app_router.dart`.

## Estructura de `lib/`
```
lib/
  main.dart                      Punto de entrada
  app/
    app.dart                     MaterialApp: servicios, tema, rutas, pantalla inicial
    app_services.dart            Base de datos, repositorios, imágenes y partido actual
    app_scope.dart               Entrega los servicios a todas las pantallas
    bootstrap.dart               Abre el almacenamiento al arrancar (con reintento si falla)
    app_routes.dart              Nombres de rutas
    app_router.dart              Ruta -> pantalla
    theme/                       Colores y tema oscuro
  core/
    app_info.dart                Nombre y versión
    formatting.dart              Formato de fechas y del reloj (MM:SS)
    time.dart                    Hora actual (inyectable en pruebas)
    db/                          Esquema SQLite, migraciones y apertura de la base
    storage/image_store.dart     Almacén persistente de escudos y logotipos
    storage/image_draft.dart     Imagen en edición: reemplazar/quitar sin perder archivos en uso
    storage/gallery_picker.dart  Galería del teléfono (image_picker) detrás de una interfaz
  shared/widgets/
    app_button.dart              Botón principal (verde) y secundario (azul)
    app_bottom_nav.dart          Barra de navegación inferior
    section_label.dart           Rótulo de sección en mayúsculas
    app_input.dart               Estilo de los campos de texto
    stored_image.dart            Escudo o logotipo guardado (sin deformarse)
    image_field.dart             Vista previa + cargar / reemplazar / quitar imagen
    color_palette.dart           Selector de color del equipo
    number_stepper.dart          Número con botones -5, -1, +1, +5
    confirm_dialog.dart          Confirmación antes de eliminar
    feature_placeholder.dart     Pantalla "Próximamente" provisional
  features/
    shell/                       Pestañas + barra inferior
    home/                        Inicio: próximo partido, accesos, estado del equipo
    match/                       Partido: dominio, repositorios, sesión (ver abajo)
                                 y pantallas: Partido, Preparar partido y Jugadores
    broadcast/                   Estudio de transmisión (provisional)
    scoreboard/                  Marcador (provisional)
    match_clock/                 Motor del cronómetro (domain) y pantalla (provisional)
    stats/                       Estadísticas (provisional)
    replays/                     Repeticiones (provisional)
    sponsors/                    Patrocinadores: lista y alta/edición con logotipo
    more/                        Más: acceso a patrocinadores y versión
```

## Decisiones de la Etapa 1
- Navegación con el `Navigator` de Flutter y rutas con nombre: no requiere paquetes externos. Si más adelante hace falta algo más avanzado, solo cambia `app_router.dart`.
- Cero dependencias de terceros (solo Flutter y `flutter_lints` para revisar el código): menos puntos de fallo al compilar.
- Diseño basado en una captura de referencia compartida por el propietario: fondo azul noche, verde brillante, tarjetas redondeadas y fuente condensada del sistema Android (si el teléfono no la tiene, se usa la normal).
- Sin torneos en el MVP: se quitaron del diseño de referencia los accesos a torneos, equipos, jugadores, calendario y patrocinadores.
- Sin datos inventados: la tarjeta de próximo partido muestra "Sin partido configurado" y la franja de cámara, micrófono y red muestra "Sin comprobar" hasta que existan esas funciones.

## Etapa 2: datos y lógica del partido
```
features/match/
  domain/        Modelos y reglas sin dependencias de Flutter ni de SQLite:
                 Team, Player, Sponsor, SoccerMatch, MatchSetup, MatchEvent,
                 ScoreSummary, GoalMinute, MatchDetail, excepciones
  data/          repositories.dart (contratos) y sqlite_repositories.dart (SQLite)
  application/   match_session.dart: el partido actual, su cronómetro, su
                 marcador y su historial; la única puerta para modificarlos
features/match_clock/domain/match_clock.dart
                 Motor del cronómetro (MatchClockState) y lectura del reloj
                 (ClockReading), puros y probados sin teléfono
```
- La interfaz solo habla con `MatchSession`; esta guarda en los repositorios y después avisa a la pantalla.
- Los repositorios son contratos: SQLite en la app y versiones en memoria en las pruebas, con las mismas reglas.
- Las operaciones que deben ir juntas (cambiar el cronómetro + estado del partido + evento; corregir un gol + su evento de corrección) se guardan en una sola transacción.
- Para la futura transmisión, el estado del partido (equipos, marcador, reloj, patrocinadores) se obtiene siempre desde `MatchSession`, de modo que los gráficos de video y la interfaz muestren los mismos datos.

Detalles de la base de datos, las imágenes y el cronómetro: `docs/ETAPA2.md`.

## Etapa 2B: formularios conectados al almacenamiento
- Las pantallas leen y escriben **solo** a través de `AppServices` (repositorios SQLite, `ImageStore`, `MatchSession`); no hay datos de ejemplo ni formularios que simulen guardar.
- `AppServices.gallery` abre la galería real en el teléfono; en las pruebas se sustituye por una galería falsa sin tocar el disco.
- `ImageDraft` encapsula el ciclo de vida de una imagen dentro de un formulario: cargar → (guardar | cancelar). `AppServices.isImageInUse` impide borrar archivos que todavía usa algún equipo o patrocinador.
- El formulario del partido reconoce equipos ya registrados por su nombre y reutiliza su escudo y color.
- Cambios de plantilla o de patrocinadores llaman a `MatchSession.load()` para que el partido actual siempre muestre datos al día.
