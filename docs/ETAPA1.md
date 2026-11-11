# Etapa 1 - Base del proyecto y pantalla de inicio

## Qué incluye
- Proyecto Flutter para Android (la carpeta `android/` la genera el flujo de compilación la primera vez).
- Pantalla de inicio con identidad TRANSMITI2, tarjeta de próximo partido (PREPARAR PARTIDO y ABRIR ESTUDIO DE TRANSMISIÓN), accesos a Marcador, Cronómetro, Estadísticas y Repeticiones, y franja de estado de cámara, micrófono y red.
- Barra inferior con cuatro pestañas: Inicio, Partido, Repeticiones y Más.
- Navegación inicial: cada acceso abre una pantalla provisional con botón para volver.
- Pruebas automáticas: la pantalla de inicio, la barra inferior y la navegación a cada pantalla.

## Qué NO incluye (etapas posteriores)
Configuración real del partido, marcador, cronómetro, estadísticas, video, transmisión, grabación, repeticiones y SQLite. Tampoco la comprobación real de cámara, micrófono y red.

## Cómo obtener el APK (desde el teléfono)
1. En el repositorio, pestaña **Actions** -> flujo **TRANSMITI2 - Compilar APK de depuración** -> **Run workflow**.
2. Cuando termine con marca verde, abre la sección **Releases** del repositorio y descarga `transmiti2-debug.apk`.
3. Ábrelo en el teléfono y permite instalar apps de esta fuente si Android lo pide.
