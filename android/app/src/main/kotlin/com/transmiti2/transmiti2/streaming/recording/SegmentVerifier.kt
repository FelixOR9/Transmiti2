package com.transmiti2.transmiti2.streaming.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Estado de un segmento tras la verificación (plan 3.1.2 §7.1). */
enum class SegmentStatus {
    /** Cerrado correctamente y verificablemente reproducible. */
    READY,

    /** Interrumpido con tramo inicial legible y cola truncada. */
    PARTIAL,

    /** No recuperable o sin tramo legible suficiente. */
    CORRUPT,
}

/**
 * Medidas de un archivo, sin clasificar. Las produce `verify()` y las
 * valora `classifySegment` (pura, testeable en JVM).
 */
data class VerificationChecks(
    val extractorOpens: Boolean,
    val hasAvcTrack: Boolean,
    val width: Int?,
    val height: Int?,
    val sampleCount: Int,
    val keyframeCount: Int,
    val firstSampleKeyframe: Boolean,
    val ptsMonotonic: Boolean,
    val durationMs: Long?,
    val fpsMeasured: Double?,
    // Duración de la fase 1 (extractor + decodificación completa),
    // en ms (ronda 4, fix 2). `null` si la fase no se midió (la
    // verificación no llegó a esa fase).
    val ioPhaseMs: Long?,
    val profileIdc: Int?,
    val decodeOk: Boolean,
    val decodedFrames: Int,
    val exoplayerReady: Boolean,
    val exoplayerDurationMs: Long?,
)

/** Resultado de verificar un segmento. */
data class SegmentVerification(
    val status: SegmentStatus,
    val checks: VerificationChecks,
    /** Motivo de `corrupt` (o `null` si es `ready`/`partial`). */
    val reason: String?,
)

/**
 * Medidas parciales que `verifyInternal` va rellenando a medida que
 * las toma (ronda 2). Si el timeout salta o una excepción corta la
 * verificación, `verify()` clasifica con lo acumulado aquí en vez de
 * devolver `emptyChecks()`: la duración medida sobrevive y el total
 * la suma. Los campos empiezan como «no medido» (igual que
 * `emptyChecks()`).
 */
private class PartialChecks {
    var extractorOpens = false
    var hasAvcTrack = false
    var width: Int? = null
    var height: Int? = null
    var sampleCount = 0
    var keyframeCount = 0
    var firstSampleKeyframe = false
    var ptsMonotonic = true
    var durationMs: Long? = null
    var fpsMeasured: Double? = null
    // Ronda 4, fix 2: duración de la fase 1 en ms (ver
    // `VerificationChecks.ioPhaseMs`). Se fija en el `finally` de
    // dentro del bloque de E/S.
    var ioPhaseMs: Long? = null
    var profileIdc: Int? = null
    var decodeOk = false
    var decodedFrames = 0
    var exoplayerReady = false
    var exoplayerDurationMs: Long? = null

    fun toChecks() = VerificationChecks(
        extractorOpens = extractorOpens,
        hasAvcTrack = hasAvcTrack,
        width = width,
        height = height,
        sampleCount = sampleCount,
        keyframeCount = keyframeCount,
        firstSampleKeyframe = firstSampleKeyframe,
        ptsMonotonic = ptsMonotonic,
        durationMs = durationMs,
        fpsMeasured = fpsMeasured,
        ioPhaseMs = ioPhaseMs,
        profileIdc = profileIdc,
        decodeOk = decodeOk,
        decodedFrames = decodedFrames,
        exoplayerReady = exoplayerReady,
        exoplayerDurationMs = exoplayerDurationMs,
    )
}

/**
 * Duración efectiva de un segmento (ms): la del contenedor
 * (`KEY_DURATION`) cuando es válida (> 0); si falta o es 0, el tramo
 * PTS de las muestras leídas + 1 frame (33 ms). Pura, testeable en
 * JVM. (Fix duraciones 3.1.2, paso 4: el fallback solo cubría la
 * clave ausente, no el valor 0 — un `KEY_DURATION = 0` clasificaba el
 * segmento `corrupt` con `duracion_no_valida` aunque las muestras
 * estuvieran completas.)
 */
