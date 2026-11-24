package com.transmiti2.transmiti2.streaming.recording

import kotlin.math.abs

/**
 * SPIKE J1-0 (3.1.4) — demux/remux opción A, SOLO PARA VALIDAR LA
 * LÓGICA EN JVM. NO ES PRODUCCIÓN:
 *
 * - Vive en `src/test`: nunca se compila en el APK ni se referencia
 *   desde `src/main`. No existe `SegmentMerger` en producción (eso es
 *   J1-a); aquí no se toca sidecar, SQLite, controller, FGS, eventos
 *   ni workflows, y no se añade ninguna dependencia.
 * - No fusiona MP4 reales: el extractor y el muxer son fakes. El
 *   comportamiento real de `MediaMuxerCompat` ante PTS rebasados con
 *   huecos de audio y ante pistas de distinta duración SOLO se conoce
 *   en el S24 Ultra (ver procedimiento físico en el informe J1-0).
 * - El algoritmo que se valida es el del plan §2.4 pasos 2, 5, 6 y 8
 *   y §2.5 pasos 2, 3 y 5: orden canónico por índice con rechazo de
 *   huecos, comparación de formatos, rebase de PTS POR PISTA (nunca
 *   un offset global), preservación del offset A/V intra-segmento,
 *   segmento sin audio (hueco registrado, offset de audio sin
 *   avanzar), monotonicidad verificada mientras se escribe, muestras
 *   `CODEC_CONFIG` no escritas y `END_OF_STREAM` solo en la última
 *   muestra del último segmento.
 *
 * Hallazgo documentado por el spike (ver `SpikeSegmentMergeTest`):
 * el offset A/V intra-segmento se conserva EXACTO solo en el
 * segmento 0; en los siguientes deriva por la diferencia acumulada
 * (video−audio) de los segmentos anteriores (~14 ms/segmento en los
 * datos medidos del Grupo W). Esa deriva es la que hace crecer
 * `avDurationDiffMs` del fusionado con N, y es la pregunta que debe
 * responder la medida física antes de fijar la tolerancia de §2.5.5
 * para partidos largos.
 */

/** `MediaCodec.BUFFER_FLAG_KEY_FRAME`. */
const val SPIKE_FLAG_KEYFRAME = 1

/** `MediaCodec.BUFFER_FLAG_CODEC_CONFIG` (csd: va en el formato). */
const val SPIKE_FLAG_CODEC_CONFIG = 2

/** `MediaCodec.BUFFER_FLAG_END_OF_STREAM`. */
const val SPIKE_FLAG_END_OF_STREAM = 4

/** Tolerancia de duración vigente (§2.5.2): `DURATION_TOLERANCE_MS`. */
const val SPIKE_DURATION_TOLERANCE_MS = 100L

/** Un frame de video a 30 fps, en ms (§2.5.2: 33 ms por segmento). */
const val SPIKE_FRAME_MS = 33L

/** Tolerancia A/V vigente (§2.5.5): 500 ms. */
const val SPIKE_AV_TOLERANCE_MS = 500L

/** Formato de la pista de video de un segmento, leído del extractor. */
data class SpikeVideoFormat(
    val mime: String,
    val width: Int,
    val height: Int,
    val profileIdc: Int,
    val csd0: ByteArray,
    val csd1: ByteArray,
)

/** Formato de la pista de audio de un segmento, leído del extractor. */
data class SpikeAudioFormat(
    val sampleRate: Int,
    val channels: Int,
    /** Perfil AAC (`AACObjectLC = 2`). */
    val profile: Int,
    val csd0: ByteArray,
)

/** Una muestra demuxada: PTS en µs + flags (`SPIKE_FLAG_*`). */
data class SpikeSample(val ptsUs: Long, val flags: Int = 0)

/** Datos demuxados de un segmento: formatos reales + muestras. */
data class SpikeTrackData(
    val videoFormat: SpikeVideoFormat,
    val audioFormat: SpikeAudioFormat?,
    val videoSamples: List<SpikeSample>,
    val audioSamples: List<SpikeSample>,
)

/**
 * Entrada de un segmento: metadatos declarados (sidecar / verificación
 * fresca) + nombre canónico. Las duraciones son las EFECTIVAS
 * verificadas (`effectiveDurationMs` / `effectiveAudioDurationMs`),
 * no `KEY_DURATION` a ciegas (plan §2.4 paso 5).
 */
