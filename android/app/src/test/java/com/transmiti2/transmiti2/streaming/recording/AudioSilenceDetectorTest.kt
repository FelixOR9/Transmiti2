package com.transmiti2.transmiti2.streaming.recording

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detector de silencio en las muestras (Grupo S, S1): ceros exactos
 * 5 s a 48 kHz o 44,1 kHz, con tolerancia `noCero1s <= 48` y
 * `pico1s <= 4` (números ajustados §5.5). Lógica pura y sintética,
 * sin `AudioRecord`: el cableado en `readLoop()` se prueba en
 * `RecordingSessionTest` (T-S11/T-S12) y en el teléfono.
 */
class AudioSilenceDetectorTest {

    private fun shortsToBytes(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(samples)
        return buffer.array()
    }

    private fun zerosBytes(samples: Int): ByteArray = ByteArray(samples * 2)

    /**
     * Alimenta `bytes` en trozos de `chunkBytes` y devuelve el primer
     * `Fire`, o `null` si no hubo disparo. Cada trozo es un array
     * nuevo: `observe()` lee desde el índice 0 del array que recibe.
     */
    private fun feedFirstFire(
        detector: AudioSilenceDetector,
        bytes: ByteArray,
        chunkBytes: Int,
        sampleRate: Int,
    ): SilenceDecision.Fire? {
        var offset = 0
        while (offset < bytes.size) {
            val take = minOf(chunkBytes, bytes.size - offset)
            val decision = detector.observe(
                bytes.copyOfRange(offset, offset + take),
                take,
                sampleRate,
            )
            if (decision is SilenceDecision.Fire) return decision
            offset += take
        }
        return null
    }

    private fun feedNoFire(
        detector: AudioSilenceDetector,
        bytes: ByteArray,
        chunkBytes: Int,
        sampleRate: Int,
    ) {
        var offset = 0
        while (offset < bytes.size) {
            val take = minOf(chunkBytes, bytes.size - offset)
            val decision = detector.observe(
                bytes.copyOfRange(offset, offset + take),
                take,
                sampleRate,
            )
            assertTrue(
                "no debe disparar (fue $decision)",
                decision is SilenceDecision.NoFire,
            )
            offset += take
        }
    }

    @Test
    fun `T-S1 ceros exactos 5 s a 48 kHz disparan con offset 0 y pico 0`() {
        val detector = AudioSilenceDetector()
        // 4,9 s no disparan.
        feedNoFire(detector, zerosBytes(235_200), 4096, 48_000)
        // Los 0,1 s restantes completan la 5ª ventana: dispara.
        val fire = feedFirstFire(detector, zerosBytes(4_800), 4096, 48_000)
        assertTrue("debe disparar a los 5 s", fire != null)
        assertEquals(5_000L, fire!!.silenceMs)
        assertEquals(0L, fire.silenceStartOffsetMs)
        assertEquals(0, fire.silencePeak)
    }

    @Test
    fun `T-S2 ceros 4,9 s y luego sonido no disparan y la racha se resetea`() {
        val detector = AudioSilenceDetector()
        feedNoFire(detector, zerosBytes(235_200), 4096, 48_000)
        // 0,5 s de sonido: rompe la racha (la ventana 5ª mezcla
        // ceros y sonido y la 6ª también: 6 s de ceros después son
        // los que completan 5 ventanas silenciosas seguidas).
        val sound = ShortArray(24_000) { 100 }
        feedNoFire(detector, shortsToBytes(sound), 4096, 48_000)
        // El segundo episodio silencioso empieza en la ventana 7ª
        // (muestra 288 000 = 6000 ms).
        val fire = feedFirstFire(detector, zerosBytes(288_000), 4096, 48_000)
        assertTrue("debe disparar tras el segundo episodio", fire != null)
        assertEquals(5_000L, fire!!.silenceMs)
        assertEquals(6_000L, fire.silenceStartOffsetMs)
        assertEquals(0, fire.silencePeak)
    }

    @Test
    fun `T-S3 pausa de comentarista 10 s de ruido bajo real no dispara`() {
        val detector = AudioSilenceDetector()
        val random = Random(42)
        val noise = ShortArray(480_000) {
            val value = random.nextInt(-1000, 1001)
            if (value == 0) 1 else value.toShort()
        }
        feedNoFire(detector, shortsToBytes(noise), 4096, 48_000)
    }

    @Test
    fun `T-S4 audio real silencioso 15 s con pico 100 no dispara`() {
        val detector = AudioSilenceDetector()
        val quiet = ShortArray(720_000) { if (it % 2 == 0) 100 else -100 }
        feedNoFire(detector, shortsToBytes(quiet), 4096, 48_000)
    }

    @Test
    fun `T-S5 ceros con un clic aislado por segundo disparan con pico del clic`() {
        val detector = AudioSilenceDetector()
        val samples = ShortArray(240_000)
        for (second in 0 until 5) {
            samples[second * 48_000 + 1_000] = 3
            samples[second * 48_000 + 2_000] = -3
        }
        val fire = feedFirstFire(
            detector,
            shortsToBytes(samples),
            4096,
            48_000,
        )
        assertTrue("los clics aislados no deben impedir el disparo", fire != null)
        assertEquals(5_000L, fire!!.silenceMs)
        assertEquals(0L, fire.silenceStartOffsetMs)
        assertEquals(3, fire.silencePeak)
    }

