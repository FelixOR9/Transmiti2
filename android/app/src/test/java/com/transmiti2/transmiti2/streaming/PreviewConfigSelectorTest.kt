package com.transmiti2.transmiti2.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewConfigSelectorTest {

    private fun select(
        sizes: List<VideoSize>,
        ranges: List<FpsRange> = listOf(FpsRange(30, 30)),
    ): PreviewConfig? {
        return PreviewConfigSelector.select(
            cameraId = "0",
            sensorOrientation = 90,
            lensFacing = 0,
            outputSizes = sizes,
            fpsRanges = ranges,
        )
    }

    @Test
    fun `720p30 disponible es la primera opcion`() {
        val config = select(
            sizes = listOf(
                VideoSize(1920, 1080),
                VideoSize(1280, 720),
                VideoSize(640, 480),
            ),
            ranges = listOf(FpsRange(15, 30), FpsRange(30, 30)),
        )!!

        assertEquals(VideoSize(1280, 720), config.size)
        assertEquals(FpsRange(30, 30), config.fpsRange)
        assertEquals(90, config.sensorOrientation)
        assertEquals("0", config.cameraId)
    }

    @Test
    fun `sin 720p se elige 480p30`() {
        val config = select(
            sizes = listOf(VideoSize(1920, 1080), VideoSize(640, 480)),
        )!!

        assertEquals(VideoSize(640, 480), config.size)
        assertEquals(FpsRange(30, 30), config.fpsRange)
    }

    @Test
    fun `sin 720p ni 480p se elige el mayor tamano hasta 720p`() {
        val config = select(
            sizes = listOf(VideoSize(1920, 1080), VideoSize(800, 600)),
        )!!

        assertEquals(VideoSize(800, 600), config.size)
    }

    @Test
    fun `si solo hay tamaños mayores se elige el mayor disponible`() {
        val config = select(
            sizes = listOf(VideoSize(1920, 1080), VideoSize(3840, 2160)),
        )!!

        assertEquals(VideoSize(3840, 2160), config.size)
    }

    @Test
    fun `sin tamaños no hay configuracion`() {
        assertNull(select(sizes = emptyList()))
    }

    @Test
    fun `rango variable que contiene 30 se elige si no hay fijo`() {
        val range = PreviewConfigSelector.selectFpsRange(
            listOf(FpsRange(15, 30), FpsRange(15, 15)),
        )

        assertEquals(FpsRange(15, 30), range)
    }

    @Test
    fun `entre rangos que contienen 30 se elige el mas ajustado`() {
        val range = PreviewConfigSelector.selectFpsRange(
            listOf(FpsRange(15, 30), FpsRange(24, 30)),
        )

        assertEquals(FpsRange(24, 30), range)
    }

    @Test
    fun `sin rango que llegue a 30 se elige el maximo mas alto`() {
        val range = PreviewConfigSelector.selectFpsRange(
            listOf(FpsRange(15, 15), FpsRange(15, 24)),
        )

        assertEquals(FpsRange(15, 24), range)
    }

    @Test
    fun `sin rangos se usa 30 30 como objetivo`() {
        val range = PreviewConfigSelector.selectFpsRange(emptyList())

        assertEquals(FpsRange(30, 30), range)
    }
}