data class SpikeSegmentInput(
    val recordingId: String,
    val index: Int,
    val status: SegmentStatus,
    val firstSampleKeyframe: Boolean,
    val ptsMonotonic: Boolean,
    val videoDurationMs: Long,
    /** `null` = segmento sin pista de audio. */
    val audioDurationMs: Long?,
    /** Solo para probar nombres no canónicos; `null` = nombre real. */
    val fileNameOverride: String? = null,
) {
    /** `<id>_seg%03d.mp4` (plan §2.2; `RecordingFileStore`). */
    val fileName: String
        get() = fileNameOverride
            ?: "${recordingId}_seg%03d.mp4".format(index)
}

/** Demux falso: sirve los datos declarados de cada segmento. */
interface SpikeExtractor {
    fun open(segment: SpikeSegmentInput): SpikeTrackData
}

/**
 * Sumidero de remux falso: registra lo escrito sin tocar Android.
 * A propósito NO impone monotonicidad de PTS: esa es la incógnita
 * del muxer real que el spike NO puede resolver (la impone el propio
 * `SpikeSegmentMerger` antes de escribir y la debe confirmar la
 * medida física). Solo impone orden de llamadas (pista antes de
 * `start`, escritura después de `start`).
 */
interface SpikeMuxer {
    fun setOrientationHint(degrees: Int)
    fun addTrack(isVideo: Boolean): Int
    fun start()
    fun writeSampleData(trackIndex: Int, ptsUs: Long, flags: Int)
    fun stop()
    fun release()
}

/** `SpikeMuxer` que registra llamadas y PTS escritos por pista. */
class FakeSpikeMuxer : SpikeMuxer {
    val structuralCalls = mutableListOf<String>()
    val writtenVideoPtsUs = mutableListOf<Long>()
    val writtenAudioPtsUs = mutableListOf<Long>()
    val writtenVideoFlags = mutableListOf<Int>()
    val writtenAudioFlags = mutableListOf<Int>()
    var orientationDegrees: Int? = null
        private set
    var videoTrackIndex = -1
        private set
    var audioTrackIndex = -1
        private set
    var started = false
        private set
    var stopped = false
        private set
    var released = false
        private set

    override fun setOrientationHint(degrees: Int) {
        orientationDegrees = degrees
        structuralCalls += "orientation"
    }

    override fun addTrack(isVideo: Boolean): Int {
        check(!started) { "addTrack despues de start" }
        structuralCalls += "addTrack"
        return if (isVideo) {
            videoTrackIndex = 0
            0
        } else {
            audioTrackIndex = 1
            1
        }
    }

    override fun start() {
        check(!started) { "start dos veces" }
        started = true
        structuralCalls += "start"
    }

    override fun writeSampleData(trackIndex: Int, ptsUs: Long, flags: Int) {
        check(started) { "write antes de start" }
        check(!stopped) { "write despues de stop" }
        if (trackIndex == videoTrackIndex) {
            writtenVideoPtsUs += ptsUs
            writtenVideoFlags += flags
        } else {
            writtenAudioPtsUs += ptsUs
            writtenAudioFlags += flags
        }
    }

    override fun stop() {
        check(started) { "stop antes de start" }
        stopped = true
        structuralCalls += "stop"
    }

    override fun release() {
        released = true
        structuralCalls += "release"
    }
}

/** Resultado del spike de fusión (éxito o aborto con motivo). */
data class SpikeMergeResult(
    val success: Boolean,
    val reason: String?,
    val mergedVideoPtsUs: List<Long>,
    val mergedAudioPtsUs: List<Long>,
    val videoSampleCount: Int,
    val audioSampleCount: Int,
    val audioGapSegments: List<Int>,
    val partialSegments: List<Int>,
    /** Suma de duraciones efectivas de video, en ms. */
    val mergedVideoDurationMs: Long?,
    /** Suma de duraciones efectivas de audio, en ms (`null` si no hay). */
    val mergedAudioDurationMs: Long?,
    /** Primer PTS audio − primer PTS video del fusionado, en ms. */
    val mergedAvOffsetMs: Long?,
    /** Duración video − duración audio del fusionado, en ms. */
    val mergedAvDurationDiffMs: Long?,
    /**
     * Deriva del offset A/V por segmento, en ms: offset A/V del
     * segmento tal como queda en el fusionado menos su offset A/V
     * original. 0 en el segmento 0; en los siguientes es la
     * diferencia acumulada (audio−video) de los segmentos
     * anteriores. Debe mantenerse dentro de ±500 ms.
     */
    val avOffsetDriftMsBySegment: Map<Int, Long>,
    /** ¿Pasa el contraste de duración de §2.5.2 por pista? */
    val durationCheckOk: Boolean,
)