    @Test
    fun `T-S6 a 44,1 kHz 5 s son 220500 muestras y disparan en el mismo punto temporal`() {
        val detector = AudioSilenceDetector()
        feedNoFire(detector, zerosBytes(215_600), 4096, 44_100)
        val fire = feedFirstFire(detector, zerosBytes(4_900), 4096, 44_100)
        assertTrue("debe disparar a los 5 s a 44,1 kHz", fire != null)
        assertEquals(5_000L, fire!!.silenceMs)
        assertEquals(0L, fire.silenceStartOffsetMs)
        assertEquals(0, fire.silencePeak)
    }

    @Test
    fun `T-S7 lecturas parciales mezcladas cuentan por muestras exactas`() {
        val detector = AudioSilenceDetector()
        val bytes = zerosBytes(240_000)
        var offset = 0
        var useSmall = true
        var fire: SilenceDecision.Fire? = null
        while (offset < bytes.size && fire == null) {
            val take = minOf(if (useSmall) 1536 else 3072, bytes.size - offset)
            val decision = detector.observe(
                bytes.copyOfRange(offset, offset + take),
                take,
                48_000,
            )
            if (decision is SilenceDecision.Fire) fire = decision
            offset += take
            useSmall = !useSmall
        }
        assertTrue("debe disparar con lecturas parciales", fire != null)
        assertEquals(5_000L, fire!!.silenceMs)
        assertEquals(0L, fire.silenceStartOffsetMs)
        assertEquals(0, fire.silencePeak)
    }

    @Test
    fun `T-S8 ceros al arrancar 5 s disparan y ceros 1 s con sonido no`() {
        val detector = AudioSilenceDetector()
        val fire = feedFirstFire(detector, zerosBytes(240_000), 4096, 48_000)
        assertTrue("debe disparar al arrancar en silencio", fire != null)
        assertEquals(0L, fire!!.silenceStartOffsetMs)

        val detector2 = AudioSilenceDetector()
        feedNoFire(detector2, zerosBytes(48_000), 4096, 48_000)
        val sound = ShortArray(48_000) { 500 }
        feedNoFire(detector2, shortsToBytes(sound), 4096, 48_000)
    }

    @Test
    fun `T-S9 tras disparar solo reset permite un segundo episodio`() {
        val detector = AudioSilenceDetector()
        val fire1 = feedFirstFire(detector, zerosBytes(240_000), 4096, 48_000)
        assertTrue("primer episodio debe disparar", fire1 != null)
        // Tras disparar, más ceros no vuelven a disparar.
        assertNull(feedFirstFire(detector, zerosBytes(48_000), 4096, 48_000))
        detector.reset()
        val fire2 = feedFirstFire(detector, zerosBytes(240_000), 4096, 48_000)
        assertTrue("segundo episodio debe disparar tras reset", fire2 != null)
        assertEquals(0L, fire2!!.silenceStartOffsetMs)
    }

    @Test
    fun `T-S10 entradas malformadas no lanzan y el detector sigue funcionando`() {
        val detector = AudioSilenceDetector()
        val bytes = zerosBytes(48_000)
        // Tamaño mayor que el buffer, negativo, frecuencia inválida,
        // vacío y tamaño impar: nunca lanzan y no disparan.
        assertTrue(
            detector.observe(bytes, 999_999, 48_000)
                is SilenceDecision.NoFire,
        )
        assertTrue(
            detector.observe(bytes, -10, 48_000)
                is SilenceDecision.NoFire,
        )
        assertTrue(
            detector.observe(bytes, 4096, 0) is SilenceDecision.NoFire,
        )
        assertTrue(
            detector.observe(bytes, 4096, -1) is SilenceDecision.NoFire,
        )
        assertTrue(
            detector.observe(ByteArray(0), 0, 48_000)
                is SilenceDecision.NoFire,
        )
        assertTrue(
            detector.observe(bytes, 4097, 48_000)
                is SilenceDecision.NoFire,
        )
        // El estado sigue intacto: 5 s de ceros disparan después.
        val fire = feedFirstFire(detector, zerosBytes(240_000), 4096, 48_000)
        assertTrue("el detector debe seguir funcionando", fire != null)
        assertEquals(5_000L, fire!!.silenceMs)
    }

    @Test
    fun `muestra menos 32768 no desborda el pico y no es silencio`() {
        val detector = AudioSilenceDetector()
        val samples = ShortArray(48_000)
        samples[10] = Short.MIN_VALUE
        // Un segundo con una muestra a -32768: pico 32768, no cero 1.
        // No es silencioso (pico > 4) y no debe lanzar.
        feedNoFire(detector, shortsToBytes(samples), 4096, 48_000)
    }
}
