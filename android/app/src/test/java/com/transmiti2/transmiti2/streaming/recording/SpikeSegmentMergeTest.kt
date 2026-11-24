package com.transmiti2.transmiti2.streaming.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPIKE J1-0 (3.1.4) — demux/remux opción A, lógica JVM con fakes.
 * Valida el algoritmo del plan §2.4 (pasos 2, 5, 6 y 8) y §2.5
 * (pasos 2, 3 y 5) ANTES de escribir `SegmentMerger` en producción
 * (J1-a). Los números realistas son los medidos en el Grupo W
 * (seg0: video 24 967 ms / audio 24 981 ms / avOffset 103 ms) y los
 * del plan §2.3 (60 s, 30 fps, ~2812 tramas AAC).
 *
 * Lo que este spike NO puede probar (queda para la medida física en
 * el S24 Ultra): si `MediaMuxerCompat` acepta PTS rebasados con
 * huecos de audio, cuánto tarda la fusión real y cuánto mide el
 * `avOffsetMs`/`avDurationDiffMs` del `_final.mp4` real.
 */
class SpikeSegmentMergeTest {

    companion object {
        private const val RECORDING_ID = "rec_20261123_143132_7d5b"
        private const val VIDEO_STEP_US = 1_000_000L / 30 // 33 333 µs
        private const val AUDIO_STEP_US = 1024L * 1_000_000L / 48_000 // 21 333 µs
    }

    private fun videoFormat(
        profileIdc: Int = 66,
        csd0: ByteArray = byteArrayOf(0x01, 0x42, 0x00, 0x1e),
        csd1: ByteArray = byteArrayOf(0x02),
    ) = SpikeVideoFormat(
        mime = "video/avc",
        width = 1280,
        height = 720,
        profileIdc = profileIdc,
        csd0 = csd0,
        csd1 = csd1,
    )

    private fun audioFormat(
        sampleRate: Int = 48_000,
        channels: Int = 1,
        csd0: ByteArray = byteArrayOf(0x11, 0x90.toByte()),
    ) = SpikeAudioFormat(
        sampleRate = sampleRate,
        channels = channels,
        profile = 2,
        csd0 = csd0,
    )

    /** Muestras de video a 30 fps desde PTS 0; la primera es keyframe. */
    private fun videoSamples(durationMs: Long): List<SpikeSample> {
        val samples = mutableListOf<SpikeSample>()
        var ptsUs = 0L
        while (ptsUs < durationMs * 1_000L) {
            val flags = if (samples.isEmpty()) SPIKE_FLAG_KEYFRAME else 0
            samples += SpikeSample(ptsUs, flags)
            ptsUs += VIDEO_STEP_US
        }
        return samples
    }

    /** Tramas AAC desde `firstPtsUs`; una cada 21 333 µs (48 kHz). */
    private fun audioSamples(durationMs: Long, firstPtsUs: Long): List<SpikeSample> {
        val samples = mutableListOf<SpikeSample>()
        var ptsUs = firstPtsUs
        while (ptsUs < durationMs * 1_000L) {
            samples += SpikeSample(ptsUs)
            ptsUs += AUDIO_STEP_US
        }
        return samples
    }

    /**
     * Un segmento de prueba: entrada (metadatos declarados) + datos
     * demuxados (formatos reales + muestras) que sirve el extractor
     * falso. `audioDurationMs == null` = segmento sin pista de audio.
     */
    private fun segment(
        index: Int,
        videoDurationMs: Long,
        audioDurationMs: Long?,
        avOffsetMs: Long = 0L,
        status: SegmentStatus = SegmentStatus.READY,
        firstSampleKeyframe: Boolean = true,
        ptsMonotonic: Boolean = true,
        videoProfileIdc: Int = 66,
        audioSampleRate: Int = 48_000,
        videoCsd0: ByteArray = byteArrayOf(0x01, 0x42, 0x00, 0x1e),
        fileNameOverride: String? = null,
        recordingId: String = RECORDING_ID,
        videoSamplesOverride: List<SpikeSample>? = null,
    ): Pair<SpikeSegmentInput, SpikeTrackData> {
        val input = SpikeSegmentInput(
            recordingId = recordingId,
            index = index,
            status = status,
            firstSampleKeyframe = firstSampleKeyframe,
            ptsMonotonic = ptsMonotonic,
            videoDurationMs = videoDurationMs,
            audioDurationMs = audioDurationMs,
            fileNameOverride = fileNameOverride,
        )
        val track = SpikeTrackData(
            videoFormat = videoFormat(
                profileIdc = videoProfileIdc,
                csd0 = videoCsd0,
            ),
            audioFormat = if (audioDurationMs == null) {
                null
            } else {
                audioFormat(sampleRate = audioSampleRate)
            },
            videoSamples = videoSamplesOverride ?: videoSamples(videoDurationMs),
            audioSamples = if (audioDurationMs == null) {
                emptyList()
            } else {
                audioSamples(audioDurationMs, avOffsetMs * 1_000L)
            },
        )
        return input to track
    }