/**
 * Contraste de duración del fusionado (plan §2.5.2):
 * `|duraciónFusionado − suma duracionesEfectivasSegmentos|`
 * `≤ 100 ms + 33 ms × N`.
 */
fun mergedDurationWithinTolerance(
    mergedMs: Long,
    sumMs: Long,
    segmentCount: Int,
): Boolean {
    val tolerance = SPIKE_DURATION_TOLERANCE_MS + SPIKE_FRAME_MS * segmentCount
    return abs(mergedMs - sumMs) <= tolerance
}

/** Comparación de formatos de video (mime, resolución, perfil, csd). */
fun spikeVideoFormatsEqual(a: SpikeVideoFormat, b: SpikeVideoFormat): Boolean =
    a.mime == b.mime &&
        a.width == b.width &&
        a.height == b.height &&
        a.profileIdc == b.profileIdc &&
        a.csd0.contentEquals(b.csd0) &&
        a.csd1.contentEquals(b.csd1)

/** Comparación de formatos de audio (frecuencia, canales, perfil, csd). */
fun spikeAudioFormatsEqual(a: SpikeAudioFormat, b: SpikeAudioFormat): Boolean =
    a.sampleRate == b.sampleRate &&
        a.channels == b.channels &&
        a.profile == b.profile &&
        a.csd0.contentEquals(b.csd0)

/** Aborto interno del spike: la fusión se descarta (el tmp se tira). */
private class MergeAbort(val reason: String) : RuntimeException(reason)

/**
 * Algoritmo de fusión del plan §2.4 (pasos 2, 5, 6 y 8) sobre fakes.
 * Rebase POR PISTA: `nuevoPts = ptsEnSegmento + offsetAcumuladoPista`,
 * con `offsetAcumuladoVideo = suma duracionesVideo anteriores` y
 * `offsetAcumuladoAudio = suma duracionesAudio anteriores`. Si un
 * segmento no tiene audio, su duración de audio es 0 y el offset de
 * audio no avanza (queda un hueco, registrado en
 * `audioGapSegments`). PROHIBIDO restar offsets "para alinear"
 * (plan §2.4 paso 6 / `AudioSync.kt`): el offset A/V intra-segmento
 * se conserva tal cual lo deja el rebase por pista.
 */
object SpikeSegmentMerger {

