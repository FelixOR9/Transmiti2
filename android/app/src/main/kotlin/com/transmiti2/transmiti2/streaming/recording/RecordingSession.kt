package com.transmiti2.transmiti2.streaming.recording

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.StatFs
import android.os.SystemClock
import android.view.Surface
import com.transmiti2.transmiti2.streaming.CameraException
import com.transmiti2.transmiti2.streaming.PreviewConfig
import com.transmiti2.transmiti2.streaming.PreviewSession
import com.transmiti2.transmiti2.streaming.StreamContract
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import com.transmiti2.transmiti2.streaming.test.PausePoint
import com.transmiti2.transmiti2.streaming.test.TestEventResult
import com.transmiti2.transmiti2.streaming.test.TestEventType
import com.transmiti2.transmiti2.streaming.test.TestHooks
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Estados de grabación (plan 3.1.2 §6.1; máquina separada de la vista previa). */
enum class RecordingState {
    IDLE,
    STARTING,
    RECORDING,
    ROTATING_SEGMENT,
    STOPPING,
    ERROR,
}

/** Información de una grabación recién iniciada (evento `recording_started`). */
data class RecordingStartedInfo(
    val recordingId: String,
    val matchId: String?,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val profile: Int,
    val bitrateMode: Int,
    val encoderName: String,
    val rotationDegrees: Int,
    val lowStorageWarning: Boolean,
    val cbrSupported: Boolean,
    /** Audio efectivo de la grabación (3.1.3): `true` si lleva pista AAC. */
    val audioEnabled: Boolean = false,
    val audioSampleRate: Int? = null,
    val audioChannels: Int? = null,
    val audioEncoderName: String? = null,
    /** Por qué es video-only aunque se pidiera audio (`null` si hay audio). */
    val audioReason: String? = null,
    /** Origen de timestamps de la cámara (`realtime`/`unknown`/null, §4). */
    val timestampSource: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "recordingId" to recordingId,
        "matchId" to matchId,
        "width" to width,
        "height" to height,
        "fps" to fps,
        "bitrateBps" to bitrateBps,
        "profile" to profile,
        "bitrateMode" to bitrateMode,
        "encoderName" to encoderName,
        "rotationDegrees" to rotationDegrees,
        "lowStorageWarning" to lowStorageWarning,
        "cbrSupported" to cbrSupported,
        "audioEnabled" to audioEnabled,
        "audioSampleRate" to audioSampleRate,
        "audioChannels" to audioChannels,
        "audioEncoderName" to audioEncoderName,
        "audioReason" to audioReason,
        "timestampSource" to timestampSource,
    )
}

/** Resultado verificado de un segmento (evento `recording_segment`). */
data class RecordingSegmentResult(
    val recordingId: String,
    val index: Int,
    val fileName: String,
    val filePath: String,
    val status: SegmentStatus,
    val durationMs: Long?,
    val sizeBytes: Long,
    val sampleCount: Int?,
    val keyframeCount: Int?,
    val firstSampleKeyframe: Boolean?,
    val ptsMonotonic: Boolean?,
    val profileIdc: Int?,
    /** FPS medido por el verificador (ronda 3): se muestra en el
     * diagnóstico para decidir la tolerancia con evidencia. */
    val fpsMeasured: Double? = null,
    /** Muestras leídas por el verificador (ronda 4, fix 3): el
     * `sampleCount` de arriba es el del drenado y puede diferir;
     * este es el recuento que vio el verificador. */
    val verifiedSampleCount: Int? = null,
    /** Duración de la fase 1 de verificación en ms (ronda 4, fix
     * 2): extractor + decodificación completa. */
    val ioPhaseMs: Long? = null,
    val closedCleanly: Boolean,
    val reason: String?,
    /** Audio (3.1.3, plan §8.1): lo que el verificador vio en la
     * pista AAC del segmento; `audioGapSamples` cuenta las muestras
     * de audio perdidas (tardías o descartadas por cola llena). */
    val hasAudio: Boolean = false,
    val audioDurationMs: Long? = null,
    val audioSampleCount: Int? = null,
    val audioSampleRate: Int? = null,
    val audioChannels: Int? = null,
    val avOffsetMs: Long? = null,
    val avDurationDiffMs: Long? = null,
    val audioReason: String? = null,
    val audioGapSamples: Int = 0,
    /** Desplazamiento del primer PTS de audio respecto al origen
     * común del segmento (ms, instrumentación 3.1.3): `null` si
     * ninguna muestra de audio llegó a la sesión (H1). */
    val audioStartOffsetMs: Long? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "recordingId" to recordingId,
        "segmentIndex" to index,
        "fileName" to fileName,
        "filePath" to filePath,
        "status" to status.name.lowercase(),
        "durationMs" to durationMs,
        "sizeBytes" to sizeBytes,
        "sampleCount" to sampleCount,
        "keyframeCount" to keyframeCount,
        "firstSampleKeyframe" to firstSampleKeyframe,
        "ptsMonotonic" to ptsMonotonic,
        "profileIdc" to profileIdc,
        "fpsMeasured" to fpsMeasured,
        "verifiedSampleCount" to verifiedSampleCount,
        "ioPhaseMs" to ioPhaseMs,
        "closedCleanly" to closedCleanly,
        "reason" to reason,
        "hasAudio" to hasAudio,
        "audioDurationMs" to audioDurationMs,
        "audioSampleCount" to audioSampleCount,
        "audioSampleRate" to audioSampleRate,
        "audioChannels" to audioChannels,
        "avOffsetMs" to avOffsetMs,
        "avDurationDiffMs" to avDurationDiffMs,
        "audioReason" to audioReason,
        "audioGapSamples" to audioGapSamples,
        "audioStartOffsetMs" to audioStartOffsetMs,
    )
}

/**
 * Resultado de una grabación detenida (evento `recording_stopped` o
 * `recording_error`). `status`:
 *
 * - `ready`: todos los segmentos `ready`.
 * - `partial`: algún segmento `partial` o `corrupt`, con al menos uno
 *   utilizable (`ready` o `partial`).
 * - `corrupt`: ningún segmento utilizable (o ninguno).
 * - `idle`: no había grabación que detener.
 *
 * La fila de la grabación en SQLite sigue la regla del plan §7.3:
 * `ready` si hay al menos un segmento utilizable, `corrupt` si no
 * hay ninguno (el detalle por segmento está en `segments`).
 */
data class RecordingResult(
    val recordingId: String,
    val segments: List<RecordingSegmentResult>,
    val totalDurationMs: Long,
    val totalSizeBytes: Long,
    val status: String,
    val errorCode: String?,
    val previewRestoreError: String?,
    /** Audio de la grabación (3.1.3, plan §8.1): `true` si al menos
     * un segmento lleva pista AAC. */
    val audioEnabled: Boolean = false,
    /** Buffers de entrada AAC descartados por cola llena
     * (instrumentación 3.1.3): leído del encoder al parar, antes de
     * liberarlo. */
    val droppedInputBuffers: Int = 0,
    /**
     * Capacidad del buffer de entrada AAC en bytes (ronda U): leída
     * del encoder al parar, antes de liberarlo (como
     * `droppedInputBuffers`). `null` si nunca se drenó un buffer.
     */
    val inputBufferCapacityBytes: Int? = null,
    /**
     * Instrumentación ronda 3 de audio 3.1.3 (grupo I): estado del
     * micrófono y contadores del hilo lector, leídos del encoder al
     * parar, antes de liberarlo (como `droppedInputBuffers`).
     * `micStateAtStart`/`micStateAtStop` son `getRecordingState()`
     * tras `startRecording()` y al parar (`RECORDING`=3,
     * `STOPPED`=1; `null` sin encoder o sin arranque).
     * `audioLastReadOkAgeMs` es la edad en ms de la última lectura
     * `> 0` al parar (`null` si nunca hubo).
     */
    val micStateAtStart: Int? = null,
    val micStateAtStop: Int? = null,
    val audioReadCount: Int = 0,
    val audioLastReadCode: Int? = null,
    val audioLastReadOkAgeMs: Long? = null,
    val audioReaderAlive: Boolean = false,
    val audioEos: Boolean = false,
    /**
     * Grupo V: `audioReaderNoData2s` (V3) es el flag del watchdog
     * del lector (sin ninguna lectura devuelta a los 2 s de
     * arrancar); `eosTimedOut` (V4) es si la espera de EOS (audio
     * y video, 5 s en total) se agotó sin que llegara el EOS.
     */
    val audioReaderNoData2s: Boolean = false,
    val eosTimedOut: Boolean = false,
    /**
     * Grupo S (S0, solo lectura): el segundo más silencioso de la
     * grabación (pico y muestras no cero de la ventana completa con
     * menos muestras no cero). `null` si aún no se completó ningún
     * segundo: nunca `0`, porque `0` significaría silencio digital.
     */
    val quietestSecondPeak: Int? = null,
    val quietestSecondNonZero: Int? = null,
    /**
     * Grupo S (S1, solo lectura): pico de la ventana silenciosa que
     * disparó el detector (`audio_silence_samples`, esperado 0).
     * `null` si no hubo disparo: nunca se inventa un valor.
     */
    val silencePeak: Int? = null,
    /**
     * J3-a (3.1.4): disparador de la auto-parada por error, tal cual
     * llegó a `scheduleErrorStop` (código, etapa, segmento, clase de
     * la excepción, sitio, instante y espacio libre cuando aplica).
     * `null` en la parada normal y en un `stop(errorCode)` sin
     * disparador (p. ej. desde el motor): en esos casos no se
     * inventa un disparador.
     */
    val trigger: ErrorTrigger? = null,
    /**
     * J3-a (3.1.4): motivo por el que el último segmento cerrado en
     * `doStop` quedó sin renombrar (`muxer_stop_failed` si falló
     * `muxer.stop()`, `rename_failed` si falló el renombre,
     * `interrupted` si el cierre fue interrumpido). `null` si el
     * cierre y el renombre fueron limpios o no había segmento.
     */
    val closeReason: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "recordingId" to recordingId,
        "segments" to segments.map { it.toMap() },
        "segmentCount" to segments.size,
        "totalDurationMs" to totalDurationMs,
        "totalSizeBytes" to totalSizeBytes,
        "status" to status,
        "errorCode" to errorCode,
        "previewRestoreError" to previewRestoreError,
        "audioEnabled" to audioEnabled,
        "droppedInputBuffers" to droppedInputBuffers,
        "inputBufferCapacityBytes" to inputBufferCapacityBytes,
        "micStateAtStart" to micStateAtStart,
        "micStateAtStop" to micStateAtStop,
        "audioReadCount" to audioReadCount,
        "audioLastReadCode" to audioLastReadCode,
        "audioLastReadOkAgeMs" to audioLastReadOkAgeMs,
        "audioReaderAlive" to audioReaderAlive,
        "audioEos" to audioEos,
        "audioReaderNoData2s" to audioReaderNoData2s,
        "eosTimedOut" to eosTimedOut,
        "quietestSecondPeak" to quietestSecondPeak,
        "quietestSecondNonZero" to quietestSecondNonZero,
        "silencePeak" to silencePeak,
        "trigger" to trigger?.toMap(),
        "closeReason" to closeReason,
    )

    companion object {
        fun empty() = RecordingResult(
            recordingId = "",
            segments = emptyList(),
            totalDurationMs = 0,
            totalSizeBytes = 0,
            status = "idle",
            errorCode = null,
            previewRestoreError = null,
        )

        fun fromSegments(
            recordingId: String,
            segments: List<RecordingSegmentResult>,
            errorCode: String?,
            previewRestoreError: String?,
            droppedInputBuffers: Int = 0,
            inputBufferCapacityBytes: Int? = null,
            micStateAtStart: Int? = null,
            micStateAtStop: Int? = null,
            audioReadCount: Int = 0,
            audioLastReadCode: Int? = null,
            audioLastReadOkAgeMs: Long? = null,
            audioReaderAlive: Boolean = false,
            audioEos: Boolean = false,
            audioReaderNoData2s: Boolean = false,
            eosTimedOut: Boolean = false,
            quietestSecondPeak: Int? = null,
            quietestSecondNonZero: Int? = null,
            silencePeak: Int? = null,
            trigger: ErrorTrigger? = null,
            closeReason: String? = null,
        ): RecordingResult {
            val status = when {
                segments.isEmpty() -> "corrupt"
                segments.all { it.status == SegmentStatus.READY } -> "ready"
                segments.any { it.status != SegmentStatus.CORRUPT } ->
                    "partial"
                else -> "corrupt"
            }
            return RecordingResult(
                recordingId = recordingId,
                segments = segments,
                totalDurationMs = segments.sumOf { it.durationMs ?: 0 },
                totalSizeBytes = segments.sumOf { it.sizeBytes },
                status = status,
                errorCode = errorCode,
                previewRestoreError = previewRestoreError,
                audioEnabled = segments.any { it.hasAudio },
                droppedInputBuffers = droppedInputBuffers,
                inputBufferCapacityBytes = inputBufferCapacityBytes,
                micStateAtStart = micStateAtStart,
                micStateAtStop = micStateAtStop,
                audioReadCount = audioReadCount,
                audioLastReadCode = audioLastReadCode,
                audioLastReadOkAgeMs = audioLastReadOkAgeMs,
                audioReaderAlive = audioReaderAlive,
                audioEos = audioEos,
                audioReaderNoData2s = audioReaderNoData2s,
                eosTimedOut = eosTimedOut,
                quietestSecondPeak = quietestSecondPeak,
                quietestSecondNonZero = quietestSecondNonZero,
                silencePeak = silencePeak,
                trigger = trigger,
                closeReason = closeReason,
            )
        }
    }
}

/**
 * Disparador de una auto-parada por error (J3-a, 3.1.4, plan §1.3
 * paso 1): el `RecordingException` completo que llegó a
 * `scheduleErrorStop`, con el sitio que lo disparó y el instante en
 * que se programó la parada. Se construye una sola vez en
 * `scheduleErrorStop` y viaja intacto por `stop()`/`doStop` hasta el
 * `RecordingResult` y el `details` de `recording_error`: ni
 * `doStop` ni nadie lo reescribe con un genérico. `freeBytes` es el
 * espacio libre medido cuando el disparador lo trae (`storage_full`
 * en rotación); `null` cuando no se midió
 * (`storage_check_unavailable`) o el disparador no lo trae: nunca
 * se inventa un valor.
 */
data class ErrorTrigger(
    val code: String,
    val message: String?,
    val stage: String?,
    val segmentIndex: Int?,
    val exceptionClass: String?,
    val triggerSite: String,
    val timestampMs: Long,
    val freeBytes: Long?,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "code" to code,
        "message" to message,
        "stage" to stage,
        "segmentIndex" to segmentIndex,
        "exceptionClass" to exceptionClass,
        "triggerSite" to triggerSite,
        "timestampMs" to timestampMs,
        "freeBytes" to freeBytes,
    )

    companion object {
        fun from(
            error: RecordingException,
            triggerSite: String,
            timestampMs: Long,
        ): ErrorTrigger {
            val details = error.details
            return ErrorTrigger(
                code = error.code,
                message = error.message,
                stage = details["stage"] as? String,
                segmentIndex = (details["segmentIndex"] as? Number)?.toInt(),
                exceptionClass = details["exceptionClass"] as? String,
                triggerSite = triggerSite,
                timestampMs = timestampMs,
                freeBytes = (details["freeBytes"] as? Number)?.toLong(),
            )
        }
    }
}

/** Instantánea del estado para `getRecordingStatus` (re-sincronizar la UI). */
data class RecordingStatusSnapshot(
    val state: String,
    val recordingId: String?,
    val elapsedMs: Long,
    val segmentIndex: Int,
    val segmentDurationMs: Long,
    val width: Int?,
    val height: Int?,
    val fps: Int?,
    val errorCode: String?,
    /** Segmentos verificados hasta ahora (paso 6): la UI los recupera
     * al re-sincronizar sin haber visto los eventos. */
    val segments: List<RecordingSegmentResult> = emptyList(),
    /** Audio (3.1.3, plan §8.1): pedido con efecto, activo en el
     * segmento actual y motivo cuando falta o se perdió. */
    val audioEnabled: Boolean = false,
    val audioActive: Boolean = false,
    val audioReason: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "state" to state,
        "recordingId" to recordingId,
        "elapsedMs" to elapsedMs,
        "segmentIndex" to segmentIndex,
        "segmentDurationMs" to segmentDurationMs,
        "width" to width,
        "height" to height,
        "fps" to fps,
        "errorCode" to errorCode,
        "segments" to segments.map { it.toMap() },
        "audioEnabled" to audioEnabled,
        "audioActive" to audioActive,
        "audioReason" to audioReason,
    )
}

/**
 * Caso de reconciliación de un segmento (subetapa 0, grupo 0-d1;
 * plan §2.5). C1–C2 confían en el sidecar (E1–E6); C3–C5 verifican;
 * C6 es lo no verificable (`PENDING_VERIFICATION`, estado local de
 * reconciliación, no un `SegmentStatus`).
 */
enum class ReconciliationCase {
    /** Confiable, `.mp4`: se confía en el `status` del sidecar. */
    C1_TRUSTED_FINAL,
    /** Confiable, `.tmp`: renombrado con guarda, se confía en el `status`. */
    C2_TRUSTED_STAGING,
    /** Incompleto (falta algún E1–E6): verificado. */
    C3_INCOMPLETE,
    /** Sin sidecar: verificado y registro creado. */
    C4_NO_SIDECAR,
    /** Contradictorio: verificado, campos sobrescritos con los medidos. */
    C5_CONTRADICTORY,
    /** No verificable: `PENDING_VERIFICATION`. */
    C6_PENDING,
}

/** Resultado de reconciliar un segmento. */
data class ReconciledSegmentOutcome(
    val recordingId: String,
    val index: Int,
    /** Nombre real en disco tras la reconciliación. */
    val fileName: String,
    val filePath: String,
    /**
     * Estado certificado (`READY`/`PARTIAL`/`CORRUPT`); `null` ⟺
     * `PENDING_VERIFICATION` (caso C6): nunca `ready`, nunca se
     * elimina, excluido de lo utilizable, listado para reintento.
     */
    val status: SegmentStatus?,
    val case: ReconciliationCase,
    val durationMs: Long?,
    val sizeBytes: Long,
    val reason: String?,
    /** El verificador corrió en esta pasada (C3–C5). */
    val verified: Boolean,
    /** El sidecar pasó E1–E6 sin verificar (C1–C2). */
    val trusted: Boolean,
    /** El `.tmp` pasó a `.mp4` en esta pasada. */
    val renamed: Boolean,
    /** El archivo era `.tmp.mp4` en el inventario. */
    val wasStaging: Boolean,
) {
    /** Utilizable para reproducir (`ready` o `partial`). */
    val usable: Boolean get() = status == SegmentStatus.READY ||
        status == SegmentStatus.PARTIAL
    /** `true` ⟺ `PENDING_VERIFICATION` (caso C6). */
    val pending: Boolean get() = status == null
}

