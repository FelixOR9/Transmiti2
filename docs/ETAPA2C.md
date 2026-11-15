# Etapa 2C - Control del partido en vivo

Esta entrega pone la interfaz sobre los motores de la 2A (marcador, cronómetro y eventos guardados en SQLite) y los datos de la 2B (equipos, escudos, jugadores y patrocinadores). No se reconstruyó ningún motor: todas las pantallas leen y escriben a través de `MatchSession`.

## Qué incluye
| Módulo | Pantalla / archivo principal | Qué hace |
|---|---|---|
| A. Partido en vivo | `LiveMatchScreen` (Inicio: MARCADOR y CRONÓMETRO; pestaña Partido: PARTIDO EN VIVO) | Escudos, nombres, marcador, cronómetro, tiempo actual y estado; acceso al historial |
| B. Marcador | `GoalDialog`, `GoalList` | Gol por equipo con minuto (sugerido por el cronómetro, editable) y jugador opcional; corregir y anular goles |
| C. Cronómetro | `ControlBar`, `ClockCard`, `LiveClock` | Iniciar, pausar, reanudar, finalizar el primer tiempo, descanso, segundo tiempo y finalizar partido |
| D. Historial | `EventsHistoryScreen` | Cronología de goles, tiempos, descanso, final y correcciones, leída de SQLite |
| E. Composición gráfica | `BroadcastOverlay`, `GraphicsPreviewScreen` | Vista 16:9 con escudos, nombres, marcador, cronómetro, competición y patrocinadores en franja inferior, lateral y zona principal |
| F. Seguridad | `ActionGuard`, `MatchSession`, repositorios SQLite | Doble toque, confirmaciones, guardado inmediato y transacciones |
| G. Estadísticas | `StatsScreen`, `MatchStats` | Goles por equipo y por jugador, conteo de eventos y resultado, solo con lo registrado |

## Decisiones de diseño
- **Una sola pantalla de control.** `MARCADOR` y `CRONÓMETRO` del inicio abren la misma pantalla de partido en vivo (cambia solo qué tarjeta aparece primero), así no hay dos formas distintas de modificar el mismo partido.
- **Una mano.** Los controles de uso constante van en una barra fija inferior: un botón de gol por equipo (con el color del equipo) y la acción principal del cronómetro. Las acciones raras o destructivas (finalizar el primer tiempo, finalizar el partido) están en la tarjeta del cronómetro y piden confirmación.
- **Descanso.** El motor trata el descanso como una fase: FINALIZAR PRIMER TIEMPO **inicia el descanso** (que se cuenta contra la duración configurada) e INICIAR SEGUNDO TIEMPO **termina el descanso**. Por eso no hay botones separados de "iniciar/finalizar descanso".
- **Goles.** Los botones de gol se activan en el primer tiempo, el descanso y el segundo tiempo. Al terminar el partido ya no se agregan goles, pero sí se pueden corregir o anular.
- **Correcciones.** Corregir o anular un gol conserva el evento original, lo marca y agrega un evento de corrección al historial; el marcador se recalcula desde los goles no anulados. Limitación: el historial conserva que hubo una corrección y los datos actuales del gol; no guarda los valores anteriores.
- **Marcador y reloj sin duplicar datos.** El marcador se calcula con los goles del historial; el reloj con marcas de tiempo (ver abajo).

## Cómo funciona el cronómetro
- Se guarda la hora de la última reanudación y el tiempo acumulado antes de ella. El tiempo mostrado siempre es `acumulado + (ahora - última reanudación)`.
- `LiveClock` solo repinta (cada 250 ms mientras el reloj corre); nunca suma segundos. Al volver a la app (por ejemplo tras bloquear el teléfono) se repinta de inmediato con la hora real.
- En pausa, en descanso y al terminar el partido el tiempo no avanza.
- Cada cambio del cronómetro (con su evento y el estado del partido) se guarda en una sola transacción SQLite antes de mostrarse.
- Límite conocido: depende de la hora del teléfono; si se cambia a mano durante un partido, el tiempo se afectará.

