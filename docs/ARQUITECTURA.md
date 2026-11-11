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
    app.dart                     MaterialApp: tema, rutas, pantalla inicial
    app_routes.dart              Nombres de rutas
    app_router.dart              Ruta -> pantalla
    theme/                       Colores y tema oscuro
  core/
    app_info.dart                Nombre y versión
  shared/widgets/
    app_button.dart              Botón principal (verde) y secundario (azul)
    app_bottom_nav.dart          Barra de navegación inferior
    section_label.dart           Rótulo de sección en mayúsculas
    feature_placeholder.dart     Pantalla "Próximamente" provisional
  features/
    shell/                       Pestañas + barra inferior
    home/                        Inicio: próximo partido, accesos, estado del equipo
    match/                       Partido (pestaña) y Preparar partido (provisionales)
    broadcast/                   Estudio de transmisión (provisional)
    scoreboard/                  Marcador (provisional)
    match_clock/                 Cronómetro (provisional)
    stats/                       Estadísticas (provisional)
    replays/                     Repeticiones (provisional)
    more/                        Más: ajustes e información (provisional)
```

## Decisiones de la Etapa 1
- Navegación con el `Navigator` de Flutter y rutas con nombre: no requiere paquetes externos. Si más adelante hace falta algo más avanzado, solo cambia `app_router.dart`.
- Cero dependencias de terceros (solo Flutter y `flutter_lints` para revisar el código): menos puntos de fallo al compilar.
- Diseño basado en una captura de referencia compartida por el propietario: fondo azul noche, verde brillante, tarjetas redondeadas y fuente condensada del sistema Android (si el teléfono no la tiene, se usa la normal).
- Sin torneos en el MVP: se quitaron del diseño de referencia los accesos a torneos, equipos, jugadores, calendario y patrocinadores.
- Sin datos inventados: la tarjeta de próximo partido muestra "Sin partido configurado" y la franja de cámara, micrófono y red muestra "Sin comprobar" hasta que existan esas funciones.