fun effectiveDurationMs(
    keyDurationMs: Long?,
    firstPtsUs: Long?,
    lastPtsUs: Long?,
): Long? {
    if (keyDurationMs != null && keyDurationMs > 0) {
        return keyDurationMs
    }
    if (firstPtsUs != null && lastPtsUs != null) {
        return (lastPtsUs - firstPtsUs) / 1000 + 33
    }
    return null
}

/**
 * FPS medido a partir de muestras y duración (ronda 4). Pura,
 * testeable en JVM. La fórmula es la misma que usaba el cálculo
 * dentro de `verifyInternal` (y que el spike: muestras/duración);
 * como función pura se puede usar también desde los `catch` de
 * `verify()` para recalcular el FPS desde lo acumulado (cinturón
 * del fix 1). Sin muestras o sin duración válida no hay FPS.
 */
fun computeFpsMeasured(sampleCount: Int, durationMs: Long?): Double? {
    if (durationMs != null && durationMs > 0 && sampleCount > 0) {
        return sampleCount * 1000.0 / durationMs
    }
    return null
}

/**
 * Clasifica un segmento a partir de sus medidas (plan §7.1). Pura:
 * no toca Android, testeable en JVM.
 *
 * - Cerrado correctamente y todas las comprobaciones pasan → `ready`.
 * - Interrumpido y todas las comprobaciones pasan → `partial` (el
 *   tramo legible existe; la cola se perdió por la interrupción).
 * - Cualquier comprobación falla → `corrupt`, con el motivo.
 *
 * La resolución y el FPS esperados pueden ser `null` (recuperación
 * sin sidecar): entonces esas dos comprobaciones se omiten y el
 * segmento se clasifica solo por su legibilidad. El perfil
 * (`profileIdc`) es solo diagnóstico: no decide el estado.
 */
fun classifySegment(
    closedCleanly: Boolean,
    checks: VerificationChecks,
    expectedWidth: Int?,
    expectedHeight: Int?,
    expectedFps: Int?,
): SegmentVerification {
    val reason = when {
        !checks.extractorOpens -> "extractor_no_abre"
        !checks.hasAvcTrack -> "sin_pista_avc"
        checks.sampleCount == 0 -> "sin_muestras"
        !checks.firstSampleKeyframe -> "primer_sample_no_keyframe"
        !checks.ptsMonotonic -> "pts_no_monotonos"
        expectedWidth != null && expectedHeight != null &&
            (checks.width != expectedWidth ||
                checks.height != expectedHeight) ->
            "resolucion_no_coincide"
        checks.durationMs == null || checks.durationMs <= 0 ->
            "duracion_no_valida"
        expectedFps != null &&
            (checks.fpsMeasured == null ||
                abs(checks.fpsMeasured - expectedFps) > FPS_TOLERANCE) ->
            "fps_no_coincide"
        !checks.decodeOk -> "decode_incompleto"
        !checks.exoplayerReady -> "exoplayer_no_prepara"
        checks.exoplayerDurationMs != null &&
            checks.durationMs != null &&
            abs(checks.exoplayerDurationMs - checks.durationMs) >
                DURATION_TOLERANCE_MS -> "duracion_no_coincide"
        else -> null
    }
    if (reason != null) {
        return SegmentVerification(SegmentStatus.CORRUPT, checks, reason)
    }
    return if (closedCleanly) {
        SegmentVerification(SegmentStatus.READY, checks, null)
    } else {
        SegmentVerification(SegmentStatus.PARTIAL, checks, null)
    }
}

