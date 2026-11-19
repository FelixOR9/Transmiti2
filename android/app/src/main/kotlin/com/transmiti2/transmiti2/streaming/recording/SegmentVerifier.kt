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
    // --- Audio (3.1.3, plan §7.1). Todos con valor por defecto «no
    // medido», para que las construcciones existentes (tests y
    // `emptyChecks()`) sigan compilando sin cambios. `audioPtsMonotonic`
    // no estaba en la lista del encargo, pero el plan §7.1 lo mide
    // (tabla «PTS monótonos») y la clasificación lo exige.
    val hasAudioTrack: Boolean = false,
    val audioSampleCount: Int = 0,
    val audioDurationMs: Long? = null,
    val audioSampleRate: Int? = null,
    val audioChannels: Int? = null,
    val audioFirstPtsUs: Long? = null,
    val audioLastPtsUs: Long? = null,
    val audioPtsMonotonic: Boolean = true,
    val audioDecodeOk: Boolean = false,
    val audioDecodedFrames: Int = 0,
    val avOffsetMs: Long? = null,
    val avDurationDiffMs: Long? = null,
    // Duración de referencia para el contraste con ExoPlayer:
    // `max(duración video, duración audio)` cuando hay audio.
    // `null` = la de video (`durationMs`). El valor por defecto es
    // `null` (y no `durationMs`) a propósito: con `copy(durationMs
    // = ...)` un valor por defecto igual a `durationMs` quedaría
    // obsoleto y el contraste usaría una duración vieja.
    val referenceDurationMs: Long? = null,
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
    // Audio (3.1.3): mismos campos que `VerificationChecks`, mutables.
    var hasAudioTrack = false
    var audioSampleCount = 0
    var audioDurationMs: Long? = null
    var audioSampleRate: Int? = null
    var audioChannels: Int? = null
    var audioFirstPtsUs: Long? = null
    var audioLastPtsUs: Long? = null
    var audioPtsMonotonic = true
    var audioDecodeOk = false
    var audioDecodedFrames = 0
    var avOffsetMs: Long? = null
    var avDurationDiffMs: Long? = null
    var referenceDurationMs: Long? = null

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
        hasAudioTrack = hasAudioTrack,
        audioSampleCount = audioSampleCount,
        audioDurationMs = audioDurationMs,
        audioSampleRate = audioSampleRate,
        audioChannels = audioChannels,
        audioFirstPtsUs = audioFirstPtsUs,
        audioLastPtsUs = audioLastPtsUs,
        audioPtsMonotonic = audioPtsMonotonic,
        audioDecodeOk = audioDecodeOk,
        audioDecodedFrames = audioDecodedFrames,
        avOffsetMs = avOffsetMs,
        avDurationDiffMs = avDurationDiffMs,
        referenceDurationMs = referenceDurationMs,
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
 * Duración efectiva de la pista de audio (ms): espejo de
 * `effectiveDurationMs` para video (3.1.3, plan §7.1). La del
 * contenedor (`KEY_DURATION`) cuando es válida (> 0); si falta o es
 * 0, el tramo PTS de las muestras leídas + 1 trama AAC
 * (`1024 / sampleRate`, en ms: 21 ms a 48 kHz, 23 ms a 44,1 kHz).
 * Sin frecuencia de muestreo conocida, 21 ms (48 kHz, el objetivo
 * de D1). Sin muestras no hay duración. Pura, testeable en JVM.
 */
fun effectiveAudioDurationMs(
    keyDurationMs: Long?,
    firstPtsUs: Long?,
    lastPtsUs: Long?,
    sampleRate: Int?,
): Long? {
    if (keyDurationMs != null && keyDurationMs > 0) {
        return keyDurationMs
    }
    if (firstPtsUs != null && lastPtsUs != null) {
        val frameMs = if (sampleRate != null && sampleRate > 0) {
            1024 * 1000L / sampleRate
        } else {
            21L
        }
        return (lastPtsUs - firstPtsUs) / 1000 + frameMs
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
    expectedAudio: Boolean? = null,
    expectedSampleRate: Int? = null,
    expectedChannels: Int? = null,
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
            abs(
                checks.exoplayerDurationMs -
                    (checks.referenceDurationMs ?: checks.durationMs),
            ) > DURATION_TOLERANCE_MS -> "duracion_no_coincide"
        else -> null
    }
    if (reason != null) {
        return SegmentVerification(SegmentStatus.CORRUPT, checks, reason)
    }
    // Video válido: las reglas de audio (3.1.3, plan §7.3). Un fallo
    // de audio con video válido es `partial` con el motivo de audio,
    // nunca `corrupt`: el video sigue siendo utilizable.
    val audioReason = audioFailureReason(
        checks,
        expectedAudio,
        expectedSampleRate,
        expectedChannels,
        incomplete = false,
    )
    if (audioReason != null) {
        return SegmentVerification(SegmentStatus.PARTIAL, checks, audioReason)
    }
    return if (closedCleanly) {
        SegmentVerification(SegmentStatus.READY, checks, null)
    } else {
        SegmentVerification(SegmentStatus.PARTIAL, checks, null)
    }
}