    private class FakeSpikeExtractor(
        private val data: Map<Int, SpikeTrackData>,
    ) : SpikeExtractor {
        override fun open(segment: SpikeSegmentInput): SpikeTrackData =
            data.getValue(segment.index)
    }

    private fun merge(
        pairs: List<Pair<SpikeSegmentInput, SpikeTrackData>>,
        orientationDegrees: Int = 90,
    ): Pair<SpikeMergeResult, FakeSpikeMuxer> {
        val muxer = FakeSpikeMuxer()
        val result = SpikeSegmentMerger.merge(
            segments = pairs.map { it.first },
            extractor = FakeSpikeExtractor(pairs.associate { it.first.index to it.second }),
            muxer = muxer,
            orientationDegrees = orientationDegrees,
        )
        return result to muxer
    }

    private fun assertStrictlyIncreasing(ptsUs: List<Long>) {
        for (i in 1 until ptsUs.size) {
            assertTrue(
                "PTS no monotono en $i: ${ptsUs[i - 1]} -> ${ptsUs[i]}",
                ptsUs[i] > ptsUs[i - 1],
            )
        }
    }

    @Test
    fun `rebase por pista con duraciones distintas por segmento`() {
        // Plan §2.4 paso 5: video y audio llevan offsets separados.
        // seg0: video 60 000 ms / audio 59 920 ms (avOffset 120 ms).
        // seg1: video 60 000 ms / audio 59 950 ms (avOffset 90 ms).
        val seg0 = segment(0, 60_000L, 59_920L, avOffsetMs = 120L)
        val seg1 = segment(1, 60_000L, 59_950L, avOffsetMs = 90L)
        val (result, muxer) = merge(listOf(seg0, seg1))

        assertTrue(result.success)
        assertNull(result.reason)
        assertTrue(result.durationCheckOk)

        // El video del seg1 empieza en 60 000 000 µs (offset video).
        val videoCountSeg0 = videoSamples(60_000L).size
        assertEquals(60_000_000L, result.mergedVideoPtsUs[videoCountSeg0])
        // El audio del seg1 empieza en 59 920 000 + 90 000 µs
        // (offset audio, distinto del de video).
        val audioCountSeg0 = audioSamples(59_920L, 120_000L).size
        assertEquals(60_010_000L, result.mergedAudioPtsUs[audioCountSeg0])

        // §2.5.3: nº de muestras = suma por pista.
        assertEquals(videoCountSeg0 * 2, result.videoSampleCount)
        assertEquals(
            audioCountSeg0 + audioSamples(59_950L, 90_000L).size,
            result.audioSampleCount,
        )

        // Monotonicidad de los PTS rebasados, por pista.
        assertStrictlyIncreasing(result.mergedVideoPtsUs)
        assertStrictlyIncreasing(result.mergedAudioPtsUs)

        // Offset A/V del fusionado = el del segmento 0 (103→120 ms).
        assertEquals(120L, result.mergedAvOffsetMs)
        // Diferencia de duraciones = suma por pista: 120 000 − 119 870.
        assertEquals(130L, result.mergedAvDurationDiffMs)

        // La deriva del seg1 es −80 ms = −(video−audio del seg0):
        // su offset A/V queda en 10 ms en el fusionado (90 − 80).
        assertEquals(0L, result.avOffsetDriftMsBySegment.getValue(0))
        assertEquals(-80L, result.avOffsetDriftMsBySegment.getValue(1))
        assertTrue(
            result.avOffsetDriftMsBySegment.values.all {
                it >= -SPIKE_AV_TOLERANCE_MS && it <= SPIKE_AV_TOLERANCE_MS
            },
        )

        // Orden de llamadas del muxer: pistas una sola vez, start,
        // stop, release; orientación registrada.
        assertEquals(
            listOf("orientation", "addTrack", "addTrack", "start", "stop", "release"),
            muxer.structuralCalls,
        )
        assertEquals(90, muxer.orientationDegrees)
        assertTrue(muxer.stopped)
        assertTrue(muxer.released)
        assertTrue(result.audioGapSegments.isEmpty())
        assertTrue(result.partialSegments.isEmpty())
    }

