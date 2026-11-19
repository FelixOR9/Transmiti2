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

    @Test
    fun `duracion del contenedor valida se usa tal cual`() {
        assertEquals(
            60_000L,
            effectiveDurationMs(60_000L, 0L, 59_967_000L),
        )
    }

    @Test
    fun `KEY_DURATION en 0 cae al tramo PTS`() {
        // Hueco del paso 4: antes, un 0 no caía al fallback y el
        // segmento quedaba `corrupt` por `duracion_no_valida`.
        assertEquals(
            60_000L,
            effectiveDurationMs(0L, 0L, 59_967_000L),
        )
    }

    @Test
    fun `sin KEY_DURATION cae al tramo PTS`() {
        assertEquals(
            60_000L,
            effectiveDurationMs(null, 0L, 59_967_000L),
        )
    }

    @Test
    fun `sin KEY_DURATION ni PTS no hay duracion`() {
        assertNull(effectiveDurationMs(null, null, null))
        assertNull(effectiveDurationMs(0L, null, null))
    }

    // --- `classifyIncomplete` (ronda 2: timeout / error con medidas
    // parciales). El teléfono mostró `seg0/seg1: corrupt —ms
    // motivo=verification_timeout cerradoLimpio=sí`: la verificación no
    // terminó en 30 s y `verify()` descartaba las medidas ya tomadas.
    // Ahora el segmento legible es `partial` con el motivo visible y
    // la duración medida sobrevive.

    private fun classifyTimedOut(
        checks: VerificationChecks = goodChecks(),
        expectedWidth: Int? = 1280,
        expectedHeight: Int? = 720,
        expectedFps: Int? = 30,
        reason: String = "verification_timeout",
    ) = classifyIncomplete(
        checks,
        expectedWidth,
        expectedHeight,
        expectedFps,
        reason,
    )

    @Test
    fun `timeout en ExoPlayer con medidas del extractor es partial con motivo y duracion`() {
        // Caso del teléfono: extractor + decodificación completos, la
        // confirmación de ExoPlayer no llegó a tiempo.
        val result = classifyTimedOut(
            checks = goodChecks().copy(
                exoplayerReady = false,
                exoplayerDurationMs = null,
            ),
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("verification_timeout", result.reason)
        assertEquals(60_000L, result.checks.durationMs)
        assertEquals(1800, result.checks.sampleCount)
    }

    @Test
    fun `timeout con decode sin completar sigue siendo partial con duracion`() {
        // La decodificación no terminó (o no se confirmó): no es
        // «fallida», es «no verificada a tiempo». El archivo legible
        // sigue siendo utilizable.
        val result = classifyTimedOut(
            checks = goodChecks().copy(
                decodeOk = false,
                decodedFrames = 900,
                exoplayerReady = false,
                exoplayerDurationMs = null,
            ),
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("verification_timeout", result.reason)
        assertEquals(60_000L, result.checks.durationMs)
    }

    @Test
    fun `error de verificacion con medidas es partial con verification_error`() {
        val result = classifyTimedOut(
            checks = goodChecks().copy(
                decodeOk = false,
                exoplayerReady = false,
                exoplayerDurationMs = null,
            ),
            reason = "verification_error",
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("verification_error", result.reason)
        assertEquals(60_000L, result.checks.durationMs)
    }

    @Test
    fun `timeout con extractor que no abre es corrupt con el motivo medido`() {
        val result = classifyTimedOut(
            checks = goodChecks().copy(
                extractorOpens = false,
                hasAvcTrack = false,
                width = null,
                height = null,
                sampleCount = 0,
                keyframeCount = 0,
                firstSampleKeyframe = false,
                durationMs = null,
                fpsMeasured = null,
                profileIdc = null,
                decodeOk = false,
                decodedFrames = 0,
                exoplayerReady = false,
                exoplayerDurationMs = null,
            ),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("extractor_no_abre", result.reason)
    }

    @Test
    fun `timeout sin muestras es corrupt`() {
        val result = classifyTimedOut(
            checks = goodChecks().copy(
                sampleCount = 0,
                keyframeCount = 0,
                durationMs = null,
                fpsMeasured = null,
                decodeOk = false,
                decodedFrames = 0,
            ),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("sin_muestras", result.reason)
    }

    @Test
    fun `timeout con duracion no valida es corrupt`() {
        val result = classifyTimedOut(
            checks = goodChecks().copy(durationMs = 0L),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("duracion_no_valida", result.reason)
    }

    @Test
    fun `timeout con resolucion distinta es corrupt`() {
        val result = classifyTimedOut(
            checks = goodChecks().copy(width = 640, height = 480),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("resolucion_no_coincide", result.reason)
    }

    @Test
    fun `timeout con FPS fuera de tolerancia es corrupt`() {
        val result = classifyTimedOut(
            checks = goodChecks().copy(fpsMeasured = 25.0),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("fps_no_coincide", result.reason)
    }

    @Test
    fun `timeout sin configuracion esperada no comprueba resolucion ni FPS`() {
        val result = classifyTimedOut(
            checks = goodChecks().copy(
                width = 640,
                height = 480,
                fpsMeasured = 24.0,
                exoplayerReady = false,
                exoplayerDurationMs = null,
            ),
            expectedWidth = null,
            expectedHeight = null,
            expectedFps = null,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("verification_timeout", result.reason)
    }
}
