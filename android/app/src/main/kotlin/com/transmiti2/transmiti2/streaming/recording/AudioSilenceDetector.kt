package com.transmiti2.transmiti2.streaming.recording

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Decisión del detector de silencio (Grupo S, S1).
 */
internal sealed interface SilenceDecision {
    /** Sin disparo: el silencio (si lo hay) no llega al umbral. */
    data object NoFire : SilenceDecision

    /**
     * Disparo: `silenceMs` ms seguidos de ceros exactos (nominal
     * 5000 ms = 5 ventanas de 1 s). `silenceStartOffsetMs` es el
     * offset en ms desde la primera muestra observada (el ancla de
     * audio) hasta la primera muestra silenciosa; `silencePeak` es
     * el pico máximo medido en la ventana silenciosa (esperado 0,
     * como mucho 4 con clics aislados del HAL).
     */
    data class Fire(
        val silenceMs: Long,
        val silenceStartOffsetMs: Long,
        val silencePeak: Int,
    ) : SilenceDecision
}

/**
 * Grupo S, fase S1 (detector): mide el *contenido* de las muestras
 * PCM y dispara cuando hay 5 segundos seguidos de ceros exactos
 * (con una tolerancia mínima para clics aislados del HAL). Es el
 * detector que faltaba en la prueba 6: durante una llamada el lector
 * sigue devolviendo buffers completos, pero las muestras son
 * silencio digital, y los detectores de lecturas vacías
 * (`audio_silence`) no lo ven.
 *
 * Números ajustados tras la medición S0 en el teléfono
 * (plan 3.1.3-grupo-s §5.5): llamada `picoMin1s=0 noCeroMin1s=0`
 * (ceros exactos confirmados); audio real más silencioso
 * `picoMin1s=16 noCeroMin1s=43101`. Un segundo es «silencioso» si
 * `noCero1s <= 48` Y `pico1s <= 4` (margen 4× en pico y ~900× en
 * muestras no cero frente al audio real medido). La pausa del
 * comentarista no puede dispararlo: la grada siempre deja muestras
 * distintas de cero, sin importar cuánto dure la pausa.
 *
 * Definiciones (plan §3, con el ajuste §5.5):
 * - Por lectura (PCM 16 bits, mono): `pico` = máximo `|muestra|`
 *   (cuidado con −32768, cuyo valor absoluto 32768 cabe en `Int`);
 *   `noCero` = muestras `!= 0`.
 * - Ventana = `sampleRate` muestras (48 000 a 48 kHz, 44 100 a
 *   44,1 kHz), así el criterio es independiente de la frecuencia y
 *   de las lecturas parciales. Por ventana: `pico1s` (máximo de sus
 *   lecturas) y `noCero1s` (suma).
 * - Un segundo que no cumple rompe la racha y resetea el contador
 *   a cero.
 *
 * Puro en JVM: no conoce `AudioRecord`, `MediaCodec` ni hilos; solo
 * recibe muestras y devuelve una decisión. El lector lo alimenta en
 * `readLoop()` (misma rama que la medición S0) y, al disparar, el
 * lector notifica `onAudioError` con el motivo nuevo
 * `audio_silence_samples` y sale del bucle (D7). Tras disparar, las
 * observaciones siguientes devuelven `NoFire` hasta `reset()`: un
 * solo disparo por arranque de encoder.
 *
 * Total: `observe()` nunca lanza con entradas malformadas
 * (`size` mayor que el buffer, `size` negativo o impar,
 * `sampleRate <= 0`, buffer vacío) — devuelve `NoFire` y el estado
 * queda intacto. El lector además lo llama dentro de `try/catch`
 * (T-S10): si fallara, se desactiva para ese arranque y el audio
 * sigue como antes de S.
 */
internal class AudioSilenceDetector {
    companion object {
        /** Ventanas silenciosas consecutivas para disparar (5 s). */
        const val SILENT_SECONDS = 5

        /** Máximo de muestras no cero por segundo silencioso. */
        const val MAX_NON_ZERO_PER_SECOND = 48

        /** Pico máximo por segundo silencioso (4 cuentas ≈ −78 dBFS). */
        const val MAX_PEAK = 4
    }

    private var windowSamples = 0
    private var windowPeak = 0
    private var windowNonZero = 0
    private var consecutiveSilentSeconds = 0
    private var silentSamples = 0L
    private var silencePeak = 0
    private var totalSamples = 0L
    private var silentRunStartSample = 0L
    private var fired = false

    // Vista reutilizable sobre el buffer de lectura (sin asignar por
    // lectura, como la medición S0): se reconstruye solo si cambia
    // la identidad del array. PCM 16 bits little-endian.
    private var viewBytes: ByteArray? = null
    private var view: ShortBuffer? = null

    /** Resetea por arranque, como `readCount` y el resto de contadores. */
    fun reset() {
        windowSamples = 0
        windowPeak = 0
        windowNonZero = 0
        consecutiveSilentSeconds = 0
        silentSamples = 0L
        silencePeak = 0
        totalSamples = 0L
        silentRunStartSample = 0L
        fired = false
        viewBytes = null
        view = null
    }

    /**
     * Observa `size` bytes de `bytes` (PCM 16 bits, mono) a
     * `sampleRate` Hz y devuelve la decisión. `size` es en bytes;
     * las muestras son `size / 2` (un byte impar sobrante se
     * ignora). Nunca lanza: con entradas malformadas devuelve
     * `NoFire` sin tocar el estado.
     */
    fun observe(bytes: ByteArray, size: Int, sampleRate: Int): SilenceDecision {
        if (fired) return SilenceDecision.NoFire
        if (size <= 0 || sampleRate <= 0) return SilenceDecision.NoFire
        val samples = minOf(size / 2, bytes.size / 2)
        if (samples <= 0) return SilenceDecision.NoFire
        if (bytes !== viewBytes) {
            viewBytes = bytes
            view = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
        val shorts = view ?: return SilenceDecision.NoFire
        var offset = 0
        while (offset < samples) {
            val take = minOf(samples - offset, sampleRate - windowSamples)
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
            totalSamples += take
            if (windowSamples == sampleRate) {
                val silent = windowNonZero <= MAX_NON_ZERO_PER_SECOND &&
                    windowPeak <= MAX_PEAK
                if (silent) {
                    if (consecutiveSilentSeconds == 0) {
                        silentRunStartSample = totalSamples - sampleRate
                    }
                    consecutiveSilentSeconds++
                    silentSamples += sampleRate
                    if (windowPeak > silencePeak) silencePeak = windowPeak
                    if (consecutiveSilentSeconds >= SILENT_SECONDS) {
                        fired = true
                        return SilenceDecision.Fire(
                            silenceMs = silentSamples * 1000L / sampleRate,
                            silenceStartOffsetMs =
                                silentRunStartSample * 1000L / sampleRate,
                            silencePeak = silencePeak,
                        )
                    }
                } else {
                    consecutiveSilentSeconds = 0
                    silentSamples = 0L
                    silencePeak = 0
                }
                windowSamples = 0
                windowPeak = 0
                windowNonZero = 0
            }
        }
        return SilenceDecision.NoFire
    }
}
