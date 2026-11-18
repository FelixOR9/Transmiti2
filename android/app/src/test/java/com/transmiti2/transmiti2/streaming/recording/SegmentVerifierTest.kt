package com.transmiti2.transmiti2.streaming.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Clasificación de segmentos (plan 3.1.2 §7.1). Lógica pura: la
 * lectura real del archivo (extractor, decodificador, ExoPlayer) se
 * prueba en el teléfono.
 */
class SegmentVerifierTest {

    private fun goodChecks() = VerificationChecks(
        extractorOpens = true,
        hasAvcTrack = true,
        width = 1280,
        height = 720,
        sampleCount = 1800,
        keyframeCount = 30,
        firstSampleKeyframe = true,
        ptsMonotonic = true,
        durationMs = 60_000L,
        fpsMeasured = 30.0,
        profileIdc = 66,
        decodeOk = true,
        decodedFrames = 1800,
        exoplayerReady = true,
        exoplayerDurationMs = 60_000L,
    )

    private fun classify(
        closedCleanly: Boolean,
        checks: VerificationChecks = goodChecks(),
        expectedWidth: Int? = 1280,
        expectedHeight: Int? = 720,
        expectedFps: Int? = 30,
    ) = classifySegment(
        closedCleanly,
        checks,
        expectedWidth,
        expectedHeight,
        expectedFps,
    )

    @Test
    fun `cerrado correctamente y todo pasa es ready`() {
        val result = classify(closedCleanly = true)
        assertEquals(SegmentStatus.READY, result.status)
        assertNull(result.reason)
    }

    @Test
    fun `interrumpido y todo pasa es partial`() {
        val result = classify(closedCleanly = false)
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertNull(result.reason)
    }

    @Test
    fun `sin extractor es corrupt`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(extractorOpens = false),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("extractor_no_abre", result.reason)
    }

    @Test
    fun `sin pista AVC es corrupt`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(hasAvcTrack = false),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("sin_pista_avc", result.reason)
    }

    @Test
    fun `sin muestras es corrupt`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(sampleCount = 0),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("sin_muestras", result.reason)
    }

    @Test
    fun `primer sample sin keyframe es corrupt`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(firstSampleKeyframe = false),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("primer_sample_no_keyframe", result.reason)
    }

    @Test
    fun `PTS no monotonos es corrupt`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(ptsMonotonic = false),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("pts_no_monotonos", result.reason)
    }

    @Test
    fun `resolucion distinta es corrupt`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(width = 640, height = 480),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("resolucion_no_coincide", result.reason)
    }

    @Test
    fun `duracion no valida es corrupt`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(durationMs = 0L),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("duracion_no_valida", result.reason)
    }

    @Test
    fun `FPS fuera de tolerancia es corrupt`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(fpsMeasured = 25.0),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("fps_no_coincide", result.reason)
    }

    @Test
    fun `FPS dentro de tolerancia pasa`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(fpsMeasured = 29.9),
        )
        assertEquals(SegmentStatus.READY, result.status)
    }

    @Test
    fun `decode incompleto es corrupt`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(decodeOk = false),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("decode_incompleto", result.reason)
    }

    @Test
    fun `ExoPlayer no prepara es corrupt`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(exoplayerReady = false),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("exoplayer_no_prepara", result.reason)
    }

    @Test
    fun `duracion ExoPlayer distinta es corrupt`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(exoplayerDurationMs = 59_000L),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("duracion_no_coincide", result.reason)
    }

    @Test
    fun `sin configuracion esperada la resolucion y el FPS no se comprueban`() {
        val result = classify(
            closedCleanly = false,
            checks = goodChecks().copy(
                width = 640,
                height = 480,
                fpsMeasured = 24.0,
            ),
            expectedWidth = null,
            expectedHeight = null,
            expectedFps = null,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
    }

    @Test
    fun `interrumpido con fallo es corrupt, no partial`() {
        val result = classify(
            closedCleanly = false,
            checks = goodChecks().copy(decodeOk = false),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("decode_incompleto", result.reason)
    }
}