    fun merge(
        segments: List<SpikeSegmentInput>,
        extractor: SpikeExtractor,
        muxer: SpikeMuxer,
        orientationDegrees: Int = 0,
    ): SpikeMergeResult {
        fun fail(
            reason: String,
            audioGaps: List<Int> = emptyList(),
            partials: List<Int> = emptyList(),
        ) = SpikeMergeResult(
            success = false,
            reason = reason,
            mergedVideoPtsUs = emptyList(),
            mergedAudioPtsUs = emptyList(),
            videoSampleCount = 0,
            audioSampleCount = 0,
            audioGapSegments = audioGaps,
            partialSegments = partials,
            mergedVideoDurationMs = null,
            mergedAudioDurationMs = null,
            mergedAvOffsetMs = null,
            mergedAvDurationDiffMs = null,
            avOffsetDriftMsBySegment = emptyMap(),
            durationCheckOk = false,
        )

        if (segments.isEmpty()) return fail("merge_sin_segmentos")

        // Paso 2: orden canónico por índice, contiguo desde 0.
        val ordered = segments.sortedBy { it.index }
        val recordingIds = ordered.map { it.recordingId }.toSet()
        if (recordingIds.size != 1) return fail("merge_ids_distintos")
        if (ordered.map { it.index }.toSet().size != ordered.size) {
            return fail("merge_indice_duplicado")
        }
        if (ordered.map { it.index } != (0 until ordered.size).toList()) {
            return fail("merge_skipped_gap")
        }
        // El nombre debe ser el canónico `<id>_seg%03d.mp4`.
        for (segment in ordered) {
            val parsed = RecordingFileStore.parseSegmentFileName(segment.fileName)
            if (parsed == null || parsed.first != segment.recordingId ||
                parsed.second != segment.index
            ) {
                return fail("merge_nombre_no_canonico")
            }
        }

        // Paso 2: validación por segmento (regla estricta: CORRUPT o
        // hueco → no se fusiona nada; PARTIAL sí se fusiona).
        val partialSegments = mutableListOf<Int>()
        for (segment in ordered) {
            when (segment.status) {
                SegmentStatus.CORRUPT -> return fail("merge_skipped_corrupt")
                SegmentStatus.PARTIAL -> partialSegments += segment.index
                SegmentStatus.READY -> Unit
            }
            if (!segment.firstSampleKeyframe) return fail("primer_sample_no_keyframe")
            if (!segment.ptsMonotonic) return fail("pts_no_monotonos")
        }

        // Paso 2: formatos reales leídos del extractor (no asumidos).
        val tracks = ordered.map { extractor.open(it) }
        val referenceVideo = tracks.first().videoFormat
        for (track in tracks) {
            if (!spikeVideoFormatsEqual(referenceVideo, track.videoFormat)) {
                return fail("merge_formato_distinto")
            }
        }
        val audioTracks = tracks.mapNotNull { it.audioFormat }
        val hasAudio = audioTracks.isNotEmpty()
        if (hasAudio) {
            val referenceAudio = audioTracks.first()
            for (audio in audioTracks) {
                if (!spikeAudioFormatsEqual(referenceAudio, audio)) {
                    return fail("merge_formato_distinto")
                }
            }
        }

        // Paso 4: pistas UNA SOLA VEZ desde el primer segmento.
        muxer.setOrientationHint(orientationDegrees)
        val videoTrack = muxer.addTrack(isVideo = true)
        val audioTrack = if (hasAudio) muxer.addTrack(isVideo = false) else -1
        muxer.start()

        val audioGapSegments = mutableListOf<Int>()
        val driftMsBySegment = mutableMapOf<Int, Long>()
        var videoOffsetUs = 0L
        var audioOffsetUs = 0L
        var totalVideoDurationMs = 0L
        var totalAudioDurationMs = 0L
        // PTS escritos por pista (el merger los conoce: es lo que
        // acabará en el tmp; el fake solo los registra por su lado).
        val writtenVideoPtsUs = mutableListOf<Long>()
        val writtenAudioPtsUs = mutableListOf<Long>()
        // Último PTS escrito por pista: vive FUERA del bucle por
        // segmento, porque la monotonicidad debe comprobarse también
        // EN LA FRONTERA entre segmentos (no solo dentro de cada uno).
        var lastVideoPtsUs: Long? = null
        var lastAudioPtsUs: Long? = null

        try {
            for ((position, segment) in ordered.withIndex()) {
                val track = tracks[position]
                val isLastSegment = position == ordered.lastIndex

                // Muestras de video con rebase por pista.
                val writableVideo = track.videoSamples.filter {
                    (it.flags and SPIKE_FLAG_CODEC_CONFIG) == 0
                }
                if (writableVideo.isEmpty()) throw MergeAbort("sin_muestras")
                for ((samplePosition, sample) in writableVideo.withIndex()) {
                    val isLastSample = isLastSegment &&
                        samplePosition == writableVideo.lastIndex
                    var flags = sample.flags
                    // EOS solo puede ir en la última muestra del
                    // último segmento; en el resto se quita.
                    if (!isLastSample) {
                        flags = flags and SPIKE_FLAG_END_OF_STREAM.inv()
                    }
                    val rebasedPtsUs = sample.ptsUs + videoOffsetUs
                    // Monotonicidad verificada mientras se escribe.
                    if (lastVideoPtsUs != null && rebasedPtsUs <= lastVideoPtsUs) {
                        throw MergeAbort("merge_pts_no_monotonos")
                    }
                    muxer.writeSampleData(videoTrack, rebasedPtsUs, flags)
                    writtenVideoPtsUs += rebasedPtsUs
                    lastVideoPtsUs = rebasedPtsUs
                }

                // Muestras de audio con rebase por pista (o hueco).
                if (track.audioFormat == null || track.audioSamples.isEmpty()) {
                    audioGapSegments += segment.index
                } else {
                    val writableAudio = track.audioSamples.filter {
                        (it.flags and SPIKE_FLAG_CODEC_CONFIG) == 0
                    }
                    if (writableAudio.isNotEmpty()) {
                        for ((samplePosition, sample) in writableAudio.withIndex()) {
                            val isLastSample = isLastSegment &&
                                samplePosition == writableAudio.lastIndex
                            var flags = sample.flags
                            if (!isLastSample) {
                                flags = flags and SPIKE_FLAG_END_OF_STREAM.inv()
                            }
                            val rebasedPtsUs = sample.ptsUs + audioOffsetUs
                            if (lastAudioPtsUs != null &&
                                rebasedPtsUs <= lastAudioPtsUs
                            ) {
                                throw MergeAbort("merge_pts_no_monotonos")
                            }
                            muxer.writeSampleData(audioTrack, rebasedPtsUs, flags)
                            writtenAudioPtsUs += rebasedPtsUs
                            lastAudioPtsUs = rebasedPtsUs
                        }
                    }
                    // Deriva del offset A/V de este segmento en el
                    // fusionado frente a su offset original.
                    if (writableAudio.isNotEmpty()) {
                        val firstVideoPtsUs = writableVideo.first().ptsUs
                        val firstAudioPtsUs = writableAudio.first().ptsUs
                        val originalAvOffsetMs =
                            (firstAudioPtsUs - firstVideoPtsUs) / 1_000L
                        val mergedAvOffsetMs =
                            ((firstAudioPtsUs + audioOffsetUs) -
                                (firstVideoPtsUs + videoOffsetUs)) / 1_000L
                        driftMsBySegment[segment.index] =
                            mergedAvOffsetMs - originalAvOffsetMs
                    }
                }

                // El offset de cada pista avanza con SU duración.
                videoOffsetUs += segment.videoDurationMs * 1_000L
                totalVideoDurationMs += segment.videoDurationMs
                val audioDurationMs = segment.audioDurationMs ?: 0L
                audioOffsetUs += audioDurationMs * 1_000L
                totalAudioDurationMs += audioDurationMs
            }
        } catch (abort: MergeAbort) {
            // Aborto a mitad: se descarta el tmp (no hay stop()).
            muxer.release()
            return fail(abort.reason, audioGapSegments, partialSegments)
        }

        muxer.stop()
        muxer.release()

        // §2.5.2: duración medida desde los PTS escritos (tramo + 1
        // frame/trama) contra la suma de duraciones efectivas.
        val mergedVideoMeasuredMs = if (writtenVideoPtsUs.isNotEmpty()) {
            (writtenVideoPtsUs.last() -
                writtenVideoPtsUs.first()) / 1_000L + SPIKE_FRAME_MS
        } else {
            null
        }
        val mergedAudioMeasuredMs = if (writtenAudioPtsUs.isNotEmpty()) {
            // 1 trama AAC a 48 kHz = 21 ms.
            (writtenAudioPtsUs.last() -
                writtenAudioPtsUs.first()) / 1_000L + 21L
        } else {
            null
        }
        val videoDurationOk = mergedVideoMeasuredMs == null ||
            mergedDurationWithinTolerance(
                mergedVideoMeasuredMs,
                totalVideoDurationMs,
                ordered.size,
            )
        val audioDurationOk = mergedAudioMeasuredMs == null ||
            mergedDurationWithinTolerance(
                mergedAudioMeasuredMs,
                totalAudioDurationMs,
                ordered.size,
            )

        val mergedVideoPtsUs = writtenVideoPtsUs.toList()
        val mergedAudioPtsUs = writtenAudioPtsUs.toList()
        val mergedAvOffsetMs = if (mergedAudioPtsUs.isNotEmpty() &&
            mergedVideoPtsUs.isNotEmpty()
        ) {
            (mergedAudioPtsUs.first() - mergedVideoPtsUs.first()) / 1_000L
        } else {
            null
        }

        return SpikeMergeResult(
            success = true,
            reason = null,
            mergedVideoPtsUs = mergedVideoPtsUs,
            mergedAudioPtsUs = mergedAudioPtsUs,
            videoSampleCount = mergedVideoPtsUs.size,
            audioSampleCount = mergedAudioPtsUs.size,
            audioGapSegments = audioGapSegments,
            partialSegments = partialSegments,
            mergedVideoDurationMs = totalVideoDurationMs,
            mergedAudioDurationMs = if (hasAudio) totalAudioDurationMs else null,
            mergedAvOffsetMs = mergedAvOffsetMs,
            mergedAvDurationDiffMs = if (hasAudio) {
                totalVideoDurationMs - totalAudioDurationMs
            } else {
                null
            },
            avOffsetDriftMsBySegment = driftMsBySegment,
            durationCheckOk = videoDurationOk && audioDurationOk,
        )
    }
}
