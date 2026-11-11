# Etapa 1 - Base del proyecto y pantalla principal

## Qué incluye
- Proyecto Flutter para Android (la carpeta `android/` la genera el flujo de compilación la primera vez).
- Pantalla principal con identidad TRANSMITI2 y accesos a: Iniciar transmisión, Marcador, Cronómetro, Estadísticas y Repeticiones.
- Navegación inicial: cada acceso abre una pantalla provisional con botón para volver.
- Pruebas automáticas: la pantalla principal y la navegación a las cinco pantallas.

## Qué NO incluye (etapas posteriores)
Marcador, cronómetro, estadísticas, video, transmisión, grabación, repeticiones y SQLite.

## Cómo obtener el APK (desde el teléfono)
1. En el repositorio, pestaña **Actions** -> flujo **TRANSMITI2 - Compilar APK de depuración** -> **Run workflow**.
2. Cuando termine con marca verde, abre la sección **Releases** del repositorio y descarga `transmiti2-debug.apk`.
3. Ábrelo en el teléfono y permite instalar apps de esta fuente si Android lo pide.
