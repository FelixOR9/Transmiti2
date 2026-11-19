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
        expectedAudio: Boolean? = null,
        expectedSampleRate: Int? = null,
        expectedChannels: Int? = null,
    ) = classifySegment(
        closedCleanly,
        checks,
        expectedWidth,
        expectedHeight,
        expectedFps,
        expectedAudio,
        expectedSampleRate,
        expectedChannels,
    )

    // Checks con audio completo y verificado (3.1.3): 60 s de video a
    // 30 FPS y ~2812 tramas AAC a 48 kHz mono; offset A/V 120 ms y
    // diferencia de duraciones 80 ms (dentro de las tolerancias de
    // 500 ms). La duración de referencia de ExoPlayer es la de video
    // (60 000 ms, mayor que la de audio).
    private fun audioChecks() = goodChecks().copy(
        hasAudioTrack = true,
        audioSampleCount = 2812,
        audioDurationMs = 59_920L,
        audioSampleRate = 48_000,
        audioChannels = 1,
        audioFirstPtsUs = 120_000L,
        audioLastPtsUs = 60_018_667L,
        audioPtsMonotonic = true,
        audioDecodeOk = true,
        audioDecodedFrames = 2812,
        avOffsetMs = 120L,
        avDurationDiffMs = 80L,
        referenceDurationMs = 60_000L,
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
        expectedAudio: Boolean? = null,
        expectedSampleRate: Int? = null,
        expectedChannels: Int? = null,
    ) = classifyIncomplete(
        checks,
        expectedWidth,
        expectedHeight,
        expectedFps,
        reason,
        expectedAudio,
        expectedSampleRate,
        expectedChannels,
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

    // --- `drainTimeoutUs` (ronda 5). El timeout de
    // `dequeueOutputBuffer` en `drainDecoder`. Antes, el drenado por
    // muestra esperaba 100 ms en cada llamada y el bucle solo salía
    // con `INFO_TRY_AGAIN_LATER`: la última espera agotaba el
    // timeout completo por muestra — 1800 muestras × ~110 ms ≈ 199 s
    // de fase 1 en el teléfono (grabación
    // `rec_20261119_021559_a602`: fase=199.0s/200.5s/19.8s). El
    // drenado por muestra ahora sondea sin esperar; el final hasta
    // EOS sigue esperando, acotado por `DRAIN_TIMEOUT_MS`.

    @Test
    fun `el drenado por muestra no espera (timeout 0)`() {
        assertEquals(0L, DefaultSegmentVerifier.drainTimeoutUs(false))
    }

    @Test
    fun `el drenado final hasta EOS espera 100 ms`() {
        assertEquals(
            100_000L,
            DefaultSegmentVerifier.drainTimeoutUs(true),
        )
    }

    @Test
    fun `el timeout del decodificador sigue en 100 ms para la entrada`() {
        // Válvula de seguridad de `dequeueInputBuffer` (y del
        // encolado del EOS): si se bajara a 10 ms, bajo carga una
        // muestra podría descartarse y `fedSamples < sampleCount`
        // → `decodeOk = false` → el segmento nunca sería `ready`.
        // La espera que dominaba el coste se elimina en el drenado,
        // no en esta constante.
        assertEquals(
            100_000L,
            DefaultSegmentVerifier.DECODER_TIMEOUT_US,
        )
    }

    @Test
    fun `1800 muestras drenadas sin esperar suman 0 ms de espera`() {
        // Cota del peor caso con el drenado no bloqueante: la
        // espera acumulada del drenado por muestra es 0. Con el
        // timeout anterior (100 ms) eran 1800 × 100 ms = 180 s de
        // pura espera — la fase 1 medida en el teléfono (199 s).
        // La decodificación real y el drenado final hasta EOS
        // quedan fuera de esta cota (acotado por `DRAIN_TIMEOUT_MS`).
        val esperaTotalMs =
            1800L * DefaultSegmentVerifier.drainTimeoutUs(false) / 1000
        assertEquals(0L, esperaTotalMs)
    }

    // --- Audio (3.1.3, plan §7.3). Comprueban RESULTADOS, no solo
    // llamadas: los checks se construyen a mano con los campos de
    // audio medidos.

    @Test
    fun `ready con audio verificado`() {
        val result = classify(
            closedCleanly = true,
            checks = audioChecks(),
            expectedAudio = true,
            expectedSampleRate = 48_000,
            expectedChannels = 1,
        )
        assertEquals(SegmentStatus.READY, result.status)
        assertNull(result.reason)
    }

    @Test
    fun `interrumpido con audio verificado es partial sin motivo`() {
        val result = classify(
            closedCleanly = false,
            checks = audioChecks(),
            expectedAudio = true,
            expectedSampleRate = 48_000,
            expectedChannels = 1,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertNull(result.reason)
    }

    @Test
    fun `ready video-only con expectedAudio false`() {
        // Sin cambio respecto a hoy: sin pista de audio y sin audio
        // esperado, la clasificación es la de video.
        val result = classify(
            closedCleanly = true,
            expectedAudio = false,
        )
        assertEquals(SegmentStatus.READY, result.status)
        assertNull(result.reason)
    }

    @Test
    fun `audio esperado sin pista es partial sin_pista_audio, no corrupt`() {
        // Video válido + audio esperado pero ausente: el video sigue
        // siendo utilizable (plan §7.3, regla 5).
        val result = classify(
            closedCleanly = true,
            expectedAudio = true,
            expectedSampleRate = 48_000,
            expectedChannels = 1,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("sin_pista_audio", result.reason)
    }

    @Test
    fun `audio esperado con pista sin muestras es partial audio_sin_muestras`() {
        val result = classify(
            closedCleanly = true,
            checks = goodChecks().copy(hasAudioTrack = true),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("audio_sin_muestras", result.reason)
    }

    @Test
    fun `audio no monotono es partial audio_no_monotono`() {
        val result = classify(
            closedCleanly = true,
            checks = audioChecks().copy(audioPtsMonotonic = false),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("audio_no_monotono", result.reason)
    }

    @Test
    fun `sampleRate distinto del esperado es partial audio_formato_no_coincide`() {
        // Motivo añadido en este commit (no está en el plan §7.3):
        // la pista existe y tiene muestras, así que `sin_pista_audio`
        // o `audio_sin_muestras` mentirían, y la duración puede
        // coincidir, así que `duracion_audio_no_coincide` también.
        val result = classify(
            closedCleanly = true,
            checks = audioChecks(),
            expectedAudio = true,
            expectedSampleRate = 44_100,
            expectedChannels = 1,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("audio_formato_no_coincide", result.reason)
    }

    @Test
    fun `canales distintos del esperado es partial audio_formato_no_coincide`() {
        val result = classify(
            closedCleanly = true,
            checks = audioChecks(),
            expectedAudio = true,
            expectedSampleRate = 48_000,
            expectedChannels = 2,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("audio_formato_no_coincide", result.reason)
    }

    @Test
    fun `offset AV de 500 ms en el limite es ready`() {
        val result = classify(
            closedCleanly = true,
            checks = audioChecks().copy(avOffsetMs = 500L),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.READY, result.status)
        assertNull(result.reason)
    }

    @Test
    fun `offset AV de 501 ms es partial audio_desincronizado`() {
        val result = classify(
            closedCleanly = true,
            checks = audioChecks().copy(avOffsetMs = 501L),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("audio_desincronizado", result.reason)
    }

    @Test
    fun `offset AV negativo de menos 501 ms es partial audio_desincronizado`() {
        // El offset puede ser negativo (audio antes que video).
        val result = classify(
            closedCleanly = true,
            checks = audioChecks().copy(avOffsetMs = -501L),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("audio_desincronizado", result.reason)
    }

    @Test
    fun `diferencia de duraciones de 500 ms en el limite es ready`() {
        val result = classify(
            closedCleanly = true,
            checks = audioChecks().copy(avDurationDiffMs = 500L),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.READY, result.status)
        assertNull(result.reason)
    }

    @Test
    fun `diferencia de duraciones de 501 ms es partial duracion_audio_no_coincide`() {
        val result = classify(
            closedCleanly = true,
            checks = audioChecks().copy(avDurationDiffMs = 501L),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("duracion_audio_no_coincide", result.reason)
    }

    @Test
    fun `audio sin decodificar completo es partial audio_decode_incompleto`() {
        val result = classify(
            closedCleanly = true,
            checks = audioChecks().copy(
                audioDecodeOk = false,
                audioDecodedFrames = 1400,
            ),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("audio_decode_incompleto", result.reason)
    }

    @Test
    fun `corrupt por video aunque el audio sea perfecto`() {
        // El audio no salva un video defectuoso (plan §7.3, regla 1).
        val result = classify(
            closedCleanly = true,
            checks = audioChecks().copy(sampleCount = 0),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("sin_muestras", result.reason)
    }

    @Test
    fun `expectedAudio null con audio medido clasifica como video`() {
        // Recuperación: el audio medido es solo diagnóstico, ni se
        // exige ni se prohíbe.
        val result = classify(
            closedCleanly = true,
            checks = audioChecks(),
            expectedAudio = null,
        )
        assertEquals(SegmentStatus.READY, result.status)
        assertNull(result.reason)
    }

    @Test
    fun `expectedAudio null sin audio clasifica como video`() {
        val result = classify(
            closedCleanly = true,
            expectedAudio = null,
        )
        assertEquals(SegmentStatus.READY, result.status)
        assertNull(result.reason)
    }

    @Test
    fun `expectedAudio null con audio defectuoso no lo denuncia`() {
        // Sin expectativa no hay fallo de audio que denunciar: el
        // video válido manda.
        val result = classify(
            closedCleanly = true,
            checks = audioChecks().copy(audioDecodeOk = false),
            expectedAudio = null,
        )
        assertEquals(SegmentStatus.READY, result.status)
        assertNull(result.reason)
    }

    @Test
    fun `expectedAudio false con audio presente lo ignora`() {
        // Modo video-only: el audio medido es solo diagnóstico.
        val result = classify(
            closedCleanly = true,
            checks = audioChecks().copy(audioDecodeOk = false),
            expectedAudio = false,
        )
        assertEquals(SegmentStatus.READY, result.status)
        assertNull(result.reason)
    }

    @Test
    fun `la duracion de referencia es el maximo de video y audio`() {
        // ExoPlayer reporta la pista más larga: con audio más largo
        // que el video, la referencia es la de audio.
        val result = classify(
            closedCleanly = true,
            checks = audioChecks().copy(
                audioDurationMs = 60_100L,
                avDurationDiffMs = -100L,
                referenceDurationMs = 60_100L,
                exoplayerDurationMs = 60_100L,
            ),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.READY, result.status)
        assertNull(result.reason)
    }

    @Test
    fun `ExoPlayer contra la duracion de referencia, no la de video`() {
        // ExoPlayer 60 100 ms coincide con la referencia (audio más
        // largo); contra la duración de video (60 000 ms) la
        // diferencia también estaría dentro de 100 ms, así que este
        // caso usa 60 250 ms: fuera de la referencia por 150 ms.
        val result = classify(
            closedCleanly = true,
            checks = audioChecks().copy(
                audioDurationMs = 60_100L,
                avDurationDiffMs = -100L,
                referenceDurationMs = 60_100L,
                exoplayerDurationMs = 60_250L,
            ),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.CORRUPT, result.status)
        assertEquals("duracion_no_coincide", result.reason)
    }

    // --- Regresión 3.1.2 con los casos reales del teléfono
    // (plan §10.1, D13): los segmentos `ready` siguen clasificando
    // `ready` con `expectedAudio = false`.

    private fun phoneChecks(durationMs: Long, sampleCount: Int) =
        goodChecks().copy(
            durationMs = durationMs,
            sampleCount = sampleCount,
            fpsMeasured = 30.0,
            exoplayerDurationMs = durationMs,
            referenceDurationMs = durationMs,
        )

    @Test
    fun `regresion 3_1_2 rec_20261119_112803_30a2 sigue ready sin audio`() {
        val segments = listOf(
            60_007L to 1800,
            60_007L to 1800,
            20_358L to 611,
        )
        for ((durationMs, sampleCount) in segments) {
            val result = classify(
                closedCleanly = true,
                checks = phoneChecks(durationMs, sampleCount),
                expectedAudio = false,
            )
            assertEquals(SegmentStatus.READY, result.status)
            assertNull(result.reason)
        }
    }

    @Test
    fun `regresion 3_1_2 rec_20261119_113418_e952 sigue ready sin audio`() {
        val segments = listOf(
            60_007L to 1800,
            60_007L to 1800,
            60_007L to 1800,
            31_719L to 952,
        )
        for ((durationMs, sampleCount) in segments) {
            val result = classify(
                closedCleanly = true,
                checks = phoneChecks(durationMs, sampleCount),
                expectedAudio = false,
            )
            assertEquals(SegmentStatus.READY, result.status)
            assertNull(result.reason)
        }
    }

    // --- `classifyIncomplete` con audio (3.1.3).

    @Test
    fun `timeout con audio parcial medido es partial verification_timeout y la duracion de audio sobrevive`() {
        // El audio se midió a medias (1400 tramas, decode sin
        // completar, diferencia de duraciones enorme por la lectura
        // cortada): nada de eso es un fallo de audio establecido, así
        // que el motivo que sobrevive es el del timeout y la duración
        // de audio medida no se descarta.
        val result = classifyTimedOut(
            checks = audioChecks().copy(
                audioSampleCount = 1400,
                audioDurationMs = 30_000L,
                avDurationDiffMs = 30_000L,
                audioDecodeOk = false,
                audioDecodedFrames = 1400,
                exoplayerReady = false,
                exoplayerDurationMs = null,
            ),
            expectedAudio = true,
            expectedSampleRate = 48_000,
            expectedChannels = 1,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("verification_timeout", result.reason)
        assertEquals(30_000L, result.checks.audioDurationMs)
        assertEquals(1400, result.checks.audioSampleCount)
    }

    @Test
    fun `timeout con audio esperado y escaneo de pistas sin audio es partial sin_pista_audio`() {
        // El escaneo de pistas corrió (hay pista AVC) y no encontró
        // audio: la ausencia es definitiva aunque la verificación no
        // terminara.
        val result = classifyTimedOut(
            checks = goodChecks().copy(
                exoplayerReady = false,
                exoplayerDurationMs = null,
            ),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("sin_pista_audio", result.reason)
    }

    @Test
    fun `timeout con offset AV medido fuera de tolerancia es partial audio_desincronizado`() {
        // El offset se mide con los primeros PTS de ambas pistas y no
        // depende de terminar la lectura: es un defecto establecido.
        val result = classifyTimedOut(
            checks = audioChecks().copy(
                avOffsetMs = 800L,
                exoplayerReady = false,
                exoplayerDurationMs = null,
            ),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("audio_desincronizado", result.reason)
    }

    @Test
    fun `timeout sin muestras de audio todavia es verification_timeout, no audio_sin_muestras`() {
        // Pista de audio encontrada pero ninguna muestra leída aún:
        // el bucle pudo cortarse antes de la primera muestra de
        // audio; no se inventa el fallo.
        val result = classifyTimedOut(
            checks = goodChecks().copy(
                hasAudioTrack = true,
                audioSampleRate = 48_000,
                audioChannels = 1,
                exoplayerReady = false,
                exoplayerDurationMs = null,
            ),
            expectedAudio = true,
        )
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals("verification_timeout", result.reason)
    }

    // --- `effectiveAudioDurationMs` (3.1.3). Espejo de
    // `effectiveDurationMs` para video, con 1 trama AAC (1024
    // muestras) en vez de 1 frame de video (33 ms).

    @Test
    fun `duracion de audio del contenedor valida se usa tal cual`() {
        assertEquals(
            60_000L,
            effectiveAudioDurationMs(60_000L, 0L, 59_979_000L, 48_000),
        )
    }

    @Test
    fun `KEY_DURATION de audio en 0 cae al tramo PTS mas una trama`() {
        // 59 979 ms de tramo + 21 ms (1 trama a 48 kHz) = 60 000 ms.
        assertEquals(
            60_000L,
            effectiveAudioDurationMs(0L, 0L, 59_979_000L, 48_000),
        )
    }

    @Test
    fun `sin KEY_DURATION de audio cae al tramo PTS mas una trama`() {
        assertEquals(
            60_000L,
            effectiveAudioDurationMs(null, 0L, 59_979_000L, 48_000),
        )
    }

    @Test
    fun `una trama a 44,1 kHz son 23 ms`() {
        // 59 977 ms de tramo + 23 ms = 60 000 ms.
        assertEquals(
            60_000L,
            effectiveAudioDurationMs(null, 0L, 59_977_000L, 44_100),
        )
    }

    @Test
    fun `sin muestras de audio no hay duracion de audio`() {
        assertNull(effectiveAudioDurationMs(null, null, null, 48_000))
        assertNull(effectiveAudioDurationMs(0L, null, null, 48_000))
    }
}
