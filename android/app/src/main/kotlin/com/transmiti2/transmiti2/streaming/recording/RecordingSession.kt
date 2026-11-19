package com.transmiti2.transmiti2.streaming.recording

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.StatFs
import android.view.Surface
import com.transmiti2.transmiti2.streaming.CameraException
import com.transmiti2.transmiti2.streaming.PreviewConfig
import com.transmiti2.transmiti2.streaming.PreviewSession
import com.transmiti2.transmiti2.streaming.StreamContract
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Segmento interrumpido (`.tmp.mp4`) clasificado en la recuperación. */
data class InterruptedSegmentInfo(
    val recordingId: String,
    val index: Int,
    val fileName: String,
    val filePath: String,
    val status: SegmentStatus,
    val durationMs: Long?,
    val sizeBytes: Long,
    val reason: String?,
)

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
 * `<documents>/recordings/`) y `verifier` (con el `Context` de la app
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
    private val freeSpaceBytes: () -> Long = {
        try {
            StatFs(fileStore.recordingsDir.absolutePath).availableBytes
        } catch (_: Exception) {
            // Sin medida: no bloquear el inicio por el chequeo.
            Long.MAX_VALUE
        }
    },
    private val clockMs: () -> Long = System::currentTimeMillis,
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
     * recuperación (`recoverInterrupted`) llama al verificador
     * directamente, pero solo corre en `idle`, así que no puede
     * solaparse con una rotación.
     */
    private val verificationMutex = Mutex()

    @Volatile
    private var state: RecordingState = RecordingState.IDLE
    private var lastError: RecordingException? = null
    private var lastResult: RecordingResult? = null
    private var stopResult: CompletableDeferred<RecordingResult>? = null

    private var recordingId: String? = null
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
        } ?: throw RecordingException.stateError(
            "Ya hay una grabación $state.",
            mapOf("stage" to "start", "state" to state.name.lowercase()),
        )
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
        audioExpected = false
        audioReason = null
        audioLost = false
        audioLostReason = null
        audioLostSegmentIndex = null
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
            // (CLOCK_MONOTONIC) es la base que exige el plan; se
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

            // Almacenamiento: < 200 MB no se empieza; < 2 GB avisa.
            val free = freeSpaceBytes()
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

            val id = fileStore.newRecordingId(clockMs())
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

    /** Fracaso del inicio: estado `ERROR`, evento y limpieza. */
    private fun failStart(e: RecordingException): Nothing {
        state = RecordingState.ERROR
        lastError = e
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
            Transmiti2Logger.d(
                "recording: audio ${setup.config.sampleRate} Hz " +
                    "${setup.config.channels}ch " +
                    "${setup.config.bitrateBps} bps " +
                    "(${setup.encoderName})",
            )
        } catch (e: Exception) {
            // Micrófono no disponible o AAC que no arranca: video-only.
            audioReason = "audio_mic_unavailable"
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
            try {
                encoder?.post { maybeOpenSegment() }
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
            encoder?.post { maybeOpenSegment() }
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
        val reason = (e as? RecordingException)?.details?.get("reason")
            as? String ?: "audio_encoder_error"
        audioLost = true
        audioLostReason = reason
        audioLostSegmentIndex = segmentIndex
        if (audioLostSegmentIndex == segmentIndex) {
            segmentAudioReason = reason
        }
        // El formato del encoder muerto queda obsoleto: sin él, la
        // rotación abre el segmento siguiente video-only (no con una
        // pista vacía); la recreación pone el formato nuevo.
        audioFormat = null
        Transmiti2Logger.e(
            "recording: audio perdido ($reason) en el segmento " +
                "$segmentIndex; el video continúa",
        )
        emitEvent(
            StreamContract.EVENT_RECORDING_AUDIO_LOST,
            mapOf(
                "recordingId" to recordingId,
                "reason" to reason,
                "segmentIndex" to segmentIndex,
                "message" to (e.message ?: "Audio perdido."),
            ),
        )
        launchAudioRecreation()
    }

    /** Fin del drenado de audio tras `signalEndOfInputStream`. */
    private fun onAudioEndOfStream(generation: Int) {
        if (generation != audioGeneration) return
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
        maybeOpenSegment()
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
                if (freeSpaceBytes() < MIN_FREE_BYTES_START) {
                    scheduleErrorStop(
                        RecordingException.storageFull(
                            "Sin espacio durante la grabación: parada " +
                                "limpia.",
                            mapOf(
                                "stage" to "rotate",
                                "segmentIndex" to segmentIndex,
                            ),
                        ),
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
        if (cleanly) {
            try {
                currentMuxer.stop()
                closedCleanly = true
            } catch (e: Exception) {
                Transmiti2Logger.e(
                    "recording: stop() del muxer falló en el segmento " +
                        "$index: ${e.javaClass.simpleName}",
                )
            }
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
            fileStore.renameStagingToFinal(staging, final)
        } else {
            false
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
        val result = RecordingSegmentResult(
            recordingId = closed.recordingId,
            index = closed.index,
            fileName = closed.fileName,
            filePath = closed.file.absolutePath,
            status = verification.status,
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
                status = result.status.name.lowercase(),
                durationMs = result.durationMs,
                sizeBytes = result.sizeBytes,
                hasAudio = result.hasAudio,
                audioDurationMs = result.audioDurationMs,
                audioSampleCount = result.audioSampleCount,
                audioStartOffsetMs = closed.audioStartOffsetMs,
                audioReason = result.audioReason,
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
        )
    }

    override fun onEndOfStream() {
        eosDeferred?.complete(Unit)
    }

    /** Programa una parada con error (desde el drenado, sin bloquearlo). */
    private fun scheduleErrorStop(error: RecordingException) {
        if (state == RecordingState.IDLE ||
            state == RecordingState.ERROR
        ) {
            return
        }
        scope.launch {
            try {
                stop(error.code)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Detiene la grabación y finaliza el segmento actual. Idempotente
     * desde `IDLE` (no falla, no hace nada) y desde `ERROR` (devuelve
     * el resultado ya calculado). `errorCode` es `null` en la parada
     * normal; en un error (encoder, muxer, almacenamiento, cámara)
     * lleva el código real y el estado queda `ERROR`.
     */
    open suspend fun stop(errorCode: String? = null): RecordingResult {
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
            val result = doStop(errorCode)
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

    private suspend fun doStop(errorCode: String?): RecordingResult {
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
        var droppedAtStop = 0
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
            if (eosTimedOut) {
                drainStopped = true
                encoder?.stopDrain()
                audioEncoder?.stopDrain()
            }
            // Cierre del último segmento en el hilo del encoder: la
            // cola de audio y el buffer pre-muxer se vacían antes.
            val closed = runOnEncoderThread {
                flushAudioQueue(null)
                flushPreMuxerBuffer()
                closeCurrentSegment(cleanly = true)
            }
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
            // entradas descartadas muere con él.
            droppedAtStop = audioEncoder?.droppedInputBuffers ?: 0
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
        )
        sessionMutex.withLock {
            lastResult = result
            if (errorCode != null) {
                state = RecordingState.ERROR
                lastError = RecordingException(
                    errorCode,
                    "La grabación se detuvo con error: $errorCode.",
                    mapOf("stage" to "stop", "recordingId" to idAtStop),
                )
            } else {
                state = RecordingState.IDLE
            }
        }
        if (errorCode != null) {
            emitEvent(
                StreamContract.EVENT_RECORDING_ERROR,
                result.toMap() + mapOf("code" to errorCode),
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
                " descartadasEntradaAAC=$droppedAtStop)",
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
     * Recupera segmentos interrumpidos (plan §7.3, alcance mínimo):
     * lista los `.tmp.mp4` que quedaron, los agrupa por grabación y
     * clasifica cada uno con el verificador (sin modificar el
     * original). La configuración esperada es `null` salvo que el
     * sidecar la dé (en 3.1.2 no se lee el sidecar: la clasificación
     * es por legibilidad). No borra nada; no fusiona (la fusión no se
     * demostró en el spike).
     */
    open suspend fun recoverInterrupted(): List<InterruptedSegmentInfo> {
        val staging = fileStore.listStagingFiles()
        val result = mutableListOf<InterruptedSegmentInfo>()
        for (file in staging) {
            val parsed = RecordingFileStore.parseSegmentFileName(file.name)
                ?: continue
            val (id, index) = parsed
            val verification = try {
                segmentVerifier.verify(
                    file,
                    closedCleanly = false,
                    expectedWidth = null,
                    expectedHeight = null,
                    expectedFps = null,
                )
            } catch (e: Exception) {
                Transmiti2Logger.e(
                    "recording: no se pudo verificar ${file.name}: " +
                        "${e.javaClass.simpleName}",
                )
                continue
            }
            result.add(
                InterruptedSegmentInfo(
                    recordingId = id,
                    index = index,
                    fileName = file.name,
                    filePath = file.absolutePath,
                    status = verification.status,
                    durationMs = verification.checks.durationMs,
                    sizeBytes = file.length(),
                    reason = verification.reason,
                ),
            )
        }
        return result.sortedWith(
            compareBy({ it.recordingId }, { it.index }),
        )
    }

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
)

/** Muestra copiada en heap (buffer + info) para el buffer pre-muxer
 * de video y la cola de audio. */
private data class BufferedSample(
    val buffer: ByteBuffer,
    val info: MediaCodec.BufferInfo,
)
