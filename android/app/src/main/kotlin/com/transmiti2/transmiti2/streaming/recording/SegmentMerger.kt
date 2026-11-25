package com.transmiti2.transmiti2.streaming.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.StatFs
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fusión de segmentos MP4 a `<id>_final.mp4` (3.1.4, J1-a; plan
 * §2.4–§2.5). Demux/remux sin re-encode (opción A): copia las
 * muestras comprimidas con `MediaExtractor` por segmento a un nuevo
 * muxer, rebasando los PTS POR PISTA (nunca un offset global).
 *
 * Alcance de este grupo (J1-a): la clase es invocable y testeable por
 * sí misma. NO hay orquestación: nadie la invoca tras `stop()` ni
 * tras la reconciliación (eso es J1-b), no emite eventos, no toca
 * filas SQLite ni añade campos de fusión al sidecar (J1-b), y no hay
 * guarda de concurrencia entre fusiones (la reserva del grupo vía
 * `ActiveRecordingRegistry` es de la orquestación, J1-b): quien la
 * invoque debe serializar las fusiones del mismo id.
 *
 * Garantías:
 * - Nunca borra segmentos originales ni sidecar; solo borra el tmp
 *   de fusión fallido (propio, regenerable).
 * - Nunca sobrescribe un `<id>_final.mp4` existente: si ya existe y
 *   verifica, la fusión es idempotente (no re-fusiona); si existe y
 *   no verifica, el duplicado se resuelve con
 *   `resolveDuplicateDestination` (el verificado gana, el otro a
 *   cuarentena).
 * - Si falta un índice, hay un segmento CORRUPT o no verificable, los
 *   formatos difieren o no hay espacio, NO fusiona nada y los
 *   segmentos quedan intactos.
 *
 * Limitaciones conocidas (hallazgos del spike J1-0, documentados, NO
 * corregidos aquí: adoptar la alternativa del spike sería una
 * desviación del plan aprobado y requiere decisión expresa de Félix):
 * 1. Deriva del offset A/V: el offset A/V intra-segmento se conserva
 *    EXACTO solo en el segmento 0; en los siguientes deriva por la
 *    diferencia acumulada (video−audio) de los segmentos anteriores
 *    (~14 ms/segmento con los datos del Grupo W). Con sesgo
 *    sistemático superaría los 500 ms a partir de 36 segmentos. Falta
 *    medir en el teléfono si el sesgo es sistemático o alterna de
 *    signo. La deriva por segmento queda registrada en
 *    [MergeResult.avOffsetDriftMsBySegment] para esa medición.
 * 2. Hueco de audio: con el plan tal como está escrito, el offset de
 *    audio NO avanza en un segmento sin audio. Consecuencia medida
 *    por el spike: NO queda un hueco de PTS en el audio — el audio
 *    del segmento posterior aterriza ~60 s adelantado respecto a su
 *    video (el fusionado resulta PARTIAL por audio, nunca READY).
 *    La alternativa del spike (avanzar el offset de audio por la
 *    duración de VIDEO del segmento sin audio, sin inventar muestras;
 *    diferencia 315 ms, dentro de tolerancia) NO se implementa aquí.
 *    El hueco queda registrado en [MergeResult.audioGapSegments].
 */

/** Tolerancia de duración vigente (§2.5.2): `DURATION_TOLERANCE_MS`. */
const val MERGE_DURATION_TOLERANCE_MS = 100L

/** Un frame de video a 30 fps, en ms (§2.5.2: 33 ms por segmento). */
const val MERGE_FRAME_MS = 33L

/**
 * Motivos de fallo de audio que hacen que un fusionado PARTIAL sea
 * aceptable (§2.5.1: «PARTIAL solo por motivo de audio con video
 * válido»). Espejo exacto de las salidas de `audioFailureReason` en
 * `SegmentVerifier.kt`. Un PARTIAL con otro motivo (p. ej.
 * `verification_timeout` del camino `classifyIncomplete`) NO se
 * acepta: la verificación no terminó de establecer el archivo.
 */
private val MERGE_AUDIO_REASONS = setOf(
    "sin_pista_audio",
    "audio_sin_muestras",
    "audio_no_monotono",
    "audio_formato_no_coincide",
    "audio_desincronizado",
    "duracion_audio_no_coincide",
    "audio_decode_incompleto",
)

/** Formato de la pista de video de un segmento, leído del extractor. */
data class MergeVideoFormat(
    val mime: String,
    val width: Int?,
    val height: Int?,
    val profileIdc: Int?,
    val csd0: ByteArray?,
    val csd1: ByteArray?,
)

/** Formato de la pista de audio de un segmento, leído del extractor. */
data class MergeAudioFormat(
    val sampleRate: Int?,
    val channels: Int?,
    /** Perfil AAC (`AACObjectLC = 2`), del AudioSpecificConfig. */
    val profile: Int?,
    val csd0: ByteArray?,
)

/** Una muestra demuxada: pista, PTS en µs, flags y payload. */
data class ExtractedSample(
    val isVideo: Boolean,
    val ptsUs: Long,
    val flags: Int,
    /** Payload (posición 0, límite = tamaño). De un solo uso. */
    val data: ByteBuffer,
)

/**
 * Demuxer de un segmento, en streaming y en orden de archivo. La
 * implementación real usa `MediaExtractor`; los tests JVM inyectan un
 * falso. `videoMediaFormat`/`audioMediaFormat` son los `MediaFormat`
 * reales de las pistas, tal como los entrega el extractor, para
 * dárselos al muxer en `addTrack` (el csd va en el formato, nunca
 * como muestra).
 */
interface SegmentExtractor : AutoCloseable {
    val videoFormat: MergeVideoFormat
    val videoMediaFormat: MediaFormat
    val audioFormat: MergeAudioFormat?
    val audioMediaFormat: MediaFormat?
    /** Siguiente muestra en orden de archivo, o `null` al final. */
    fun nextSample(): ExtractedSample?
    override fun close()
}

/** Comparación de formatos de video (mime, resolución, perfil, csd). */
fun mergeVideoFormatsEqual(a: MergeVideoFormat, b: MergeVideoFormat): Boolean {
    val csd0Equal = if (a.csd0 == null || b.csd0 == null) {
        a.csd0 == null && b.csd0 == null
    } else {
        a.csd0.contentEquals(b.csd0)
    }
    val csd1Equal = if (a.csd1 == null || b.csd1 == null) {
        a.csd1 == null && b.csd1 == null
    } else {
        a.csd1.contentEquals(b.csd1)
    }
    return a.mime == b.mime &&
        a.width == b.width &&
        a.height == b.height &&
        a.profileIdc == b.profileIdc &&
        csd0Equal &&
        csd1Equal
}

