# Arquitectura de TRANSMITI2

## Principios
- Organización por funciones (`features/`): cada función del partido vive en su propia carpeta.
- Dentro de cada función, la interfaz va en `presentation/`. Cuando una función necesite lógica o datos, se añaden `domain/` (reglas) y `data/` (SQLite, red) en esa misma carpeta.
- Lo compartido va en `shared/` (widgets reutilizables) y `core/` (datos generales de la app).
- La parte audiovisual (cámara, codificación H.264/AAC, RTMP/RTMPS, buffer circular, clips MP4) irá en Kotlin dentro de `android/app/src/main/kotlin/`, conectada a Flutter por un canal de plataforma. No se implementa en la Etapa 1.
- El marcador y el cronómetro no dependerán de internet: su estado vivirá en el teléfono (SQLite) y la transmisión solo lo "dibujará" sobre el video.
- La transmisión simultánea mediante relay externo se podrá añadir después, porque la transmisión se aislará detrás de una interfaz propia.

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
    app_info.dart                Nombre, versión y lema
  shared/widgets/
    feature_placeholder.dart     Pantalla "Próximamente" provisional
  features/
    home/                        Pantalla inicial (identidad y accesos)
    broadcast/                   Transmisión (provisional)
    scoreboard/                  Marcador (provisional)
    match_clock/                 Cronómetro (provisional)
    stats/                       Estadísticas (provisional)
    replays/                     Repeticiones (provisional)
```

## Decisiones de la Etapa 1
- Navegación con el `Navigator` de Flutter y rutas con nombre: no requiere paquetes externos. Si más adelante hace falta algo más avanzado, solo cambia `app_router.dart`.
- Cero dependencias de terceros (solo Flutter y `flutter_lints` para revisar el código): menos puntos de fallo al compilar.
- Tema oscuro único, verde cancha y rojo "en vivo", pensado para usarse al aire libre.
