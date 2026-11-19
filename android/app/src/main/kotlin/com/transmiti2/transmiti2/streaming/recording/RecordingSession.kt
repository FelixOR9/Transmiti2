package com.transmiti2.transmiti2.streaming.recording

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
)

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

        try {
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
            created.listener = this
            created.configureAndStart()
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
            openSegment(segmentIndex)
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
        val currentMuxer = muxer
        if (currentMuxer == null || trackIndex < 0) {
            samplesBeforeMuxer++
            return
        }
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

        writeSample(currentMuxer, buffer, info, ptsUs)
    }

    /** Escribe una muestra en el muxer actual (PTS rebasado a 0). */
    private fun writeSample(
        target: RecordingMuxer,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        ptsUs: Long,
    ) {
        val firstPts = segmentFirstPtsUs ?: ptsUs
        val rebased = MediaCodec.BufferInfo().apply {
            offset = info.offset
            size = info.size
            presentationTimeUs = ptsUs - firstPts
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
        // El keyframe que disparó la rotación es la primera muestra
        // del segmento nuevo (PTS rebasado a 0).
        segmentFirstPtsUs = ptsUs
        segmentFirstKeyframe = true
        segmentLastPtsUs = ptsUs
        segmentSamples = 1
        segmentKeyframes = 1
        val newIndex = segmentIndex
        scope.launch {
            updateSegmentMetadata(newIndex) {
                it.copy(firstEncoderPtsUs = ptsUs)
            }
        }
        val target = muxer
        if (target != null) {
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
        segmentStagingFile = null
        val final = fileStore.segmentFile(id, index)
        val renamed = if (closedCleanly) {
            fileStore.renameStagingToFinal(staging, final)
        } else {
            false
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
        )
        synchronized(segmentResults) { segmentResults.add(result) }
        updateSegmentMetadata(closed.index) {
            it.copy(
                status = result.status.name.lowercase(),
                durationMs = result.durationMs,
                sizeBytes = result.sizeBytes,
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
        try {
            // EOS al encoder y drenado final (5 s); si no llega, se
            // detiene el drenado antes de soltar el muxer (lección del
            // spike: nunca escribir tras cerrar el muxer).
            var eosTimedOut = false
            try {
                encoder?.signalEndOfInputStream()
                withTimeout(eosTimeoutMs) {
                    eosDeferred?.await()
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
            }
            // Cierre del último segmento en el hilo del encoder.
            val closed = runOnEncoderThread {
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
            try {
                encoder?.release()
            } catch (_: Exception) {
            }
            encoder = null
            outputFormat = null
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
                "${result.totalDurationMs}ms)",
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
)
