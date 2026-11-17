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
    action_guard.dart            Protección contra doble toque
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
    live/                        Partido en vivo: pantalla, cabecera, barra de controles,
                                 tarjeta del cronómetro, diálogo y lista de goles, reloj en vivo
    history/                     Historial de eventos
    graphics/                    Composición gráfica 16:9 (datos, zonas de patrocinadores,
                                 dibujo y vista previa)
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

## Etapa 2C: interfaz del partido en vivo
- Toda la interfaz del partido pasa por `MatchSession`; ninguna pantalla habla con SQLite directamente.
- `LiveClock` repinta el reloj, pero el tiempo siempre se calcula con la hora real a partir del estado guardado; por eso sobrevive a bloqueos, minimizaciones y reinicios.
- `ControlBar` (goles y acción principal) está fija abajo, al alcance del pulgar; las acciones destructivas piden confirmación (`confirmAction`) y todas las acciones pasan por `ActionGuard`.
- `MatchStats` (estadísticas) y `buildOverlayData` (composición gráfica) son funciones puras sobre los eventos y la sesión: se prueban sin teléfono y no inventan datos.
- `BroadcastOverlay` se dibuja sobre un lienzo de 1280 x 720 escalado al ancho disponible y con fondo transparente, para poder reutilizarlo como superposición de video en la etapa de transmisión.

Detalles, decisiones y tabla de pruebas: `docs/ETAPA2C.md`.

## Etapa 3 — Fase 3.0: infraestructura de transmisión
- Solo cimientos, sin video: contrato Flutter-Kotlin por MethodChannel
  (`com.transmiti2.transmiti2/streaming`), destino manual RTMPS, base v2
  y CI solo de verificación. Los métodos de video devuelven el error
  `not_available_in_phase_3_0`.
- `features/streaming/`: `domain/` (destino, contrato, AAD canónico),
  `data/` (repositorio SQLite de destinos), `application/` (servicio por
  MethodChannel y controlador con flujo sin huérfanos) y `presentation/`
  (Estudio de transmisión con el formulario de destino y los botones de
  video deshabilitados con su fase). En Kotlin:
  `streaming/` (contrato, AAD, almacén seguro, servicio en primer plano
  como esqueleto, motor como interfaz y manejador del canal).
- La clave de transmisión nunca vive en SQLite (solo `has_key`) ni en
  Dart (solo pasa una vez por el canal): se cifra con Tink AEAD
  (AES256-GCM) con un AAD propio por destino y se guarda en DataStore;
  la llave maestra está en Android Keystore y el keyset de Tink se guarda
  cifrado con ella. Si Keystore no está activo, todo falla de forma
  segura (`keystore_not_active`) y no se escribe nada.
- En la Fase 3.0 no se pide cámara ni micrófono al usuario: el manifiesto
  solo los declara y `requestPermissions` está aplazado a la Fase 3.1.
- `MatchSession` y `OverlayData` no cambian en esta fase.

Detalles, decisiones y checklist manual: `docs/ETAPA3_FASE30.md`.

## Etapa 3 — Fase 3.1.0: contratos y permisos
- Contratos ampliados sin romper la 3.0: `startPreview()` →
  `{textureId, width, height}`, `stopPreview()`,
  `startRecording({matchId?, resolution: '720p', fps: 30,
  videoBitrateBps: 2500000, audioEnabled: true})` → `{recordingId}`,
  `stopRecording()` → `{recordingId, filePath, durationMs, sizeBytes,
  status}` y `stopAll()` sin cambios. Los métodos de video siguen
  devolviendo `not_available_in_phase_3_0` (la vista previa llega en
  3.1.1 y la grabación en 3.1.2). Los 10 códigos de error nuevos
  (`camera_unavailable`, `camera_in_use`, `camera_disconnected`,
  `encoder_error`, `muxer_error`, `audio_unavailable`, `storage_full`,
  `thermal_shutdown`, `permission_denied`, `recording_not_found`) están
  definidos en ambos lados; en 3.1.0 solo se produce `permission_denied`.
- Permisos reales: `requestPermissions` (antes
  `permission_request_deferred_to_phase_3_1`) con
  `ActivityResultLauncher` en `MainActivity`; nuevos `checkPermissions`
  (estados, sin diálogos) y `openAppSettings`. Estados
  `notRequested` / `granted` / `denied` / `permanentlyDenied` en
  `PermissionStates` (Dart) y `PermissionManager` (Kotlin). La
  distinción entre primera solicitud y denegación permanente NO usa
  solo `shouldShowRequestPermissionRationale()`: hay un historial
  persistente local de solicitudes en SharedPreferences
  (`transmiti2_permission_history`, nunca en SQLite). Los estados se
  vuelven a consultar al regresar a la app (`PermissionController` como
  `WidgetsBindingObserver`, con contador para varias pantallas) y nunca
  se cachean. La cámara solo se pide al tocar "Vista previa", con motivo
  en la app antes del diálogo del sistema; si se concede se muestra el
  estado honesto "vista previa en 3.1.1" (sin cámara ficticia); si se
  deniega permanentemente se ofrece abrir los ajustes. Micrófono y
  notificaciones quedan construidos y probados, sin activarse desde la
  UI hasta 3.1.2 / 3.1.3. El canal de eventos
  (`.../streaming/events`) emite `permission_result` tras cada
  solicitud y `permission_changed` ante cambios detectados.
