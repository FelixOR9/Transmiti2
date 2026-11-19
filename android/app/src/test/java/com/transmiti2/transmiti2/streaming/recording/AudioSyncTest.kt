package com.transmiti2.transmiti2.streaming.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Origen común por segmento, asignación por frontera, offset A/V y
 * diferencia de duraciones (plan 3.1.3 §4.2 y §4.4). Funciones puras,
 * sin `AudioRecord` ni `MediaCodec` reales.
 */
class AudioSyncTest {

    @Test
    fun `origen comun es el primer PTS de audio cuando llega antes`() {
        assertEquals(4_800_000L, segmentOrigin(5_000_000L, 4_800_000L))
    }

    @Test
    fun `origen comun es el primer PTS de video cuando el audio llega despues`() {
        assertEquals(5_000_000L, segmentOrigin(5_000_000L, 5_300_000L))
    }

    @Test
    fun `origen comun con ambos PTS iguales`() {
        assertEquals(5_000_000L, segmentOrigin(5_000_000L, 5_000_000L))
    }

    @Test
    fun `origen video-only es el primer PTS de video`() {
        assertEquals(5_000_000L, segmentOrigin(5_000_000L, null))
    }

    @Test
    fun `muestra justo antes de la frontera pertenece al segmento actual`() {
        // Última trama AAC antes de los 60 s: 60.000.000 − 21.333 µs.
        assertTrue(assignAudioToSegment(59_978_667L, 60_000_000L))
    }

    @Test
    fun `muestra justo en la frontera va al segmento siguiente`() {
        assertFalse(assignAudioToSegment(60_000_000L, 60_000_000L))
    }

    @Test
    fun `muestra justo despues de la frontera va al segmento siguiente`() {
        assertFalse(assignAudioToSegment(60_021_333L, 60_000_000L))
    }

    @Test
    fun `muestra al inicio pertenece al segmento actual`() {
        assertTrue(assignAudioToSegment(0L, 60_000_000L))
    }

    @Test
    fun `offset cero cuando ambas pistas empiezan a la vez`() {
        assertEquals(0L, avOffsetMs(5_000_000L, 5_000_000L))
    }

    @Test
    fun `offset positivo cuando el audio empieza 300 ms despues`() {
        assertEquals(300L, avOffsetMs(5_000_000L, 5_300_000L))
    }

    @Test
    fun `offset negativo cuando el audio empieza 150 ms antes`() {
        assertEquals(-150L, avOffsetMs(5_000_000L, 4_850_000L))
    }

    @Test
    fun `duracion igual da diferencia cero`() {
        assertEquals(0L, avDurationDiffMs(60_000L, 60_000L))
    }

    @Test
    fun `video mas largo que el audio da diferencia positiva`() {
        assertEquals(200L, avDurationDiffMs(60_000L, 59_800L))
    }

    @Test
    fun `audio mas largo que el video da diferencia negativa`() {
        assertEquals(-100L, avDurationDiffMs(60_000L, 60_100L))
    }
}
