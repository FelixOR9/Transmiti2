# Etapa 2 - Partido, jugadores, patrocinadores, marcador y cronómetro

La Etapa 2 se construye en tres entregas, cada una verificada por GitHub Actions antes de seguir:

| Entrega | Contenido | Estado |
|---|---|---|
| **2A - Base** | Base de datos SQLite con migraciones, modelos, almacén de imágenes, motor del cronómetro, marcador e historial, sesión del partido, pruebas | Entregada |
| **2B - Preparación** | Formulario de preparación del partido, escudos y colores, jugadores, patrocinadores y logotipos | Pendiente |
| **2C - Juego** | Pantallas de marcador, cronómetro e historial, y composición gráfica 16:9 | Pendiente |

## Qué incluye la entrega 2A
- **Base de datos SQLite local** (sin internet) con migraciones por versión.
- **Almacén de imágenes**: valida PNG/JPG/JPEG por su contenido, copia el archivo a la carpeta propia de la app y devuelve una ruta relativa.
- **Cronómetro**: mide el tiempo con marcas de la hora del teléfono, no con temporizadores. Pausar, reanudar, primer tiempo, descanso, segundo tiempo y final. Se recupera tras cerrar la app.
- **Marcador e historial**: goles con minuto y jugador, corrección y anulación de goles, eventos de inicio y fin de cada tiempo y del partido.
- **Sesión del partido** (`MatchSession`): única puerta para cambiar el partido; guarda antes de actualizar la pantalla.
- **Inicio**: la tarjeta de próximo partido ya lee el partido guardado (el diseño no cambió).
- **Pruebas**: cronómetro, minutos, reglas de validación, marcador, sesión completa, almacén de imágenes, repositorios SQLite reales (incluida la recuperación tras cerrar y abrir) y pantalla de inicio.

## Dependencias agregadas
| Paquete | Para qué |
|---|---|
| `sqflite` | SQLite en Android |
| `path_provider` | Carpeta privada de la app para las imágenes |
| `path` | Unir rutas y evitar salirse de la carpeta de imágenes |
| `sqflite_common_ffi` (solo pruebas) | Probar la base de datos real en el equipo de pruebas |

En la 2B se agregará `image_picker` (galería sin pedir permisos en Android).

## Estructura de la base de datos (versión 1)
| Tabla | Contenido |
|---|---|
| `teams` | Equipos: nombre, clave única del nombre, color, ruta del escudo |
| `players` | Jugadores de un equipo; el número es único dentro del equipo |
| `sponsors` | Patrocinadores: nombre y ruta del logotipo |
| `matches` | Partidos: equipos, torneo, fecha, duración de cada tiempo y del descanso, estado |
| `match_sponsors` | Patrocinadores elegidos para cada partido y su orden |
| `match_clock` | Estado del cronómetro de cada partido (fase, tiempo acumulado, última reanudación) |
| `match_events` | Historial: goles, correcciones, inicio y fin de tiempos y del partido |

- El marcador no se duplica: se calcula con los goles no anulados de `match_events`.
- Al borrar un partido se borran su cronómetro, eventos y patrocinadores elegidos. Al borrar un jugador sus goles se conservan sin jugador. Al borrar un patrocinador se quita de los partidos.
- Las imágenes **no** se guardan en la base: solo su ruta relativa y su tipo.

## Ubicación y administración de imágenes
- Carpeta: `<documentos de la app>/images/crests` (escudos) y `images/logos` (logotipos).
- Es una carpeta privada de la app: sobrevive a cerrar la app y no depende de la galería.
- Cada imagen recibe un nombre único; al reemplazar o eliminar se borra el archivo anterior.
- Se admiten PNG, JPG y JPEG, de hasta 8 MB; el formato se comprueba por el contenido del archivo, no por su nombre.
- Si Android o el usuario borran el archivo, la app lo detecta y muestra el equipo o patrocinador sin imagen.

## Cómo funciona el cronómetro
- Guarda la hora de la última reanudación y el tiempo acumulado antes de ella. El tiempo mostrado siempre es `acumulado + (ahora - última reanudación)`.
- Por eso no se pierde tiempo si la pantalla se apaga, si Flutter reconstruye la interfaz o si la app se cierra: al abrirla se lee el estado guardado y el tiempo sigue correcto.
- Límite conocido: depende de la hora del teléfono; si el usuario la cambia manualmente durante un partido, el tiempo se verá afectado.