## Protección contra errores y pérdida de datos
- **Doble toque:** `ActionGuard` ignora toques mientras una acción sigue en curso y durante 700 ms después; además el botón REGISTRAR GOL se bloquea mientras guarda.
- **Confirmaciones:** finalizar el primer tiempo, finalizar el partido y anular un gol.
- **Guardado inmediato y atómico:** primero se escribe en SQLite (en transacción cuando hay varias escrituras) y solo después se actualiza la pantalla. Si el guardado falla, la pantalla, el marcador y el historial quedan como estaban y se avisa al usuario (probado con un repositorio que falla a propósito).
- **Recuperación:** al abrir la app se recupera el partido, el reloj, el marcador y el historial. Hay una sola sesión de partido en toda la app; preparar el partido otra vez edita el mismo en lugar de crear otro.
- **Sin internet:** ninguna función usa la red (el código no importa nada de red y la versión de producción no pide el permiso de internet; ver `test/offline_test.dart`).

## Composición gráfica 16:9
- Diseñada sobre un lienzo de 1280 x 720 y escalada con el ancho disponible; el fondo es transparente (solo se dibujan los elementos).
- Elementos: marcador con escudos y colores de equipo, cronómetro con tiempo añadido y periodo, nombre de la competición y patrocinadores.
- Zonas de patrocinadores: **principal** (el primero elegido), **franja inferior** y **lateral** (los demás, en el orden elegido; si la zona principal está apagada, entran todos). No hay límite de patrocinadores: las zonas ajustan su tamaño.
- Los datos salen de `buildOverlayData(session)`; cualquier cambio del marcador o del reloj se refleja de inmediato.
- Todavía **no** se integra con la señal de video: eso corresponde a la etapa de transmisión.

## Dependencias
No se agregó ninguna dependencia nueva en la 2C.

## Pruebas
| Verificación solicitada | Dónde se prueba |
|---|---|
| 1-2. Abrir el partido, escudos y nombres | `live_flow_test` (grupo "1 y 2") |
| 3. Iniciar el cronómetro | `live_flow_test` ("iniciar el partido..."), `match_clock_test` |
| 4. Pausar y reanudar | `live_flow_test` ("pausar congela..."), `match_clock_test` |
| 5. Paso del tiempo | `live_flow_test` (repintado periódico, tiempo añadido), `match_clock_test`, `match_session_test` |
| 6. Bloquear el teléfono | `live_flow_test` ("al bloquear el teléfono..."), `match_session_test` |
| 7. Goles de ambos equipos | `live_flow_test` (grupo "7 y 8") |
| 8. Corregir un gol | `live_flow_test` ("corregir..." y "anular..."), `match_session_test` |
| 9. Historial | `live_flow_test` (grupo "9") |
| 10-12. Primer tiempo, segundo tiempo y final | `live_flow_test` (grupo "10 al 12") |
| 13. Estadísticas | `live_flow_test` (grupo "13"), `match_stats_test` |
| 14. Persistencia | `live_flow_test` (grupo "14 y 15"), `sqlite_repositories_test`, `match_session_test` |
| 15. Sin internet | `offline_test` |
| 16. Patrocinadores en la composición | `live_flow_test` (grupo "16"), `overlay_data_test` |
| Doble toque y fallos de guardado | `action_guard_test`, `session_failure_test`, `live_flow_test` |

## Pendiente para etapas posteriores
- Integrar la composición gráfica con la señal de video y la transmisión (RTMP/RTMPS), cámara, grabación y repeticiones reales.
- Guardar los valores anteriores de un gol corregido.
- Tarjetas, sustituciones y otras estadísticas (la estructura de eventos y `MatchStats` ya está preparada para sumarlas).