/**
 * Tolerancia del FPS medido frente al esperado (ronda 3: 2,0 → 10,0).
 *
 * La comprobación NUNCA exigió 30 exactos: ±2,0 ya cubría la
 * fluctuación del codificador (el spike mide 29,9 de forma
 * consistente y el test `FPS dentro de tolerancia pasa` lo prueba).
 * Pero el teléfono marcó `fps_no_coincide` en dos segmentos de
 * 60007 ms y 50178 ms cerrados limpios (grabación
 * `rec_20261119_003423_9c1c`, 00:34, de noche): el FPS medido estaba
 * fuera de [28, 32]. La cámara entrega el mínimo de su rango
 * (`fpsRange`, variable) con poca luz o pierde muestras bajo carga;
 * eso es entrega legítima, no un archivo corrupto. El calentamiento
 * del codificador (~0,6 s) NO explica el fallo: el FPS se calcula
 * como muestras/duración, así que menos muestras en menos tiempo
 * sigue dando ~30.
 *
 * ±10 acepta hasta 20 FPS (2/3 del objetivo de 30: el mínimo de un
 * rango variable típico, 24, con margen, y caídas por carga hasta
 * ~20) y sigue rechazando la mitad del objetivo (15 FPS) y menos
 * como defecto real. El FPS medido real del teléfono se muestra
 * ahora en el diagnóstico (ronda 3, instrumentación): si resulta
 * estar por debajo de 20, el siguiente paso es investigar el rango
 * de la cámara y las condiciones, no ampliar a ciegas.
 * Compartida por `classifySegment` y `classifyIncomplete`.
 */
private const val FPS_TOLERANCE = 10.0
private const val DURATION_TOLERANCE_MS = 100L

/**
 * Clasifica un segmento cuya verificación NO terminó (timeout o
 * excepción) a partir de las medidas parciales que sí se tomaron
 * (ronda 2). Pura, testeable en JVM. Regla:
 *
 * - Si las medidas de legibilidad pasan (extractor, pista AVC,
 *   muestras, primer keyframe, PTS, resolución, duración, FPS), el
 *   archivo es utilizable aunque la decodificación completa o
 *   ExoPlayer no se confirmaran a tiempo → `partial` con el motivo
 *   (`verification_timeout` / `verification_error`) conservado y
 *   visible, y la duración medida sobrevive (el total la suma).
 * - Si alguna medida de legibilidad falla, el archivo es genuinamente
 *   defectuoso según lo medido → `corrupt` con ese motivo (más
 *   específico que el timeout).
 *
 * No usa `decodeOk` ni `exoplayerReady`: en este camino esos checks
 * son «no verificados a tiempo», no «fallidos». Tampoco usa
 * `exoplayerDurationMs`: sin confirmación de ExoPlayer no hay
 * contraste de duraciones que hacer.
 */
fun classifyIncomplete(
    checks: VerificationChecks,
    expectedWidth: Int?,
    expectedHeight: Int?,
    expectedFps: Int?,
    reason: String,
): SegmentVerification {
    val legibilityReason = when {
        !checks.extractorOpens -> "extractor_no_abre"
        !checks.hasAvcTrack -> "sin_pista_avc"
        checks.sampleCount == 0 -> "sin_muestras"
        !checks.firstSampleKeyframe -> "primer_sample_no_keyframe"
        !checks.ptsMonotonic -> "pts_no_monotonos"
        expectedWidth != null && expectedHeight != null &&
            (checks.width != expectedWidth ||
                checks.height != expectedHeight) ->
            "resolucion_no_coincide"
        checks.durationMs == null || checks.durationMs <= 0 ->
            "duracion_no_valida"
        expectedFps != null &&
            (checks.fpsMeasured == null ||
                abs(checks.fpsMeasured - expectedFps) > FPS_TOLERANCE) ->
            "fps_no_coincide"
        else -> null
    }
    if (legibilityReason != null) {
        return SegmentVerification(SegmentStatus.CORRUPT, checks, legibilityReason)
    }
    return SegmentVerification(SegmentStatus.PARTIAL, checks, reason)
}

/** Verifica un archivo de segmento y lo clasifica (plan §7.1). */
interface SegmentVerifier {
    /**
     * La configuración esperada puede ser `null` (recuperación sin
     * sidecar): entonces la resolución y el FPS no se comprueban y el
     * segmento se clasifica solo por su legibilidad.
     */
    suspend fun verify(
        file: File,
        closedCleanly: Boolean,
        expectedWidth: Int?,
        expectedHeight: Int?,
        expectedFps: Int?,
    ): SegmentVerification
}