    @Test
    fun `entrada desordenada se fusiona en orden canonico`() {
        val seg0 = segment(0, 60_000L, 59_920L, avOffsetMs = 120L)
        val seg1 = segment(1, 60_000L, 59_950L, avOffsetMs = 90L)
        // Se entregan al revés: el merger ordena por índice.
        val (result, _) = merge(listOf(seg1, seg0))
        assertTrue(result.success)
        assertEquals(0L, result.mergedVideoPtsUs.first())
        assertEquals(60_000_000L, result.mergedVideoPtsUs[videoSamples(60_000L).size])
    }

    @Test
    fun `hueco en los indices aborta sin fusionar`() {
        val seg0 = segment(0, 60_000L, 59_920L, avOffsetMs = 120L)
        val seg2 = segment(2, 60_000L, 59_950L, avOffsetMs = 90L)
        val (result, muxer) = merge(listOf(seg0, seg2))
        assertFalse(result.success)
        assertEquals("merge_skipped_gap", result.reason)
        assertTrue(result.mergedVideoPtsUs.isEmpty())
        // No se llegó ni a abrir el muxer.
        assertTrue(muxer.structuralCalls.isEmpty())
        assertFalse(muxer.started)
    }

    @Test
    fun `indice duplicado aborta`() {
        val seg0 = segment(0, 60_000L, 59_920L, avOffsetMs = 120L)
        val seg0bis = segment(0, 60_000L, 59_920L, avOffsetMs = 120L)
        val (result, _) = merge(listOf(seg0, seg0bis))
        assertFalse(result.success)
        assertEquals("merge_indice_duplicado", result.reason)
    }

    @Test
    fun `ids distintos abortan`() {
        val seg0 = segment(0, 60_000L, 59_920L, avOffsetMs = 120L)
        val seg1 = segment(
            1, 60_000L, 59_950L, avOffsetMs = 90L,
            recordingId = "rec_20261123_150000_0000",
        )
        val (result, _) = merge(listOf(seg0, seg1))
        assertFalse(result.success)
        assertEquals("merge_ids_distintos", result.reason)
    }

    @Test
    fun `nombre no canonico aborta`() {
        val seg0 = segment(0, 60_000L, 59_920L, avOffsetMs = 120L)
        // El nombre dice seg1 pero el índice declarado es 0.
        val seg1 = segment(
            1, 60_000L, 59_950L, avOffsetMs = 90L,
            fileNameOverride = "${RECORDING_ID}_seg000.mp4",
        )
        val (result, _) = merge(listOf(seg0, seg1))
        assertFalse(result.success)
        assertEquals("merge_nombre_no_canonico", result.reason)
    }

    @Test
    fun `formato distinto entre segmentos aborta`() {
        val seg0 = segment(0, 60_000L, 59_920L, avOffsetMs = 120L)

        // Perfil de video distinto (Baseline 66 vs Main 100).
        val seg1Main = segment(1, 60_000L, 59_950L, videoProfileIdc = 100)
        val (mainResult, _) = merge(listOf(seg0, seg1Main))
        assertFalse(mainResult.success)
        assertEquals("merge_formato_distinto", mainResult.reason)

        // Frecuencia de audio distinta (48 kHz vs 44,1 kHz).
        val seg1Rate = segment(1, 60_000L, 59_950L, audioSampleRate = 44_100)
        val (rateResult, _) = merge(listOf(seg0, seg1Rate))
        assertFalse(rateResult.success)
        assertEquals("merge_formato_distinto", rateResult.reason)

        // SPS distinto (csd-0 de video).
        val seg1Csd = segment(
            1, 60_000L, 59_950L,
            videoCsd0 = byteArrayOf(0x01, 0x42, 0x00, 0x1f),
        )
        val (csdResult, _) = merge(listOf(seg0, seg1Csd))
        assertFalse(csdResult.success)
        assertEquals("merge_formato_distinto", csdResult.reason)
    }