/** Referencia a un segmento pendiente de verificación (caso C6). */
data class ReconciledSegmentRef(
    val recordingId: String,
    val index: Int,
    val fileName: String,
    val reason: String?,
)

/**
 * Resumen de una pasada de reconciliación (subetapa 0, grupo 0-d1;
 * plan §2.6 + instrumentación F7b): inicio/fin, grupos reservados,
 * `skipped_active`, conflictos, tokens y contador de liberaciones
 * ajenas. Lo consume el diagnóstico (y en 0-d2, el canal a Dart).
 */
data class ReconciliationSummary(
    val runId: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val groupsTotal: Int,
    val groupsReconciled: Int,
    val groupsSkippedActive: List<String>,
    /** Grupos que fallaron con excepción (no tumban la pasada). */
    val groupsFailed: List<String>,
    val segments: List<ReconciledSegmentOutcome>,
    val segmentsTrusted: Int,
    val segmentsVerified: Int,
    val segmentsRenamed: Int,
    val segmentsPending: List<ReconciledSegmentRef>,
    val segmentsQuarantined: Int,
    val unknownFiles: List<String>,
    val missingFiles: List<String>,
    val orphanWriteTestFilesSwept: Int,
    /** Grupo → token de reserva que lo procesó. */
    val reservationTokens: Map<String, OwnershipToken>,
    /** Liberaciones ajenas rechazadas por el registro (acumulado). */
    val foreignReleaseRejected: Long,
    /** Conflictos de destino duplicado encontrados. */
    val conflicts: Int,
) {
    /** Duración medida de la pasada (ms). */
    val durationMs: Long get() = finishedAtMs - startedAtMs

    fun toMap(): Map<String, Any?> = mapOf(
        "runId" to runId,
        "startedAtMs" to startedAtMs,
        "finishedAtMs" to finishedAtMs,
        "durationMs" to durationMs,
        "groupsTotal" to groupsTotal,
        "groupsReconciled" to groupsReconciled,
        "groupsSkippedActive" to groupsSkippedActive,
        "groupsFailed" to groupsFailed,
        "segmentsTrusted" to segmentsTrusted,
        "segmentsVerified" to segmentsVerified,
        "segmentsRenamed" to segmentsRenamed,
        "segmentsPending" to segmentsPending.map {
            mapOf(
                "recordingId" to it.recordingId,
                "segmentIndex" to it.index,
                "fileName" to it.fileName,
                "reason" to it.reason,
            )
        },
        "segmentsQuarantined" to segmentsQuarantined,
        "unknownFiles" to unknownFiles,
        "missingFiles" to missingFiles,
        "orphanWriteTestFilesSwept" to orphanWriteTestFilesSwept,
        "reservationTokens" to reservationTokens,
        "foreignReleaseRejected" to foreignReleaseRejected,
        "conflicts" to conflicts,
    )
}

/**
 * Configuración de audio decidida para una grabación (3.1.3): la
 * configuración AAC elegida por la escalera (§2 D1) y el nombre del
 * codificador AAC real. La produce `audioCapabilityCheck`; el nombre
 * viene de `AudioEncoder.checkAacCapabilities()` (`AudioCapabilities`
 * no lo lleva).
 */
data class AudioSetup(
    val config: AudioEncoderConfig,
    val encoderName: String,
)

/**
 * Coordina cámara, codificador y muxer para la grabación local
 * (Fase B, 3.1.2; plan §2 y §6). Una sola `CameraCaptureSession` con
 * dos superficies (vista previa + entrada del encoder) y un solo
 * `MediaCodec`; un `RecordingMuxer` (MediaMuxerCompat) por segmento.
 *
 * - El drenado corre en el hilo del encoder: escribe en el muxer en
 *   ese mismo hilo (regla de mismo hilo), nunca toca el hilo
 *   principal y nunca lanza fuera del callback.
 * - Los eventos hacia Flutter salen por `emitEvent` (no suspendida:
 *   el motor la envuelve y la emite en el hilo principal, lección de
 *   la Fase B de 3.1.1).
 * - `start()`/`stop()` se llaman en serie (el motor las serializa
 *   con su mutex); el drenado es el único actor concurrente y solo
 *   toca volátiles y lo confinado al hilo del encoder.
 * - Un error fatal (encoder o muxer) detiene la grabación con el
 *   código real, conserva los segmentos cerrados, verifica el
 *   segmento en curso como interrumpido y restaura la vista previa.
 *
 * No depende de `Context`: el motor construye `fileStore` (con
 * `<filesDir>/recordings/`) y `verifier` (con el `Context` de la app
 * para ExoPlayer) y los inyecta.
 *
 * La clase y su API pública son `open` (costura de pruebas): los tests
 * del motor sustituyen la sesión por una falsa que registra llamadas
 * sin codificador ni cámara; la lógica real se prueba en
 * `RecordingSessionTest`.
 */