/**
 * Tolerancias A/V iniciales (3.1.3, plan §7.4): 500 ms, a confirmar
 * o estrechar con la medición del teléfono, nunca a ensanchar a
 * ciegas (misma regla que el FPS en la ronda 3 de 3.1.2).
 */
private const val AV_OFFSET_TOLERANCE_MS = 500L
private const val AV_DURATION_TOLERANCE_MS = 500L

/**
 * Motivo de fallo de audio, o `null` si el audio no tiene nada que
 * objetar (plan §7.3, en orden). Solo se aplica cuando se espera
 * audio (`expectedAudio == true`): con `false` (video-only) o `null`
 * (recuperación, desconocido) el audio medido es solo diagnóstico —
 * ni se exige ni se prohíbe nada.
 *
 * - `audio_formato_no_coincide` no está en la lista del plan §7.3:
 *   es una extensión justificada de este commit. El plan lista
 *   motivos pero no cubre el caso de un `sampleRate`/canales medido
 *   distinto del esperado; clasificarlo como `sin_pista_audio` o
 *   `audio_sin_muestras` mentiría (la pista existe y tiene
 *   muestras) y como `duracion_audio_no_coincide` también (la
 *   duración puede coincidir). Un formato distinto es un fallo de
 *   audio real que debe distinguirse.
 *
 * - `incomplete = true` (camino `classifyIncomplete`, timeout o
 *   error): solo se denuncian los defectos establecidos de forma
 *   positiva por lo medido, que no dependen de haber leído el
 *   archivo entero. `sin_pista_audio` solo si el escaneo de pistas
 *   corrió (`hasAvcTrack`: la pista AVC solo se marca tras el
 *   escaneo, así que `hasAudioTrack == false` es definitivo);
 *   `audio_sin_muestras`, `duracion_audio_no_coincide` y
 *   `audio_decode_incompleto` dependen de una lectura completa (una
 *   lectura cortada subestima el audio y deja el decode a medias),
 *   así que en este camino no se inventan y el motivo que sobrevive
 *   es el del timeout/error. `audio_no_monotono`,
 *   `audio_formato_no_coincide` y `audio_desincronizado` se miden
 *   con el formato de la pista y los primeros PTS, que no dependen
 *   de terminar la lectura.
 */
