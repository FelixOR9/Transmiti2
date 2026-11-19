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
        ioPhaseMs = 60_000L,
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
        // Ronda 3: la tolerancia es ±10 (antes ±2). 25,0 está dentro
        // ahora; el caso fuera de tolerancia es 15,0 (la mitad del
        // objetivo): un defecto real de FPS, no variación legítima.
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(fpsMeasured = 15.0),
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
    fun `FPS 30 exacto es ready`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(fpsMeasured = 30.0),
        )
        assertEquals(SegmentStatus.READY, result.status)
    }

    @Test
    fun `FPS 20 en el limite inferior es ready`() {
        // |20 − 30| = 10, no mayor que la tolerancia: 2/3 del objetivo
        // es el mínimo aceptable (entrega legítima con poca luz o
        // carga); por debajo, el segmento es `corrupt`.
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(fpsMeasured = 20.0),
        )
        assertEquals(SegmentStatus.READY, result.status)
    }

    @Test
    fun `FPS 19,9 bajo el limite es corrupt`() {
        // |19,9 − 30| = 10,1 > 10: por debajo de 2/3 del objetivo.
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(fpsMeasured = 19.9),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("fps_no_coincide", result.reason)
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

    // --- `computeFpsMeasured` (ronda 4, fix 1). La función pura que
    // `verifyInternal` usa dentro de la fase de E/S y los `catch` de
    // `verify()` para recalcular desde lo acumulado.

    @Test
    fun `1800 muestras en 60000 ms son 30,0 FPS`() {
        assertEquals(30.0, computeFpsMeasured(1800, 60_000L)!!, 0.001)
    }

    @Test
    fun `1794 muestras en 60000 ms son 29,9 FPS, el caso del spike`() {
        assertEquals(29.9, computeFpsMeasured(1794, 60_000L)!!, 0.001)
    }

    @Test
    fun `el caso del telefono, 1801 muestras en 60007 ms son 30,01 FPS`() {
        assertEquals(30.01, computeFpsMeasured(1801, 60_007L)!!, 0.01)
    }

    @Test
    fun `sin muestras no hay FPS`() {
        assertNull(computeFpsMeasured(0, 60_000L))
    }

    @Test
    fun `sin duracion no hay FPS`() {
        assertNull(computeFpsMeasured(1800, null))
        assertNull(computeFpsMeasured(1800, 0L))
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
        // Ronda 3: la tolerancia es ±10 (antes ±2). 25,0 está dentro
        // ahora; el caso fuera de tolerancia es 15,0 (la mitad del
        // objetivo).
        val result = classifyTimedOut(
            checks = goodChecks().copy(fpsMeasured = 15.0),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("fps_no_coincide", result.reason)
    }

    @Test
    fun `timeout sin FPS medido es corrupt con fps_no_coincide`() {
        // Ronda 4, diagnóstico `fps_no_coincide` §3: el FPS se
        // calculaba DESPUÉS del `withContext` y el timeout saltaba
        // en su retorno, así que `fpsMeasured` quedaba en `null` y
        // la comprobación fallaba por la rama del nulo aunque todo
        // lo demás estuviera medido (duración 60007 ms, 1801
        // muestras). El fix 1 calcula el FPS dentro de la fase de
        // E/S para que este caso no se dé en un timeout en el borde;
        // la rama del nulo sigue existiendo para cuando de verdad no
        // hay medidas (sin muestras o sin duración, los tests de
        // arriba la cubren con otro motivo anterior en el orden).
        val result = classifyTimedOut(
            checks = goodChecks().copy(fpsMeasured = null),
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("fps_no_coincide", result.reason)
    }

    @Test
    fun `timeout con FPS 20 en el limite es partial`() {
        // |20 − 30| = 10, dentro de la tolerancia: el archivo es
        // legible y utilizable; solo falta la confirmación a tiempo.
        val result = classifyTimedOut(
            checks = goodChecks().copy(fpsMeasured = 20.0),
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("verification_timeout", result.reason)
    }

    @Test
    fun `timeout con FPS 29,9 es partial`() {
        // El FPS del teléfono (29,9, medido en el spike): dentro de la
        // tolerancia; el timeout no lo convierte en `corrupt`.
        val result = classifyTimedOut(
            checks = goodChecks().copy(fpsMeasured = 29.9),
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("verification_timeout", result.reason)
    }

    @Test
    fun `la duracion de la fase 1 sobrevive en las medidas clasificadas`() {
        // Ronda 4, fix 2: `ioPhaseMs` viaja en `VerificationChecks`
        // (de `PartialChecks.toChecks()` a la tarjeta, vía
        // `RecordingSegmentResult` en la ronda 4 grupo C). Sin ella
        // la próxima decisión (¿la fase 1 cabe en 60 s?) no tendría
        // dato en el teléfono.
        val result = classifyTimedOut(
            checks = goodChecks().copy(ioPhaseMs = 61_234L),
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals(61_234L, result.checks.ioPhaseMs)
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