/** Comparación de formatos de audio (frecuencia, canales, perfil, csd). */
fun mergeAudioFormatsEqual(a: MergeAudioFormat, b: MergeAudioFormat): Boolean {
    val csd0Equal = if (a.csd0 == null || b.csd0 == null) {
        a.csd0 == null && b.csd0 == null
    } else {
        a.csd0.contentEquals(b.csd0)
    }
    return a.sampleRate == b.sampleRate &&
        a.channels == b.channels &&
        a.profile == b.profile &&
        csd0Equal
}

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
    val tolerance = MERGE_DURATION_TOLERANCE_MS + MERGE_FRAME_MS * segmentCount
    return abs(mergedMs - sumMs) <= tolerance
}

/** Motivos de aborto de fusión REINTENTABLES (J1-b): la fila queda
 * `finalizing` y los segmentos intactos; la reanudación (J1-c) puede
 * reintentar. Cualquier otro motivo es definitivo: la fila pasa a
 * `corrupt` (segmentos intactos, sin borrar). */
val MERGE_RETRYABLE_REASONS = setOf(
    "storage_full",
    "storage_check_unavailable",
    "merge_skipped_gap",
    "merge_skipped_corrupt",
    "merge_skipped_pending",
    "merge_rename_failed",
)

/** `true` si el aborto de fusión con este motivo es reintentable. */
fun mergeFailureIsRetryable(reason: String?): Boolean =
    reason in MERGE_RETRYABLE_REASONS

/** Fase de la fusión para el progreso (J1-b, notificación del FGS). */
enum class MergeStage {
    VALIDATING,
    COPYING,
    VERIFYING,
}

/** Progreso de la fusión (J1-b): fase y segmentos copiados de N. */
data class MergeProgress(
    val stage: MergeStage,
    val segmentsDone: Int,
    val segmentCount: Int,
)

/** Resultado de una fusión (éxito o aborto con motivo exacto). */
data class MergeResult(
    val success: Boolean,
    /** Motivo del aborto (`null` en éxito). Ver `SegmentMerger`. */
    val reason: String?,
    /** Motivo del verificador o clase de excepción, cuando lo hay. */
    val detailReason: String?,
    val recordingId: String,
    /** Archivo final (en éxito; `null` si se abortó). */
    val finalFile: File?,
    /** Estado de verificación del fusionado (o del final existente). */
    val status: SegmentStatus?,
    val segmentCount: Int,
    val segmentIndices: List<Int>,
    /** Segmentos sin pista de audio (hueco registrado, plan §2.4.8). */
    val audioGapSegments: List<Int>,
    /** Segmentos PARTIAL que sí se fusionaron. */
    val partialSegments: List<Int>,
    /** Índices afectados por el aborto (hueco, corrupto, pendiente). */
    val affectedIndices: List<Int>,
    val videoSampleCount: Int,
    val audioSampleCount: Int,
    /** Suma de duraciones efectivas de video, en ms. */
    val totalVideoDurationMs: Long?,
    /** Suma de duraciones efectivas de audio, en ms (`null` si no hay). */
    val totalAudioDurationMs: Long?,
    /** Duración de referencia medida del fusionado, en ms. */
    val mergedDurationMs: Long?,
    val mergedSizeBytes: Long?,
    /** Primer PTS audio − primer PTS video del fusionado, en ms. */
    val mergedAvOffsetMs: Long?,
    /** Duración video − duración audio del fusionado, en ms. */
    val mergedAvDurationDiffMs: Long?,
    /**
     * Deriva del offset A/V por segmento, en ms: offset A/V del
     * segmento tal como queda en el fusionado menos su offset A/V
     * original. 0 en el segmento 0; en los siguientes es la
     * diferencia acumulada (audio−video) de los segmentos anteriores
     * (hallazgo 1 del spike J1-0; debe mantenerse dentro de ±500 ms).
     */
    val avOffsetDriftMsBySegment: Map<Int, Long>,
    /** `true` si el `_final.mp4` ya existía verificado (no re-fusión). */
    val alreadyMerged: Boolean,
    /** Resolución aplicada si el destino ya existía al renombrar. */
    val duplicateResolution: DuplicateResolution?,
)

/**
 * Aborto interno: la fusión se descarta (el tmp se tira, los
 * segmentos quedan intactos). No es un crash: [MergeResult] lleva el
 * motivo. El nombre NO es `MergeAbort`: el spike J1-0 define una
 * clase privada con ese mismo nombre en el mismo paquete y en el
 * runtime de las unitarias (clases de test antes que las de main) haría
 * sombra a esta, con `NoSuchMethodError` en los abortos.
 */
private class MergeAbortException(
    val reason: String,
    val detailReason: String? = null,
    val affectedIndices: List<Int> = emptyList(),
) : Exception(reason)

/** Entrada validada de un segmento: archivo + duraciones efectivas. */
private data class MergeSegmentInput(
    val file: File,
    val index: Int,
    val status: SegmentStatus,
    /** Duración efectiva de video en ms (`null` → tramo PTS medido). */
    val videoDurationMs: Long?,
    /** Duración efectiva de audio en ms (`null` → tramo PTS medido). */
    val audioDurationMs: Long?,
)

/** Formatos leídos de un segmento (pasada A, antes de copiar). */
private data class MergeSegmentTracks(
    val input: MergeSegmentInput,
    val videoFormat: MergeVideoFormat,
    val videoMediaFormat: MediaFormat,
    val audioFormat: MergeAudioFormat?,
    val audioMediaFormat: MediaFormat?,
)

/**
 * Fusiona los segmentos de una grabación en `<id>_final.mp4`.
 *
 * @param fileStore inventario y nombres (listado canónico, tmp, final).
 * @param verifier verificación de segmentos y del fusionado
 *   (`SegmentVerifier.verify()` sin cambios de lógica).
 * @param extractorFactory demuxer por segmento (real:
 *   `MediaSegmentExtractor`; tests: falso).
 * @param muxerFactory muxer de salida (real: `CompatRecordingMuxer`;
 *   tests: falso). Reutiliza la interfaz `RecordingMuxer`, con su
 *   guarda de mismo hilo: toda la copia corre en un solo hilo, sin
 *   suspensiones entre `start()` y `stop()`.
 * @param freeSpaceBytes espacio libre medible (`null` = no medible,
 *   nunca se inventa un valor; mismo patrón `StatFs` que la sesión).
 * @param ioDispatcher dispatcher del trabajo bloqueante (I/O + muxer).
 */