/**
 * Verificación real de un segmento (Fase B, 3.1.2; adopta la
 * verificación del spike, `MuxerSpikeRunner.verifyFileInternal`, y le
 * añade la decodificación secuencial completa que el plan §7.1 exige
 * para `ready`):
 *
 * 1. `MediaExtractor` abre el archivo y lee TODAS las muestras (nunca
 *    un muestreo): cuenta muestras y keyframes, primer keyframe, PTS
 *    estrictamente crecientes, duración, resolución y `profile_idc`.
 * 2. Decodificador `MediaCodec` H.264: decodifica TODAS las muestras
 *    en orden, hasta EOS. Un segmento `ready` exige la decodificación
 *    completa, no solo la lectura.
 * 3. ExoPlayer (en el hilo principal) prepara el archivo y confirma
 *    su duración (±100 ms de la del extractor).
 *
 * No lanza por un archivo ilegible: lo mide y lo clasifica `corrupt`
 * con el motivo. Nunca modifica el original: la verificación es de
 * solo lectura. Timeout global 60 s por segmento (ronda 2: 30 s →
 * 60 s).
 */
class DefaultSegmentVerifier(
    private val context: Context,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : SegmentVerifier {

    override suspend fun verify(
        file: File,
        closedCleanly: Boolean,
        expectedWidth: Int?,
        expectedHeight: Int?,
        expectedFps: Int?,
    ): SegmentVerification {
        if (!file.isFile) {
            return SegmentVerification(
                SegmentStatus.CORRUPT,
                emptyChecks(),
                "archivo_no_existe",
            )
        }
        // Acumulador de medidas parciales (ronda 2): `verifyInternal`
        // escribe cada medida en cuanto la toma. Si el timeout salta
        // (o una excepción posterior), lo ya medido NO se descarta:
        // el segmento se clasifica con esas medidas (`partial` con
        // duración si es legible) en vez de `corrupt` sin medidas.
        val partial = PartialChecks()
        return try {
            withTimeout(VERIFY_TIMEOUT_MS) {
                val checks = verifyInternal(file, partial)
                classifySegment(
                    closedCleanly,
                    checks,
                    expectedWidth,
                    expectedHeight,
                    expectedFps,
                )
            }
        } catch (e: TimeoutCancellationException) {
            Transmiti2Logger.e(
                "verificacion: timeout verificando ${file.name} " +
                    "(fase E/S ${partial.ioPhaseMs}ms)",
            )
            // Cinturón (ronda 4, fix 1): recalcular el FPS desde lo
            // acumulado por si la excepción escapó antes de la línea
            // de dentro del bloque. Idempotente cuando ya está
            // fijado; `null` si no hay medidas (correcto: sin
            // medidas no hay FPS).
            partial.fpsMeasured = computeFpsMeasured(
                partial.sampleCount,
                partial.durationMs,
            )
            classifyIncomplete(
                partial.toChecks(),
                expectedWidth,
                expectedHeight,
                expectedFps,
                "verification_timeout",
            )
        } catch (e: Exception) {
            Transmiti2Logger.e(
                "verificacion: error verificando ${file.name}: " +
                    "${e.javaClass.simpleName} ${e.message} " +
                    "(fase E/S ${partial.ioPhaseMs}ms)",
            )
            // Cinturón (ronda 4, fix 1): como arriba.
            partial.fpsMeasured = computeFpsMeasured(
                partial.sampleCount,
                partial.durationMs,
            )
            classifyIncomplete(
                partial.toChecks(),
                expectedWidth,
                expectedHeight,
                expectedFps,
                "verification_error",
            )
        }
    }

    private fun emptyChecks() = VerificationChecks(
        extractorOpens = false,
        hasAvcTrack = false,
        width = null,
        height = null,
        sampleCount = 0,
        keyframeCount = 0,
        firstSampleKeyframe = false,
        ptsMonotonic = false,
        durationMs = null,
        fpsMeasured = null,
        ioPhaseMs = null,
        profileIdc = null,
        decodeOk = false,
        decodedFrames = 0,
        exoplayerReady = false,
        exoplayerDurationMs = null,
    )

    private suspend fun verifyInternal(
        file: File,
        partial: PartialChecks,
    ): VerificationChecks {
        var trackFormat: MediaFormat? = null

        // 1. Lectura completa con el extractor (y recolección de las
        // muestras para la decodificación: el extractor solo se lee en
        // orden, así que la decodificación se hace en la misma pasada).
        // Cada medida se escribe en `partial` en cuanto se toma: si el
        // timeout salta en la fase 2 (o una excepción posterior), lo ya
        // medido sobrevive (ronda 2). Esta fase no tiene puntos de
        // suspensión EN SU INTERIOR — solo llamadas bloqueantes — pero
        // el retorno del `withContext` SÍ es un punto de suspensión:
        // el timeout puede saltar justo ahí, antes de cualquier línea
        // posterior al bloque (ronda 4, diagnóstico `fps_no_coincide`
        // §3: así quedó `fpsMeasured` en `null`). Por eso cada medida
        // que deba sobrevivir al timeout se fija DENTRO del bloque.
        // Ronda 4, fix 2: inicio de la fase 1. La duración se fija
        // en el `finally` de dentro del bloque, no después de él:
        // el timeout salta en el retorno del `withContext` y una
        // medida tomada después no existiría justo en el caso que
        // hay que diagnosticar.
        val ioStartMs = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            var decodedFrames = 0
            var decodeOk = false
            try {
                extractor.setDataSource(file.absolutePath)
                partial.extractorOpens = true
                var videoTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                        videoTrack = i
                        trackFormat = format
                        break
                    }
                }
                if (videoTrack < 0 || trackFormat == null) {
                    return@withContext
                }
                partial.hasAvcTrack = true
                val format = trackFormat!!
                partial.width = format.intOrNull(MediaFormat.KEY_WIDTH)
                    ?.takeIf { it > 0 }
                partial.height = format.intOrNull(MediaFormat.KEY_HEIGHT)
                    ?.takeIf { it > 0 }
                val durationUs = format.longOrNull(
                    MediaFormat.KEY_DURATION,
                )
                partial.durationMs = durationUs?.div(1000)
                partial.profileIdc = format.intOrNull(MediaFormat.KEY_PROFILE)
                    ?: format.getByteBuffer("csd-0")
                        ?.duplicate()
                        ?.takeIf { it.remaining() >= 2 }
                        ?.get(1)
                        ?.toInt()
                extractor.selectTrack(videoTrack)
                val maxInputSize = (format.intOrNull(
                    MediaFormat.KEY_MAX_INPUT_SIZE,
                ) ?: (1024 * 1024)).coerceAtLeast(1024)
                val readBuffer = ByteBuffer.allocate(maxInputSize)
                // Decodificador: se alimenta en la misma pasada en la
                // que se leen las muestras (el extractor es secuencial).
                decoder = try {
                    MediaCodec.createDecoderByType(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                    ).apply {
                        configure(format, null, null, 0)
                        start()
                    }
                } catch (e: Exception) {
                    Transmiti2Logger.e(
                        "verificacion: sin decodificador: " +
                            "${e.javaClass.simpleName}",
                    )
                    null
                }
                var prevPtsUs = Long.MIN_VALUE
                var firstPtsUs: Long? = null
                var lastPtsUs: Long? = null
                var fedSamples = 0
                var eosSeen = false
                while (true) {
                    readBuffer.clear()
                    val sampleSize = extractor.readSampleData(
                        readBuffer,
                        0,
                    )
                    if (sampleSize < 0) break
                    val ptsUs = extractor.sampleTime
                    val flags = extractor.sampleFlags
                    val isKeyframe = flags and
                        MediaCodec.BUFFER_FLAG_SYNC_FRAME != 0
                    if (partial.sampleCount == 0) {
                        partial.firstSampleKeyframe = isKeyframe
                        firstPtsUs = ptsUs
                    }
                    if (ptsUs <= prevPtsUs && partial.sampleCount > 0) {
                        partial.ptsMonotonic = false
                    }
                    prevPtsUs = ptsUs
                    lastPtsUs = ptsUs
                    if (isKeyframe) partial.keyframeCount++
                    partial.sampleCount++
                    // Alimentar al decodificador con esta muestra.
                    if (decoder != null) {
                        val queued = queueSampleToDecoder(
                            decoder,
                            readBuffer,
                            sampleSize,
                            ptsUs,
                            flags,
                        )
                        if (queued) fedSamples++
                        val drained = drainDecoder(decoder, false)
                        decodedFrames += drained.first
                        if (drained.second) eosSeen = true
                    }
                    extractor.advance()
                }
                // Duración efectiva: si el contenedor no trae duración
                // (o trae 0), el tramo PTS de las muestras (paso 4).
                partial.durationMs = effectiveDurationMs(
                    partial.durationMs,
                    firstPtsUs,
                    lastPtsUs,
                )
                // Ronda 4, fix 1: el FPS se calcula AQUÍ, dentro de la
                // fase de E/S, cuando duración y muestras ya son
                // finales. Antes se calculaba después del
                // `withContext` y el timeout saltaba en su retorno
                // antes de esa línea: `fpsMeasured` quedaba en `null`
                // y la comprobación fallaba por la rama del nulo con
                // `fps_no_coincide`. Con el cálculo dentro, un timeout
                // en el borde deja el FPS fijado y `classifyIncomplete`
                // lo usa (→ `partial` con `verification_timeout`).
                partial.fpsMeasured = computeFpsMeasured(
                    partial.sampleCount,
                    partial.durationMs,
                )
                // EOS al decodificador y drenado final.
                if (decoder != null) {
                    var eosQueued = false
                    try {
                        val inputIndex = decoder.dequeueInputBuffer(
                            DECODER_TIMEOUT_US,
                        )
                        if (inputIndex >= 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            eosQueued = true
                        }
                    } catch (_: Exception) {
                    }
                    if (eosQueued) {
                        val drained = drainDecoder(decoder, true)
                        decodedFrames += drained.first
                        if (drained.second) eosSeen = true
                    }
                    // `ready` exige la decodificación COMPLETA: todas
                    // las muestras alimentadas y el EOS de salida visto.
                    decodeOk = eosSeen && fedSamples == partial.sampleCount
                }
                partial.decodeOk = decodeOk
                partial.decodedFrames = decodedFrames
            } finally {
                // Ronda 4, fix 2: la duración de la fase 1 se mide
                // AQUÍ, dentro del bloque. El `finally` corre siempre
                // que el bloque termina — también si una excepción
                // escapa de dentro — y corre ANTES del retorno del
                // `withContext`, que es donde salta el timeout. Una
                // medida tomada después del bloque no existiría en
                // ese caso.
                partial.ioPhaseMs = System.currentTimeMillis() - ioStartMs
                try {
                    decoder?.stop()
                } catch (_: Exception) {
                }
                try {
                    decoder?.release()
                } catch (_: Exception) {
                }
                try {
                    extractor.release()
                } catch (_: Exception) {
                }
            }
        }
        // (Ronda 4, fix 1: el FPS ya se calculó dentro de la fase de
        // E/S, con `computeFpsMeasured`; no se recalcula aquí para no
        // dejar dos fuentes del mismo valor.)

        // Ronda 4, fix 2: duración de la fase 1 en logcat. En el
        // camino normal `partial.ioPhaseMs` ya se fijó en el
        // `finally` de dentro del bloque; aquí solo se registra para
        // la próxima decisión (si la fase 1 sigue pasando de 60 s,
        // el timeout la corta en el borde y lo dirá el log del
        // `catch` de `verify()`).
        Transmiti2Logger.d(
            "verificacion: fase E/S ${file.name} " +
                "${partial.ioPhaseMs}ms",
        )

        // 2. ExoPlayer: prepara y confirma la duración.
        val (exoplayerReady, exoplayerDurationMs) = withContext(
            mainDispatcher,
        ) {
            verifyWithPlayer(file)
        }
        partial.exoplayerReady = exoplayerReady
        partial.exoplayerDurationMs = exoplayerDurationMs

        return partial.toChecks()
    }

    /** Encola una muestra al decodificador; `false` si no hubo buffer. */
    private fun queueSampleToDecoder(
        decoder: MediaCodec,
        readBuffer: ByteBuffer,
        sampleSize: Int,
        ptsUs: Long,
        flags: Int,
    ): Boolean {
        return try {
            val inputIndex = decoder.dequeueInputBuffer(DECODER_TIMEOUT_US)
            if (inputIndex < 0) return false
            val inputBuffer = decoder.getInputBuffer(inputIndex)
                ?: return false
            inputBuffer.clear()
            // `readBuffer` quedó con la muestra en [0, sampleSize).
            val duplicate = readBuffer.duplicate()
            duplicate.position(0)
            duplicate.limit(sampleSize)
            inputBuffer.put(duplicate)
            decoder.queueInputBuffer(
                inputIndex,
                0,
                sampleSize,
                ptsUs,
                flags,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Drena las salidas disponibles del decodificador. Con
     * `hastaEos`, espera al EOS de salida (timeout 5 s). Devuelve
     * `(frames decodificados, EOS de salida visto)`.
     */
    private fun drainDecoder(
        decoder: MediaCodec,
        hastaEos: Boolean,
    ): Pair<Int, Boolean> {
        var frames = 0
        val info = MediaCodec.BufferInfo()
        val deadlineMs = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        while (true) {
            val outputIndex = try {
                decoder.dequeueOutputBuffer(info, DECODER_TIMEOUT_US)
            } catch (_: Exception) {
                return frames to false
            }
            when {
                outputIndex >= 0 -> {
                    if (info.size > 0) frames++
                    val isEos = info.flags and
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    try {
                        decoder.releaseOutputBuffer(outputIndex, false)
                    } catch (_: Exception) {
                    }
                    if (isEos) return frames to true
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // El formato de salida del decodificador no cambia
                    // el resultado: se sigue drenando.
                }
                else -> {
                    if (!hastaEos) return frames to false
                    if (System.currentTimeMillis() >= deadlineMs) {
                        return frames to false
                    }
                }
            }
        }
    }

    /** ExoPlayer prepara el archivo y confirma su duración. */
    private suspend fun verifyWithPlayer(
        file: File,
    ): Pair<Boolean, Long?> {
        return suspendCancellableCoroutine { cont ->
            val player = ExoPlayer.Builder(context).build()
            cont.invokeOnCancellation {
                try {
                    player.release()
                } catch (_: Exception) {
                }
            }
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(
                    playbackState: Int,
                ) {
                    if (!cont.isActive) return
                    if (playbackState == Player.STATE_READY) {
                        val duration = player.duration
                            .takeIf { it != C.TIME_UNSET }
                        try {
                            player.release()
                        } catch (_: Exception) {
                        }
                        cont.resume(Pair(true, duration))
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!cont.isActive) return
                    try {
                        player.release()
                    } catch (_: Exception) {
                    }
                    cont.resume(Pair(false, null))
                }
            }
            player.addListener(listener)
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
        }
    }

    companion object {
        /**
         * Timeout global de verificación por segmento (ronda 2: 30 s →
         * 60 s). Evidencia del teléfono: la verificación completa de un
         * segmento de 60 s (lectura + decodificación completa +
         * ExoPlayer) no cabe en 30 s mientras el codificador sigue
         * grabando; el de 3,8 s sí. 60 s es la duración del propio
         * segmento y el intervalo de rotación: la decodificación
         * completa corre a ~1× en tiempo real bajo carga, así que el
         * presupuesto debe ser al menos la duración del contenido. Con
         * las verificaciones serializadas, cada una dispone de ese
         * presupuesto sin competir con la siguiente, y la cola no puede
         * crecer sin control: cada verificación está acotada por el
         * intervalo entre rotaciones. Si aun así salta, la ronda 2
         * conserva las medidas (`partial` + duración) en vez de
         * descartarlas, así que el timeout ya no es un acantilado.
         */
        const val VERIFY_TIMEOUT_MS = 60_000L
        const val DECODER_TIMEOUT_US = 100_000L
        const val DRAIN_TIMEOUT_MS = 5_000L
    }
}

/** `getInteger` con valor por defecto (no existe en `MediaFormat`). */
private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) getInteger(key) else null

/** `getLong` con valor por defecto (no existe en `MediaFormat`). */
private fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) getLong(key) else null
