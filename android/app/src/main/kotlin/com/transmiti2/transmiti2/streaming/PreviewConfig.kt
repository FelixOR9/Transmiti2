package com.transmiti2.transmiti2.streaming

/** Tamaño de video en píxeles, independiente de `android.util.Size`. */
data class VideoSize(val width: Int, val height: Int) {
    val area: Int get() = width * height
}

/** Rango de FPS (mínimo y máximo por segundo), sin `android.util.Range`. */
data class FpsRange(val min: Int, val max: Int)

/**
 * Configuración de vista previa elegida para una cámara.
 *
 * El tamaño está en orientación del sensor (sin rotar): para la cámara
 * trasera de la mayoría de teléfonos es apaisado (por ejemplo 1280×720)
 * y la rotación para mostrarlo derecha se calcula aparte
 * (`rotationDegrees` en [PreviewInfo]).
 */
data class PreviewConfig(
    val cameraId: String,
    val size: VideoSize,
    val fpsRange: FpsRange,
    val sensorOrientation: Int,
    val lensFacing: Int,
)

/**
 * Selección pura de la configuración de vista previa (sin Camera2).
 *
 * Escalera de resolución (documentada, determinista):
 * 1. 1280×720 exacto.
 * 2. 640×480 exacto.
 * 3. El mayor tamaño con área ≤ 1280×720.
 * 4. El mayor tamaño disponible.
 *
 * Escalera de FPS (objetivo 30):
 * 1. Rango fijo [30, 30] si existe.
 * 2. Un rango que contenga 30, con el mínimo más alto (el más
 *    ajustado, menos fluctuación).
 * 3. El rango con el máximo más alto (el FPS más cercano a 30 por
 *    debajo).
 * 4. Si no hay rangos, [30, 30] como objetivo y no se fija el rango en
 *    la petición (la cámara decide); el `fps` informado es el objetivo,
 *    no una medición.
 *
 * Nunca se fuerza una resolución o frecuencia que el dispositivo no
 * soporte: todo se elige de lo que `StreamConfigurationMap` y
 * `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` reportan. La cámara frontal
 * queda diferida en la 3.1.1 (solo trasera); por eso la selección no
 * recibe cámaras frontales.
 */
object PreviewConfigSelector {
    const val TARGET_WIDTH = 1280
    const val TARGET_HEIGHT = 720
    const val TARGET_FPS = 30
    const val FALLBACK_WIDTH = 640
    const val FALLBACK_HEIGHT = 480

    fun select(
        cameraId: String,
        sensorOrientation: Int,
        lensFacing: Int,
        outputSizes: List<VideoSize>,
        fpsRanges: List<FpsRange>,
    ): PreviewConfig? {
        val size = selectSize(outputSizes) ?: return null
        val fpsRange = selectFpsRange(fpsRanges, TARGET_FPS)
        return PreviewConfig(
            cameraId = cameraId,
            size = size,
            fpsRange = fpsRange,
            sensorOrientation = sensorOrientation,
            lensFacing = lensFacing,
        )
    }

    fun selectSize(sizes: List<VideoSize>): VideoSize? {
        if (sizes.isEmpty()) {
            return null
        }
        sizes.firstOrNull {
            it.width == TARGET_WIDTH && it.height == TARGET_HEIGHT
        }?.let { return it }
        sizes.firstOrNull {
            it.width == FALLBACK_WIDTH && it.height == FALLBACK_HEIGHT
        }?.let { return it }
        val maxArea = TARGET_WIDTH * TARGET_HEIGHT
        sizes.filter { it.area <= maxArea }
            .maxByOrNull { it.area }
            ?.let { return it }
        return sizes.maxByOrNull { it.area }
    }

    fun selectFpsRange(
        ranges: List<FpsRange>,
        targetFps: Int = TARGET_FPS,
    ): FpsRange {
        if (ranges.isEmpty()) {
            return FpsRange(targetFps, targetFps)
        }
        ranges.firstOrNull { it.min == targetFps && it.max == targetFps }
            ?.let { return it }
        val containing = ranges.filter {
            it.min <= targetFps && it.max >= targetFps
        }
        if (containing.isNotEmpty()) {
            return containing.maxByOrNull { it.min }!!
        }
        return ranges.maxByOrNull { it.max }!!
    }
}