    @Test
    fun `duracion incoherente con las muestras rompe la monotonicidad`() {
        // seg0 declara 60 000 ms de video pero sus muestras llegan a
        // 60 099 399 µs: el primer PTS rebasado del seg1 (60 000 000)
        // no es mayor que el último escrito → aborto, tmp descartado.
        val seg0 = segment(
            0, 60_000L, 59_920L, avOffsetMs = 120L,
            videoSamplesOverride = videoSamples(60_100L),
        )
        val seg1 = segment(1, 60_000L, 59_950L, avOffsetMs = 90L)
        val (result, muxer) = merge(listOf(seg0, seg1))
        assertFalse(result.success)
        assertEquals("merge_pts_no_monotonos", result.reason)
        // Aborto a mitad: sin stop(), con release() (tmp descartado).
        assertFalse(muxer.stopped)
        assertTrue(muxer.released)
    }

    @Test
    fun `offset AV intra-segmento se conserva en el seg0 y deriva despues`() {
        // Datos reales del Grupo W: video 24 967 ms / audio 24 981 ms.
        val seg0 = segment(0, 24_967L, 24_981L, avOffsetMs = 103L)
        val seg1 = segment(1, 24_967L, 24_981L, avOffsetMs = 102L)
        val (result, _) = merge(listOf(seg0, seg1))
        assertTrue(result.success)
        assertTrue(result.durationCheckOk)

        // En el seg0 el offset se conserva exacto: 103 ms.
        assertEquals(103L, result.mergedAvOffsetMs)
        assertEquals(0L, result.avOffsetDriftMsBySegment.getValue(0))
        // En el seg1 deriva +14 ms (audio 14 ms más largo que video
        // en el seg0): queda en 116 ms, dentro de ±500 ms.
        assertEquals(14L, result.avOffsetDriftMsBySegment.getValue(1))
        assertEquals(-28L, result.mergedAvDurationDiffMs)
    }

    @Test
    fun `la diferencia AV acumulada crece con N y supera 500 ms a 36 segmentos`() {
        // Proyección con la deriva medida del Grupo W (−14 ms por
        // segmento en video−audio): SI el sesgo es sistemático, un
        // partido de 36 segmentos (~36 min) superaría la tolerancia
        // de §2.5.5 (|avDurationDiffMs| ≤ 500 ms). La medida física
        // debe decir si el sesgo es sistemático o alterna de signo.
        assertEquals(-504L, -14L * 36)
        assertTrue(kotlin.math.abs(-14L * 36) > SPIKE_AV_TOLERANCE_MS)
        // Con 35 segmentos aún estaría dentro.
        assertTrue(kotlin.math.abs(-14L * 35) <= SPIKE_AV_TOLERANCE_MS)
    }