- Servicio en primer plano preparado: registrado en el manifiesto con
  `foregroundServiceType="camera|microphone"` (no exportado) y permisos
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`,
  `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` y `WAKE_LOCK`.
  Dos escenarios en `ForegroundServicePrecheck`: con audio (máscara
  `camera|microphone`, exige CAMERA y RECORD_AUDIO) y sin audio
  (máscara `camera`, exige CAMERA); sin los permisos no se arranca y se
  devuelve `permission_denied`. `startForeground()` es la primera
  operación de `onStartCommand()`, con la notificación ya construida
  (canal propio, acción "Detener", tap que abre la app) y dentro de los
  5 s. En API < 29 se usa la llamada de dos argumentos; en API < 33 no
  se pide `POST_NOTIFICATIONS`. El wake lock parcial existe pero solo
  se adquiere desde 3.1.2.
- Compatibilidad: minSdk efectivo 24, targetSdk/compileSdk 36, cero
  dependencias nuevas. `getStatus` sigue devolviendo
  `foregroundServiceAvailable: false` hasta 3.1.2.
- Pendiente (NO validado en 3.1.0): el arranque real del servicio con
  una grabación, el diálogo real del sistema y la cámara real. Se
  verifican en 3.1.2 y en el teléfono de Félix con el checklist manual.

## Etapa 3 — Fase 3.1.1: vista previa real con Camera2
- Captura: `CameraSession` (Camera2) con la cámara trasera por defecto
  (se prefiere la cámara lógica multi-cámara si el dispositivo la
  expone; la frontal queda diferida). La sesión recibe su superficie de
  salida de video como parámetro intercambiable: en 3.1.1 es `null`
  (solo preview); en 3.1.2 será la superficie de entrada del codificador
  y más tarde la del compositor EGL. Sin MediaCodec, MediaMuxer,
  grabación, audio, RTMP ni composición.
- Superficie: `TextureRegistry.createSurfaceProducer()` (sin argumentos
  → `SurfaceLifecycle.manual`), verificado en el código del motor de
  Flutter 3.50.0: `onSurfaceAvailable` / `onSurfaceCleanup` (los
  antiguos `onSurfaceCreated` / `onSurfaceDestroyed` están deprecados
  desde 3.27 / 3.28), backend `ImageReader` en API ≥ 29 y
  `SurfaceTexture` como reserva. El tamaño se fija ANTES de pedir la
  superficie (el productor crea su `ImageReader` al primer
  `getSurface()`); la superficie no se cachea: se pide de nuevo cada
  vez que hace falta. La rotación NO la gestiona el motor
  (`handlesCropAndRotation()` es falso): Dart rota la presentación con
  `RotatedBox` según `rotationDegrees`.
- Configuración (escalera en `PreviewConfigSelector`, sin forzar nada
  que el dispositivo no soporte): 1280×720 @ 30 FPS → 1280×720 @ 24 →
  640×480 @ 30 → 640×480 @ 24 → mayor tamaño ≤ 720p → mayor tamaño
  disponible; FPS con el rango fijo [30, 30] si existe, si no un rango
  que contenga 30 (el más ajustado), si no el máximo más alto. El tamaño
  elegido debe estar en las listas de `SurfaceTexture` e `ImageReader`
  a la vez. Enfoque continuo si el dispositivo lo ofrece. Los valores
  reales del S24 Ultra se confirman con el checklist manual (la
  escalera es determinista, el resultado depende del dispositivo).
- Contratos: `startPreview()` → `{textureId, width, height,
  sensorOrientation, rotationDegrees, fps, lensFacing}` (tamaño en
  orientación del sensor; `rotationDegrees =
  (sensorOrientation - rotación de pantalla + 360) % 360`, trasera);
  `stopPreview()` y `stopAll()` (parada de emergencia, idempotente)
  reales; la grabación sigue devolviendo `not_available_in_phase_3_0`.
  Eventos nuevos: `preview_started`, `preview_stopped`,
  `preview_error` (con `code`) y `preview_surface_lost`. `getStatus`
  devuelve `streamingSupported: true` desde 3.1.1.
- Ciclo de vida: al ir a segundo plano se detiene la vista previa y al
  volver se restaura sola si estaba activa; al girar el teléfono se
  reinicia para recalcular la rotación (la Activity NO se recrea:
  `configChanges` incluye la orientación); al salir de la pantalla la
  cámara se cierra. Si la superficie se pierde temporalmente, se cierra
  solo la sesión, el productor se conserva y al volver la superficie se
  reabre la sesión con la misma textura. Sin sesiones duplicadas:
  `startPreview` con la vista activa devuelve la información actual y
  dos inicios simultáneos comparten el mismo trabajo. La cámara se
  cierra ante desconexión o error y se avisa con `preview_error`.
- Permisos: se reutiliza el sistema 3.1.0 sin duplicarlo. La cámara no
  se abre sin el permiso concedido (el motor lo comprueba antes de
  crear nada); revocado → `permission_denied`; ocupada →
  `camera_in_use`; no disponible → `camera_unavailable`; desconectada
  → `camera_disconnected`.
- UI: `PreviewController` (estados `closed`, `preparing`, `active`,
  `recovering`, `unavailable`, `permissionDenied`, `error`) y
  `BroadcastScreen` con `Texture` dentro de `AspectRatio` +
  `RotatedBox`, sin deformar. Estados visibles: cerrada, preparando,
  activa, recuperando, no disponible, permiso denegado y error.
- Limitaciones conocidas: la rotación de 180° (vertical boca abajo) no
  se detecta (no cambia la orientación apaisado/vertical) y la vista
  previa queda boca abajo hasta girar el teléfono; la cámara frontal
  no está implementada (diferida); la mecánica Camera2 (`CameraSession`
  real) no se puede probar en CI sin emulador: en CI se prueban la
  selección de configuración y toda la orquestación con sesiones
  falsas, y la cámara real se verifica en el S24 Ultra.
