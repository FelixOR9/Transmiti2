package com.transmiti2.transmiti2.streaming.recording

import android.media.AudioRecord
import com.transmiti2.transmiti2.streaming.StreamContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Escalera de configuración de audio (plan 3.1.3 §2 D1), PTS desde
 * el ancla (§4.3) y tamaño del buffer de lectura. Lógica pura, sin
 * `AudioRecord` real: la captura y la codificación reales solo se
 * verifican en el teléfono.
 */
class AudioEncoderTest {

    private fun caps(
        minBuffer48k: Int = 4096,
        minBuffer441k: Int = 4096,
        aacRates: Set<Int> = setOf(48000, 44100, 32000, 16000),
    ) = AudioCapabilities(
        minBufferSizeBySampleRate = mapOf(
            48000 to minBuffer48k,
            44100 to minBuffer441k,
        ),
        aacSupportedSampleRates = aacRates,
    )

    @Test
    fun `configuracion valida pasa la validacion`() {
        AudioEncoderConfig(
            sampleRate = 48000,
            channels = 1,
            bitrateBps = 128_000,
        ).validate()
    }

    @Test
    fun `frecuencia no valida es rechazada`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioEncoderConfig(
                sampleRate = 0,
                channels = 1,
                bitrateBps = 128_000,
            ).validate()
        }
    }

    @Test
    fun `estereo es rechazado, solo mono`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioEncoderConfig(
                sampleRate = 48000,
                channels = 2,
                bitrateBps = 128_000,
            ).validate()
        }
    }

    @Test
    fun `cero canales es rechazado`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioEncoderConfig(
                sampleRate = 48000,
                channels = 0,
                bitrateBps = 128_000,
            ).validate()
        }
    }

    @Test
    fun `bitrate no valido es rechazado`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioEncoderConfig(
                sampleRate = 48000,
                channels = 1,
                bitrateBps = 0,
            ).validate()
        }
    }

    @Test
    fun `48 kHz disponible se elige con 128 kbps mono`() {
        val config = chooseAudioConfig(caps())
        assertEquals(48000, config.sampleRate)
        assertEquals(1, config.channels)
        assertEquals(128_000, config.bitrateBps)
    }

    @Test
    fun `48 kHz sin buffer minimo cae a 44,1 kHz`() {
        val config = chooseAudioConfig(
            caps(minBuffer48k = AudioRecord.ERROR),
        )
        assertEquals(44100, config.sampleRate)
        assertEquals(1, config.channels)
        assertEquals(128_000, config.bitrateBps)
    }

    @Test
    fun `48 kHz con ERROR_BAD_VALUE cae a 44,1 kHz`() {
        val config = chooseAudioConfig(
            caps(minBuffer48k = AudioRecord.ERROR_BAD_VALUE),
        )
        assertEquals(44100, config.sampleRate)
    }

    @Test
    fun `48 kHz sin soporte AAC cae a 44,1 kHz`() {
        val config = chooseAudioConfig(
            caps(aacRates = setOf(44100, 32000)),
        )
        assertEquals(44100, config.sampleRate)
    }

    @Test
    fun `44,1 kHz sin buffer minimo es audio_unavailable`() {
        val thrown = assertThrows(RecordingException::class.java) {
            chooseAudioConfig(
                caps(
                    minBuffer48k = AudioRecord.ERROR,
                    minBuffer441k = AudioRecord.ERROR,
                ),
            )
        }
        assertEquals(StreamContract.AUDIO_UNAVAILABLE, thrown.code)
    }

    @Test
    fun `sin soporte AAC en ninguna frecuencia es audio_unavailable`() {
        val thrown = assertThrows(RecordingException::class.java) {
            chooseAudioConfig(caps(aacRates = emptySet()))
        }
        assertEquals(StreamContract.AUDIO_UNAVAILABLE, thrown.code)
    }

    @Test
    fun `el fallo del mono no reserva a estereo`() {
        // La escalera solo contempla mono (48 kHz → 44,1 kHz): si el
        // mono falla en ambas frecuencias, el resultado es error,
        // aunque el codificador AAC anuncie esas frecuencias (que
        // servirían en estéreo). Ningún camino devuelve 2 canales.
        val thrown = assertThrows(RecordingException::class.java) {
            chooseAudioConfig(
                caps(
                    minBuffer48k = AudioRecord.ERROR,
                    minBuffer441k = AudioRecord.ERROR_BAD_VALUE,
                    aacRates = setOf(48000, 44100),
                ),
            )
        }
        assertEquals(StreamContract.AUDIO_UNAVAILABLE, thrown.code)
    }

    @Test
    fun `primera trama en el ancla`() {
        assertEquals(1_000_000L, audioPtsUs(1_000_000_000L, 0, 48000))
    }

    @Test
    fun `1024 muestras son 21333 microsegundos a 48 kHz`() {
        assertEquals(1_021_333L, audioPtsUs(1_000_000_000L, 1024, 48000))
    }

    @Test
    fun `varias tramas acumuladas desde el ancla`() {
        // 10 tramas AAC de 1024 muestras = 10240 muestras = 213333 µs.
        assertEquals(1_213_333L, audioPtsUs(1_000_000_000L, 10240, 48000))
    }

    @Test
    fun `ancla con offset`() {
        // Ancla 5.000.000.123 ns = 5.000.000 µs; + 1024 muestras.
        assertEquals(5_021_333L, audioPtsUs(5_000_000_123L, 1024, 48000))
    }

    @Test
    fun `a 44,1 kHz 1024 muestras son 23219 microsegundos`() {
        assertEquals(23_219L, audioPtsUs(0L, 1024, 44100))
    }

    @Test
    fun `un segundo de audio son 48000 muestras`() {
        assertEquals(2_000_000L, audioPtsUs(1_000_000_000L, 48000, 48000))
    }

    @Test
    fun `muestras negativas se rechazan`() {
        assertThrows(IllegalArgumentException::class.java) {
            audioPtsUs(1_000_000_000L, -1, 48000)
        }
    }

    @Test
    fun `frecuencia no valida en audioPtsUs se rechaza`() {
        assertThrows(IllegalArgumentException::class.java) {
            audioPtsUs(1_000_000_000L, 0, 0)
        }
    }

    @Test
    fun `buffer de lectura es el minimo por cuatro`() {
        assertEquals(32_768, audioReadBufferSizeBytes(8192))
    }

    @Test
    fun `buffer de lectura tiene piso de 16 KB`() {
        assertEquals(16_384, audioReadBufferSizeBytes(1024))
        assertEquals(16_384, audioReadBufferSizeBytes(4096))
    }

    @Test
    fun `2048 muestras son 4096 bytes por lectura`() {
        assertEquals(2048, AUDIO_READ_SAMPLES)
        assertEquals(4096, AUDIO_READ_BYTES)
    }
}
