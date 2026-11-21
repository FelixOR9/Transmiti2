package com.transmiti2.transmiti2.streaming.recording

import java.nio.ShortBuffer

/**
 * Grupo S, fase S0 (instrumentación solo, sin cambio de comportamiento):
 * mide por lectura PCM el pico y el número de muestras distintas de
 * cero, agrega por segundo y expone el segundo más silencioso de la
 * grabación. S1 (detector) reutilizará esta medición.
 *
 * Definiciones (plan 3.1.3-grupo-s §3):
 * - Por lectura (PCM 16 bits, mono): `pico` = máximo `|muestra|`
 *   (cuidado con −32768, cuyo valor absoluto 32768 cabe en `Int`);
 *   `noCero` = muestras `!= 0`.
 * - Ventana = `sampleRate` muestras (48 000 a 48 kHz, 44 100 a
 *   44,1 kHz), así el criterio es independiente de la frecuencia y
 *   de las lecturas parciales. Por ventana: `pico1s` (máximo de sus
 *   lecturas) y `noCero1s` (suma).
 * - El segundo más silencioso es la ventana completa con menos
 *   `noCero1s`; a igualdad, la de menor `pico1s`. Solo cuentan las
 *   ventanas completas: una ventana parcial al parar no es un
 *   segundo y no se compara (sus muestras serían menos por
 *   construcción). Sin ninguna ventana completa, los valores son
 *   `null` — nunca `0`, porque `0` significaría silencio digital.
 *
 * Puro en JVM: no conoce `AudioRecord`, `MediaCodec` ni hilos; solo
 * recibe muestras. El lector lo alimenta en `readLoop()` y la sesión
 * lee los valores al parar, como los demás contadores del puerto.
 */
internal class AudioLevelTracker {
    private var windowSamples = 0
    private var windowPeak = 0
    private var windowNonZero = 0

    @Volatile
    private var bestPeak: Int? = null

    @Volatile
    private var bestNonZero: Int? = null

    /** Pico del segundo más silencioso (`null` sin ventana completa). */
    val quietestSecondPeak: Int?
        get() = bestPeak

    /** Muestras no cero del segundo más silencioso (`null` sin ventana). */
    val quietestSecondNonZero: Int?
        get() = bestNonZero

    /** Resetea por arranque, como `readCount` y el resto de contadores. */
    fun reset() {
        windowSamples = 0
        windowPeak = 0
        windowNonZero = 0
        bestPeak = null
        bestNonZero = null
    }

    /**
     * Observa `count` muestras de `shorts` (índices absolutos
     * `0..count-1`; `count <= shorts.capacity()`). `sampleRate <= 0`
     * o `count <= 0` no hacen nada: nunca debe bloquear ni lanzar
     * en el hilo lector.
     */
    fun observe(shorts: ShortBuffer, count: Int, sampleRate: Int) {
        if (count <= 0 || sampleRate <= 0) return
        var offset = 0
        while (offset < count) {
            val take = minOf(count - offset, sampleRate - windowSamples)
            var j = 0
            while (j < take) {
                val value = shorts[offset + j].toInt()
                val abs = if (value < 0) -value else value
                if (abs > windowPeak) windowPeak = abs
                if (value != 0) windowNonZero++
                j++
            }
            offset += take
            windowSamples += take
            if (windowSamples == sampleRate) {
                val currentBestNonZero = bestNonZero
                if (currentBestNonZero == null ||
                    windowNonZero < currentBestNonZero ||
                    (windowNonZero == currentBestNonZero &&
                        windowPeak < (bestPeak ?: Int.MAX_VALUE))
                ) {
                    bestPeak = windowPeak
                    bestNonZero = windowNonZero
                }
                windowSamples = 0
                windowPeak = 0
                windowNonZero = 0
            }
        }
    }
}