private fun audioFailureReason(
    checks: VerificationChecks,
    expectedAudio: Boolean?,
    expectedSampleRate: Int?,
    expectedChannels: Int?,
    incomplete: Boolean,
): String? {
    if (expectedAudio != true) return null
    if (!checks.hasAudioTrack) {
        if (!incomplete || checks.hasAvcTrack) return "sin_pista_audio"
        return null
    }
    if (checks.audioSampleCount == 0) {
        if (incomplete) return null
        return "audio_sin_muestras"
    }
    if (!checks.audioPtsMonotonic) return "audio_no_monotono"
    val sampleRateMismatch = expectedSampleRate != null &&
        checks.audioSampleRate != null &&
        checks.audioSampleRate != expectedSampleRate
    val channelsMismatch = expectedChannels != null &&
        checks.audioChannels != null &&
        checks.audioChannels != expectedChannels
    if (sampleRateMismatch || channelsMismatch) {
        return "audio_formato_no_coincide"
    }
    if (checks.avOffsetMs != null &&
        abs(checks.avOffsetMs) > AV_OFFSET_TOLERANCE_MS
    ) {
        return "audio_desincronizado"
    }
    if (checks.avDurationDiffMs != null &&
        abs(checks.avDurationDiffMs) > AV_DURATION_TOLERANCE_MS
    ) {
        if (incomplete) return null
        return "duracion_audio_no_coincide"
    }
    if (!checks.audioDecodeOk) {
        if (incomplete) return null
        return "audio_decode_incompleto"
    }
    return null
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
 *
 * Audio (3.1.3): las mismas reglas sobre lo medido, con la salvedad
 * de `audioFailureReason(incomplete = true)`: un fallo de audio solo
 * se denuncia si está establecido de forma positiva por lo medido;
 * si el audio no se midió a tiempo, el motivo que sobrevive es el
 * del timeout/error, no un fallo de audio inventado.
 */
fun classifyIncomplete(
    checks: VerificationChecks,
    expectedWidth: Int?,
    expectedHeight: Int?,
    expectedFps: Int?,
    reason: String,
    expectedAudio: Boolean? = null,
    expectedSampleRate: Int? = null,
    expectedChannels: Int? = null,
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
    val audioReason = audioFailureReason(
        checks,
        expectedAudio,
        expectedSampleRate,
        expectedChannels,
        incomplete = true,
    )
    if (audioReason != null) {
        return SegmentVerification(SegmentStatus.PARTIAL, checks, audioReason)
    }
    return SegmentVerification(SegmentStatus.PARTIAL, checks, reason)
}

/** Verifica un archivo de segmento y lo clasifica (plan §7.1). */
interface SegmentVerifier {
    /**
     * La configuración esperada puede ser `null` (recuperación sin
     * sidecar): entonces la resolución y el FPS no se comprueban y el
     * segmento se clasifica solo por su legibilidad.
     *
     * `expectedAudio`: `true` = se espera pista de audio; `false` =
     * modo video-only (sin audio esperado); `null` = desconocido
     * (recuperación: medir lo que hay sin exigir audio).
     * `expectedSampleRate`/`expectedChannels` solo se comprueban
     * cuando `expectedAudio == true` y no son `null`.
     */
    suspend fun verify(
        file: File,
        closedCleanly: Boolean,
        expectedWidth: Int?,
        expectedHeight: Int?,
        expectedFps: Int?,
        expectedAudio: Boolean? = null,
        expectedSampleRate: Int? = null,
        expectedChannels: Int? = null,
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
 *    Con audio (3.1.3), lee también la pista AAC en la misma pasada
 *    y mide muestras, PTS, duración, formato y offset A/V.
 * 2. Decodificadores `MediaCodec` H.264 y AAC: decodifican TODAS las
 *    muestras en orden, hasta EOS. Un segmento `ready` exige la
 *    decodificación completa, no solo la lectura.
 * 3. ExoPlayer (en el hilo principal) prepara el archivo y confirma
 *    su duración (±100 ms de la de referencia: la pista más larga
 *    cuando hay audio, la de video si no).
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
        expectedAudio: Boolean?,
        expectedSampleRate: Int?,
        expectedChannels: Int?,
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
                    expectedAudio,
                    expectedSampleRate,
                    expectedChannels,
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
                expectedAudio,
                expectedSampleRate,
                expectedChannels,
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
                expectedAudio,
                expectedSampleRate,
                expectedChannels,
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
            var audioDecoder: MediaCodec? = null
            var decodedFrames = 0
            var decodeOk = false
            try {
                extractor.setDataSource(file.absolutePath)
                partial.extractorOpens = true
                var videoTrack = -1
                var audioTrack = -1
                var audioTrackFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    when (format.getString(MediaFormat.KEY_MIME)) {
                        MediaFormat.MIMETYPE_VIDEO_AVC -> {
                            if (videoTrack < 0) {
                                videoTrack = i
                                trackFormat = format
                            }
                        }
                        MediaFormat.MIMETYPE_AUDIO_AAC -> {
                            if (audioTrack < 0) {
                                audioTrack = i
                                audioTrackFormat = format
                            }
                        }
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
                // Audio (3.1.3): formato de la pista, si la hay. Sin
                // pista de audio, `hasAudioTrack` queda en `false` y
                // el resto de campos de audio en «no medido»: no se
                // inventa ningún valor.
                if (audioTrack >= 0 && audioTrackFormat != null) {
                    partial.hasAudioTrack = true
                    val audioFormat = audioTrackFormat!!
                    partial.audioSampleRate = audioFormat
                        .intOrNull(MediaFormat.KEY_SAMPLE_RATE)
                        ?.takeIf { it > 0 }
                    partial.audioChannels = audioFormat
                        .intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                        ?.takeIf { it > 0 }
                    partial.audioDurationMs = audioFormat
                        .longOrNull(MediaFormat.KEY_DURATION)
                        ?.div(1000)
                }
                extractor.selectTrack(videoTrack)
                if (audioTrack >= 0) {
                    extractor.selectTrack(audioTrack)
                }
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
                // Decodificador AAC (3.1.3): se alimenta en la misma
                // pasada, con la misma regla de drenado sin espera de
                // la ronda 5 (`drainDecoder`). Si no se puede crear,
                // `audioDecodeOk` queda en `false`: no se inventa.
                if (audioTrack >= 0 && audioTrackFormat != null) {
                    audioDecoder = try {
                        MediaCodec.createDecoderByType(
                            MediaFormat.MIMETYPE_AUDIO_AAC,
                        ).apply {
                            configure(audioTrackFormat, null, null, 0)
                            start()
                        }
                    } catch (e: Exception) {
                        Transmiti2Logger.e(
                            "verificacion: sin decodificador AAC: " +
                                "${e.javaClass.simpleName}",
                        )
                        null
                    }
                }
                var prevPtsUs = Long.MIN_VALUE
                var firstPtsUs: Long? = null
                var lastPtsUs: Long? = null
                var fedSamples = 0
                var eosSeen = false
                // Audio (3.1.3): mismas medidas, por pista.
                var prevAudioPtsUs = Long.MIN_VALUE
                var fedAudioSamples = 0
                var audioEosSeen = false
                var audioDecodedFrames = 0
                var audioDecodeOk = false
                while (true) {
                    readBuffer.clear()
                    val sampleSize = extractor.readSampleData(
                        readBuffer,
                        0,
                    )
                    if (sampleSize < 0) break
                    val ptsUs = extractor.sampleTime
                    val flags = extractor.sampleFlags
                    // Una sola pasada, dos pistas: el extractor
                    // entrega las muestras en orden de archivo
                    // (entrelazadas por PTS) y cada una se despacha
                    // por su índice de pista.
                    if (extractor.sampleTrackIndex == audioTrack) {
                        if (partial.audioSampleCount == 0) {
                            partial.audioFirstPtsUs = ptsUs
                        }
                        if (ptsUs <= prevAudioPtsUs &&
                            partial.audioSampleCount > 0
                        ) {
                            partial.audioPtsMonotonic = false
                        }
                        prevAudioPtsUs = ptsUs
                        partial.audioLastPtsUs = ptsUs
                        partial.audioSampleCount++
                        // Alimentar al decodificador AAC con esta
                        // muestra (mismas funciones que el video:
                        // `queueSampleToDecoder` y `drainDecoder`
                        // son agnósticas al codec).
                        if (audioDecoder != null) {
                            val queued = queueSampleToDecoder(
                                audioDecoder,
                                readBuffer,
                                sampleSize,
                                ptsUs,
                                flags,
                            )
                            if (queued) fedAudioSamples++
                            val drained = drainDecoder(audioDecoder, false)
                            audioDecodedFrames += drained.first
                            if (drained.second) audioEosSeen = true
                        }
                        extractor.advance()
                        continue
                    }
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
                // Medidas de audio (3.1.3), AQUÍ, dentro de la fase
                // de E/S: el timeout salta en el retorno del
                // `withContext` y una medida tomada después no
                // existiría (ronda 4). La duración de audio es el
                // tramo PTS + 1 trama; el offset y la diferencia de
                // duraciones usan los PTS del archivo, ya rebajados
                // al origen común por la sesión.
                partial.audioDurationMs = effectiveAudioDurationMs(
                    partial.audioDurationMs,
                    partial.audioFirstPtsUs,
                    partial.audioLastPtsUs,
                    partial.audioSampleRate,
                )
                if (firstPtsUs != null &&
                    partial.audioFirstPtsUs != null
                ) {
                    partial.avOffsetMs = avOffsetMs(
                        firstPtsUs,
                        partial.audioFirstPtsUs!!,
                    )
                }
                if (partial.durationMs != null &&
                    partial.audioDurationMs != null
                ) {
                    partial.avDurationDiffMs = avDurationDiffMs(
                        partial.durationMs!!,
                        partial.audioDurationMs!!,
                    )
                }
                // Duración de referencia para ExoPlayer: la pista más
                // larga (ExoPlayer reporta esa); sin audio, la de
                // video como hoy.
                partial.referenceDurationMs =
                    if (partial.durationMs != null &&
                        partial.audioDurationMs != null
                    ) {
                        maxOf(
                            partial.durationMs!!,
                            partial.audioDurationMs!!,
                        )
                    } else {
                        partial.durationMs
                    }
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
                // EOS al decodificador AAC y drenado final (3.1.3).
                // `audioDecodeOk` exige la decodificación completa
                // del audio igual que el video: todas las muestras
                // alimentadas y el EOS de salida visto.
                if (audioDecoder != null) {
                    var eosQueued = false
                    try {
                        val inputIndex = audioDecoder.dequeueInputBuffer(
                            DECODER_TIMEOUT_US,
                        )
                        if (inputIndex >= 0) {
                            audioDecoder.queueInputBuffer(
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
                        val drained = drainDecoder(audioDecoder, true)
                        audioDecodedFrames += drained.first
                        if (drained.second) audioEosSeen = true
                    }
                    audioDecodeOk = audioEosSeen &&
                        fedAudioSamples == partial.audioSampleCount
                }
                partial.audioDecodeOk = audioDecodeOk
                partial.audioDecodedFrames = audioDecodedFrames
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
                    audioDecoder?.stop()
                } catch (_: Exception) {
                }
                try {
                    audioDecoder?.release()
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
     *
     * Ronda 5: el drenado por muestra (`hastaEos = false`) NO
     * espera — `dequeueOutputBuffer` con timeout 0
     * (`drainTimeoutUs`). Antes esperaba `DECODER_TIMEOUT_US`
     * (100 ms) en cada llamada: como el bucle solo sale con
     * `INFO_TRY_AGAIN_LATER`, la ÚLTIMA espera de cada llamada
     * agotaba el timeout completo para un frame que aún no estaba
     * listo, y como se llama una vez por muestra, eso era
     * ~100 ms por muestra — 1800 muestras × ~110 ms ≈ 199 s de
     * fase 1 en el teléfono (grabación
     * `rec_20261119_021559_a602`: fase=199.0s/200.5s/19.8s, un
     * coste plano de ~110 ms por muestra en los tres segmentos).
     * El decodificador sigue trabajando de forma asíncrona: los
     * frames que aún no están listos se recogen en el drenado de
     * una muestra posterior, y el drenado final hasta EOS (que SÍ
     * espera, acotado por `DRAIN_TIMEOUT_MS`) vacía la cola y
     * confirma el EOS. Sin espera por muestra no hay bucle
     * ocupado: cada llamada hace como mucho (frames disponibles
     * + 1) sondeos no bloqueantes y el bucle exterior está
     * acotado por el número de muestras del archivo.
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
                decoder.dequeueOutputBuffer(info, drainTimeoutUs(hastaEos))
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
        /**
         * Espera máxima del decodificador en cada `dequeue` que
         * bloquea (ronda 5): `dequeueInputBuffer` al alimentar
         * (`queueSampleToDecoder`) y al encolar el EOS, y
         * `dequeueOutputBuffer` en el drenado final hasta EOS.
         * NO se aplica al drenado por muestra: ahí
         * `drainTimeoutUs(false)` es 0 (sondeo no bloqueante).
         *
         * Por qué no se baja a 10 ms: la espera de entrada solo
         * se paga si no hay buffer libre, y es la válvula de
         * seguridad que evita perder muestras — si agotara el
         * timeout, la muestra se descarta (`queued = false`) y
         * `fedSamples < sampleCount` → `decodeOk = false` → el
         * segmento nunca puede ser `ready` (`decode_incompleto`).
         * Bajarla a 10 ms bajo carga (el codificador sigue
         * grabando el siguiente segmento) cambiaría un fallo por
         * otro. La espera que dominaba el coste (~100 ms por
         * muestra, garantizada por construcción en el drenado) se
         * elimina en `drainTimeoutUs`, no aquí.
         */
        const val DECODER_TIMEOUT_US = 100_000L
        const val DRAIN_TIMEOUT_MS = 5_000L

        /**
         * Timeout de `dequeueOutputBuffer` en `drainDecoder`
         * (ronda 5). Pura, testeable en JVM.
         *
         * - Drenado por muestra (`hastaEos = false`): 0 — sondeo
         *   no bloqueante. El bucle de `drainDecoder` solo sale
         *   con `INFO_TRY_AGAIN_LATER`, así que con un timeout
         *   positivo la última espera de cada llamada agotaba el
         *   timeout completo (~100 ms por muestra, 199 s de fase 1
         *   para 1800 muestras en el teléfono). Con 0, cada llamada
         *   vacía la cola disponible y vuelve; los frames que aún
         *   no están listos los recoge el drenado de una muestra
         *   posterior.
         * - Drenado final (`hastaEos = true`):
         *   `DECODER_TIMEOUT_US` — ahí sí hay que esperar al EOS
         *   de salida, acotado por `DRAIN_TIMEOUT_MS`.
         */
        fun drainTimeoutUs(hastaEos: Boolean): Long =
            if (hastaEos) DECODER_TIMEOUT_US else 0L
    }
}

/** `getInteger` con valor por defecto (no existe en `MediaFormat`). */
private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) getInteger(key) else null

/** `getLong` con valor por defecto (no existe en `MediaFormat`). */
private fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) getLong(key) else null