class SegmentMerger(
    private val fileStore: RecordingFileStore,
    private val verifier: SegmentVerifier,
    private val extractorFactory: (File) -> SegmentExtractor =
        { file -> MediaSegmentExtractor(file) },
    private val muxerFactory: (File) -> RecordingMuxer =
        { file -> CompatRecordingMuxer(file.absolutePath) },
    private val freeSpaceBytes: () -> Long? = {
        try {
            StatFs(fileStore.recordingsDir.absolutePath).availableBytes
        } catch (_: Exception) {
            null
        }
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Fusiona la grabación. Motivos de aborto (exactos):
     * - `merge_sin_segmentos`: no hay ningún segmento `.mp4`.
     * - `merge_nombre_no_canonico`: un archivo del inventario no
     *   sigue `<id>_seg%03d.mp4`.
     * - `merge_skipped_gap`: falta un índice en 0..N-1.
     * - `merge_skipped_corrupt`: un segmento es CORRUPT (sidecar o
     *   verificación fresca).
     * - `merge_skipped_pending`: un segmento no se pudo verificar
     *   (el verificador lanzó; PENDING_VERIFICATION, análogo al C6
     *   de reconciliación). Motivo propio, no del plan: lo pendiente
     *   no es corrupto y no debe etiquetarse como tal.
     * - `merge_segmento_no_legible`: el extractor no pudo abrir o
     *   leer un segmento (o no tiene pista de video).
     * - `merge_formato_distinto`: formato de pista distinto entre
     *   segmentos (video o audio).
     * - `storage_full`: libre < suma tamaños + 200 MB.
     * - `storage_check_unavailable`: el espacio no se pudo medir.
     * - `sin_muestras`: un segmento no tiene muestras de video
     *   escribibles.
     * - `merge_pts_no_monotonos`: un PTS rebasado no es mayor que el
     *   anterior en su pista (duración declarada incoherente con las
     *   muestras).
     * - `muxer_error`: fallo al crear el muxer, añadir pistas o
     *   escribir una muestra.
     * - `muxer_stop_failed`: `stop()` del muxer falló.
     * - `merge_verify_failed`: el tmp no verificó READY ni PARTIAL
     *   solo por audio, o falló el contraste de duración (§2.5.2) o
     *   de muestras (§2.5.3).
     * - `merge_rename_failed`: el renombre atómico falló y el
     *   duplicado no se pudo resolver (el tmp queda huérfano para
     *   reintento; nada se pierde).
     *
     * En éxito, `reason` es `null`. Si el `_final.mp4` ya existía
     * verificado, `alreadyMerged` es `true` y no se re-fusiona.
     *
     * @param onProgress progreso por fases (J1-b, notificación del
     *   FGS): se invoca al validar, por cada segmento copiado y al
     *   verificar. Nunca aborta la fusión: si el callback lanza, se
     *   traga y se sigue (guard_new_execution_path).
     */
    suspend fun merge(
        recordingId: String,
        onProgress: ((MergeProgress) -> Unit)? = null,
    ): MergeResult =
        withContext(ioDispatcher) {
            try {
                mergeInternal(recordingId, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MergeAbortException) {
                Transmiti2Logger.e(
                    "merge: $recordingId abortado (${e.reason}" +
                        (e.detailReason?.let { ": $it" } ?: "") + ")",
                )
                failResult(recordingId, e.reason, e.detailReason, e.affectedIndices)
            } catch (e: Exception) {
                Transmiti2Logger.e(
                    "merge: $recordingId fallo inesperado: " +
                        e.javaClass.simpleName,
                )
                failResult(recordingId, "muxer_error", e.javaClass.simpleName)
            }
        }

    private fun failResult(
        recordingId: String,
        reason: String,
        detailReason: String? = null,
        affectedIndices: List<Int> = emptyList(),
    ) = MergeResult(
        success = false,
        reason = reason,
        detailReason = detailReason,
        recordingId = recordingId,
        finalFile = null,
        status = null,
        segmentCount = 0,
        segmentIndices = emptyList(),
        audioGapSegments = emptyList(),
        partialSegments = emptyList(),
        affectedIndices = affectedIndices,
        videoSampleCount = 0,
        audioSampleCount = 0,
        totalVideoDurationMs = null,
        totalAudioDurationMs = null,
        mergedDurationMs = null,
        mergedSizeBytes = null,
        mergedAvOffsetMs = null,
        mergedAvDurationDiffMs = null,
        avOffsetDriftMsBySegment = emptyMap(),
        alreadyMerged = false,
        duplicateResolution = null,
    )

    private suspend fun mergeInternal(
        recordingId: String,
        onProgress: ((MergeProgress) -> Unit)? = null,
    ): MergeResult {
        val sidecar = fileStore.readSidecar(recordingId)
        val rotationDegrees = sidecar?.rotationDegrees ?: 0
        val finalFile = fileStore.finalFile(recordingId)
        // J1-b: el progreso nunca aborta la fusión.
        val reportProgress: (MergeProgress) -> Unit = { progress ->
            try {
                onProgress?.invoke(progress)
            } catch (_: Exception) {
            }
        }
        reportProgress(MergeProgress(MergeStage.VALIDATING, 0, 0))

        // Paso 9 (reanudación idempotente): si el final ya existe y
        // verifica, no se re-fusiona. Solo la clasificación del
        // verificador decide aquí (los contrastes de §2.5.2/§2.5.3 se
        // aplican a las fusiones que produce esta pasada).
        if (finalFile.isFile) {
            val existing = verifyFinalFile(finalFile, sidecar)
            if (existing != null && isAcceptableMerged(existing)) {
                return MergeResult(
                    success = true,
                    reason = null,
                    detailReason = null,
                    recordingId = recordingId,
                    finalFile = finalFile,
                    status = existing.status,
                    segmentCount = 0,
                    segmentIndices = emptyList(),
                    audioGapSegments = emptyList(),
                    partialSegments = emptyList(),
                    affectedIndices = emptyList(),
                    videoSampleCount = existing.checks.sampleCount,
                    audioSampleCount = existing.checks.audioSampleCount,
                    totalVideoDurationMs = null,
                    totalAudioDurationMs = null,
                    mergedDurationMs = existing.checks.referenceDurationMs
                        ?: existing.checks.durationMs,
                    mergedSizeBytes = finalFile.length(),
                    mergedAvOffsetMs = existing.checks.avOffsetMs,
                    mergedAvDurationDiffMs = existing.checks.avDurationDiffMs,
                    avOffsetDriftMsBySegment = emptyMap(),
                    alreadyMerged = true,
                    duplicateResolution = null,
                )
            }
            // Existe pero no verifica: se sigue fusionando; el
            // duplicado se resuelve tras verificar el tmp (paso 7).
        }

        // Paso 2: entrada validada (orden canónico, contigüidad,
        // estado READY/PARTIAL, duraciones efectivas).
        val inputs = validatedInputs(recordingId, sidecar)
        val segmentCount = inputs.size
        val partialSegments = inputs
            .filter { it.status == SegmentStatus.PARTIAL }
            .map { it.index }
        reportProgress(MergeProgress(MergeStage.COPYING, 0, segmentCount))

        // Paso 3: espacio libre (suma + 200 MB, reutiliza
        // MIN_FREE_BYTES_START de la sesión). Sin tocar nada si no hay.
        val totalSizeBytes = inputs.sumOf { it.file.length() }
        val free = try {
            freeSpaceBytes()
        } catch (_: Exception) {
            null
        }
        if (free == null) {
            throw MergeAbortException("storage_check_unavailable")
        }
        if (free < totalSizeBytes + RecordingSession.MIN_FREE_BYTES_START) {
            throw MergeAbortException("storage_full")
        }

        // Pasada A: formatos reales de cada segmento (sin asumirlos).
        // Se abre y se cierra cada extractor: nunca hay N extractores
        // abiertos a la vez. El `MediaFormat` capturado es un dato
        // desligado del extractor y sigue valiendo tras cerrarlo.
        val tracks = inputs.map { input ->
            readSegmentTracks(input)
        }
        val referenceVideo = tracks.first().videoFormat
        for (track in tracks) {
            if (!mergeVideoFormatsEqual(referenceVideo, track.videoFormat)) {
                throw MergeAbortException(
                    "merge_formato_distinto",
                    "video seg${track.input.index}",
                )
            }
        }
        val audioTracks = tracks.mapNotNull { it.audioFormat }
        val hasAudio = audioTracks.isNotEmpty()
        if (hasAudio) {
            val referenceAudio = audioTracks.first()
            for (audio in audioTracks) {
                if (!mergeAudioFormatsEqual(referenceAudio, audio)) {
                    throw MergeAbortException("merge_formato_distinto", "audio")
                }
            }
        }
        val referenceAudioMediaFormat = tracks
            .firstOrNull { it.audioMediaFormat != null }
            ?.audioMediaFormat
        val referenceAudioFormat = tracks
            .firstOrNull { it.audioFormat != null }
            ?.audioFormat

        // Paso 4: tmp + muxer (pistas UNA SOLA VEZ, desde el primer
        // segmento con cada pista; orientación del sidecar).
        val tmpFile = fileStore.finalStagingFile(recordingId)
        // El tmp huérfano de un intento anterior es propio y
        // regenerable: se sobrescribe (el muxer trunca al crear).
        try {
            if (tmpFile.exists()) tmpFile.delete()
        } catch (_: Exception) {
            // Si no se pudo borrar, el muxer lo sobrescribe igual.
        }
        val muxer = try {
            muxerFactory(tmpFile)
        } catch (e: Exception) {
            throw MergeAbortException("muxer_error", e.javaClass.simpleName)
        }
        val copy = try {
            muxer.setOrientationHint(rotationDegrees)
            val videoTrack = muxer.addTrack(tracks.first().videoMediaFormat)
            val audioTrack = if (hasAudio) {
                muxer.addTrack(referenceAudioMediaFormat!!)
            } else {
                -1
            }
            muxer.start()
            // Paso 5: copia con rebase POR PISTA (nunca global).
            val result = copySegments(
                tracks = tracks,
                muxer = muxer,
                videoTrack = videoTrack,
                audioTrack = audioTrack,
                onSegmentCopied = { done ->
                    reportProgress(
                        MergeProgress(MergeStage.COPYING, done, segmentCount),
                    )
                },
            )
            try {
                muxer.stop()
            } catch (e: Exception) {
                throw MergeAbortException("muxer_stop_failed", e.javaClass.simpleName)
            }
            result
        } catch (abort: MergeAbortException) {
            safeRelease(muxer)
            discardTmp(tmpFile)
            throw abort
        } catch (e: CancellationException) {
            safeRelease(muxer)
            discardTmp(tmpFile)
            throw e
        } catch (e: Exception) {
            safeRelease(muxer)
            discardTmp(tmpFile)
            throw MergeAbortException("muxer_error", e.javaClass.simpleName)
        }
        safeRelease(muxer)

        // Paso 7 (verificación del tmp, §2.5): clasificación READY o
        // PARTIAL solo por audio, duración-suma y muestras-suma.
        reportProgress(MergeProgress(MergeStage.VERIFYING, segmentCount, segmentCount))
        val verification = verifyFinalFile(
            tmpFile,
            sidecar,
            expectedVideoWidth = sidecar?.width?.takeIf { it > 0 }
                ?: tracks.first().videoFormat.width,
            expectedVideoHeight = sidecar?.height?.takeIf { it > 0 }
                ?: tracks.first().videoFormat.height,
            expectedFps = sidecar?.fps?.takeIf { it > 0 },
            expectedAudio = hasAudio,
            expectedSampleRate = referenceAudioFormat?.sampleRate
                ?: sidecar?.audioSampleRate,
            expectedChannels = referenceAudioFormat?.channels
                ?: sidecar?.audioChannels,
        ) ?: run {
            discardTmp(tmpFile)
            throw MergeAbortException("merge_verify_failed", "verificacion_sin_resultado")
        }
        if (!isAcceptableMerged(verification)) {
            discardTmp(tmpFile)
            throw MergeAbortException("merge_verify_failed", verification.reason)
        }
        val checks = verification.checks
        val durationToleranceMs = MERGE_DURATION_TOLERANCE_MS +
            MERGE_FRAME_MS * segmentCount
        val sumVideoMs = copy.totalVideoDurationMs
        val sumAudioMs = copy.totalAudioDurationMs
        if (checks.durationMs == null ||
            abs(checks.durationMs - sumVideoMs) > durationToleranceMs
        ) {
            discardTmp(tmpFile)
            throw MergeAbortException("merge_verify_failed", "duracion_video_no_coincide")
        }
        if (hasAudio && copy.audioSampleCount > 0) {
            if (checks.audioDurationMs == null ||
                abs(checks.audioDurationMs - sumAudioMs) > durationToleranceMs
            ) {
                discardTmp(tmpFile)
                throw MergeAbortException(
                    "merge_verify_failed",
                    "duracion_audio_no_coincide",
                )
            }
        }
        val referenceMs = checks.referenceDurationMs
        if (referenceMs != null &&
            abs(referenceMs - maxOf(sumVideoMs, sumAudioMs)) >
            durationToleranceMs
        ) {
            discardTmp(tmpFile)
            throw MergeAbortException(
                "merge_verify_failed",
                "duracion_referencia_no_coincide",
            )
        }
        if (checks.sampleCount != copy.videoSampleCount) {
            discardTmp(tmpFile)
            throw MergeAbortException("merge_verify_failed", "muestras_video_no_coinciden")
        }
        if (hasAudio && checks.audioSampleCount != copy.audioSampleCount) {
            discardTmp(tmpFile)
            throw MergeAbortException("merge_verify_failed", "muestras_audio_no_coinciden")
        }

        // Paso 7 (renombre atómico): solo si verifica. Si el destino
        // apareció entre tanto, el verificado gana y el otro va a
        // cuarentena; nunca se sobrescribe ni se borra.
        val resolution: DuplicateResolution?
        if (!finalFile.exists()) {
            if (!fileStore.renameStagingToFinal(tmpFile, finalFile)) {
                throw MergeAbortException("merge_rename_failed")
            }
            resolution = null
        } else {
            val finalVerification = try {
                verifier.verify(
                    finalFile,
                    closedCleanly = true,
                    expectedWidth = sidecar?.width?.takeIf { it > 0 },
                    expectedHeight = sidecar?.height?.takeIf { it > 0 },
                    expectedFps = sidecar?.fps?.takeIf { it > 0 },
                    expectedAudio = hasAudio,
                    expectedSampleRate = referenceAudioFormat?.sampleRate
                        ?: sidecar?.audioSampleRate,
                    expectedChannels = referenceAudioFormat?.channels
                        ?: sidecar?.audioChannels,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val finalVerified = finalVerification != null &&
                isAcceptableMerged(finalVerification)
            resolution = fileStore.resolveDuplicateDestination(
                staging = tmpFile,
                final = finalFile,
                stagingVerified = true,
                finalVerified = finalVerified,
            )
            when (resolution) {
                DuplicateResolution.STAGING_KEPT -> Unit
                DuplicateResolution.FINAL_KEPT -> {
                    // El final existente ganó: el tmp producido queda
                    // en cuarentena (no perdido) y el final es el
                    // resultado.
                    return MergeResult(
                        success = true,
                        reason = null,
                        detailReason = null,
                        recordingId = recordingId,
                        finalFile = finalFile,
                        status = finalVerification?.status,
                        segmentCount = segmentCount,
                        segmentIndices = inputs.map { it.index },
                        audioGapSegments = copy.audioGapSegments,
                        partialSegments = partialSegments,
                        affectedIndices = emptyList(),
                        videoSampleCount = copy.videoSampleCount,
                        audioSampleCount = copy.audioSampleCount,
                        totalVideoDurationMs = sumVideoMs,
                        totalAudioDurationMs = if (hasAudio) sumAudioMs else null,
                        mergedDurationMs = finalVerification?.checks
                            ?.referenceDurationMs
                            ?: finalVerification?.checks?.durationMs,
                        mergedSizeBytes = finalFile.length(),
                        mergedAvOffsetMs = finalVerification?.checks?.avOffsetMs,
                        mergedAvDurationDiffMs = finalVerification?.checks
                            ?.avDurationDiffMs,
                        avOffsetDriftMsBySegment = copy.avOffsetDriftMsBySegment,
                        alreadyMerged = true,
                        duplicateResolution = resolution,
                    )
                }
                DuplicateResolution.UNRESOLVED -> {
                    throw MergeAbortException("merge_rename_failed")
                }
            }
        }

        return MergeResult(
            success = true,
            reason = null,
            detailReason = null,
            recordingId = recordingId,
            finalFile = finalFile,
            status = verification.status,
            segmentCount = segmentCount,
            segmentIndices = inputs.map { it.index },
            audioGapSegments = copy.audioGapSegments,
            partialSegments = partialSegments,
            affectedIndices = emptyList(),
            videoSampleCount = copy.videoSampleCount,
            audioSampleCount = copy.audioSampleCount,
            totalVideoDurationMs = sumVideoMs,
            totalAudioDurationMs = if (hasAudio) sumAudioMs else null,
            mergedDurationMs = checks.referenceDurationMs ?: checks.durationMs,
            mergedSizeBytes = finalFile.length(),
            mergedAvOffsetMs = checks.avOffsetMs,
            mergedAvDurationDiffMs = checks.avDurationDiffMs,
            avOffsetDriftMsBySegment = copy.avOffsetDriftMsBySegment,
            alreadyMerged = false,
            duplicateResolution = resolution,
        )
    }

    /**
     * Verifica un candidato a final (existente o tmp recién fusionado)
     * y devuelve la verificación (`null` solo si la verificación
     * lanzó). Quien llama decide si es aceptable con
     * [isAcceptableMerged]; así el motivo del verificador sobrevive
     * para el informe. El contraste de muestras del tmp (§2.5.3) lo
     * hace el llamador con el resultado de la copia. La cancelación
     * se propaga, nunca se traga.
     */
    private suspend fun verifyFinalFile(
        file: File,
        sidecar: RecordingMetadata?,
        expectedVideoWidth: Int? = null,
        expectedVideoHeight: Int? = null,
        expectedFps: Int? = null,
        expectedAudio: Boolean? = null,
        expectedSampleRate: Int? = null,
        expectedChannels: Int? = null,
    ): SegmentVerification? {
        val width = expectedVideoWidth
            ?: sidecar?.width?.takeIf { it > 0 }
        val height = expectedVideoHeight
            ?: sidecar?.height?.takeIf { it > 0 }
        val fps = expectedFps ?: sidecar?.fps?.takeIf { it > 0 }
        val audio = expectedAudio ?: sidecar?.audioEnabled
        val sampleRate = expectedSampleRate ?: sidecar?.audioSampleRate
        val channels = expectedChannels ?: sidecar?.audioChannels
        val verification = try {
            verifier.verify(
                file,
                closedCleanly = true,
                expectedWidth = width,
                expectedHeight = height,
                expectedFps = fps,
                expectedAudio = audio,
                expectedSampleRate = sampleRate,
                expectedChannels = channels,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return verification
    }

    /**
     * Aceptable como fusionado: READY, o PARTIAL solo por motivo de
     * audio con video válido (§2.5.1). `classifySegment` con
     * `closedCleanly = true` solo devuelve PARTIAL con motivo de
     * audio, pero se comprueba el motivo explícitamente: un PARTIAL
     * por timeout (`classifyIncomplete`) no es aceptable.
     */
    private fun isAcceptableMerged(verification: SegmentVerification): Boolean =
        when (verification.status) {
            SegmentStatus.READY -> true
            SegmentStatus.PARTIAL ->
                verification.reason != null &&
                    verification.reason in MERGE_AUDIO_REASONS
            SegmentStatus.CORRUPT -> false
        }

    /**
     * Paso 2: inventario + sidecar → entradas validadas en orden
     * canónico 0..N-1. El sidecar confiable (E1–E6, espejo de
     * `isTrustedSidecar` de la reconciliación) evita re-verificar;
     * sin él, verificación fresca. Un sidecar confiable READY/PARTIAL
     * implica `firstSampleKeyframe == true` y `ptsMonotonic == true`
     * (la clasificación los exige para no ser CORRUPT), así que no se
     * re-comprueban por separado.
     */
    private suspend fun validatedInputs(
        recordingId: String,
        sidecar: RecordingMetadata?,
    ): List<MergeSegmentInput> {
        val files = fileStore.listSegmentFiles(recordingId)
        if (files.isEmpty()) throw MergeAbortException("merge_sin_segmentos")
        val parsed = files.map { file ->
            val parsedName = RecordingFileStore.parseSegmentFileName(file.name)
            if (parsedName == null || parsedName.first != recordingId) {
                throw MergeAbortException("merge_nombre_no_canonico", file.name)
            }
            file to parsedName.second
        }
        val indices = parsed.map { it.second }
        if (indices.toSet().size != indices.size) {
            throw MergeAbortException("merge_indice_duplicado")
        }
        val maxIndex = indices.max()
        val missing = (0..maxIndex).filter { it !in indices.toSet() }
        if (missing.isNotEmpty()) {
            throw MergeAbortException("merge_skipped_gap", affectedIndices = missing)
        }
        val ordered = parsed.sortedBy { it.second }
        return ordered.map { (file, index) ->
            val entry = sidecar?.segments?.find { it.index == index }
            if (entry?.status == "corrupt") {
                throw MergeAbortException(
                    "merge_skipped_corrupt",
                    "sidecar seg$index",
                    listOf(index),
                )
            }
            if (entry != null && isTrustedSidecarEntry(entry, file)) {
                MergeSegmentInput(
                    file = file,
                    index = index,
                    status = if (entry.status == "ready") {
                        SegmentStatus.READY
                    } else {
                        SegmentStatus.PARTIAL
                    },
                    videoDurationMs = entry.durationMs,
                    audioDurationMs = entry.audioDurationMs,
                )
            } else {
                val verification = try {
                    verifier.verify(
                        file,
                        closedCleanly = true,
                        expectedWidth = sidecar?.width?.takeIf { it > 0 },
                        expectedHeight = sidecar?.height?.takeIf { it > 0 },
                        expectedFps = sidecar?.fps?.takeIf { it > 0 },
                        expectedAudio = entry?.audioExpected
                            ?: sidecar?.audioEnabled,
                        expectedSampleRate = sidecar?.audioSampleRate,
                        expectedChannels = sidecar?.audioChannels,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw MergeAbortException(
                        "merge_skipped_pending",
                        e.javaClass.simpleName,
                        listOf(index),
                    )
                }
                when (verification.status) {
                    SegmentStatus.CORRUPT -> throw MergeAbortException(
                        "merge_skipped_corrupt",
                        verification.reason,
                        listOf(index),
                    )
                    SegmentStatus.READY, SegmentStatus.PARTIAL -> MergeSegmentInput(
                        file = file,
                        index = index,
                        status = verification.status,
                        videoDurationMs = verification.checks.durationMs,
                        audioDurationMs = verification.checks.audioDurationMs,
                    )
                }
            }
        }
    }

    /**
     * Sidecar confiable E1–E6 (espejo de `isTrustedSidecar` de la
     * reconciliación, `RecordingSession.kt`): estado ready/partial,
     * duración válida, tamaño y nombre coherentes con el disco,
     * verificación marcada con la versión vigente del verificador, y
     * sin contradicción de audio (ready + audio esperado sin audio
     * medido).
     */
    private fun isTrustedSidecarEntry(
        entry: SegmentMetadata,
        file: File,
    ): Boolean {
        if (entry.status != "ready" && entry.status != "partial") {
            return false // E1
        }
        val durationMs = entry.durationMs ?: return false // E2
        if (durationMs <= 0) return false
        if (entry.sizeBytes != file.length()) return false // E3
        if (entry.fileName != file.name) return false // E4
        if (entry.verifiedAtMs == null) return false // E5
        if (entry.verifierVersion != SegmentVerifier.VERIFIER_VERSION) {
            return false // E6
        }
        if (entry.status == "ready" && entry.audioExpected &&
            (!entry.hasAudio || entry.audioSampleCount == null)
        ) {
            return false // audio contradictorio
        }
        return true
    }

    /** Pasada A: formatos reales de un segmento (abrir, leer, cerrar). */
    private fun readSegmentTracks(input: MergeSegmentInput): MergeSegmentTracks {
        val extractor = try {
            extractorFactory(input.file)
        } catch (e: Exception) {
            throw MergeAbortException(
                "merge_segmento_no_legible",
                e.javaClass.simpleName,
                listOf(input.index),
            )
        }
        try {
            return MergeSegmentTracks(
                input = input,
                videoFormat = extractor.videoFormat,
                videoMediaFormat = extractor.videoMediaFormat,
                audioFormat = extractor.audioFormat,
                audioMediaFormat = extractor.audioMediaFormat,
            )
        } catch (e: MergeAbortException) {
            throw e
        } catch (e: Exception) {
            throw MergeAbortException(
                "merge_segmento_no_legible",
                e.javaClass.simpleName,
                listOf(input.index),
            )
        } finally {
            try {
                extractor.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Resultado de la copia: contadores y medidas que la verificación
     * del tmp contrastará (§2.5.2/§2.5.3).
     */
    private data class MergeCopyResult(
        val videoSampleCount: Int,
        val audioSampleCount: Int,
        val totalVideoDurationMs: Long,
        val totalAudioDurationMs: Long,
        val audioGapSegments: List<Int>,
        val avOffsetDriftMsBySegment: Map<Int, Long>,
    )

    /**
     * Paso 5: copia con rebase POR PISTA. `nuevoPts = ptsEnSegmento +
     * offsetAcumuladoPista`; el offset de cada pista avanza con SU
     * duración efectiva (declarada, o tramo PTS medido si falta).
     * Si un segmento no tiene audio, su duración de audio es 0 y el
     * offset de audio NO avanza (plan §2.4 paso 5 tal como está
     * escrito; ver la limitación 2 del KDoc de la clase): el audio
     * del segmento posterior aterriza adelantado respecto a su video
     * y el hueco queda registrado en `audioGapSegments`.
     * PROHIBIDO restar offsets «para alinear» (regla de
     * `AudioSync.kt`): el offset A/V intra-segmento se conserva tal
     * cual lo deja el rebase por pista (salvo la deriva documentada).
     *
     * Muestras `BUFFER_FLAG_CODEC_CONFIG` no se escriben (el csd va
     * en el formato de pista); `BUFFER_FLAG_END_OF_STREAM` se quita
     * de toda muestra intermedia y solo se conserva en la última
     * muestra de cada pista del último segmento, si la llevaba.
     * Monotonicidad por pista verificada mientras se escribe
     * (también EN LA FRONTERA entre segmentos).
     *
     * Sin suspensiones en todo el bucle: el hilo del muxer no cambia.
     */
    private fun copySegments(
        tracks: List<MergeSegmentTracks>,
        muxer: RecordingMuxer,
        videoTrack: Int,
        audioTrack: Int,
        onSegmentCopied: ((Int) -> Unit)? = null,
    ): MergeCopyResult {
        val audioGapSegments = mutableListOf<Int>()
        val driftMsBySegment = mutableMapOf<Int, Long>()
        var videoOffsetUs = 0L
        var audioOffsetUs = 0L
        var totalVideoDurationMs = 0L
        var totalAudioDurationMs = 0L
        var totalVideoSamples = 0
        var totalAudioSamples = 0
        // Último PTS REBASADO escrito por pista (vive fuera del bucle
        // por segmento: la monotonicidad se comprueba también EN LA
        // FRONTERA entre segmentos, no solo dentro de cada uno).
        var lastWrittenVideoPtsUs: Long? = null
        var lastWrittenAudioPtsUs: Long? = null

        for ((position, track) in tracks.withIndex()) {
            val input = track.input
            val isLastSegment = position == tracks.lastIndex
            val extractor = try {
                extractorFactory(input.file)
            } catch (e: Exception) {
                throw MergeAbortException(
                    "merge_segmento_no_legible",
                    e.javaClass.simpleName,
                    listOf(input.index),
                )
            }
            try {
                var firstVideoPtsUs: Long? = null
                var lastSegmentVideoPtsUs: Long? = null
                var firstAudioPtsUs: Long? = null
                var lastSegmentAudioPtsUs: Long? = null
                var videoSamples = 0
                var audioSamples = 0
                // Retención de una muestra por pista: la última
                // muestra de cada pista solo se escribe al saber si
                // hay sucesora (o al cerrar el segmento), para
                // decidir el flag END_OF_STREAM con exactitud.
                var pendingVideo: ExtractedSample? = null
                var pendingAudio: ExtractedSample? = null

                fun writeVideo(sample: ExtractedSample, preserveEos: Boolean) {
                    val flags = if (preserveEos) {
                        sample.flags
                    } else {
                        sample.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                    }
                    val rebasedPtsUs = sample.ptsUs + videoOffsetUs
                    if (lastWrittenVideoPtsUs != null &&
                        rebasedPtsUs <= lastWrittenVideoPtsUs!!
                    ) {
                        throw MergeAbortException(
                            "merge_pts_no_monotonos",
                            "video seg${input.index}",
                        )
                    }
                    muxer.writeSampleData(
                        videoTrack,
                        sample.data,
                        MediaCodec.BufferInfo().apply {
                            offset = 0
                            size = sample.data.remaining()
                            presentationTimeUs = rebasedPtsUs
                            this.flags = flags
                        },
                    )
                    lastWrittenVideoPtsUs = rebasedPtsUs
                    if (firstVideoPtsUs == null) {
                        firstVideoPtsUs = sample.ptsUs
                    }
                    lastSegmentVideoPtsUs = sample.ptsUs
                    videoSamples++
                }

                fun writeAudio(sample: ExtractedSample, preserveEos: Boolean) {
                    val flags = if (preserveEos) {
                        sample.flags
                    } else {
                        sample.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                    }
                    val rebasedPtsUs = sample.ptsUs + audioOffsetUs
                    if (lastWrittenAudioPtsUs != null &&
                        rebasedPtsUs <= lastWrittenAudioPtsUs!!
                    ) {
                        throw MergeAbortException(
                            "merge_pts_no_monotonos",
                            "audio seg${input.index}",
                        )
                    }
                    muxer.writeSampleData(
                        audioTrack,
                        sample.data,
                        MediaCodec.BufferInfo().apply {
                            offset = 0
                            size = sample.data.remaining()
                            presentationTimeUs = rebasedPtsUs
                            this.flags = flags
                        },
                    )
                    lastWrittenAudioPtsUs = rebasedPtsUs
                    if (firstAudioPtsUs == null) {
                        firstAudioPtsUs = sample.ptsUs
                    }
                    lastSegmentAudioPtsUs = sample.ptsUs
                    audioSamples++
                }

                while (true) {
                    val sample = try {
                        extractor.nextSample()
                    } catch (e: Exception) {
                        throw MergeAbortException(
                            "merge_segmento_no_legible",
                            e.javaClass.simpleName,
                            listOf(input.index),
                        )
                    } ?: break
                    // El csd va en el formato de pista, nunca como
                    // muestra (plan §2.4 paso 5).
                    if (sample.flags and
                        MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    ) {
                        continue
                    }
                    if (sample.isVideo) {
                        pendingVideo?.let { writeVideo(it, preserveEos = false) }
                        pendingVideo = sample
                    } else {
                        pendingAudio?.let { writeAudio(it, preserveEos = false) }
                        pendingAudio = sample
                    }
                }
                // Última muestra de cada pista del segmento: solo en
                // el último segmento conserva su EOS original.
                pendingVideo?.let { writeVideo(it, preserveEos = isLastSegment) }
                pendingAudio?.let { writeAudio(it, preserveEos = isLastSegment) }

                if (videoSamples == 0) {
                    throw MergeAbortException("sin_muestras", "video seg${input.index}")
                }
                if (audioSamples == 0) {
                    // Segmento sin audio (sin pista o sin muestras):
                    // hueco registrado; el offset de audio no avanza.
                    audioGapSegments += input.index
                } else if (firstVideoPtsUs != null && firstAudioPtsUs != null) {
                    // Deriva del offset A/V de este segmento en el
                    // fusionado frente a su offset original.
                    val originalAvOffsetMs =
                        (firstAudioPtsUs!! - firstVideoPtsUs!!) / 1_000L
                    val mergedAvOffsetMs =
                        ((firstAudioPtsUs!! + audioOffsetUs) -
                            (firstVideoPtsUs!! + videoOffsetUs)) / 1_000L
                    driftMsBySegment[input.index] =
                        mergedAvOffsetMs - originalAvOffsetMs
                }

                // El offset de cada pista avanza con SU duración
                // efectiva (declarada, o tramo PTS + 1 frame/trama).
                val videoDurationMs = effectiveDurationMs(
                    input.videoDurationMs,
                    firstVideoPtsUs,
                    lastSegmentVideoPtsUs,
                ) ?: throw MergeAbortException(
                    "sin_muestras",
                    "video seg${input.index}",
                )
                videoOffsetUs += videoDurationMs * 1_000L
                totalVideoDurationMs += videoDurationMs
                totalVideoSamples += videoSamples
                val audioDurationMs = if (audioSamples == 0) {
                    0L
                } else {
                    effectiveAudioDurationMs(
                        input.audioDurationMs,
                        firstAudioPtsUs,
                        lastSegmentAudioPtsUs,
                        track.audioFormat?.sampleRate,
                    ) ?: 0L
                }
                audioOffsetUs += audioDurationMs * 1_000L
                totalAudioDurationMs += audioDurationMs
                totalAudioSamples += audioSamples
            } finally {
                try {
                    extractor.close()
                } catch (_: Exception) {
                }
            }
            // J1-b: progreso por segmento copiado. Nunca aborta la
            // fusión: un callback que lance se traga aquí.
            try {
                onSegmentCopied?.invoke(position + 1)
            } catch (_: Exception) {
            }
        }

        return MergeCopyResult(
            videoSampleCount = totalVideoSamples,
            audioSampleCount = totalAudioSamples,
            totalVideoDurationMs = totalVideoDurationMs,
            totalAudioDurationMs = totalAudioDurationMs,
            audioGapSegments = audioGapSegments,
            avOffsetDriftMsBySegment = driftMsBySegment,
        )
    }

    private fun safeRelease(muxer: RecordingMuxer) {
        try {
            muxer.release()
        } catch (_: Exception) {
        }
    }

    /** Borra el tmp de fusión fallido (propio, regenerable). */
    private fun discardTmp(tmpFile: File) {
        try {
            if (tmpFile.exists()) tmpFile.delete()
        } catch (_: Exception) {
        }
    }
}

/** `getInteger` con valor por defecto (no existe en `MediaFormat`). */
private fun MediaFormat.mergeIntOrNull(key: String): Int? =
    if (containsKey(key)) getInteger(key) else null

/**
 * Demuxer real de un segmento con `MediaExtractor` (solo producción:
 * en JVM no existe; los tests inyectan un falso). Lee las muestras en
 * orden de archivo (entrelazadas por PTS) y los formatos reales de
 * las pistas. El `MediaFormat` de cada pista se devuelve tal cual
 * para `addTrack` del muxer.
 */
internal class MediaSegmentExtractor(file: File) : SegmentExtractor {
    private val extractor = MediaExtractor()
    private val videoTrackIndex: Int
    private val audioTrackIndex: Int
    private val readBuffer: ByteBuffer
    private var closed = false

    override val videoFormat: MergeVideoFormat
    override val videoMediaFormat: MediaFormat
    override val audioFormat: MergeAudioFormat?
    override val audioMediaFormat: MediaFormat?

    init {
        extractor.setDataSource(file.absolutePath)
        var videoTrack = -1
        var audioTrack = -1
        var videoMediaFormat: MediaFormat? = null
        var audioMediaFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            when (format.getString(MediaFormat.KEY_MIME)) {
                MediaFormat.MIMETYPE_VIDEO_AVC -> {
                    if (videoTrack < 0) {
                        videoTrack = i
                        videoMediaFormat = format
                    }
                }
                MediaFormat.MIMETYPE_AUDIO_AAC -> {
                    if (audioTrack < 0) {
                        audioTrack = i
                        audioMediaFormat = format
                    }
                }
            }
        }
        if (videoTrack < 0 || videoMediaFormat == null) {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
            throw IllegalStateException("sin_pista_avc")
        }
        videoTrackIndex = videoTrack
        audioTrackIndex = audioTrack
        this.videoMediaFormat = videoMediaFormat
        this.audioMediaFormat = audioMediaFormat
        videoFormat = videoMediaFormat.toMergeVideoFormat()
        audioFormat = audioMediaFormat?.toMergeAudioFormat()
        extractor.selectTrack(videoTrackIndex)
        if (audioTrackIndex >= 0) {
            extractor.selectTrack(audioTrackIndex)
        }
        // Como el verificador: KEY_MAX_INPUT_SIZE o 1 MB.
        val maxInputSize = (videoMediaFormat.mergeIntOrNull(
            MediaFormat.KEY_MAX_INPUT_SIZE,
        ) ?: (1024 * 1024)).coerceAtLeast(1024)
        readBuffer = ByteBuffer.allocate(maxInputSize)
    }

    override fun nextSample(): ExtractedSample? {
        check(!closed) { "extractor cerrado" }
        readBuffer.clear()
        val sampleSize = extractor.readSampleData(readBuffer, 0)
        if (sampleSize < 0) return null
        val isVideo = extractor.sampleTrackIndex == videoTrackIndex
        val ptsUs = extractor.sampleTime
        val flags = extractor.sampleFlags
        // Copia propia por muestra: el muxer la consume de forma
        // síncrona, pero el buffer de lectura se reutiliza.
        val payload = ByteBuffer.allocate(sampleSize)
        readBuffer.limit(sampleSize)
        readBuffer.position(0)
        payload.put(readBuffer)
        payload.flip()
        extractor.advance()
        return ExtractedSample(
            isVideo = isVideo,
            ptsUs = ptsUs,
            flags = flags,
            data = payload,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            extractor.release()
        } catch (_: Exception) {
        }
    }
}

/** `csd-0`/`csd-1` como copia (`null` si la pista no lo lleva). */
private fun MediaFormat.mergeCsd(key: String): ByteArray? {
    val buffer = try {
        getByteBuffer(key)?.duplicate()
    } catch (_: Exception) {
        null
    } ?: return null
    if (!buffer.hasRemaining()) return null
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

/**
 * Formato de video plano desde el `MediaFormat` real. `profileIdc`
 * por `KEY_PROFILE` o, como el verificador, por el byte 1 del
 * `csd-0` (AVCProfileIndication).
 */
private fun MediaFormat.toMergeVideoFormat(): MergeVideoFormat {
    val csd0 = mergeCsd("csd-0")
    val profileIdc = mergeIntOrNull(MediaFormat.KEY_PROFILE)
        ?: csd0?.takeIf { it.size >= 2 }?.get(1)?.toInt()?.and(0xFF)
    return MergeVideoFormat(
        mime = try {
            getString(MediaFormat.KEY_MIME)
        } catch (_: Exception) {
            null
        } ?: "",
        width = mergeIntOrNull(MediaFormat.KEY_WIDTH)?.takeIf { it > 0 },
        height = mergeIntOrNull(MediaFormat.KEY_HEIGHT)?.takeIf { it > 0 },
        profileIdc = profileIdc,
        csd0 = csd0,
        csd1 = mergeCsd("csd-1"),
    )
}

/**
 * Formato de audio plano desde el `MediaFormat` real. El perfil AAC
 * sale del AudioSpecificConfig (`csd-0`): los 5 bits altos del
 * primer byte son el `audioObjectType` (AAC-LC = 2).
 */
private fun MediaFormat.toMergeAudioFormat(): MergeAudioFormat {
    val csd0 = mergeCsd("csd-0")
    val profile = csd0?.takeIf { it.isNotEmpty() }
        ?.get(0)?.toInt()?.and(0xFF)?.ushr(3)
    return MergeAudioFormat(
        sampleRate = mergeIntOrNull(MediaFormat.KEY_SAMPLE_RATE)
            ?.takeIf { it > 0 },
        channels = mergeIntOrNull(MediaFormat.KEY_CHANNEL_COUNT)
            ?.takeIf { it > 0 },
        profile = profile,
        csd0 = csd0,
    )
}