    @Test
    fun `segmento sin audio deja hueco registrado y el audio no avanza`() {
        // Plan §2.4 paso 5 tal como está escrito: el offset de audio
        // NO avanza en el segmento sin audio. Consecuencia medida
        // por el spike (ver informe J1-0): NO queda un hueco de PTS
        // en el audio — el audio del seg2 aterriza justo detrás del
        // seg0, ~60 s ANTES de donde le correspondería por video.
        val seg0 = segment(0, 60_000L, 59_920L, avOffsetMs = 120L)
        val seg1 = segment(1, 60_000L, null)
        val seg2 = segment(2, 60_000L, 59_900L, avOffsetMs = 100L)
        val (result, _) = merge(listOf(seg0, seg1, seg2))

        assertTrue(result.success)
        assertEquals(listOf(1), result.audioGapSegments)
        // El audio del seg2 empieza en 59 920 000 + 100 000 µs: el
        // offset de audio no avanzó por el seg1.
        val audioCountSeg0 = audioSamples(59_920L, 120_000L).size
        assertEquals(60_020_000L, result.mergedAudioPtsUs[audioCountSeg0])
        // El video del seg2 sí empieza en 120 000 000 µs.
        val videoCountTwo = videoSamples(60_000L).size * 2
        assertEquals(120_000_000L, result.mergedVideoPtsUs[videoCountTwo])
        // La deriva del seg2 es −60 080 ms: su audio queda ~60 s
        // adelantado respecto a su video.
        assertEquals(-60_080L, result.avOffsetDriftMsBySegment.getValue(2))
        // Y la diferencia de duraciones del fusionado es ~60 s: el
        // fusionado sería PARTIAL por audio (§2.5.5), no READY.
        assertEquals(60_180L, result.mergedAvDurationDiffMs)
        assertTrue(kotlin.math.abs(result.mergedAvDurationDiffMs!!) > SPIKE_AV_TOLERANCE_MS)
        // El audio del seg0 + seg2 suma; el video suma los 3.
        assertEquals(videoSamples(60_000L).size * 3, result.videoSampleCount)
        assertEquals(
            audioCountSeg0 + audioSamples(59_900L, 100_000L).size,
            result.audioSampleCount,
        )
    }

    @Test
    fun `con offset de audio avanzado por el hueco la sincronia se conservaria`() {
        // Aritmética de la alternativa (NO implementada en el spike:
        // el plan §2.4 paso 5 dice que el offset no avanza). Si el
        // offset de audio avanzara por la duración de VIDEO del
        // segmento sin audio (60 000 ms), el audio del seg2 empezaría
        // en 120 020 000 µs (hueco real de ~60 s, sin inventar
        // muestras de silencio) y la duración de la pista de audio
        // MEDIDA por tramo PTS (179 717 ms) casi igualaría a la de
        // video (180 032 ms): diferencia 315 ms, dentro de ±500 ms.
        // (La suma de duraciones de audio seguiría siendo 119 820 ms
        // y su diferencia con el video 60 180 ms: lo que cambia es
        // el tramo PTS, que es lo que mide el verificador sobre el
        // archivo.) Decisión pendiente para J1-a.
        val audioOffsetWithGapUs = (59_920L + 60_000L) * 1_000L
        val seg2FirstAudioPtsUs = audioOffsetWithGapUs + 100_000L
        assertEquals(120_020_000L, seg2FirstAudioPtsUs)
        val seg2LastAudioPtsUs = audioOffsetWithGapUs +
            audioSamples(59_900L, 100_000L).last().ptsUs
        val audioSpanMs = (seg2LastAudioPtsUs - 120_000L) / 1_000L + 21L
        val videoSpanMs =
            (120_000_000L + videoSamples(60_000L).last().ptsUs) / 1_000L + 33L
        assertEquals(179_717L, audioSpanMs)
        assertEquals(180_032L, videoSpanMs)
        assertEquals(315L, videoSpanMs - audioSpanMs)
        assertTrue(videoSpanMs - audioSpanMs <= SPIKE_AV_TOLERANCE_MS)
    }

    @Test
    fun `segmento corrupto o sin keyframe aborta y partial si se fusiona`() {
        val seg0 = segment(0, 60_000L, 59_920L, avOffsetMs = 120L)

        val seg1Corrupt = segment(
            1, 60_000L, 59_950L,
            status = SegmentStatus.CORRUPT,
        )
        val (corruptResult, _) = merge(listOf(seg0, seg1Corrupt))
        assertFalse(corruptResult.success)
        assertEquals("merge_skipped_corrupt", corruptResult.reason)

        val seg1NoKeyframe = segment(
            1, 60_000L, 59_950L, firstSampleKeyframe = false,
        )
        val (keyframeResult, _) = merge(listOf(seg0, seg1NoKeyframe))
        assertFalse(keyframeResult.success)
        assertEquals("primer_sample_no_keyframe", keyframeResult.reason)

        val seg1NoMono = segment(
            1, 60_000L, 59_950L, ptsMonotonic = false,
        )
        val (monoResult, _) = merge(listOf(seg0, seg1NoMono))
        assertFalse(monoResult.success)
        assertEquals("pts_no_monotonos", monoResult.reason)

        // PARTIAL (video válido, audio con problema) SÍ se fusiona.
        val seg1Partial = segment(
            1, 60_000L, 59_950L, status = SegmentStatus.PARTIAL,
        )
        val (partialResult, _) = merge(listOf(seg0, seg1Partial))
        assertTrue(partialResult.success)
        assertEquals(listOf(1), partialResult.partialSegments)
    }

