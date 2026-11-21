package com.transmiti2.transmiti2.streaming.recording

import java.nio.ShortBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Grupo S, fase S0: agregación por segundo del nivel PCM
 * (`AudioLevelTracker`, puro en JVM, sin `AudioRecord`).
 *
 * T-M1: ventana de 48 000 muestras con picos exactos; ventana que
 * cruza lecturas parciales (1536 + 3072 bytes) → el conteo es por
 * muestras. T-M2: a 44,1 kHz la ventana es 44 100 muestras.
 * T-M3: el mínimo por grabación se actualiza solo con el segundo
 * más silencioso y sobrevive a segundos con sonido.
 */
class AudioLevelTrackerTest {

    private fun bufferOf(values: ShortArray): ShortBuffer =
        ShortBuffer.wrap(values)

    /** Alimenta `samples` en trozos de los tamaños dados (el último
     * se ajusta al resto); cada trozo es una vista nueva. */
    private fun feedInChunks(
        tracker: AudioLevelTracker,
        samples: ShortArray,
        chunkSizes: List<Int>,
        sampleRate: Int,
    ) {
        var offset = 0
        for (size in chunkSizes) {
            if (offset >= samples.size) break
            val take = minOf(size, samples.size - offset)
            tracker.observe(
                bufferOf(samples.copyOfRange(offset, offset + take)),
                take,
                sampleRate,
            )
            offset += take
        }
        check(offset == samples.size) {
            "los trozos no cubren las muestras: $offset de ${samples.size}"
        }
    }

    @Test
    fun `T-M1 agregacion por segundo a 48 kHz con lecturas parciales`() {
        // Sin ninguna ventana completa, no hay mínimo (nunca 0).
        // (Tracker aparte: sus muestras no deben contaminar la
        // ventana del tracker principal.)
        val empty = AudioLevelTracker()
        empty.observe(bufferOf(ShortArray(1000) { 100 }), 1000, 48_000)
        assertNull(empty.quietestSecondPeak)
        assertNull(empty.quietestSecondNonZero)

        val tracker = AudioLevelTracker()

        // Ventana de 48 000 muestras: primera muestra −32768, resto
        // de la primera mitad a 1000, segunda mitad a 0 → pico
        // 32768, no-cero 24 000. Alimentada en lecturas parciales
        // (768 + 1536 muestras = 1536 + 3072 bytes) y luego de
        // 2048 en 2048 hasta completar.
        val window = ShortArray(48_000) { i ->
            when {
                i == 0 -> (-32768).toShort()
                i < 24_000 -> 1000
                else -> 0
            }
        }
        val chunks = mutableListOf(768, 1536)
        var remaining = 48_000 - 768 - 1536
        while (remaining > 0) {
            val take = minOf(2048, remaining)
            chunks += take
            remaining -= take
        }
        feedInChunks(tracker, window, chunks, 48_000)
        assertEquals(32768, tracker.quietestSecondPeak)
        assertEquals(24_000, tracker.quietestSecondNonZero)

        // Segunda ventana, todo ceros → el mínimo baja a (0, 0).
        tracker.observe(bufferOf(ShortArray(48_000)), 48_000, 48_000)
        assertEquals(0, tracker.quietestSecondPeak)
        assertEquals(0, tracker.quietestSecondNonZero)
    }

    @Test
    fun `T-M2 a 44 coma 1 kHz la ventana es 44 100 muestras`() {
        val tracker = AudioLevelTracker()
        // Primera ventana de 44 100: mitad a 500, mitad a 0.
        val window = ShortArray(44_100) { i ->
            if (i < 22_050) 500 else 0
        }
        tracker.observe(bufferOf(window), window.size, 44_100)
        assertEquals(500, tracker.quietestSecondPeak)
        assertEquals(22_050, tracker.quietestSecondNonZero)

        // 48 000 muestras más de ceros: completan una ventana
        // (44 100) y dejan 3900 en una ventana parcial, que no
        // cuenta para el mínimo. El mínimo baja a (0, 0) por la
        // ventana completa de ceros.
        tracker.observe(bufferOf(ShortArray(48_000)), 48_000, 44_100)
        assertEquals(0, tracker.quietestSecondPeak)
        assertEquals(0, tracker.quietestSecondNonZero)
    }

    @Test
    fun `T-M3 el minimo solo baja con el segundo mas silencioso`() {
        val tracker = AudioLevelTracker()
        // Ventana ruidosa: todo a 30 000.
        tracker.observe(bufferOf(ShortArray(48_000) { 30_000 }), 48_000, 48_000)
        assertEquals(30_000, tracker.quietestSecondPeak)
        assertEquals(48_000, tracker.quietestSecondNonZero)

        // Ventana silenciosa: todo ceros → el mínimo baja.
        tracker.observe(bufferOf(ShortArray(48_000)), 48_000, 48_000)
        assertEquals(0, tracker.quietestSecondPeak)
        assertEquals(0, tracker.quietestSecondNonZero)

        // Otra ventana ruidosa: el mínimo sobrevive.
        tracker.observe(bufferOf(ShortArray(48_000) { 20_000 }), 48_000, 48_000)
        assertEquals(0, tracker.quietestSecondPeak)
        assertEquals(0, tracker.quietestSecondNonZero)

        // Desempate por pico: mismo no-cero (100), menor pico gana.
        val tracker2 = AudioLevelTracker()
        val a = ShortArray(48_000) { i -> if (i < 100) 50 else 0 }
        tracker2.observe(bufferOf(a), a.size, 48_000)
        assertEquals(50, tracker2.quietestSecondPeak)
        assertEquals(100, tracker2.quietestSecondNonZero)
        val b = ShortArray(48_000) { i -> if (i < 100) 20 else 0 }
        tracker2.observe(bufferOf(b), b.size, 48_000)
        assertEquals(20, tracker2.quietestSecondPeak)
        assertEquals(100, tracker2.quietestSecondNonZero)
        val c = ShortArray(48_000) { i -> if (i < 100) 90 else 0 }
        tracker2.observe(bufferOf(c), c.size, 48_000)
        assertEquals(20, tracker2.quietestSecondPeak)
        assertEquals(100, tracker2.quietestSecondNonZero)

        // Reset: vuelve a no haber mínimo.
        tracker2.reset()
        assertNull(tracker2.quietestSecondPeak)
        assertNull(tracker2.quietestSecondNonZero)
    }

    @Test
    fun `observe sin muestras o sin frecuencia no hace nada ni se cuelga`() {
        val tracker = AudioLevelTracker()
        tracker.observe(bufferOf(ShortArray(100) { 100 }), 0, 48_000)
        tracker.observe(bufferOf(ShortArray(100) { 100 }), 100, 0)
        tracker.observe(bufferOf(ShortArray(100) { 100 }), 100, -1)
        assertNull(tracker.quietestSecondPeak)
        assertNull(tracker.quietestSecondNonZero)
    }
}