open class RecordingSession(
    private val scope: CoroutineScope,
    private val fileStore: RecordingFileStore,
    private val verifier: SegmentVerifier,
    private val emitEvent: (
        type: String,
        payload: Map<String, Any?>,
    ) -> Unit = { _, _ -> },
    private val encoderFactory: (
        VideoEncoderConfig,
    ) -> VideoEncoderPort = { VideoEncoder(it) },
    private val muxerFactory: (String) -> RecordingMuxer =
        { CompatRecordingMuxer(it) },
    /**
     * Comprobación de capacidades del encoder. Por defecto la real
     * (`VideoEncoder.checkAvcCapabilities`); en las unitarias se
     * sustituye (un `CameraManager` y un `MediaCodecList` reales no
     * existen en JVM).
     */
    private val capabilityCheck: (
        CameraManager?,
        String,
        Int,
        Int,
    ) -> EncoderCapabilities = { manager, id, width, height ->
        if (manager == null) {
            throw RecordingException.encoderError(
                "Sin CameraManager para comprobar capacidades.",
                mapOf("stage" to "checkCapabilities"),
            )
        }
        VideoEncoder.checkAvcCapabilities(manager, id, width, height)
    },
    /** Timeouts (inyectables para las unitarias; 5 s en producción). */
    private val formatTimeoutMs: Long = FORMAT_TIMEOUT_MS,
    private val eosTimeoutMs: Long = EOS_TIMEOUT_MS,
    /**
     * Fábrica del encoder de audio (3.1.3): por defecto el real
     * (`AudioEncoder`); en las unitarias se sustituye por un falso
     * (un `AudioRecord` real no existe en JVM).
     */
    private val audioEncoderFactory: (AudioEncoderConfig) -> AudioEncoderPort =
        { AudioEncoder(it) },
    /**
     * Comprobación de capacidades de audio (3.1.3, §2 D1): mide las
     * capacidades reales y decide la configuración AAC (escalera
     * 48 kHz → 44,1 kHz) con el nombre del encoder. Por defecto la
     * real; en las unitarias se sustituye.
     */
    private val audioCapabilityCheck: () -> AudioSetup = {
        val caps = measureAudioCapabilities()
        val config = chooseAudioConfig(caps)
        AudioSetup(config, AudioEncoder.checkAacCapabilities().encoderName)
    },
    /** Espera del formato AAC desde el formato de video (2 s, §5.1). */
    private val audioFormatTimeoutMs: Long = AUDIO_FORMAT_TIMEOUT_MS,
    /**
     * Espacio libre medible en la carpeta de grabaciones (subetapa 0,
     * grupo 0-b): `null` cuando `StatFs` no pudo medirlo (constructor
     * o `availableBytes` lanzan). Nunca se inventa un valor: el
     * `Long.MAX_VALUE` anterior se eliminó por completo — con `null`
     * el inicio falla con `storage_check_unavailable` y la rotación
     * hace una parada limpia con el mismo código (U20/U20b/U20c).
     */
    private val freeSpaceBytes: () -> Long? = {
        try {
            StatFs(fileStore.recordingsDir.absolutePath).availableBytes
        } catch (_: Exception) {
            null
        }
    },
    private val clockMs: () -> Long = System::currentTimeMillis,
    /**
     * Registro de coordinación grabación/reconciliación (subetapa 0,
     * grupo 0-c; revisión 3 §3.1). Inyectable por constructor, como
     * `verifier`/`fileStore`; por defecto uno propio por sesión. El
     * registro es por instancia de sesión, nunca singleton.
     */
    private val registry: ActiveRecordingRegistry = ActiveRecordingRegistry(),
    /**
     * Ganchos de la infraestructura de pruebas físicas (FASE 2, grupo
     * 2-c; diseño base §4.A/§5). Por defecto [TestHooks.NO_OP]: con
     * él, los ganchos retornan de inmediato y `record()` es no-op, así
     * que el flujo es idéntico al actual. El cableado (MainActivity →
     * motor) instala los ganchos reales solo en builds de pruebas.
     * Último parámetro con defecto: las construcciones existentes en
     * producción y tests no cambian.
     */
    private val testHooks: TestHooks = TestHooks.NO_OP,
) : VideoEncoderListener {

    private val segmentVerifier: SegmentVerifier = verifier

    private val sessionMutex = Mutex()

    /**
     * Serializa las verificaciones de segmentos (ronda 2): sin él,
     * cada rotación lanza su verificación en `Dispatchers.IO` y dos
     * pueden solaparse mientras el codificador sigue grabando (cada
     * una decodifica un segmento completo y prepara ExoPlayer en el
     * hilo principal). Con una a la vez, cada verificación dispone de
     * todo el presupuesto sin competir con la siguiente. La
     * reconciliación (`reconcileRecordings`) llama al verificador
     * bajo este mutex, igual que `verifyAndRecord`, así que tampoco
     * puede solaparse con una rotación.
     */
    private val verificationMutex = Mutex()

    @Volatile
    private var state: RecordingState = RecordingState.IDLE
    private var lastError: RecordingException? = null
    private var lastResult: RecordingResult? = null
    private var stopResult: CompletableDeferred<RecordingResult>? = null

    private var recordingId: String? = null
    /**
     * Token del claim vivo en el registro de coordinación (subetapa 0,
     * grupo 0-c). Se escribe inmediatamente tras el claim con éxito
     * en `start()` (asignación plana, sin `suspend` entre el claim y
     * el guardado) y se pone a `null` al liberar. Invariante: token
     * no-nulo ⟹ `recordingId` es el id reclamado (ambos se escriben
     * sin punto de suspensión entre medias, así que ningún otro camino
     * puede observar el token con otro id).
     */
    private var activeClaimToken: OwnershipToken? = null
    /**
     * Contador de pasadas de reconciliación (subetapa 0, grupo 0-d1):
     * distingue dos pasadas en el diagnóstico (`owner =
     * "reconcile:<runId>"`); la identidad es el token, nunca el
     * `owner`.
     */
    private val reconcileRunCounter = AtomicLong(0)
    private var matchId: String? = null
    private var recordingStartWallMs: Long = 0
    private var previewSession: PreviewSession? = null
    private var previewConfig: PreviewConfig? = null
    private var rotationDegrees: Int = 0
    private var sessionRecreated: Boolean = false

    private var encoder: VideoEncoderPort? = null
    private var encoderConfig: VideoEncoderConfig? = null
    private var encoderName: String = ""
    private var cbrSupported: Boolean = true
    private var lowStorageWarning: Boolean = false

    /** Formato de salida real del encoder (con `csd-0`/`csd-1`). */
    @Volatile
    private var outputFormat: MediaFormat? = null
    private var formatDeferred: CompletableDeferred<MediaFormat>? = null
    private var eosDeferred: CompletableDeferred<Unit>? = null
    /** El primer segmento queda abierto cuando lo permite la guarda
     * de audio (o expira la espera); `start()` lo espera. */
    private var segmentOpenDeferred: CompletableDeferred<Unit>? = null

    // --- Audio (3.1.3, plan §4-§5) ---
    /** Encoder AAC + `AudioRecord` (su propio hilo de lectura y su
     * propio hilo de callback; nunca el hilo del encoder de video). */
    private var audioEncoder: AudioEncoderPort? = null
    private var audioConfig: AudioEncoderConfig? = null
    private var audioEncoderName: String = ""
    /** Formato de salida real del encoder AAC (con `csd-0`). */
    @Volatile
    private var audioFormat: MediaFormat? = null
    private var audioEosDeferred: CompletableDeferred<Unit>? = null
    /**
     * EOS de audio llegado antes del cierre (ronda 3, grupo I):
     * se fija en `onAudioEndOfStream` (generación actual) y se
     * resetea en `start()` y al crear un encoder nuevo en
     * `tryStartAudio()`. Decide si el defecto del EOS se cumple en
     * el teléfono (paradas de ~5 s).
     */
    @Volatile
    private var audioEosArrived: Boolean = false
    /** Generación del encoder de audio vigente: cada (re)creación la
     * incrementa; los callbacks de un encoder ya liberado se
     * descartan por generación (el `HandlerThread` puede entregar
     * mensajes encolados tras `release()`). */
    @Volatile
    private var audioGeneration: Int = 0
    /** Audio pedido por la UI y con encoder creado (puede degradar a
     * video-only: la espera expiró o el micrófono falló). */
    private var audioExpected: Boolean = false
    /** Motivo por el que la grabación es video-only aunque se pidiera
     * audio (`audio_format_timeout`, `audio_mic_unavailable`). */
    private var audioReason: String? = null
    /** Pérdida de audio en caliente (D7): el video continúa; la
     * recreación en segundo plano intenta recuperar el audio para el
     * segmento siguiente, nunca a mitad de uno. */
    @Volatile
    private var audioLost: Boolean = false
    private var audioLostReason: String? = null
    private var audioLostSegmentIndex: Int? = null
    /**
     * Grupo S (S1): pico de la ventana silenciosa que disparó el
     * detector (`audio_silence_samples`, esperado 0). Se captura en
     * `onAudioError` desde `details` y se guarda para el evento y
     * para el resultado de parada (`silencioPico`): aquí, no en el
     * puerto del encoder — tras la recreación el encoder es otro y
     * el pico del viejo se perdería. `null` si no hubo disparo.
     */
    private var silencePeakAtLoss: Int? = null
    /** Una recreación en segundo plano en curso (como mucho una por
     * segmento: si también falla, se reintenta en la rotación). */
    @Volatile
    private var audioRecreateInFlight: Boolean = false
    /** Segmento para el que ya se lanzó una recreación. */
    private var audioRecreatedSegmentIndex: Int? = null
    /** Espera del formato AAC expirada (2 s desde el formato de
     * video) o forzada por el buffer pre-muxer lleno. */
    @Volatile
    private var audioFormatWaitExpired: Boolean = false
    /** Origen de timestamps de la cámara (`realtime`/`unknown`/null). */
    private var timestampSource: String? = null
    /** Pista de audio del muxer del segmento actual (`-1` si no hay). */
    private var audioTrackIndex: Int = -1
    /** El segmento actual lleva pista de audio en el muxer (es la
     * expectativa que usa la verificación). */
    private var segmentAudioExpected: Boolean = false
    /** Generación del encoder que abrió la pista de audio del
     * segmento actual (`-1` si no hay pista). */
    private var segmentAudioGeneration: Int = -1
    private var segmentAudioSampleRate: Int? = null
    private var segmentAudioChannels: Int? = null
    /** Motivo de audio del segmento actual (video-only al abrir o
     * pérdida en caliente durante el segmento). */
    private var segmentAudioReason: String? = null
    /** Origen común del segmento actual (`CLOCK_MONOTONIC`, µs):
     * `min(primer PTS de video, primer PTS de audio)` (§4.2). */
    private var segmentOriginUs: Long? = null
    private var segmentFirstAudioPtsUs: Long? = null
    private var segmentAudioSamples: Int = 0
    private var segmentAudioLastPtsUs: Long? = null
    /** Muestras de audio perdidas (tardías o descartadas por cola
     * llena); > 5 en una rotación es `audio_gap` en el diagnóstico. */
    private var segmentAudioGapSamples: Int = 0
    /** Muestras de video antes de fijar el origen (copias en heap;
     * acotado a 150; nunca se tira una muestra). */
    private val preMuxerVideoBuffer = ArrayDeque<BufferedSample>()
    /** Muestras de audio pendientes de escribir (solo las toca el
     * hilo del encoder de video; acotado a 100). */
    private val audioQueue = ArrayDeque<BufferedSample>()

    // --- Estado del segmento actual (confinado al hilo del encoder,
    // salvo los volátiles que lee el estado) ---
    private var muxer: RecordingMuxer? = null
    private var trackIndex: Int = -1
    private var segmentIndex: Int = 0
    private var segmentStagingFile: File? = null
    private var segmentFirstPtsUs: Long? = null
    private var segmentStartWallMs: Long = 0
    private var segmentSamples: Int = 0
    private var segmentKeyframes: Int = 0
    private var segmentFirstKeyframe: Boolean = false
    private var segmentPtsMonotonic: Boolean = true
    private var segmentLastPtsUs: Long? = null

    @Volatile
    private var drainStopped: Boolean = false
    @Volatile
    private var rotationPending: Boolean = false
    private var rotationRequestedAtMs: Long = 0
    private var rotationForced: Boolean = false

    private var samplesBeforeMuxer: Int = 0
    private var maxWriteLatencyMs: Long = 0

    private val segmentResults = mutableListOf<RecordingSegmentResult>()
    private val pendingVerifications =
        mutableListOf<CompletableDeferred<RecordingSegmentResult>>()
    private val metadataSegments = mutableListOf<SegmentMetadata>()

    fun currentState(): RecordingState = state

    /**
     * J3-a (3.1.4): último error tal cual quedó registrado, sin
     * reescribir: tras una auto-parada es el disparador original
     * (`scheduleErrorStop`); tras `failStart`, el error de inicio.
     * Solo lectura, para diagnóstico y tests: la UI lee el error
     * desde el evento `recording_error`.
     */
    fun lastErrorSnapshot(): RecordingException? = lastError

    /** Instantánea del estado para `getRecordingStatus`. */
    open fun statusSnapshot(): RecordingStatusSnapshot {
        val firstPts = segmentFirstPtsUs
        val lastPts = segmentLastPtsUs
        val segmentDurationMs =
            if (firstPts != null && lastPts != null && lastPts >= firstPts) {
                (lastPts - firstPts) / 1000
            } else {
                0
            }
        return RecordingStatusSnapshot(
            state = state.name.lowercase(),
            recordingId = recordingId,
            elapsedMs = if (recordingStartWallMs > 0) {
                clockMs() - recordingStartWallMs
            } else {
                0
            },
            segmentIndex = segmentIndex,
            segmentDurationMs = segmentDurationMs,
            width = encoderConfig?.width,
            height = encoderConfig?.height,
            fps = encoderConfig?.fps,
            errorCode = lastError?.code,
            segments = synchronized(segmentResults) {
                segmentResults.sortedBy { it.index }.toList()
            },
            audioEnabled = audioExpected,
            audioActive = segmentAudioExpected && muxer != null,
            audioReason = if (audioLost) audioLostReason else audioReason,
        )
    }

    // --- Accesores solo de pruebas (FASE 2, grupo 2-c) ------------

    /**
     * Snapshot del registro de coordinación (solo pruebas): lo lee el
     * motor para el canal de pruebas. No toca el registro; el mapa
     * que se expone por el canal excluye los valores de los tokens.
     */
    internal suspend fun testRegistrySnapshot(): RegistrySnapshot =
        registry.snapshot()

    /**
     * Archivo `.tmp` del segmento en curso (solo pruebas): `null` si
     * no hay grabación activa. Lo lee el motor para el muestreador
     * del `.tmp` activo (diseño base §8): solo `File.length()`, nunca
     * escritura.
     */
    internal fun activeStagingFile(): File? = segmentStagingFile

    /**
     * Inicia la grabación. Exige vista previa activa (el motor lo
     * comprueba antes de llamar). Devuelve la información del inicio
     * (evento `recording_started`). Lanza `RecordingException` con el
     * código real si algo falla; en ese caso el estado queda `ERROR`,
     * el encoder se libera y la vista previa se restaura si se había
     * recreado. Solo desde `IDLE` o `ERROR` (desde `ERROR` limpia lo
     * anterior y empieza de nuevo); un duplicado se rechaza con
     * `recording_state_error`.
     */
    open suspend fun start(
        previewSession: PreviewSession,
        previewConfig: PreviewConfig,
        rotationDegrees: Int,
        matchId: String?,
        cameraManager: CameraManager?,
        /** Audio (3.1.3): `true` pide pista AAC; sin micrófono o con
         * fallo de audio la grabación arranca video-only (D9). */
        audioEnabled: Boolean = false,
    ): RecordingStartedInfo {
        val currentState = sessionMutex.withLock {
            when (state) {
                RecordingState.IDLE -> {
                    state = RecordingState.STARTING
                    RecordingState.IDLE
                }
                RecordingState.ERROR -> {
                    // Un nuevo inicio limpia el error anterior.
                    cleanupAfterErrorLocked()
                    state = RecordingState.STARTING
                    RecordingState.ERROR
                }
                else -> null
            }
        }
        if (currentState == null) {
            // 2-c (diseño base §8.2): la solicitud rechazada queda en
            // el log de pruebas (incluso sin claim): es la evidencia
            // de una segunda grabación intentada con la sesión
            // ocupada. No-op con los ganchos desactivados.
            testHooks.record(
                TestEventType.RECORDING_START_REQUESTED,
                operation = "start",
                result = TestEventResult.FAILED,
                errorCode = StreamContract.RECORDING_STATE_ERROR,
                detail = mapOf("state" to state.name.lowercase()),
            )
            throw RecordingException.stateError(
                "Ya hay una grabación $state.",
                mapOf("stage" to "start", "state" to state.name.lowercase()),
            )
        }
        Transmiti2Logger.d("recording: start desde $currentState")
        this.previewSession = previewSession
        this.previewConfig = previewConfig
        this.rotationDegrees = rotationDegrees
        this.matchId = matchId
        sessionRecreated = false
        lastError = null
        lastResult = null
        stopResult = null
        synchronized(segmentResults) { segmentResults.clear() }
        synchronized(pendingVerifications) { pendingVerifications.clear() }
        synchronized(metadataSegments) { metadataSegments.clear() }
        segmentIndex = 0
        segmentFirstPtsUs = null
        segmentLastPtsUs = null
        segmentStagingFile = null
        samplesBeforeMuxer = 0
        maxWriteLatencyMs = 0
        drainStopped = false
        rotationPending = false
        // Audio (3.1.3): el estado se reinicia por grabación; el
        // encoder de audio se crea después del de video (D4).
        audioEncoder = null
        audioConfig = null
        audioEncoderName = ""
        audioFormat = null
        audioEosDeferred = null
        audioEosArrived = false
        audioExpected = false
        audioReason = null
        audioLost = false
        audioLostReason = null
        audioLostSegmentIndex = null
        silencePeakAtLoss = null
        audioRecreateInFlight = false
        audioRecreatedSegmentIndex = null
        audioFormatWaitExpired = false
        timestampSource = null
        audioTrackIndex = -1
        segmentAudioExpected = false
        segmentAudioGeneration = -1
        segmentAudioSampleRate = null
        segmentAudioChannels = null
        segmentAudioReason = null
        segmentOriginUs = null
        segmentFirstAudioPtsUs = null
        segmentAudioSamples = 0
        segmentAudioLastPtsUs = null
        segmentAudioGapSamples = 0
        preMuxerVideoBuffer.clear()
        audioQueue.clear()

        try {
            // Origen de timestamps de la cámara (§4): `realtime`
            // (CLOCK_BOOTTIME) es la base que exige el plan; se
            // registra en `recording_started` para el diagnóstico.
            timestampSource = try {
                val characteristics = cameraManager
                    ?.getCameraCharacteristics(previewConfig.cameraId)
                when (
                    characteristics?.get(
                        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
                    )
                ) {
                    CameraCharacteristics
                        .SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "realtime"
                    CameraCharacteristics
                        .SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "unknown"
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
            Transmiti2Logger.d(
                "recording: timestampSource=$timestampSource",
            )
            // Audio pedido: se marca la expectativa ANTES de crear el
            // encoder de video, para que `maybeOpenSegment` (que corre
            // al llegar el formato de video) espere al formato AAC.
            if (audioEnabled) {
                audioExpected = true
            }

            // Subetapa 0, grupo 0-b. Orden en start(): revalidar
            // directorio → medir espacio → resto. La re-validación
            // V1–V4 es Kotlin directo (inmune al canal, con
            // `withContext(Dispatchers.IO)`; `start()` ya es
            // `suspend`): si falla, no se crea encoder, segmento ni
            // fila — `failStart` con `recordings_dir_unavailable`.
            // La medida de espacio también va a IO (`StatFs` es una
            // llamada `statvfs` bloqueante).
            val revalidation = withContext(Dispatchers.IO) {
                validateRecordingsDir(
                    fileStore.recordingsDir,
                    nowMs = clockMs,
                )
            }
            when (revalidation) {
                is RecordingsDirOutcome.Ok -> {
                    if (revalidation.writeTestDeleteFailed) {
                        Transmiti2Logger.e(
                            "recording: no se pudo borrar el archivo " +
                                "de prueba " +
                                "${revalidation.writeTestFile}",
                        )
                    }
                }
                is RecordingsDirOutcome.Failed ->
                    throw RecordingException.recordingsDirUnavailable(
                        revalidation.stage,
                        revalidation.reason,
                        revalidation.exceptionClass,
                    )
            }

            // Almacenamiento: < 200 MB no se empieza; < 2 GB avisa.
            // Espacio no medible (`null`) → `storage_check_unavailable`
            // (nunca `storage_full` ni aviso; U20/U20c).
            val free: Long? = try {
                withContext(Dispatchers.IO) { freeSpaceBytes() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw RecordingException.storageCheckUnavailable(
                    stage = "start",
                    check = "free_space_check",
                    exceptionClass = e.javaClass.simpleName,
                )
            }
            if (free == null) {
                throw RecordingException.storageCheckUnavailable(
                    stage = "start",
                    check = "free_space_check",
                )
            }
            if (free < MIN_FREE_BYTES_START) {
                throw RecordingException.storageFull(
                    "Sin espacio para grabar: quedan " +
                        "${free / (1024 * 1024)} MB libres.",
                    mapOf("stage" to "start", "freeBytes" to free),
                )
            }
            lowStorageWarning = free < LOW_STORAGE_WARN_BYTES

            // Capacidades reales (§3.2): la grabación usa la misma
            // configuración que la vista previa activa.
            val width = previewConfig.size.width
            val height = previewConfig.size.height
            val fps = previewConfig.fpsRange.max
            val caps = capabilityCheck(
                cameraManager,
                previewConfig.cameraId,
                width,
                height,
            )
            val config = VideoEncoder.chooseConfig(
                caps,
                width = width,
                height = height,
                fps = fps,
                bitrateBps = TARGET_BITRATE_BPS,
            )
            encoderConfig = config
            encoderName = caps.encoderName
            cbrSupported = caps.cbrSupported
            Transmiti2Logger.d(
                "recording: encoder=${caps.encoderName} " +
                    "${width}x$height ${fps}fps perfil=${config.profile} " +
                    "modo=${config.bitrateMode} cbr=${caps.cbrSupported}",
            )

            val id = claimRecordingId()
            // 2-c: claim concedido (solo el id y el resultado; nunca
            // el valor del token). No-op con los ganchos desactivados.
            testHooks.record(
                TestEventType.CLAIM_GRANTED,
                operation = "start",
                recordingId = id,
            )
            recordingId = id
            recordingStartWallMs = clockMs()
            fileStore.ensureDir()

            // Encoder: la superficie de entrada es la segunda
            // superficie de la sesión de cámara.
            val created = encoderFactory(config)
            encoder = created
            formatDeferred = CompletableDeferred()
            eosDeferred = CompletableDeferred()
            segmentOpenDeferred = CompletableDeferred()
            created.listener = this
            created.configureAndStart()
            // Audio (3.1.3, D4/D9): se crea tras el encoder de video;
            // un fallo aquí nunca hace fallar el inicio (video-only).
            if (audioExpected) {
                tryStartAudio()
            }
            val inputSurface: Surface = created.surface
                ?: throw RecordingException.encoderError(
                    "Sin superficie de entrada del encoder.",
                    mapOf("stage" to "start"),
                )

            // Una sola sesión con dos superficies (vista previa +
            // encoder), plantilla de grabación. Si falla, la sesión
            // restaura sola la vista previa (rollback en
            // `CameraSession.recreateSession`).
            try {
                previewSession.recreateSession(
                    inputSurface,
                    CameraDevice.TEMPLATE_RECORD,
                )
            } catch (e: CameraException) {
                throw RecordingException(
                    e.code,
                    e.message ?: "No se pudo recrear la sesión de cámara.",
                    e.details,
                )
            }
            sessionRecreated = true

            // El formato de salida real llega antes del primer sample;
            // con él se abre el primer segmento (en el hilo del
            // encoder, dentro de `onOutputFormatChanged`).
            val format = try {
                withTimeout(formatTimeoutMs) {
                    formatDeferred!!.await()
                }
            } catch (e: TimeoutCancellationException) {
                throw RecordingException.encoderError(
                    "El codificador no entregó su formato de salida " +
                        "en 5 s.",
                    mapOf("stage" to "start"),
                )
            }
            // El primer segmento se abre en `maybeOpenSegment` (hilo
            // del encoder): con audio, espera al formato AAC o a la
            // expiración de 2 s; video-only abre de inmediato.
            try {
                withTimeout(formatTimeoutMs) {
                    segmentOpenDeferred!!.await()
                }
            } catch (e: TimeoutCancellationException) {
                throw RecordingException.encoderError(
                    "El primer segmento no se abrió en 5 s.",
                    mapOf("stage" to "start"),
                )
            }
            // La parada pudo llegar mientras se esperaba el formato
            // (el motor serializa, pero no se confía en ello).
            if (state != RecordingState.STARTING) {
                throw RecordingException.stateError(
                    "La grabación se canceló durante el inicio.",
                    mapOf("stage" to "start"),
                )
            }
            outputFormat = format
            state = RecordingState.RECORDING
            // 2-c (diseño base §8.2): la solicitud aceptada queda
            // registrada con su id (el par de la rechazada de la
            // guarda: una solicitud, un evento).
            testHooks.record(
                TestEventType.RECORDING_START_REQUESTED,
                operation = "start",
                recordingId = id,
            )
            // Audio efectivo del inicio: el segmento 0 lleva pista AAC
            // solo si el formato llegó y el muxer la añadió.
            val effectiveAudio = audioExpected && audioFormat != null
            val info = RecordingStartedInfo(
                recordingId = id,
                matchId = matchId,
                width = width,
                height = height,
                fps = fps,
                bitrateBps = config.bitrateBps,
                profile = config.profile,
                bitrateMode = config.bitrateMode,
                encoderName = caps.encoderName,
                rotationDegrees = rotationDegrees,
                lowStorageWarning = lowStorageWarning,
                cbrSupported = caps.cbrSupported,
                audioEnabled = effectiveAudio,
                audioSampleRate = if (effectiveAudio) {
                    audioConfig?.sampleRate
                } else {
                    null
                },
                audioChannels = if (effectiveAudio) {
                    audioConfig?.channels
                } else {
                    null
                },
                audioEncoderName = if (effectiveAudio) {
                    audioEncoderName.takeIf { it.isNotEmpty() }
                } else {
                    null
                },
                audioReason = audioReason,
                timestampSource = timestampSource,
            )
            emitEvent(StreamContract.EVENT_RECORDING_STARTED, info.toMap())
            Transmiti2Logger.d("recording: iniciada $id")
            return info
        } catch (e: RecordingException) {
            // 2-c: el claim rechazado (id agotado tras 10 intentos)
            // queda en el log; el resto de fallos de inicio no son
            // rechazos de claim. No-op con los ganchos desactivados.
            if (e.details["stage"] == "id_reservation") {
                testHooks.record(
                    TestEventType.CLAIM_REJECTED,
                    operation = "start",
                    result = TestEventResult.FAILED,
                    errorCode = e.code,
                    detail = mapOf("stage" to "id_reservation"),
                )
            }
            failStart(e)
        } catch (e: Exception) {
            failStart(
                RecordingException.encoderError(
                    e.message ?: "No se pudo iniciar la grabación.",
                    mapOf(
                        "stage" to "start",
                        "exceptionClass" to e.javaClass.simpleName,
                    ),
                ),
            )
        }
    }

    /** Limpieza tras un error, con el mutex ya tomado. */
    private fun cleanupAfterErrorLocked() {
        lastError = null
        lastResult = null
        stopResult = null
        synchronized(segmentResults) { segmentResults.clear() }
        synchronized(pendingVerifications) { pendingVerifications.clear() }
        synchronized(metadataSegments) { metadataSegments.clear() }
    }

    /**
     * S4' (subetapa 0, grupo 0-c): reclama un id en el registro de
     * coordinación ANTES de `ensureDir()` y antes de crear ningún
     * archivo. Si el id está reservado por la reconciliación (claim
     * `null`), regenera el id y reintenta, acotado a
     * [MAX_ID_CLAIM_ATTEMPTS] intentos; agotado → `recording_state_error`
     * con `stage = "id_reservation"` (error visible, no grabar).
     *
     * El token queda guardado en [activeClaimToken] inmediatamente tras
     * el claim con éxito, sin `suspend` entre medias. Si `start()`
     * falla antes del claim (p. ej. `storage_full`), no hay nada que
     * liberar.
     *
     * `newRecordingId()` ya trae la S3' (subetapa 0, grupo 0-b):
     * comprobación de existencia + regeneración del sufijo, máx. 10
     * intentos; si sigue colisionando lanza `recording_state_error`
     * con `stage = "id_reservation"`.
     */
    private suspend fun claimRecordingId(): String {
        repeat(MAX_ID_CLAIM_ATTEMPTS) { attempt ->
            val candidate = fileStore.newRecordingId(clockMs())
            val token = registry.tryClaimRecording(candidate)
            if (token != null) {
                activeClaimToken = token
                return candidate
            }
            Transmiti2Logger.d(
                "recording: id $candidate reservado, regenerando " +
                    "(intento ${attempt + 1}/$MAX_ID_CLAIM_ATTEMPTS)",
            )
        }
        throw RecordingException.stateError(
            "No se pudo reservar un id de grabación libre.",
            mapOf("stage" to "id_reservation"),
        )
    }

    /**
     * Toma el token del claim vivo y lo pone a `null` en la misma
     * sección crítica bajo `sessionMutex` (toma-y-limpia atómica): dos
     * vías de salida (`stop()` + `release()`, o `doStop()` +
     * `failStart()`) no pueden liberar dos veces el mismo claim.
     * El lock se libera antes de llamar al registro: el
     * `coordinationMutex` es hoja y nunca se toma con otro mutex.
     */
    private suspend fun takeClaimToken(): OwnershipToken? =
        sessionMutex.withLock {
            val token = activeClaimToken
            activeClaimToken = null
            token
        }

    /**
     * Libera el claim de `id` con `token`. Nunca lanza por el estado
     * del registro: `NOT_FOUND` en `finally` se ignora, `NOT_OWNER`
     * se registra como aviso (alguien intentó soltar lo ajeno).
     * Devuelve el resultado para que el llamador lo registre (2-c).
     */
    private suspend fun releaseClaim(id: String, token: OwnershipToken): ReleaseResult {
        val result = registry.releaseRecording(id, token)
        when (result) {
            ReleaseResult.RELEASED ->
                Transmiti2Logger.d(
                    "recording: claim liberado id=$id token=$token",
                )
            ReleaseResult.NOT_FOUND ->
                Transmiti2Logger.d(
                    "recording: claim ya liberado id=$id token=$token",
                )
            ReleaseResult.NOT_OWNER ->
                Transmiti2Logger.e(
                    "recording: intento de liberar claim ajeno " +
                        "id=$id token=$token",
                )
        }
        return result
    }

    /**
     * Libera el claim vivo, si lo hay, con el id dado. Corre en
     * `NonCancellable`: una cancelación no debe fugar el claim (R-N1).
     * Sin claim vivo no hace nada. El id debe ser el reclamado (ver
     * invariante de [activeClaimToken]); si no coincide, el registro
     * devuelve `NOT_FOUND`/`NOT_OWNER` sin tocar la entrada viva.
     * La vía (`failStart` | `stop`) queda en el log de pruebas (2-c,
     * diseño base §8.2): es el destino del claim de la sesión.
     */
    private suspend fun releaseClaimIfHeld(id: String, via: String = "stop") {
        withContext(NonCancellable) {
            val token = takeClaimToken() ?: return@withContext
            val result = releaseClaim(id, token)
            // 2-c: solo cuando había un claim vivo que liberar (solo
            // id y resultado; nunca el valor del token).
            testHooks.record(
                TestEventType.CLAIM_RELEASED,
                operation = "recording",
                recordingId = id,
                result = if (result == ReleaseResult.RELEASED) {
                    TestEventResult.OK
                } else {
                    TestEventResult.FAILED
                },
                detail = mapOf("via" to via, "releaseResult" to result.name),
            )
        }
    }

    /** Fracaso del inicio: estado `ERROR`, evento y limpieza. */
    private suspend fun failStart(e: RecordingException): Nothing {
        // 2-c: el id reclamado (si lo hubo) se captura ANTES de
        // liberar: `recordingId` no se resetea entre grabaciones y
        // podría ser obsoleto si el fallo llegó antes del claim. El
        // invariante de `activeClaimToken` (token no-nulo ⟹
        // `recordingId` es el id reclamado) hace exacta la captura.
        val claimedId = if (activeClaimToken != null) recordingId else null
        // 0-c: liberar el claim ANTES de publicar ERROR (la transición
        // que admite un nuevo inicio). Con el orden contrario, un nuevo
        // inicio podría reclamar el mismo id antes de la liberación;
        // con tokens no robaría el claim, pero dejaría ruido
        // (NOT_OWNER) y un aviso espurio.
        recordingId?.let { releaseClaimIfHeld(it, via = "failStart") }
        state = RecordingState.ERROR
        lastError = e
        // 2-c (diseño base §8.2): la solicitud que llegó hasta aquí
        // fue aceptada por la guarda pero falló en el inicio; queda
        // registrada como fallida con su código. El claim, si lo hubo,
        // ya se liberó arriba, antes de publicar ERROR (el evento
        // CLAIM_RELEASED precede a este en el log).
        testHooks.record(
            TestEventType.RECORDING_START_REQUESTED,
            operation = "start",
            recordingId = claimedId,
            result = TestEventResult.FAILED,
            errorCode = e.code,
            detail = mapOf("stage" to "failStart"),
        )
        Transmiti2Logger.e(
            "recording: fallo al iniciar: ${e.code} ${e.message}",
        )
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
        outputFormat = null
        // Audio (3.1.3, §5.3): ningún camino de salida deja el
        // micrófono abierto.
        try {
            audioEncoder?.release()
        } catch (_: Exception) {
        }
        audioEncoder = null
        audioFormat = null
        audioEosDeferred = null
        // Solo se restaura la vista previa si se llegó a recrear la
        // sesión; si no, la sesión sigue siendo de solo vista previa.
        if (sessionRecreated) {
            scope.launch {
                try {
                    previewSession?.recreateSession(
                        null,
                        CameraDevice.TEMPLATE_PREVIEW,
                    )
                } catch (_: Exception) {
                }
            }
            sessionRecreated = false
        }
        emitEvent(
            StreamContract.EVENT_RECORDING_ERROR,
            mapOf(
                "recordingId" to recordingId,
                "code" to e.code,
                "message" to e.message,
                "details" to e.details,
            ),
        )
        throw e
    }

    // --- Drenado (hilo del encoder) ---

    override fun onOutputFormatChanged(format: MediaFormat) {
        // El formato solo se entrega una vez al inicio.
        if (outputFormat != null) return
        outputFormat = format
        val deferred = formatDeferred
        try {
            // Con audio, el segmento se abre cuando llega el formato
            // AAC (o expira la espera de 2 s); video-only abre ya.
            maybeOpenSegment()
            deferred?.complete(format)
        } catch (e: Exception) {
            val error = when (e) {
                is RecordingException -> e
                else -> RecordingException.muxerError(
                    "No se pudo abrir el primer segmento: ${e.message}",
                    mapOf(
                        "stage" to "openSegment",
                        "segmentIndex" to segmentIndex,
                        "exceptionClass" to e.javaClass.simpleName,
                    ),
                )
            }
            deferred?.completeExceptionally(error)
        }
        // Espera del formato AAC acotada (2 s desde el formato de
        // video, §5.1): si no llega, el segmento se abre video-only
        // con motivo `audio_format_timeout`. Video-only no espera.
        if (audioExpected) {
            scope.launch {
                delay(audioFormatTimeoutMs)
                try {
                    encoder?.post { onAudioFormatTimeout() }
                } catch (_: Exception) {
                }
            }
        }
    }

    // --- Audio (3.1.3, plan §4-§5) ---
    // Todo lo de audio corre en el hilo del encoder de video salvo
    // la creación (en `start()`, hilo del motor) y la recreación (en
    // segundo plano). Los callbacks del encoder de audio llegan en su
    // propio hilo: copian el buffer y publican (`post`) al hilo del
    // encoder de video, que es el único que toca el muxer y las
    // colas. La generación descarta callbacks de un encoder ya
    // liberado.

    /**
     * Crea y arranca el encoder de audio (D4). Nunca lanza: un fallo
     * (micrófono no disponible, sin codificador AAC, `AudioRecord`
     * que no se inicializa) degrada a video-only con el motivo en
     * `audioReason` y la grabación arranca (D9).
     */
    private fun tryStartAudio() {
        try {
            val setup = audioCapabilityCheck()
            val created = audioEncoderFactory(setup.config)
            audioGeneration++
            created.listener = audioListenerFor(audioGeneration)
            created.configureAndStart()
            audioEncoder = created
            audioConfig = setup.config
            audioEncoderName = setup.encoderName
            audioEosDeferred = CompletableDeferred()
            audioEosArrived = false
            Transmiti2Logger.d(
                "recording: audio ${setup.config.sampleRate} Hz " +
                    "${setup.config.channels}ch " +
                    "${setup.config.bitrateBps} bps " +
                    "(${setup.encoderName})",
            )
        } catch (e: Exception) {
            // Micrófono no disponible o AAC que no arranca: video-only.
            // Grupo F (ronda 3): el motivo viaja en `details["reason"]`
            // cuando el fallo lo trae (p. ej.
            // `audio_capture_not_started` si la captura no arrancó);
            // otros fallos siguen con `audio_mic_unavailable`. Mismo
            // patrón que `onAudioError`.
            audioReason = (e as? RecordingException)?.details?.get("reason")
                as? String ?: "audio_mic_unavailable"
            audioExpected = false
            audioEncoder = null
            audioConfig = null
            audioEncoderName = ""
            Transmiti2Logger.e(
                "recording: sin audio (${e.javaClass.simpleName}): " +
                    "video-only",
            )
            // El segmento ya no espera al formato AAC: se abre
            // video-only en el hilo del encoder (si el formato de
            // video aún no llegó, `onOutputFormatChanged` lo abre).
            // Ronda U: la apertura va por `maybeOpenSegmentPosted`
            // para que un fallo no mate el hilo del encoder.
            try {
                encoder?.post { maybeOpenSegmentPosted() }
            } catch (_: Exception) {
            }
        }
    }

    /** Listener del encoder de audio con la generación capturada. */
    private fun audioListenerFor(generation: Int): AudioEncoderListener =
        object : AudioEncoderListener {
            override fun onAudioFormatChanged(format: MediaFormat) {
                this@RecordingSession.onAudioFormatChanged(format, generation)
            }

            override fun onAudioSample(
                buffer: ByteBuffer,
                info: MediaCodec.BufferInfo,
            ) {
                this@RecordingSession.onAudioSample(buffer, info, generation)
            }

            override fun onAudioError(e: Exception) {
                this@RecordingSession.onAudioError(e, generation)
            }

            override fun onAudioEndOfStream() {
                this@RecordingSession.onAudioEndOfStream(generation)
            }
        }

    /**
     * Formato AAC recibido (hilo del encoder de audio): se guarda y
     * se publica al hilo del encoder de video, que abre el segmento
     * si la guarda lo permite. Un formato tardío (tras degradar a
     * video-only) se ignora.
     */
    private fun onAudioFormatChanged(format: MediaFormat, generation: Int) {
        if (generation != audioGeneration) return
        if (!audioExpected) return
        if (audioFormat != null) return
        audioFormat = format
        try {
            // Ronda U: la apertura va por `maybeOpenSegmentPosted`
            // para que un fallo no mate el hilo del encoder.
            encoder?.post { maybeOpenSegmentPosted() }
        } catch (_: Exception) {
        }
    }

    /**
     * Muestra AAC (hilo del encoder de audio): el `ByteBuffer` solo
     * es válido durante la llamada, así que se copia en heap y la
     * copia se publica al hilo del encoder de video.
     */
    private fun onAudioSample(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        generation: Int,
    ) {
        if (generation != audioGeneration) return
        if (drainStopped) return
        if (!audioExpected) return
        val copy = copySampleBuffer(buffer, info)
        val infoCopy = MediaCodec.BufferInfo().apply {
            offset = 0
            size = info.size
            presentationTimeUs = info.presentationTimeUs
            flags = info.flags
        }
        try {
            encoder?.post {
                onAudioSampleOnEncoderThread(copy, infoCopy, generation)
            }
        } catch (_: Exception) {
        }
    }

    /** Copia en heap del rango válido de un buffer de salida. */
    private fun copySampleBuffer(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ): ByteBuffer {
        val dup = buffer.duplicate()
        dup.position(info.offset)
        dup.limit(info.offset + info.size)
        return ByteBuffer.allocate(info.size).apply {
            put(dup)
            flip()
        }
    }

    /** Copia de un `BufferInfo` (la clase no tiene `copy()`). */
    private fun copyInfo(info: MediaCodec.BufferInfo): MediaCodec.BufferInfo =
        MediaCodec.BufferInfo().apply {
            offset = 0
            size = info.size
            presentationTimeUs = info.presentationTimeUs
            flags = info.flags
        }

    /**
     * Muestra AAC en el hilo del encoder de video: se encola (acotada
     * a 100) y se vacía lo que ya se puede escribir. Una muestra de
     * otra generación (encoder recreado a mitad de segmento) se
     * descarta: el audio se reanuda solo en el segmento siguiente
     * (D7). Una muestra tardía (PTS anterior a la frontera del
     * segmento actual) también se descarta y se cuenta.
     */
    private fun onAudioSampleOnEncoderThread(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        generation: Int,
    ) {
        if (drainStopped) return
        if (!audioExpected) return
        if (!segmentAudioExpected) return
        if (generation != segmentAudioGeneration) return
        val ptsUs = info.presentationTimeUs
        val firstVideo = segmentFirstPtsUs
        if (segmentIndex > 0 && firstVideo != null && ptsUs < firstVideo) {
            // Pertenece a un segmento ya cerrado (llegó tras la
            // rotación): se descarta y se cuenta (§5.2).
            segmentAudioGapSamples++
            return
        }
        if (segmentFirstAudioPtsUs == null) {
            segmentFirstAudioPtsUs = ptsUs
            Transmiti2Logger.d(
                "recording: primera muestra de audio " +
                    "seg $segmentIndex ptsUs=$ptsUs",
            )
        }
        if (audioQueue.size >= AUDIO_QUEUE_MAX_SAMPLES) {
            // Cola llena: se descarta la más antigua (el origen ya
            // está fijado por el primer PTS guardado).
            audioQueue.removeFirst()
            segmentAudioGapSamples++
        }
        audioQueue.addLast(BufferedSample(buffer, info))
        maybeFixOrigin()
        flushAudioQueue(null)
    }

    /**
     * Error del encoder de audio o pérdida del micrófono (hilo del
     * encoder de audio): se marca la pérdida, se emite
     * `recording_audio_lost` y el video continúa (D7). La recreación
     * en segundo plano intenta recuperar el audio para el segmento
     * siguiente.
     */
    private fun onAudioError(e: Exception, generation: Int) {
        if (generation != audioGeneration) return
        if (audioLost) return
        val details = (e as? RecordingException)?.details
        val reason = details?.get("reason")
            as? String ?: "audio_encoder_error"
        audioLost = true
        audioLostReason = reason
        audioLostSegmentIndex = segmentIndex
        if (audioLostSegmentIndex == segmentIndex) {
            segmentAudioReason = reason
        }
        // Grupo S (S1): las claves del disparo de silencio
        // (`silenceMs`, `silenceStartOffsetMs`, `silencePeak`) viajan
        // en `details` y se guardan para el evento y para el
        // resultado de parada (`silencioPico`). Solo existen con el
        // motivo `audio_silence_samples`; los demás motivos no las
        // llevan y el mapa del evento queda como antes. Se leen como
        // `Number`: en un `mapOf` mixto Kotlin infiere `Long` para
        // el literal `0` (probado: `as? Int` da `null`), así que el
        // cast debe aceptar `Int` y `Long`.
        val silencePeak = (details?.get("silencePeak") as? Number)?.toInt()
        if (silencePeak != null) {
            silencePeakAtLoss = silencePeak
        }
        // El formato del encoder muerto queda obsoleto: sin él, la
        // rotación abre el segmento siguiente video-only (no con una
        // pista vacía); la recreación pone el formato nuevo.
        audioFormat = null
        Transmiti2Logger.e(
            "recording: audio perdido ($reason) en el segmento " +
                "$segmentIndex; el video continúa",
        )
        val eventMap = mutableMapOf<String, Any?>(
            "recordingId" to recordingId,
            "reason" to reason,
            "segmentIndex" to segmentIndex,
            "message" to (e.message ?: "Audio perdido."),
        )
        (details?.get("silenceMs") as? Number)?.let {
            eventMap["silenceMs"] = it.toLong()
        }
        (details?.get("silenceStartOffsetMs") as? Number)?.let {
            eventMap["silenceStartOffsetMs"] = it.toLong()
        }
        silencePeak?.let { eventMap["silencePeak"] = it }
        emitEvent(
            StreamContract.EVENT_RECORDING_AUDIO_LOST,
            eventMap,
        )
        launchAudioRecreation()
    }

    /** Fin del drenado de audio tras `signalEndOfInputStream`. */
    private fun onAudioEndOfStream(generation: Int) {
        if (generation != audioGeneration) return
        audioEosArrived = true
        audioEosDeferred?.complete(Unit)
    }

    /**
     * Abre el segmento actual cuando la guarda lo permite (hilo del
     * encoder de video): formato de video conocido Y (formato AAC
     * conocido O video-only). Si el muxer ya está abierto, no hace
     * nada. Completa `segmentOpenDeferred` al abrir el primer
     * segmento (lo espera `start()`).
     */
    private fun maybeOpenSegment() {
        if (muxer != null) return
        if (outputFormat == null) return
        if (audioExpected && audioFormat == null) return
        try {
            openSegment(segmentIndex)
            if (segmentIndex == 0) {
                segmentOpenDeferred?.complete(Unit)
            }
        } catch (e: Exception) {
            if (segmentIndex == 0) {
                segmentOpenDeferred?.completeExceptionally(e)
            }
            throw e
        }
    }

    /**
     * Abre el segmento desde un `post` al hilo del encoder (ronda U):
     * un fallo de apertura no debe matar el hilo. El segmento 0 ya
     * deja la excepción en `segmentOpenDeferred` (la convierte
     * `start()` en evento de error); en un segmento posterior no hay
     * deferred, así que se para la grabación con error. Devuelve
     * `false` si la apertura falló.
     */
    private fun maybeOpenSegmentPosted(): Boolean {
        return try {
            maybeOpenSegment()
            true
        } catch (e: Exception) {
            Transmiti2Logger.e(
                "recording: no se pudo abrir el segmento " +
                    "$segmentIndex " +
                    "(${e.javaClass.simpleName}): ${e.message}",
            )
            if (segmentIndex != 0) {
                scheduleErrorStop(
                    when (e) {
                        is RecordingException -> e
                        else -> RecordingException.muxerError(
                            "No se pudo abrir el segmento: ${e.message}",
                            mapOf(
                                "stage" to "openSegment",
                                "segmentIndex" to segmentIndex,
                                "exceptionClass" to e.javaClass.simpleName,
                            ),
                        )
                    },
                    triggerSite = "maybeOpenSegmentPosted",
                )
            }
            false
        }
    }

    /**
     * La espera del formato AAC expiró (2 s desde el formato de
     * video): degradación a video-only con motivo
     * `audio_format_timeout` (§5.1). El encoder de audio se libera
     * (nunca entregó su formato; el micrófono no sirve para nada) y
     * el segmento se abre video-only. Si el formato ya llegó, no
     * hace nada.
     */
    private fun onAudioFormatTimeout() {
        if (!audioExpected || audioFormat != null) return
        audioFormatWaitExpired = true
        audioReason = "audio_format_timeout"
        Transmiti2Logger.d(
            "recording: formato AAC no llegó en 2 s; video-only",
        )
        try {
            audioEncoder?.release()
        } catch (_: Exception) {
        }
        audioEncoder = null
        audioFormat = null
        audioEosDeferred = null
        audioExpected = false
        // Ronda U: si la apertura falla, no se sigue con
        // `maybeFixOrigin` (como antes, la excepción lo saltaba).
        if (!maybeOpenSegmentPosted()) return
        maybeFixOrigin()
    }

    /**
     * Fija el origen común del segmento actual (§4.2):
     * `min(primer PTS de video, primer PTS de audio)`. Con pista de
     * audio espera al primer PTS de audio salvo que la espera haya
     * expirado (entonces fija desde video y las muestras de audio
     * anteriores al origen se descartan como tardías). Video-only
     * fija desde video, idéntico a 3.1.2. Al fijarlo vacía el buffer
     * pre-muxer y la cola de audio.
     */
    private fun maybeFixOrigin() {
        if (segmentOriginUs != null) return
        val firstVideo = segmentFirstPtsUs ?: return
        if (segmentAudioExpected) {
            val firstAudio = segmentFirstAudioPtsUs
            if (firstAudio == null && !audioFormatWaitExpired) return
            segmentOriginUs = segmentOrigin(firstVideo, firstAudio)
        } else {
            segmentOriginUs = firstVideo
        }
        flushPreMuxerBuffer()
        flushAudioQueue(null)
    }

    /** Vacía el buffer pre-muxer de video al muxer (origen fijado). */
    private fun flushPreMuxerBuffer() {
        if (segmentOriginUs == null) return
        val target = muxer ?: return
        if (trackIndex < 0) return
        while (preMuxerVideoBuffer.isNotEmpty()) {
            val sample = preMuxerVideoBuffer.removeFirst()
            writeSample(
                target,
                sample.buffer,
                sample.info,
                sample.info.presentationTimeUs,
            )
        }
    }

    /**
     * Vacía la cola de audio al muxer. Sin frontera escribe todo lo
     * encolado; con frontera (`rotate()`), solo las muestras con PTS
     * anterior a ella (el resto queda en cola para el segmento
     * siguiente). Las muestras con PTS rebajado negativo (anteriores
     * al origen) se descartan y se cuentan.
     */
    private fun flushAudioQueue(boundaryUs: Long?) {
        val origin = segmentOriginUs ?: return
        val target = muxer ?: return
        if (audioTrackIndex < 0) return
        while (audioQueue.isNotEmpty()) {
            val head = audioQueue.first()
            if (boundaryUs != null &&
                !assignAudioToSegment(
                    head.info.presentationTimeUs,
                    boundaryUs,
                )
            ) {
                break
            }
            audioQueue.removeFirst()
            writeAudioSample(target, head.buffer, head.info, origin)
        }
    }

    /** Escribe una muestra de audio en el muxer (PTS rebasado al origen). */
    private fun writeAudioSample(
        target: RecordingMuxer,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        originUs: Long,
    ) {
        val rebasedPtsUs = info.presentationTimeUs - originUs
        if (rebasedPtsUs < 0) {
            // Anterior al origen (el origen se fijó desde video tras
            // expirar la espera): se descarta y se cuenta.
            segmentAudioGapSamples++
            return
        }
        val rebased = MediaCodec.BufferInfo().apply {
            offset = info.offset
            size = info.size
            presentationTimeUs = rebasedPtsUs
            flags = info.flags
        }
        try {
            target.writeSampleData(audioTrackIndex, buffer, rebased)
        } catch (e: Exception) {
            scheduleErrorStop(
                RecordingException.muxerError(
                    "Error al escribir audio en el muxer: ${e.message}",
                    mapOf(
                        "stage" to "writeAudioSample",
                        "segmentIndex" to segmentIndex,
                        "exceptionClass" to e.javaClass.simpleName,
                    ),
                ),
                triggerSite = "writeAudioSample",
            )
            return
        }
        segmentAudioSamples++
        segmentAudioLastPtsUs = info.presentationTimeUs
    }

    /**
     * Recreación del audio en segundo plano tras una pérdida (D7):
     * libera el encoder viejo, crea uno nuevo y lo arranca. Si tiene
     * éxito, el segmento siguiente recupera la pista (el formato
     * llega de forma asíncrona y `openSegment` lo añade); el segmento
     * en curso no recibe muestras del encoder nuevo (la generación
     * del segmento no coincide). Como mucho una por segmento: si
     * también falla, se reintenta en la rotación.
     */
    private fun launchAudioRecreation() {
        if (audioRecreateInFlight) return
        if (audioRecreatedSegmentIndex == segmentIndex) return
        if (state != RecordingState.RECORDING &&
            state != RecordingState.ROTATING_SEGMENT
        ) {
            return
        }
        audioRecreateInFlight = true
        audioRecreatedSegmentIndex = segmentIndex
        scope.launch {
            try {
                try {
                    audioEncoder?.release()
                } catch (_: Exception) {
                }
                audioEncoder = null
                audioFormat = null
                val setup = audioCapabilityCheck()
                val created = audioEncoderFactory(setup.config)
                audioGeneration++
                created.listener = audioListenerFor(audioGeneration)
                created.configureAndStart()
                audioEncoder = created
                audioConfig = setup.config
                audioEncoderName = setup.encoderName
                audioEosDeferred = CompletableDeferred()
                audioLost = false
                audioLostReason = null
                Transmiti2Logger.d(
                    "recording: audio recreado " +
                        "(${setup.config.sampleRate} Hz)",
                )
            } catch (e: Exception) {
                Transmiti2Logger.e(
                    "recording: no se pudo recrear el audio: " +
                        "${e.javaClass.simpleName}",
                )
            } finally {
                audioRecreateInFlight = false
            }
        }
    }

    /**
     * Abre el segmento `index` en el hilo del encoder: archivo en
     * staging, muxer con el formato de salida retenido, orientación
     * fijada al iniciar la grabación. Lanza `RecordingException`
     * (`muxer_error`) si el muxer no se puede crear o arrancar.
     */
    private fun openSegment(index: Int) {
        val id = recordingId
            ?: throw RecordingException.muxerError(
                "Sin id de grabación al abrir el segmento.",
                mapOf("stage" to "openSegment", "segmentIndex" to index),
            )
        val format = outputFormat
            ?: throw RecordingException.muxerError(
                "Sin formato de salida del encoder al abrir el segmento.",
                mapOf("stage" to "openSegment", "segmentIndex" to index),
            )
        val staging = fileStore.segmentStagingFile(id, index)
        val created: RecordingMuxer
        try {
            created = muxerFactory(staging.absolutePath)
            created.setOrientationHint(rotationDegrees)
            trackIndex = created.addTrack(format)
            // Pista de audio (3.1.3): el formato AAC retenido se
            // reutiliza para CADA segmento nuevo (el encoder solo
            // emite el `csd` una vez); sin formato, video-only.
            val audioFormatNow = if (audioExpected) audioFormat else null
            audioTrackIndex = if (audioFormatNow != null) {
                created.addTrack(audioFormatNow)
            } else {
                -1
            }
            created.start()
        } catch (e: Exception) {
            throw RecordingException.muxerError(
                "No se pudo crear o arrancar el muxer del segmento " +
                    "$index: ${e.message}",
                mapOf(
                    "stage" to "openSegment",
                    "segmentIndex" to index,
                    "fileName" to staging.name,
                    "exceptionClass" to e.javaClass.simpleName,
                ),
            )
        }
        muxer = created
        segmentIndex = index
        segmentStagingFile = staging
        segmentFirstPtsUs = null
        segmentStartWallMs = clockMs()
        segmentSamples = 0
        segmentKeyframes = 0
        segmentFirstKeyframe = false
        segmentPtsMonotonic = true
        segmentLastPtsUs = null
        rotationForced = false
        // Audio del segmento (3.1.3): la expectativa de audio es
        // tener pista en el muxer; la generación es la del encoder
        // que la abrió (las muestras de otro encoder no entran).
        segmentAudioExpected = audioTrackIndex >= 0
        segmentAudioGeneration = if (segmentAudioExpected) {
            audioGeneration
        } else {
            -1
        }
        segmentAudioSampleRate = if (segmentAudioExpected) {
            audioConfig?.sampleRate
        } else {
            null
        }
        segmentAudioChannels = if (segmentAudioExpected) {
            audioConfig?.channels
        } else {
            null
        }
        segmentAudioReason = if (segmentAudioExpected) {
            null
        } else {
            audioReason
        }
        segmentOriginUs = null
        segmentFirstAudioPtsUs = null
        segmentAudioSamples = 0
        segmentAudioLastPtsUs = null
        segmentAudioGapSamples = 0
        // El alta en los metadatos es síncrona (la primera muestra del
        // segmento llega enseguida y la busca); la escritura del
        // sidecar puede ser asíncrona.
        synchronized(metadataSegments) {
            metadataSegments.add(
                SegmentMetadata(
                    index = index,
                    fileName = staging.name,
                    firstEncoderPtsUs = null,
                    startWallMs = segmentStartWallMs,
                    audioExpected = segmentAudioExpected,
                    audioReason = segmentAudioReason,
                ),
            )
        }
        scope.launch { writeSidecarSnapshot() }
    }

    /** Escribe el sidecar con los metadatos actuales. */
    private suspend fun writeSidecarSnapshot() {
        val metadata: RecordingMetadata
        synchronized(metadataSegments) {
            val id = recordingId ?: return
            val config = encoderConfig ?: return
            metadata = RecordingMetadata(
                recordingId = id,
                matchId = matchId,
                width = config.width,
                height = config.height,
                fps = config.fps,
                bitrateBps = config.bitrateBps,
                rotationDegrees = rotationDegrees,
                encoderName = encoderName,
                profile = config.profile,
                bitrateMode = config.bitrateMode,
                segments = metadataSegments.toList(),
                audioEnabled = audioExpected && audioConfig != null,
                audioSampleRate = if (audioExpected) {
                    audioConfig?.sampleRate
                } else {
                    null
                },
                audioChannels = if (audioExpected) {
                    audioConfig?.channels
                } else {
                    null
                },
                audioEncoderName = if (audioExpected) {
                    audioEncoderName.takeIf { it.isNotEmpty() }
                } else {
                    null
                },
            )
        }
        try {
            fileStore.writeSidecar(metadata)
        } catch (e: Exception) {
            Transmiti2Logger.e(
                "recording: no se pudo escribir el sidecar: " +
                    "${e.javaClass.simpleName}",
            )
        }
    }

    /**
     * Actualiza los metadatos de un segmento (estado, duración, tamaño
     * o primer PTS) y reescribe el sidecar. Se llama desde el hilo del
     * encoder (primera muestra) y desde la verificación (cierre).
     */
    private suspend fun updateSegmentMetadata(
        index: Int,
        update: (SegmentMetadata) -> SegmentMetadata,
    ) {
        synchronized(metadataSegments) {
            val i = metadataSegments.indexOfFirst { it.index == index }
            if (i < 0) return
            metadataSegments[i] = update(metadataSegments[i])
        }
        writeSidecarSnapshot()
    }

    override fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        // El drenado nunca lanza fuera del callback: un fallo aquí es
        // un `muxer_error` y una parada con error, no un crash.
        try {
            drainSample(buffer, info)
        } catch (e: Exception) {
            Transmiti2Logger.e(
                "recording: error en el drenado: " +
                    "${e.javaClass.simpleName} ${e.message}",
            )
            scheduleErrorStop(
                RecordingException.muxerError(
                    "Error al drenar al muxer: ${e.message}",
                    mapOf(
                        "stage" to "drain",
                        "segmentIndex" to segmentIndex,
                        "exceptionClass" to e.javaClass.simpleName,
                    ),
                ),
                triggerSite = "onSample",
            )
        }
    }

    private fun drainSample(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        // Muestras tardías (tras `stopDrain`): se ignoran, no se
        // registran como errores (lección del spike, fix `542b2d95`).
        if (drainStopped) return
        val ptsUs = info.presentationTimeUs
        val isKeyframe = info.flags and
            MediaCodec.BUFFER_FLAG_SYNC_FRAME != 0
        if (segmentFirstPtsUs == null) {
            segmentFirstPtsUs = ptsUs
            segmentFirstKeyframe = isKeyframe
            // El primer PTS del segmento ya se conoce: sidecar.
            val firstPts = ptsUs
            val index = segmentIndex
            scope.launch {
                updateSegmentMetadata(index) {
                    it.copy(firstEncoderPtsUs = firstPts)
                }
            }
        }
        if (segmentSamples > 0 && segmentLastPtsUs != null &&
            ptsUs <= segmentLastPtsUs!!
        ) {
            segmentPtsMonotonic = false
        }
        segmentLastPtsUs = ptsUs
        segmentSamples++
        if (isKeyframe) segmentKeyframes++

        // Rotación por duración (~60 s): se pide el keyframe y se
        // espera a él (o al natural, ≤ 2 s). Sin espacio, parada limpia.
        if (state == RecordingState.RECORDING && !rotationPending) {
            val firstPts = segmentFirstPtsUs ?: ptsUs
            if (ptsUs - firstPts >= SEGMENT_TARGET_US) {
                // Subetapa 0, grupo 0-b: espacio no medible (`null`,
                // `StatFs` lanzó o el lambda falló) → parada limpia
                // con `storage_check_unavailable`, mismo camino que
                // `storage_full`, segmentos previos intactos (U20b).
                // La medida corre en el hilo del encoder (este
                // `drainSample` no es `suspend`: `withContext(IO)`
                // no aplica aquí — ver informe del grupo 0-b, R-T5);
                // es un hilo de fondo dedicado, nunca Main.
                val free: Long? = try {
                    freeSpaceBytes()
                } catch (_: Exception) {
                    null
                }
                if (free == null) {
                    scheduleErrorStop(
                        RecordingException.storageCheckUnavailable(
                            stage = "rotate",
                            check = "free_space_check",
                            segmentIndex = segmentIndex,
                        ),
                        triggerSite = "drainSample:storageCheck",
                    )
                    return
                }
                if (free < MIN_FREE_BYTES_START) {
                    scheduleErrorStop(
                        RecordingException.storageFull(
                            "Sin espacio durante la grabación: parada " +
                                "limpia.",
                            mapOf(
                                "stage" to "rotate",
                                "segmentIndex" to segmentIndex,
                                // J3-a: el espacio medido viaja con
                                // el disparador (nunca se inventa).
                                "freeBytes" to free,
                            ),
                        ),
                        triggerSite = "drainSample:storageFull",
                    )
                    return
                }
                rotationPending = true
                rotationRequestedAtMs = clockMs()
                state = RecordingState.ROTATING_SEGMENT
                encoder?.requestSyncFrame()
            }
        }
        if (rotationPending) {
            val forced = clockMs() - rotationRequestedAtMs >=
                ROTATION_FORCE_TIMEOUT_MS
            if (isKeyframe || forced) {
                rotate(buffer, info, ptsUs, forced)
                return
            }
            // Aún sin keyframe: la muestra va al segmento actual.
        }

        // Sin muxer o sin origen fijado: la muestra se guarda en el
        // buffer pre-muxer (copia en heap, acotado a 150; nunca se
        // tira una muestra). Con audio, el origen espera al primer
        // PTS de audio; si el buffer se llena, se fija desde video
        // (la espera del formato ya expiró a los 2 s si no llegó).
        val currentMuxer = muxer
        if (currentMuxer == null || segmentOriginUs == null) {
            if (currentMuxer == null) samplesBeforeMuxer++
            preMuxerVideoBuffer.addLast(
                BufferedSample(copySampleBuffer(buffer, info), copyInfo(info)),
            )
            if (preMuxerVideoBuffer.size >= PRE_MUXER_VIDEO_MAX_SAMPLES) {
                audioFormatWaitExpired = true
            }
            maybeFixOrigin()
            return
        }

        writeSample(currentMuxer, buffer, info, ptsUs)
        // Tras cada muestra de video, las de audio debidas se
        // escriben (las pistas quedan entrelazadas y la cola corta).
        flushAudioQueue(null)
    }

    /** Escribe una muestra en el muxer actual (PTS rebasado al origen). */
    private fun writeSample(
        target: RecordingMuxer,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        ptsUs: Long,
    ) {
        // Origen común del segmento (§4.2): video-only es el primer
        // PTS de video (idéntico a 3.1.2); con audio es el mínimo de
        // ambos primeros PTS.
        val origin = segmentOriginUs ?: segmentFirstPtsUs ?: ptsUs
        val rebased = MediaCodec.BufferInfo().apply {
            offset = info.offset
            size = info.size
            presentationTimeUs = ptsUs - origin
            flags = info.flags
        }
        val startedMs = System.currentTimeMillis()
        try {
            target.writeSampleData(trackIndex, buffer, rebased)
        } catch (e: Exception) {
            scheduleErrorStop(
                RecordingException.muxerError(
                    "Error al escribir en el muxer: ${e.message}",
                    mapOf(
                        "stage" to "writeSample",
                        "segmentIndex" to segmentIndex,
                        "exceptionClass" to e.javaClass.simpleName,
                    ),
                ),
                triggerSite = "writeSample",
            )
            return
        }
        val latencyMs = System.currentTimeMillis() - startedMs
        if (latencyMs > maxWriteLatencyMs) {
            maxWriteLatencyMs = latencyMs
        }
    }

    /**
     * Rotación de segmento (hilo del encoder, en el `onSample` del
     * keyframe): cierra el segmento actual, abre el siguiente y
     * escribe el keyframe como primera muestra del nuevo (PTS 0).
     * Todo ocurre dentro del mismo callback, así que ninguna muestra
     * llega entre el cierre y la apertura. Si la apertura falla, la
     * grabación se detiene con error (el segmento cerrado ya está en
     * verificación).
     */
    private fun rotate(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        ptsUs: Long,
        forced: Boolean,
    ) {
        rotationForced = forced
        // Audio (D5): la cola se vacía con la frontera = PTS del
        // keyframe (las muestras anteriores van al segmento actual;
        // el resto queda en cola para el siguiente) y el buffer de
        // video al segmento actual, antes del cierre.
        flushAudioQueue(boundaryUs = ptsUs)
        flushPreMuxerBuffer()
        val closed = closeCurrentSegment(cleanly = true)
        if (closed != null) {
            verifyAsync(closed, closedCleanly = true)
        }
        rotationPending = false
        state = RecordingState.RECORDING
        try {
            openSegment(segmentIndex + 1)
        } catch (e: Exception) {
            scheduleErrorStop(
                when (e) {
                    is RecordingException -> e
                    else -> RecordingException.muxerError(
                        "No se pudo abrir el segmento siguiente: " +
                            "${e.message}",
                        mapOf(
                            "stage" to "rotate",
                            "segmentIndex" to segmentIndex + 1,
                            "exceptionClass" to e.javaClass.simpleName,
                        ),
                    )
                },
                triggerSite = "rotate",
            )
            return
        }
        // Si el audio se perdió y aún no se recuperó, se intenta la
        // recreación para el segmento siguiente (como mucho una por
        // segmento).
        if (audioLost) {
            launchAudioRecreation()
        }
        // El keyframe que disparó la rotación es la primera muestra
        // del segmento nuevo (PTS rebasado a 0). El origen del nuevo
        // segmento es el PTS del keyframe: por construcción, el primer
        // PTS de audio del segmento nuevo es >= frontera (§4.2).
        segmentFirstPtsUs = ptsUs
        segmentOriginUs = ptsUs
        segmentFirstKeyframe = true
        segmentLastPtsUs = ptsUs
        segmentSamples = 1
        segmentKeyframes = 1
        segmentFirstAudioPtsUs =
            audioQueue.firstOrNull()?.info?.presentationTimeUs
        val newIndex = segmentIndex
        scope.launch {
            updateSegmentMetadata(newIndex) {
                it.copy(firstEncoderPtsUs = ptsUs)
            }
        }
        val target = muxer
        if (target != null) {
            // Las muestras de audio en cola (>= frontera) van al
            // segmento nuevo antes que el keyframe.
            flushAudioQueue(null)
            writeSample(target, buffer, info, ptsUs)
        }
    }

    /**
     * Renombre staging → final con un reintento (subetapa 0, grupo
     * 0-d1): si el destino ya existe, no se reintenta (la guarda de
     * `renameStagingToFinal` lo impide: nunca sobrescribir); si el
     * primer intento falla por otra causa, log específico + un intento
     * más; si sigue fallando, `false` y el llamador degrada
     * (`closedCleanly = false` → como mucho `PARTIAL`, nunca `READY`,
     * como hoy). Nunca lanza.
     */
    private fun renameStagingWithRetry(
        staging: File,
        final: File,
        index: Int,
    ): Boolean {
        if (final.exists()) {
            Transmiti2Logger.e(
                "recording: renombre del segmento $index imposible: " +
                    "el destino ${final.name} ya existe; el segmento " +
                    "queda en staging como parcial, nunca ready",
            )
            return false
        }
        if (fileStore.renameStagingToFinal(staging, final)) return true
        Transmiti2Logger.e(
            "recording: renombre del segmento $index falló " +
                "(${staging.name} -> ${final.name}), reintentando una vez",
        )
        if (fileStore.renameStagingToFinal(staging, final)) return true
        Transmiti2Logger.e(
            "recording: renombre del segmento $index falló de nuevo; " +
                "el segmento queda en staging como parcial, nunca ready",
        )
        return false
    }

    /**
     * Cierra el segmento actual (hilo del encoder): `stop()` del
     * muxer, `release()` y renombrado del staging al nombre final.
     * Devuelve el segmento cerrado o `null` si no había muxer.
     * `cleanly = false` (interrupción) libera sin `stop()`: el
     * archivo queda como estaba y la verificación decide.
     */
    private fun closeCurrentSegment(cleanly: Boolean): ClosedSegment? {
        val currentMuxer = muxer ?: return null
        val staging = segmentStagingFile ?: return null
        val index = segmentIndex
        val id = recordingId ?: return null
        val firstPts = segmentFirstPtsUs
        val startWall = segmentStartWallMs
        val samples = segmentSamples
        val keyframes = segmentKeyframes
        val firstKeyframe = segmentFirstKeyframe
        val monotonic = segmentPtsMonotonic
        var closedCleanly = false
        // J3-a (3.1.4): distinguir "falló muxer.stop()" de "falló
        // el renombre": hoy ambos dejaban el .tmp con
        // closedCleanly=false sin motivo.
        var closeReason: String? = null
        if (cleanly) {
            try {
                currentMuxer.stop()
                closedCleanly = true
            } catch (e: Exception) {
                closeReason = "muxer_stop_failed"
                Transmiti2Logger.e(
                    "recording: stop() del muxer falló en el segmento " +
                        "$index: ${e.javaClass.simpleName}",
                )
            }
        } else {
            closeReason = "interrupted"
        }
        try {
            currentMuxer.release()
        } catch (_: Exception) {
        }
        muxer = null
        trackIndex = -1
        audioTrackIndex = -1
        segmentStagingFile = null
        val final = fileStore.segmentFile(id, index)
        val renamed = if (closedCleanly) {
            renameStagingWithRetry(staging, final, index)
        } else {
            false
        }
        // J3-a (3.1.4): el renombre solo se intenta con el muxer
        // parado; si falló, el motivo es el renombre (no el stop,
        // que ya fue bien y deja closeReason en null).
        if (closedCleanly && !renamed) {
            closeReason = "rename_failed"
        }
        if (renamed) {
            // El sidecar debe reflejar el nombre real (0-d1, primer
            // punto; `verifyAndRecord` lo repite defensivamente). Si
            // la escritura falla, el sidecar queda con el nombre
            // staging y la reconciliación lo corrige (C5).
            scope.launch {
                updateSegmentMetadata(index) {
                    it.copy(fileName = final.name)
                }
            }
        }
        // Desplazamiento del primer PTS de audio respecto al origen
        // común (ms): `null` sin audio o sin primer PTS.
        val audioStartOffsetMs =
            if (segmentFirstAudioPtsUs != null && segmentOriginUs != null) {
                (segmentFirstAudioPtsUs!! - segmentOriginUs!!) / 1_000L
            } else {
                null
            }
        return ClosedSegment(
            recordingId = id,
            index = index,
            file = if (renamed) final else staging,
            fileName = if (renamed) final.name else staging.name,
            firstEncoderPtsUs = firstPts,
            startWallMs = startWall,
            sampleCount = samples,
            keyframeCount = keyframes,
            firstSampleKeyframe = firstKeyframe,
            ptsMonotonic = monotonic,
            closedCleanly = closedCleanly && renamed,
            audioExpected = segmentAudioExpected,
            audioSampleRate = segmentAudioSampleRate,
            audioChannels = segmentAudioChannels,
            audioReason = segmentAudioReason,
            audioStartOffsetMs = audioStartOffsetMs,
            audioGapSamples = segmentAudioGapSamples,
            closeReason = closeReason,
        )
    }

    /** Verifica un segmento cerrado en segundo plano y emite su evento. */
    private fun verifyAsync(
        closed: ClosedSegment,
        closedCleanly: Boolean,
    ) {
        val deferred = CompletableDeferred<RecordingSegmentResult>()
        synchronized(pendingVerifications) {
            pendingVerifications.add(deferred)
        }
        scope.launch(Dispatchers.IO) {
            val result = verifyAndRecord(closed, closedCleanly)
            deferred.complete(result)
            synchronized(pendingVerifications) {
                pendingVerifications.remove(deferred)
            }
        }
    }

    private suspend fun verifyAndRecord(
        closed: ClosedSegment,
        closedCleanly: Boolean,
    ): RecordingSegmentResult {
        val config = encoderConfig
        // Una verificación a la vez (ronda 2): el mutex solo cubre la
        // llamada al verificador, no el registro del resultado.
        val verification = verificationMutex.withLock {
            segmentVerifier.verify(
                closed.file,
                closedCleanly,
                config?.width,
                config?.height,
                config?.fps,
                expectedAudio = closed.audioExpected,
                expectedSampleRate = closed.audioSampleRate,
                expectedChannels = closed.audioChannels,
            )
        }
        // Invariante 3 (plan §2.8): un `.tmp` nunca es `ready`. El
        // verificador real ya lo garantiza (`classifyIncomplete`),
        // pero la sesión lo impone también aquí: si el cierre no fue
        // limpio, un `READY` del verificador se degrada a `PARTIAL`.
        // Sin este tope, un verificador que devolviera `READY` para
        // un cierre no limpio colaría un `.tmp` como utilizable.
        val effectiveStatus = if (
            !closed.closedCleanly &&
            verification.status == SegmentStatus.READY
        ) {
            SegmentStatus.PARTIAL
        } else {
            verification.status
        }
        val result = RecordingSegmentResult(
            recordingId = closed.recordingId,
            index = closed.index,
            fileName = closed.fileName,
            filePath = closed.file.absolutePath,
            status = effectiveStatus,
            durationMs = verification.checks.durationMs,
            sizeBytes = closed.file.length(),
            sampleCount = closed.sampleCount,
            keyframeCount = closed.keyframeCount,
            firstSampleKeyframe = closed.firstSampleKeyframe,
            ptsMonotonic = closed.ptsMonotonic,
            profileIdc = verification.checks.profileIdc,
            fpsMeasured = verification.checks.fpsMeasured,
            verifiedSampleCount = verification.checks.sampleCount,
            ioPhaseMs = verification.checks.ioPhaseMs,
            closedCleanly = closed.closedCleanly,
            reason = verification.reason,
            hasAudio = verification.checks.hasAudioTrack,
            audioDurationMs = verification.checks.audioDurationMs,
            audioSampleCount = verification.checks.audioSampleCount,
            audioSampleRate = verification.checks.audioSampleRate,
            audioChannels = verification.checks.audioChannels,
            avOffsetMs = verification.checks.avOffsetMs,
            avDurationDiffMs = verification.checks.avDurationDiffMs,
            audioReason = closed.audioReason,
            audioGapSamples = closed.audioGapSamples,
            audioStartOffsetMs = closed.audioStartOffsetMs,
        )
        synchronized(segmentResults) { segmentResults.add(result) }
        updateSegmentMetadata(closed.index) {
            it.copy(
                // Segundo punto defensivo (0-d1): el sidecar refleja
                // el nombre real tras el renombre (el primer punto es
                // `closeCurrentSegment`).
                fileName = closed.fileName,
                status = result.status.name.lowercase(),
                durationMs = result.durationMs,
                sizeBytes = result.sizeBytes,
                hasAudio = result.hasAudio,
                audioDurationMs = result.audioDurationMs,
                audioSampleCount = result.audioSampleCount,
                audioStartOffsetMs = closed.audioStartOffsetMs,
                audioReason = result.audioReason,
                // Evidencia de verificación (0-d1, plan §2.5): la
                // reconciliación confía en el sidecar solo con E1–E6.
                verifiedAtMs = clockMs(),
                verifierVersion = SegmentVerifier.VERIFIER_VERSION,
                verificationReason = verification.reason,
                fpsMeasured = verification.checks.fpsMeasured,
            )
        }
        emitEvent(StreamContract.EVENT_RECORDING_SEGMENT, result.toMap())
        Transmiti2Logger.d(
            "recording: segmento ${closed.index} ${result.status} " +
                "${result.durationMs}ms ${result.sizeBytes}B",
        )
        return result
    }

    override fun onError(e: Exception) {
        scheduleErrorStop(
            RecordingException.encoderError(
                "Error del codificador: ${e.message}",
                mapOf(
                    "stage" to "encoder",
                    "segmentIndex" to segmentIndex,
                    "exceptionClass" to e.javaClass.simpleName,
                ),
            ),
            triggerSite = "onError",
        )
    }

    override fun onEndOfStream() {
        eosDeferred?.complete(Unit)
    }

    /**
     * Programa una parada con error (desde el drenado, sin bloquearlo).
     * J3-a (3.1.4, plan §1.3 paso 1): el disparador viaja completo
     * (`ErrorTrigger`: excepción original + sitio + instante) a
     * `stop()`/`doStop`, no solo `error.code`. El FGS no se toca
     * aquí: eso es J3-b.
     */
    private fun scheduleErrorStop(
        error: RecordingException,
        triggerSite: String,
    ) {
        if (state == RecordingState.IDLE ||
            state == RecordingState.ERROR
        ) {
            return
        }
        val trigger = ErrorTrigger.from(error, triggerSite, clockMs())
        scope.launch {
            try {
                stop(error.code, trigger)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Detiene la grabación y finaliza el segmento actual. Idempotente
     * desde `IDLE` (no falla, no hace nada) y desde `ERROR` (devuelve
     * el resultado ya calculado). `errorCode` es `null` en la parada
     * normal; en un error (encoder, muxer, almacenamiento, cámara)
     * lleva el código real y el estado queda `ERROR`. `errorTrigger`
     * es el disparador completo de una auto-parada por error (J3-a):
     * `null` en la parada normal y en un `stop(errorCode)` sin
     * disparador (p. ej. desde el motor); en ese caso `doStop`
     * conserva el genérico de siempre y no inventa un disparador.
     */
    open suspend fun stop(
        errorCode: String? = null,
        errorTrigger: ErrorTrigger? = null,
    ): RecordingResult {
        val existing = sessionMutex.withLock { stopResult }
        if (existing != null) return existing.await()
        val deferred = CompletableDeferred<RecordingResult>()
        val isFirst = sessionMutex.withLock {
            if (stopResult != null) {
                false
            } else {
                stopResult = deferred
                true
            }
        }
        if (!isFirst) return stopResult!!.await()
        return try {
            val result = doStop(errorCode, errorTrigger)
            deferred.complete(result)
            // Tras una parada normal el estado vuelve a IDLE: la
            // siguiente llamada a `stop()` debe devolver «vacío», no
            // el resultado de la grabación anterior. Tras un error el
            // resultado se conserva (parada idempotente desde ERROR).
            if (errorCode == null) {
                sessionMutex.withLock {
                    if (stopResult === deferred) stopResult = null
                }
            }
            result
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            throw e
        }
    }

    private suspend fun doStop(
        errorCode: String?,
        errorTrigger: ErrorTrigger? = null,
    ): RecordingResult {
        val idAtStop: String?
        val wasActive: Boolean
        sessionMutex.withLock {
            idAtStop = recordingId
            wasActive = when (state) {
                RecordingState.IDLE -> false
                RecordingState.ERROR -> lastResult != null
                else -> true
            }
            if (!wasActive && state == RecordingState.IDLE) {
                return RecordingResult.empty()
            }
            if (!wasActive) {
                // ERROR sin resultado: no había nada que detener.
                return lastResult ?: RecordingResult.empty()
            }
            state = RecordingState.STOPPING
        }
        var previewRestoreError: String? = null
        // Buffers de entrada AAC descartados (instrumentación
        // 3.1.3): se capturan en el `finally`, antes de liberar el
        // encoder de audio (tras `release()` ya no se puede leer).
        // Ronda 3 (grupo I): igual para el estado del micrófono y
        // los contadores del lector. Ronda U: igual para la capacidad
        // del buffer de entrada AAC.
        var droppedAtStop = 0
        var inputBufferCapacityAtStop: Int? = null
        var micStateAtStart: Int? = null
        var micStateAtStop: Int? = null
        var readCountAtStop = 0
        var lastReadCodeAtStop: Int? = null
        var lastReadOkAgeMsAtStop: Long? = null
        var readerAliveAtStop = false
        var audioEosAtStop = false
        // Grupo V: flag del watchdog (V3) y si la espera de EOS
        // agotó los 5 s (V4); se capturan como los demás contadores.
        var readerNoDataAtStop = false
        var eosTimedOutAtStop = false
        // Grupo S (S0): el segundo más silencioso, del mismo
        // encoder y antes de liberarlo que los demás contadores.
        var quietestPeakAtStop: Int? = null
        var quietestNonZeroAtStop: Int? = null
        // Grupo S (S1): el pico del disparo de silencio no se lee
        // del encoder (tras la recreación es otro): se capturó en
        // `onAudioError` (`silencePeakAtLoss`).
        var silencePeakAtStop: Int? = null
        // J3-a: motivo del cierre del último segmento (se fija en el
        // `try`, se lee al construir el resultado, tras el `finally`).
        var closeReasonAtStop: String? = null
        try {
            // EOS a ambos encoders (audio primero: parar la captura)
            // y drenado final (5 s en total); si no llega, se detiene
            // el drenado antes de soltar el muxer (lección del spike:
            // nunca escribir tras cerrar el muxer).
            var eosTimedOut = false
            try {
                audioEncoder?.signalEndOfInputStream()
                encoder?.signalEndOfInputStream()
                withTimeout(eosTimeoutMs) {
                    eosDeferred?.await()
                    if (audioEncoder != null) {
                        audioEosDeferred?.await()
                    }
                }
            } catch (e: TimeoutCancellationException) {
                eosTimedOut = true
                Transmiti2Logger.e("recording: EOS no llegó en 5 s")
            } catch (_: Exception) {
                eosTimedOut = true
            }
            // Grupo V (V4): la espera agotó los 5 s o no; se guarda
            // para el resultado (el `if` de abajo no cambia).
            eosTimedOutAtStop = eosTimedOut
            if (eosTimedOut) {
                drainStopped = true
                encoder?.stopDrain()
                audioEncoder?.stopDrain()
            }
            // Cierre del último segmento en el hilo del encoder: la
            // cola de audio y el buffer pre-muxer se vacían antes.
            // J3-a: el motivo del cierre queda en el resultado (si
            // el muxer no paró o el renombre falló, el .tmp queda
            // sin renombrar y hay que saber por qué).
            val closed = runOnEncoderThread {
                flushAudioQueue(null)
                flushPreMuxerBuffer()
                closeCurrentSegment(cleanly = true)
            }
            closeReasonAtStop = closed?.closeReason
            if (closed != null) {
                verifyAsync(closed, closedCleanly = closed.closedCleanly)
            }
            // Esperar todas las verificaciones (rotaciones incluidas).
            val pending: List<CompletableDeferred<RecordingSegmentResult>>
            synchronized(pendingVerifications) {
                pending = pendingVerifications.toList()
            }
            for (deferred in pending) {
                try {
                    deferred.await()
                } catch (_: Exception) {
                }
            }
            // Restaurar la sesión de solo vista previa.
            try {
                previewSession?.recreateSession(
                    null,
                    CameraDevice.TEMPLATE_PREVIEW,
                )
                sessionRecreated = false
            } catch (e: Exception) {
                previewRestoreError =
                    "${e.javaClass.simpleName}: ${e.message}"
                Transmiti2Logger.e(
                    "recording: no se pudo restaurar la vista previa: " +
                        "$previewRestoreError",
                )
            }
        } finally {
            // Antes de liberar el encoder de audio: su contador de
            // entradas descartadas muere con él. Ronda 3 (grupo I):
            // igual el estado del micrófono y los contadores del
            // lector; la edad de la última lectura OK se calcula
            // aquí (`elapsedRealtime()` ahora − instante guardado).
            val audioEncoderAtStop = audioEncoder
            droppedAtStop = audioEncoderAtStop?.droppedInputBuffers ?: 0
            inputBufferCapacityAtStop =
                audioEncoderAtStop?.inputBufferCapacityBytes
            micStateAtStart = audioEncoderAtStop?.recordingStateAtStart
            micStateAtStop = audioEncoderAtStop?.recordingState
            readCountAtStop = audioEncoderAtStop?.readCount ?: 0
            lastReadCodeAtStop = audioEncoderAtStop?.lastReadCode
            lastReadOkAgeMsAtStop =
                audioEncoderAtStop?.lastSuccessfulReadElapsedMs?.let {
                    SystemClock.elapsedRealtime() - it
                }
            readerAliveAtStop = audioEncoderAtStop?.isReaderAlive ?: false
            audioEosAtStop = audioEosArrived
            // Grupo V (V3): el flag del watchdog, del mismo encoder
            // y antes de liberarlo que los demás contadores.
            readerNoDataAtStop = audioEncoderAtStop?.readerNoData2s ?: false
            // Grupo S (S0): el segundo más silencioso, igual.
            quietestPeakAtStop = audioEncoderAtStop?.quietestSecondPeak
            quietestNonZeroAtStop =
                audioEncoderAtStop?.quietestSecondNonZero
            // Grupo S (S1): el pico del disparo, capturado en
            // `onAudioError` (no del encoder: tras la recreación es
            // otro).
            silencePeakAtStop = silencePeakAtLoss
            try {
                encoder?.release()
            } catch (_: Exception) {
            }
            encoder = null
            outputFormat = null
            // Audio (3.1.3, §5.3): ningún camino de salida deja el
            // micrófono abierto.
            try {
                audioEncoder?.release()
            } catch (_: Exception) {
            }
            audioEncoder = null
            audioFormat = null
            audioEosDeferred = null
            drainStopped = false
            rotationPending = false
            // 0-c: liberar el claim DESPUÉS de esperar las
            // verificaciones (arriba, en el `try`) y ANTES de volver a
            // IDLE/ERROR (abajo, fuera del `try`). Va en el `finally`
            // para cubrir también la salida por excepción; en
            // `NonCancellable` (ver `releaseClaimIfHeld`), para que
            // una cancelación no fugue el claim. Sin claim vivo (parada
            // desde IDLE/ERROR, o `stop()` durante el inicio antes del
            // claim) no hace nada.
            idAtStop?.let { releaseClaimIfHeld(it) }
        }
        val segments: List<RecordingSegmentResult>
        synchronized(segmentResults) {
            segments = segmentResults.sortedBy { it.index }
        }
        val result = RecordingResult.fromSegments(
            recordingId = idAtStop ?: "",
            segments = segments,
            errorCode = errorCode,
            previewRestoreError = previewRestoreError,
            droppedInputBuffers = droppedAtStop,
            inputBufferCapacityBytes = inputBufferCapacityAtStop,
            micStateAtStart = micStateAtStart,
            micStateAtStop = micStateAtStop,
            audioReadCount = readCountAtStop,
            audioLastReadCode = lastReadCodeAtStop,
            audioLastReadOkAgeMs = lastReadOkAgeMsAtStop,
            audioReaderAlive = readerAliveAtStop,
            audioEos = audioEosAtStop,
            audioReaderNoData2s = readerNoDataAtStop,
            eosTimedOut = eosTimedOutAtStop,
            quietestSecondPeak = quietestPeakAtStop,
            quietestSecondNonZero = quietestNonZeroAtStop,
            silencePeak = silencePeakAtStop,
            trigger = errorTrigger,
            closeReason = closeReasonAtStop,
        )
        sessionMutex.withLock {
            lastResult = result
            if (errorCode != null) {
                state = RecordingState.ERROR
                // J3-a (3.1.4, plan §1.3 paso 1): NO sobrescribir
                // `lastError` con el genérico de antes: se conserva
                // el disparador original tal cual llegó a
                // `scheduleErrorStop`. Sin disparador (parada con
                // error pedida desde fuera, sin `ErrorTrigger`), se
                // conserva el genérico de siempre.
                lastError = if (errorTrigger != null) {
                    RecordingException(
                        errorTrigger.code,
                        errorTrigger.message
                            ?: "La grabación se detuvo con error: " +
                                "$errorCode.",
                        errorTrigger.toMap(),
                    )
                } else {
                    RecordingException(
                        errorCode,
                        "La grabación se detuvo con error: $errorCode.",
                        mapOf("stage" to "stop", "recordingId" to idAtStop),
                    )
                }
            } else {
                state = RecordingState.IDLE
            }
        }
        if (errorCode != null) {
            // J3-a: mismo shape que `failStart` ({recordingId, code,
            // message, details}) para que
            // `RecordingDiagnostics.fromDetails` funcione siempre: el
            // resultado completo sigue viajando en el resto de claves
            // (segmentos, trigger, closeReason...).
            val errorDetails: Map<String, Any?> =
                errorTrigger?.toMap()?.let { triggerMap ->
                    if (closeReasonAtStop != null) {
                        triggerMap + ("closeReason" to closeReasonAtStop)
                    } else {
                        triggerMap
                    }
                } ?: mapOf(
                    "stage" to "stop",
                    "recordingId" to idAtStop,
                )
            emitEvent(
                StreamContract.EVENT_RECORDING_ERROR,
                result.toMap() + mapOf(
                    "code" to errorCode,
                    "message" to (errorTrigger?.message
                        ?: "La grabación se detuvo con error: " +
                            "$errorCode."),
                    "details" to errorDetails,
                ),
            )
        } else {
            emitEvent(StreamContract.EVENT_RECORDING_STOPPED, result.toMap())
        }
        Transmiti2Logger.d(
            "recording: detenida $idAtStop (${result.status}, " +
                "${segments.size} segmentos, " +
                "${result.totalDurationMs}ms, " +
                "muestrasAudio=" +
                segments.sumOf { it.audioSampleCount ?: 0 } +
                " huecosAudio=" +
                segments.sumOf { it.audioGapSamples } +
                " descartadasEntradaAAC=$droppedAtStop " +
                "estadoMic=$micStateAtStart/$micStateAtStop " +
                "lecturas=$readCountAtStop " +
                "ultimoRead=${lastReadCodeAtStop ?: "—"} " +
                "ultimaLecturaOkMs=${lastReadOkAgeMsAtStop ?: "—"} " +
                "lectorVivo=$readerAliveAtStop " +
                "audioEos=$audioEosAtStop " +
                "lectorSinDatos2s=$readerNoDataAtStop " +
                "eosTimeout=$eosTimedOutAtStop)",
        )
        return result
    }

    /**
     * Ejecuta `block` en el hilo del encoder y espera a que termine.
     * El muxer solo se toca desde ese hilo.
     */
    private suspend fun <T> runOnEncoderThread(block: () -> T): T {
        val encoderPort = encoder
            ?: throw RecordingException.encoderError(
                "Sin encoder para ejecutar en su hilo.",
                mapOf("stage" to "encoderThread"),
            )
        val done = CompletableDeferred<T>()
        encoderPort.post {
            try {
                done.complete(block())
            } catch (e: Exception) {
                done.completeExceptionally(e)
            }
        }
        return withTimeout(OPERATION_TIMEOUT_MS) { done.await() }
    }

    /**
     * Reconciliación de grabaciones interrumpidas (subetapa 0, grupo
     * 0-d1; plan §2.5–§2.6): inventario (sin lock) → agrupar por
     * `recordingId` → por grupo, `tryReserveGroup(G)` del registro
     * 0-c (`false` → `skipped_active`) → procesar sin el
     * `coordinationMutex` tomado (verificar bajo `verificationMutex`
     * como `verifyAndRecord`, renombrar con guarda, sidecar E1–E6,
     * casos C1–C6) → `releaseGroup(G)` en `finally` siempre.
     *
     * - Nunca se borra ni se sobrescribe nada: los duplicados van a
     *   cuarentena con timestamp y lo no verificable queda
     *   `PENDING_VERIFICATION` (nunca `ready`, nunca se elimina,
     *   excluido de lo utilizable, listado para reintento).
     * - Idempotente: una segunda pasada confía en los sidecars que la
     *   primera persistió (E1–E6) y no re-verifica.
     * - Nunca bloquea una grabación: el grupo activo se salta por
     *   reserva, no por comparación de id; un grupo que falla no
     *   tumba la pasada (queda en `groupsFailed` y se reintenta en la
     *   siguiente).
     * - Dos pasadas concurrentes son seguras por construcción: el
     *   registro reserva cada grupo para una sola (la otra lo ve
     *   `skipped_active`). La puerta de ejecución única por proceso
     *   es trabajo de 0-d2; aquí no se añade otro mutex (el
     *   `coordinationMutex` es hoja y no se toma teniendo otro).
     * - El barrido V4a de `.write-test-*.tmp` > 60 s corre también en
     *   el inventario (segunda sede, plan §6 A12).
     */
    open suspend fun reconcileRecordings(): ReconciliationSummary {
        val runId = "run-${reconcileRunCounter.incrementAndGet()}"
        val startedAtMs = clockMs()
        // P0 (2-c; diseño §5.1, Revisión 1 §4.2): justo antes del
        // inventario. Sin mutex tomado, sin reserva y sin archivos
        // abiertos: la pausa es una suspensión de corrutina inocua.
        // Con los ganchos desactivados retorna de inmediato.
        testHooks.awaitPauseIfArmed(PausePoint.P0, runId)
        // Inventario (sin lock): `.tmp.mp4` + `.mp4` + `.json`. La
        // reconciliación ignora `.write-test-*` por prefijo (nunca lo
        // verifica ni lo renombra).
        val finals = withContext(Dispatchers.IO) {
            fileStore.listFinalSegmentFiles()
        }
        val stagings = withContext(Dispatchers.IO) {
            fileStore.listStagingFiles()
        }
        val orphanWriteTestFilesSwept = withContext(Dispatchers.IO) {
            fileStore.sweepWriteTestOrphans(clockMs())
        }
        val groups = mutableMapOf<String, MutableList<InventoryEntry>>()
        val unknownFiles = mutableListOf<String>()
        for (file in finals + stagings) {
            val parsed = RecordingFileStore.parseSegmentFileName(file.name)
            if (parsed == null) {
                // Los archivos de cuarentena ya tienen disposición y
                // `.write-test-*` nunca es segmento: el resto va al
                // cubo «desconocido» (p. ej. `<id>_final.mp4`): se
                // lista, nunca se toca.
                if (!file.name.contains(".quarantine-") &&
                    !file.name.startsWith(".write-test-")
                ) {
                    unknownFiles += file.name
                }
                continue
            }
            val (id, index) = parsed
            groups.getOrPut(id) { mutableListOf() } += InventoryEntry(
                file = file,
                index = index,
                isStaging = file.name.endsWith(".tmp.mp4"),
            )
        }
        val outcomes = mutableListOf<ReconciledSegmentOutcome>()
        val skippedActive = mutableListOf<String>()
        val failedGroups = mutableListOf<String>()
        val tokens = mutableMapOf<String, OwnershipToken>()
        val missingFiles = mutableListOf<String>()
        var segmentsQuarantined = 0
        var conflicts = 0
        // P1 (2-c; diseño §5.1): entre el inventario y el bucle de
        // grupos. El inventario ya es una foto fija en memoria y aún
        // no hay ninguna reserva tomada.
        testHooks.awaitPauseIfArmed(PausePoint.P1, runId)
        for ((groupId, entries) in groups.toSortedMap()) {
            val token = registry.tryReserveGroup(
                groupId,
                "reconcile:$runId",
            )
            if (token == null) {
                skippedActive += groupId
                // 2-c: grupo saltado por reserva activa (el motivo es
                // el rechazo del registro; nunca se inventa otro).
                testHooks.record(
                    TestEventType.GROUP_SKIPPED_ACTIVE,
                    operation = "reconcile",
                    result = TestEventResult.SKIPPED,
                    detail = mapOf("groupId" to groupId, "runId" to runId),
                )
                Transmiti2Logger.d(
                    "reconciliation: grupo $groupId saltado " +
                        "(grabación activa o reservada)",
                )
                continue
            }
            tokens[groupId] = token
            // 2-c: reserva concedida (solo el id del grupo; nunca el
            // valor del token).
            testHooks.record(
                TestEventType.GROUP_RESERVED,
                operation = "reconcile",
                detail = mapOf("groupId" to groupId, "runId" to runId),
            )
            try {
                val group = reconcileGroup(groupId, entries)
                outcomes += group.outcomes
                missingFiles += group.missingFiles
                segmentsQuarantined += group.quarantined
                conflicts += group.conflicts
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Un grupo que falla no tumba la pasada: queda
                // registrado y se reintenta en la siguiente.
                failedGroups += groupId
                // 2-c: el grupo falló (con su clase de excepción).
                testHooks.record(
                    TestEventType.GROUP_FAILED,
                    operation = "reconcile",
                    result = TestEventResult.FAILED,
                    errorCode = "reconciliation_group_failed",
                    detail = mapOf(
                        "groupId" to groupId,
                        "runId" to runId,
                        "exceptionClass" to e.javaClass.simpleName,
                    ),
                )
                Transmiti2Logger.e(
                    "reconciliation: grupo $groupId falló: " +
                        "${e.javaClass.simpleName}",
                )
            } finally {
                // Liberación en `finally` siempre, en
                // `NonCancellable` (una cancelación no debe fugar la
                // reserva: toda reconciliación futura de ese grupo
                // daría `skipped_active`).
                withContext(NonCancellable) {
                    val released = registry.releaseGroup(groupId, token)
                    // 2-c: liberación de la reserva (siempre, incluso
                    // con cancelación).
                    testHooks.record(
                        TestEventType.GROUP_RELEASED,
                        operation = "reconcile",
                        result = if (released == ReleaseResult.RELEASED) {
                            TestEventResult.OK
                        } else {
                            TestEventResult.FAILED
                        },
                        detail = mapOf(
                            "groupId" to groupId,
                            "runId" to runId,
                            "releaseResult" to released.name,
                        ),
                    )
                    if (released != ReleaseResult.RELEASED) {
                        Transmiti2Logger.e(
                            "reconciliation: liberación de $groupId " +
                                "devolvió $released",
                        )
                    }
                }
            }
            // P2 (2-c; diseño §5.1): entre grupos, tras liberar la
            // reserva del grupo procesado y antes de reservar el
            // siguiente. Sin mutex tomado ni reserva viva. Tras el
            // último grupo también se alcanza (sin grupo siguiente):
            // la pausa ahí solo retrasa el resumen, sin retener nada.
            testHooks.awaitPauseIfArmed(PausePoint.P2, runId)
        }
        val finishedAtMs = clockMs()
        val summary = ReconciliationSummary(
            runId = runId,
            startedAtMs = startedAtMs,
            finishedAtMs = finishedAtMs,
            groupsTotal = groups.size,
            groupsReconciled = tokens.size,
            groupsSkippedActive = skippedActive,
            groupsFailed = failedGroups,
            segments = outcomes.sortedWith(
                compareBy({ it.recordingId }, { it.index }, { it.fileName }),
            ),
            segmentsTrusted = outcomes.count { it.trusted },
            segmentsVerified = outcomes.count { it.verified },
            segmentsRenamed = outcomes.count { it.renamed },
            segmentsPending = outcomes.filter { it.pending }.map {
                ReconciledSegmentRef(
                    recordingId = it.recordingId,
                    index = it.index,
                    fileName = it.fileName,
                    reason = it.reason,
                )
            },
            segmentsQuarantined = segmentsQuarantined,
            unknownFiles = unknownFiles.sorted(),
            missingFiles = missingFiles.sorted(),
            orphanWriteTestFilesSwept = orphanWriteTestFilesSwept,
            reservationTokens = tokens,
            foreignReleaseRejected = registry.snapshot()
                .foreignReleaseRejected,
            conflicts = conflicts,
        )
        // 2-c: resumen de la pasada en el log (runId, duración,
        // contadores). Lo consume el constructor del informe: C1/C2
        // exigen RECONCILIATION_FINISHED con este runId (Revisión 2
        // §2.8). No-op con los ganchos desactivados.
        testHooks.record(
            TestEventType.RECONCILIATION_FINISHED,
            operation = "reconcile",
            detail = mapOf(
                "runId" to summary.runId,
                "durationMs" to (summary.finishedAtMs - summary.startedAtMs),
                "groupsTotal" to summary.groupsTotal,
                "groupsReconciled" to summary.groupsReconciled,
                "groupsSkippedActive" to summary.groupsSkippedActive.size,
                "groupsFailed" to summary.groupsFailed.size,
                "segmentsTrusted" to summary.segmentsTrusted,
                "segmentsVerified" to summary.segmentsVerified,
                "segmentsRenamed" to summary.segmentsRenamed,
                "segmentsPending" to summary.segmentsPending.size,
                "segmentsQuarantined" to summary.segmentsQuarantined,
                "conflicts" to summary.conflicts,
                "foreignReleaseRejected" to summary.foreignReleaseRejected,
                "source" to "session",
            ),
        )
        return summary
    }

    /**
     * Reconcilia un grupo ya reservado (sin el `coordinationMutex`
     * tomado). Finales primero: la disposición del `.mp4` decide el
     * duplicado cuando el `.tmp` del mismo índice se renombra. Las
     * entradas del sidecar sin archivo en disco no se borran: se
     * listan en `missingFiles`.
     */
    private suspend fun reconcileGroup(
        groupId: String,
        entries: List<InventoryEntry>,
    ): GroupReconciliation {
        val baseSidecar = withContext(Dispatchers.IO) {
            fileStore.readSidecar(groupId)
        }
        val sidecarEntries = mutableMapOf<Int, SegmentMetadata>()
        baseSidecar?.segments?.forEach { sidecarEntries[it.index] = it }
        val ordered = entries.sortedWith(
            compareBy({ it.isStaging }, { it.index }),
        )
        val outcomes = mutableListOf<ReconciledSegmentOutcome>()
        val counters = GroupCounters()
        val finalOutcomeByIndex =
            mutableMapOf<Int, ReconciledSegmentOutcome>()
        val seenIndices = mutableSetOf<Int>()
        for (entry in ordered) {
            seenIndices += entry.index
            val outcome = reconcileSegment(
                groupId = groupId,
                entry = entry,
                baseSidecar = baseSidecar,
                sidecarEntries = sidecarEntries,
                finalOutcomeByIndex = finalOutcomeByIndex,
                counters = counters,
            ) ?: continue
            // Si un staging renombrado desplazó al final del mismo
            // índice (duplicado resuelto a su favor), el `outcome`
            // del final queda obsoleto: su archivo está en
            // cuarentena, no es un segmento.
            if (outcome.wasStaging && outcome.renamed) {
                outcomes.removeAll {
                    it.index == outcome.index && !it.wasStaging
                }
            }
            outcomes += outcome
            if (!outcome.wasStaging) {
                finalOutcomeByIndex[outcome.index] = outcome
            }
        }
        val missingFiles = sidecarEntries.values
            .filter { it.index !in seenIndices }
            .map {
                it.fileName.ifEmpty {
                    fileStore.segmentFileName(groupId, it.index)
                }
            }
        return GroupReconciliation(
            outcomes = outcomes,
            missingFiles = missingFiles,
            quarantined = counters.quarantined,
            conflicts = counters.conflicts,
        )
    }

    /**
     * Reconcilia un archivo del inventario (casos C1–C6, plan §2.5).
     * Devuelve `null` solo cuando el archivo quedó en cuarentena (el
     * final del mismo índice ganó el duplicado): su `outcome` ya
     * existe y el archivo ya no es un segmento.
     */
    private suspend fun reconcileSegment(
        groupId: String,
        entry: InventoryEntry,
        baseSidecar: RecordingMetadata?,
        sidecarEntries: MutableMap<Int, SegmentMetadata>,
        finalOutcomeByIndex: Map<Int, ReconciledSegmentOutcome>,
        counters: GroupCounters,
    ): ReconciledSegmentOutcome? {
        val file = entry.file
        val index = entry.index
        val sidecarEntry = sidecarEntries[index]
        // C1–C2: E1–E6 completos → confiar sin verificar.
        if (sidecarEntry != null && isTrustedSidecar(sidecarEntry, file)) {
            val status = if (sidecarEntry.status == "ready") {
                SegmentStatus.READY
            } else {
                SegmentStatus.PARTIAL
            }
            if (!entry.isStaging) {
                return ReconciledSegmentOutcome(
                    recordingId = groupId,
                    index = index,
                    fileName = file.name,
                    filePath = file.absolutePath,
                    status = status,
                    case = ReconciliationCase.C1_TRUSTED_FINAL,
                    durationMs = sidecarEntry.durationMs,
                    sizeBytes = file.length(),
                    reason = sidecarEntry.verificationReason,
                    verified = false,
                    trusted = true,
                    renamed = false,
                    wasStaging = false,
                )
            }
            val rename = renameUsableStaging(
                groupId = groupId,
                staging = file,
                index = index,
                finalOutcomeByIndex = finalOutcomeByIndex,
                counters = counters,
            )
            if (rename.quarantined) return null
            val finalStatus = if (rename.renamed) {
                status
            } else {
                SegmentStatus.PARTIAL
            }
            sidecarEntries[index] = sidecarEntry.copy(
                fileName = rename.fileName,
                status = finalStatus.name.lowercase(),
            )
            persistGroupSidecar(groupId, baseSidecar, sidecarEntries)
            return ReconciledSegmentOutcome(
                recordingId = groupId,
                index = index,
                fileName = rename.fileName,
                filePath = rename.file.absolutePath,
                status = finalStatus,
                case = ReconciliationCase.C2_TRUSTED_STAGING,
                durationMs = sidecarEntry.durationMs,
                sizeBytes = rename.file.length(),
                reason = rename.reason
                    ?: sidecarEntry.verificationReason,
                verified = false,
                trusted = true,
                renamed = rename.renamed,
                wasStaging = true,
            )
        }
        // C3/C4/C5: verificar (C4 sin sidecar; C5 contradictorio;
        // C3 incompleto). `closedCleanly` por extensión (`.mp4` →
        // `true`, `.tmp` → `false`); esperados del sidecar si
        // existen, si no `null`.
        val case = when {
            sidecarEntry == null -> ReconciliationCase.C4_NO_SIDECAR
            isContradictorySidecar(sidecarEntry, file) ->
                ReconciliationCase.C5_CONTRADICTORY
            else -> ReconciliationCase.C3_INCOMPLETE
        }
        val verification = try {
            verificationMutex.withLock {
                segmentVerifier.verify(
                    file,
                    closedCleanly = !entry.isStaging,
                    expectedWidth = baseSidecar?.width?.takeIf { it > 0 },
                    expectedHeight = baseSidecar?.height?.takeIf { it > 0 },
                    expectedFps = baseSidecar?.fps?.takeIf { it > 0 },
                    expectedAudio = sidecarEntry?.audioExpected
                        ?: baseSidecar?.audioEnabled?.takeIf { it },
                    expectedSampleRate = baseSidecar?.audioSampleRate,
                    expectedChannels = baseSidecar?.audioChannels,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // C6: no verificable → PENDING_VERIFICATION. Nunca
            // `ready`, nunca se elimina, excluido de lo utilizable,
            // listado para reintento. No es un fallo del grupo.
            Transmiti2Logger.e(
                "reconciliation: no se pudo verificar ${file.name}: " +
                    "${e.javaClass.simpleName}",
            )
            return ReconciledSegmentOutcome(
                recordingId = groupId,
                index = index,
                fileName = file.name,
                filePath = file.absolutePath,
                status = null,
                case = ReconciliationCase.C6_PENDING,
                durationMs = sidecarEntry?.durationMs,
                sizeBytes = file.length(),
                reason = e.javaClass.simpleName,
                verified = false,
                trusted = false,
                renamed = false,
                wasStaging = entry.isStaging,
            )
        }
        // Invariante 3 (igual que en `verifyAndRecord`): un `.tmp`
        // nunca es `ready`.
        val status = if (
            entry.isStaging &&
            verification.status == SegmentStatus.READY
        ) {
            SegmentStatus.PARTIAL
        } else {
            verification.status
        }
        val usable = status == SegmentStatus.READY ||
            status == SegmentStatus.PARTIAL
        val measured = SegmentMetadata(
            index = index,
            fileName = file.name,
            firstEncoderPtsUs = sidecarEntry?.firstEncoderPtsUs,
            startWallMs = sidecarEntry?.startWallMs ?: file.lastModified(),
            status = status.name.lowercase(),
            durationMs = verification.checks.durationMs,
            sizeBytes = file.length(),
            audioExpected = sidecarEntry?.audioExpected
                ?: verification.checks.hasAudioTrack,
            hasAudio = verification.checks.hasAudioTrack,
            audioDurationMs = verification.checks.audioDurationMs,
            audioSampleCount = verification.checks.audioSampleCount,
            audioStartOffsetMs = sidecarEntry?.audioStartOffsetMs,
            audioReason = sidecarEntry?.audioReason,
            verifiedAtMs = clockMs(),
            verifierVersion = SegmentVerifier.VERIFIER_VERSION,
            verificationReason = verification.reason,
            fpsMeasured = verification.checks.fpsMeasured,
        )
        sidecarEntries[index] = measured
        persistGroupSidecar(groupId, baseSidecar, sidecarEntries)
        var currentFile = file
        var currentFileName = file.name
        var renamed = false
        var renameReason: String? = null
        if (usable && entry.isStaging) {
            val rename = renameUsableStaging(
                groupId = groupId,
                staging = file,
                index = index,
                finalOutcomeByIndex = finalOutcomeByIndex,
                counters = counters,
            )
            if (rename.quarantined) return null
            currentFile = rename.file
            currentFileName = rename.fileName
            renamed = rename.renamed
            renameReason = rename.reason
            if (renamed || renameReason != null) {
                // El renombre (o su fallo) cambia el nombre o el
                // estado del sidecar: persistir de nuevo.
                sidecarEntries[index] = measured.copy(
                    fileName = currentFileName,
                    status = if (renamed) {
                        status.name.lowercase()
                    } else {
                        SegmentStatus.PARTIAL.name.lowercase()
                    },
                    sizeBytes = currentFile.length(),
                )
                persistGroupSidecar(groupId, baseSidecar, sidecarEntries)
            }
        }
        val finalStatus = if (!renamed && entry.isStaging && usable) {
            // El renombre falló: queda `.tmp`, parcial (nunca ready).
            SegmentStatus.PARTIAL
        } else {
            status
        }
        return ReconciledSegmentOutcome(
            recordingId = groupId,
            index = index,
            fileName = currentFileName,
            filePath = currentFile.absolutePath,
            status = finalStatus,
            case = case,
            durationMs = verification.checks.durationMs,
            sizeBytes = currentFile.length(),
            reason = renameReason ?: verification.reason,
            verified = true,
            trusted = false,
            renamed = renamed,
            wasStaging = entry.isStaging,
        )
    }

    /**
     * Confianza en sidecars E1–E6 (plan §2.5): un segmento es
     * previamente verificado si y solo si el sidecar tiene TODOS
     * E1–E6. E3 (tamaño) es consistencia, nunca suficiente por sí
     * sola. La consistencia de audio (`ready` + audio esperado sin
     * audio medido) es contradictoria → no confiable.
     */
    private fun isTrustedSidecar(
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

    /**
     * Sidecar contradictorio (caso C5): algún campo evaluable no
     * cuadra con el disco o con la versión actual. Si ningún campo
     * evaluable falla pero falta alguno (C3), devuelve `false`.
     */
    private fun isContradictorySidecar(
        entry: SegmentMetadata,
        file: File,
    ): Boolean {
        if (entry.sizeBytes != null && entry.sizeBytes != file.length()) {
            return true
        }
        if (entry.fileName.isNotEmpty() && entry.fileName != file.name) {
            return true
        }
        if (entry.verifierVersion != null &&
            entry.verifierVersion != SegmentVerifier.VERIFIER_VERSION
        ) {
            return true
        }
        if (entry.status != null &&
            entry.status != "ready" && entry.status != "partial"
        ) {
            return true
        }
        if (entry.status == "ready" && entry.audioExpected &&
            (!entry.hasAudio || entry.audioSampleCount == null)
        ) {
            return true
        }
        return false
    }

    /**
     * Renombra un staging utilizable al nombre final, con guarda de
     * destino duplicado (plan §2.5): si el `.mp4` ya existe, se
     * verifica ambos (el staging es utilizable; el final lo es si su
     * `outcome` lo dice), se conserva el verificado y el otro va a
     * cuarentena con timestamp; nunca se sobrescribe.
     */
    private fun renameUsableStaging(
        groupId: String,
        staging: File,
        index: Int,
        finalOutcomeByIndex: Map<Int, ReconciledSegmentOutcome>,
        counters: GroupCounters,
    ): StagingRename {
        val final = fileStore.segmentFile(groupId, index)
        if (!final.exists()) {
            return if (fileStore.renameStagingToFinal(staging, final)) {
                StagingRename(
                    fileName = final.name,
                    file = final,
                    renamed = true,
                    quarantined = false,
                    reason = null,
                )
            } else {
                Transmiti2Logger.e(
                    "reconciliation: renombre de ${staging.name} " +
                        "falló; queda .tmp como parcial, nunca ready",
                )
                StagingRename(
                    fileName = staging.name,
                    file = staging,
                    renamed = false,
                    quarantined = false,
                    reason = "rename_failed",
                )
            }
        }
        counters.conflicts++
        val finalVerified = finalOutcomeByIndex[index]?.usable == true
        return when (
            fileStore.resolveDuplicateDestination(
                staging = staging,
                final = final,
                stagingVerified = true,
                finalVerified = finalVerified,
            )
        ) {
            DuplicateResolution.STAGING_KEPT -> {
                counters.quarantined++
                StagingRename(
                    fileName = final.name,
                    file = final,
                    renamed = true,
                    quarantined = false,
                    reason = null,
                )
            }
            DuplicateResolution.FINAL_KEPT -> {
                counters.quarantined++
                StagingRename(
                    fileName = staging.name,
                    file = staging,
                    renamed = false,
                    quarantined = true,
                    reason = null,
                )
            }
            DuplicateResolution.UNRESOLVED -> {
                Transmiti2Logger.e(
                    "reconciliation: duplicado de ${staging.name} sin " +
                        "resolver; queda .tmp como parcial, nunca ready",
                )
                StagingRename(
                    fileName = staging.name,
                    file = staging,
                    renamed = false,
                    quarantined = false,
                    reason = "rename_failed",
                )
            }
        }
    }

    /**
     * Persiste el sidecar del grupo con las entradas actuales. La
     * escritura es atómica (`.tmp` + renombre, como hoy). Si falla,
     * solo se registra: la reconciliación no falla por no poder
     * persistir; el segmento queda clasificado en el resumen y la
     * siguiente pasada lo reintenta (idempotente). Cuando no hay
     * sidecar previo, los campos de grabación se reconstruyen con
     * valores desconocidos (0/`""`): el registro que importa es el
     * del segmento; nunca se inventan dimensiones ni códec.
     */
    private suspend fun persistGroupSidecar(
        groupId: String,
        base: RecordingMetadata?,
        entries: Map<Int, SegmentMetadata>,
    ) {
        if (entries.isEmpty()) return
        val metadata = base?.copy(
            segments = entries.values.sortedBy { it.index },
        ) ?: RecordingMetadata(
            recordingId = groupId,
            matchId = null,
            width = 0,
            height = 0,
            fps = 0,
            bitrateBps = 0,
            rotationDegrees = 0,
            encoderName = "",
            profile = 0,
            bitrateMode = 0,
            segments = entries.values.sortedBy { it.index },
        )
        try {
            withContext(Dispatchers.IO) { fileStore.writeSidecar(metadata) }
        } catch (e: Exception) {
            Transmiti2Logger.e(
                "reconciliation: no se pudo escribir el sidecar de " +
                    "$groupId: ${e.javaClass.simpleName}",
            )
        }
    }

    /**
     * La envoltura fina `recoverInterrupted()` (0-d1) se eliminó en
     * 0-d2 con el renombre del canal a `reconcileRecordings()`: toda
     * la lógica vive en [reconcileRecordings] y no hay dos caminos.
     * Dart migra con ella (el controlador llama a
     * `reconcileRecordings()` y recibe el resumen por grupo).
     */

    /** Libera encoder y muxer (cierre del motor). Idempotente. */
    open suspend fun release() {
        if (state == RecordingState.RECORDING ||
            state == RecordingState.ROTATING_SEGMENT ||
            state == RecordingState.STARTING
        ) {
            try {
                stop()
            } catch (_: Exception) {
            }
        }
        try {
            runOnEncoderThread {
                try {
                    muxer?.release()
                } catch (_: Exception) {
                }
                muxer = null
                trackIndex = -1
            }
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
        outputFormat = null
        // Audio (3.1.3, §5.3): ningún camino de salida deja el
        // micrófono abierto.
        try {
            audioEncoder?.release()
        } catch (_: Exception) {
        }
        audioEncoder = null
        audioFormat = null
        audioEosDeferred = null
        // 0-c: red de seguridad del claim: `stop()` ya lo liberó en
        // `doStop()` en el camino normal, pero si `release()` llega
        // sin pasar por ahí (o `doStop()` no llegó a su `finally`),
        // el claim no debe fugar. Toma-y-limpia atómica bajo
        // `sessionMutex`; la liberación al registro ocurre fuera del
        // lock (el `coordinationMutex` es hoja).
        val claimId = recordingId
        val claimToken = takeClaimToken()
        if (claimId != null && claimToken != null) {
            withContext(NonCancellable) { releaseClaim(claimId, claimToken) }
        }
        sessionMutex.withLock { state = RecordingState.IDLE }
    }

    companion object {
        /** Duración objetivo de cada segmento (plan §4.1). */
        const val SEGMENT_TARGET_US = 60_000_000L
        const val TARGET_BITRATE_BPS = 2_500_000
        const val FORMAT_TIMEOUT_MS = 5_000L
        const val EOS_TIMEOUT_MS = 5_000L
        const val OPERATION_TIMEOUT_MS = 5_000L

        /** Sin espacio para empezar o seguir (< 200 MB, plan §6.3). */
        const val MIN_FREE_BYTES_START = 200L * 1024 * 1024

        /**
         * Intentos de regeneración del id si el claim es rechazado
         * (grupo reservado, subetapa 0 grupo 0-c): mismo tope que la
         * S3' del plan (10 intentos); agotado → `recording_state_error`
         * con `stage = "id_reservation"`.
         */
        const val MAX_ID_CLAIM_ATTEMPTS = 10

        /** Aviso de poco espacio al iniciar (< 2 GB, plan §9.2). */
        const val LOW_STORAGE_WARN_BYTES = 2L * 1024 * 1024 * 1024

        /** Si el keyframe pedido no llega en 5 s, rotación forzada. */
        const val ROTATION_FORCE_TIMEOUT_MS = 5_000L

        /** Espera del formato AAC desde el formato de video (§5.1). */
        const val AUDIO_FORMAT_TIMEOUT_MS = 2_000L

        /** Muestras de video en el buffer pre-muxer (§5.1, ~5 s a 30 fps). */
        const val PRE_MUXER_VIDEO_MAX_SAMPLES = 150

        /** Muestras de audio en la cola pre-muxer (§5.1, ~2,1 s). */
        const val AUDIO_QUEUE_MAX_SAMPLES = 100
    }
}

/** Segmento cerrado, pendiente de verificación. */
private data class ClosedSegment(
    val recordingId: String,
    val index: Int,
    val file: File,
    val fileName: String,
    val firstEncoderPtsUs: Long?,
    val startWallMs: Long,
    val sampleCount: Int,
    val keyframeCount: Int,
    val firstSampleKeyframe: Boolean,
    val ptsMonotonic: Boolean,
    val closedCleanly: Boolean,
    /** Audio (3.1.3): expectativa y configuración del segmento. */
    val audioExpected: Boolean = false,
    val audioSampleRate: Int? = null,
    val audioChannels: Int? = null,
    val audioReason: String? = null,
    val audioStartOffsetMs: Long? = null,
    val audioGapSamples: Int = 0,
    /**
     * J3-a (3.1.4): por qué el segmento quedó sin renombrar
     * (`muxer_stop_failed` | `rename_failed` | `interrupted`).
     * `null` si el cierre y el renombre fueron limpios.
     */
    val closeReason: String? = null,
)

/** Archivo del inventario de reconciliación (subetapa 0, grupo 0-d1). */
private data class InventoryEntry(
    val file: File,
    val index: Int,
    val isStaging: Boolean,
)

/** Resultado de intentar el renombre de un staging utilizable. */
private data class StagingRename(
    /** Nombre del archivo tras el intento. */
    val fileName: String,
    val file: File,
    val renamed: Boolean,
    /** `true` si el staging quedó en cuarentena (el final ganó). */
    val quarantined: Boolean,
    /** Motivo cuando no se renombró (`rename_failed`). */
    val reason: String?,
)

/** Resultado de reconciliar un grupo. */
private data class GroupReconciliation(
    val outcomes: List<ReconciledSegmentOutcome>,
    val missingFiles: List<String>,
    val quarantined: Int,
    val conflicts: Int,
)

/** Contadores de un grupo durante su reconciliación. */
private class GroupCounters {
    var quarantined: Int = 0
    var conflicts: Int = 0
}

/** Muestra copiada en heap (buffer + info) para el buffer pre-muxer
 * de video y la cola de audio. */
private data class BufferedSample(
    val buffer: ByteBuffer,
    val info: MediaCodec.BufferInfo,
)