    @Test
    fun `sin segmentos aborta`() {
        val muxer = FakeSpikeMuxer()
        val result = SpikeSegmentMerger.merge(
            segments = emptyList(),
            extractor = FakeSpikeExtractor(emptyMap()),
            muxer = muxer,
        )
        assertFalse(result.success)
        assertEquals("merge_sin_segmentos", result.reason)
        assertTrue(muxer.structuralCalls.isEmpty())
    }

    @Test
    fun `tolerancia de duracion del fusionado es 100 ms mas 33 ms por segmento`() {
        // Plan §2.5.2, con N = 2: tolerancia 166 ms.
        assertTrue(mergedDurationWithinTolerance(120_000L, 120_000L, 2))
        assertTrue(mergedDurationWithinTolerance(120_166L, 120_000L, 2))
        assertFalse(mergedDurationWithinTolerance(120_167L, 120_000L, 2))
        assertTrue(mergedDurationWithinTolerance(119_834L, 120_000L, 2))
        assertFalse(mergedDurationWithinTolerance(119_833L, 120_000L, 2))
    }

    @Test
    fun `video-only sin audio en ningun segmento se fusiona`() {
        val seg0 = segment(0, 60_000L, null)
        val seg1 = segment(1, 60_000L, null)
        val (result, muxer) = merge(listOf(seg0, seg1))
        assertTrue(result.success)
        assertTrue(result.mergedAudioPtsUs.isEmpty())
        assertEquals(0, result.audioSampleCount)
        assertNull(result.mergedAvOffsetMs)
        assertNull(result.mergedAvDurationDiffMs)
        assertNull(result.mergedAudioDurationMs)
        assertEquals(listOf(0, 1), result.audioGapSegments)
        // Sin audio no hay segunda pista: un único addTrack.
        assertEquals(
            listOf("orientation", "addTrack", "start", "stop", "release"),
            muxer.structuralCalls,
        )
    }

    @Test
    fun `muestras codec-config no se escriben y EOS solo va al final`() {
        val base0 = videoSamples(60_000L).mapIndexed { i, sample ->
            if (i == 5) {
                sample.copy(flags = sample.flags or SPIKE_FLAG_END_OF_STREAM)
            } else {
                sample
            }
        }
        val config = SpikeSample(0L, SPIKE_FLAG_CODEC_CONFIG)
        val seg0 = segment(
            0, 60_000L, 59_920L, avOffsetMs = 120L,
            videoSamplesOverride = listOf(config) + base0,
        )
        val base1 = videoSamples(60_000L).mapIndexed { i, sample ->
            if (i == videoSamples(60_000L).size - 1) {
                sample.copy(flags = sample.flags or SPIKE_FLAG_END_OF_STREAM)
            } else {
                sample
            }
        }
        val seg1 = segment(
            1, 60_000L, 59_950L, avOffsetMs = 90L,
            videoSamplesOverride = base1,
        )
        val (result, muxer) = merge(listOf(seg0, seg1))
        assertTrue(result.success)
        // La muestra de config no se escribió: el nº de muestras de
        // video es la suma de las reales (sin la de config).
        assertEquals(videoSamples(60_000L).size * 2, result.videoSampleCount)
        assertTrue(
            muxer.writtenVideoFlags.none {
                (it and SPIKE_FLAG_CODEC_CONFIG) != 0
            },
        )
        // EOS solo en la última muestra escrita.
        val eosWritten = muxer.writtenVideoFlags.filter {
            (it and SPIKE_FLAG_END_OF_STREAM) != 0
        }
        assertEquals(1, eosWritten.size)
        assertEquals(
            muxer.writtenVideoFlags.last(),
            eosWritten.single(),
        )
        // El EOS intermedio del seg0 se quitó.
        assertEquals(0, muxer.writtenVideoFlags[5] and SPIKE_FLAG_END_OF_STREAM)
    }
}
