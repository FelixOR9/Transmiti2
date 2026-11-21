package com.transmiti2.transmiti2.streaming

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.transmiti2.transmiti2.streaming.recording.DefaultSegmentVerifier
import com.transmiti2.transmiti2.streaming.recording.InterruptedSegmentInfo
import com.transmiti2.transmiti2.streaming.recording.RecordingException
import com.transmiti2.transmiti2.streaming.recording.RecordingFileStore
import com.transmiti2.transmiti2.streaming.recording.RecordingResult
import com.transmiti2.transmiti2.streaming.recording.RecordingSession
import com.transmiti2.transmiti2.streaming.recording.RecordingStatusSnapshot
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Rotación de la pantalla en grados (0, 90, 180 o 270). En API 30+ se
 * lee de `Context.display`; por debajo, del `WindowManager`. Si no se
 * puede leer, 0. Nunca lanza.
 */
fun displayRotationDegrees(context: Context): Int {
    return try {
        val rotation = if (Build.VERSION.SDK_INT >= 30) {
            context.display?.rotation ?: Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            val windowManager = context.getSystemService(
                Context.WINDOW_SERVICE,
            ) as? WindowManager
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation
                ?: Surface.ROTATION_0
        }
        when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}

/** Fábrica de sesiones de vista previa (la de producción usa Camera2). */
typealias PreviewSessionFactory = (SurfaceProducerHandle) -> PreviewSession

/**
 * Motor de vista previa real (subetapa 3.1.1).
 *
 * Orquesta, en este orden: comprobación del permiso de cámara,
 * creación del productor de superficie (`SurfaceProducer` de Flutter),
 * apertura de la sesión Camera2 y repetición de la vista previa.
 *
 * Máquina de estados:
 * - `IDLE` → `STARTING` → `ACTIVE` (vista previa en pantalla).
 * - `ACTIVE` → `RECOVERING` cuando la superficie se pierde
 *   temporalmente (`onSurfaceCleanup`): se cierra solo la sesión, el
 *   productor se conserva, y al volver la superficie
 *   (`onSurfaceAvailable`) se reabre la sesión con la superficie nueva
 *   y la misma textura. Si la reapertura falla, `IDLE` + error.
 * - Desconexión o error de cámara: se libera todo, `IDLE` y evento
 *   `preview_error`.
 * - `stopPreview` y `stopAll` son idempotentes: parar sin vista previa
 *   no falla. `startPreview` con la vista previa activa devuelve la
 *   información actual sin reabrir; dos inicios simultáneos comparten
 *   el mismo trabajo (un solo `Deferred`).
 *
 * La cámara nunca se abre sin el permiso concedido (se comprueba antes
 * de crear nada) y nunca se mantiene abierta innecesariamente: al parar
 * se cierran sesión y dispositivo y se libera el productor.
 *
 * Fase B, 3.1.2: `startRecording` y `stopRecording` son reales. La
 * grabación exige vista previa `ACTIVE` (la cámara ya está abierta y la
 * configuración elegida es la de la vista previa) y arranca el FGS
 * ANTES de la sesión (plan §9.2: protege el proceso durante el
 * arranque pesado); si la sesión falla, el servicio se detiene. En
 * 3.1.3 el escenario del FGS es por grabación (`WITH_AUDIO` solo si se
 * pidió audio y el micrófono está concedido; `WITHOUT_AUDIO` en caso
 * contrario, plan §6 D8). `stopPreview`, `onSurfaceCleanup` y la desconexión o el
 * error de cámara detienen primero la grabación limpiamente (plan
 * §6.4) y después aplican la lógica de vista previa. `startRecording`
 * y `stopRecording` se serializan con `recordingMutex`; el orden de
 * bloqueo es siempre `recordingMutex` antes que el mutex del motor
 * (nunca al revés), para no bloquearse entre sí.
 */
class StreamEngineImpl(
    private val cameraPermissionGranted: () -> Boolean,
    /**
     * Micrófono concedido (3.1.3, plan §6.1): espejo de
     * `cameraPermissionGranted`. Junto con `options.audioEnabled`
     * decide el escenario del FGS y el audio efectivo de la sesión.
     * Por defecto `false` (sin comprobación explícita no hay audio:
     * nunca se arranca el FGS con el tipo `microphone` sin haber
     * comprobado el permiso).
     */
    private val microphonePermissionGranted: () -> Boolean = { false },
    private val surfaceProvider: SurfaceProducerProvider,
    private val sessionFactory: PreviewSessionFactory,
    private val displayRotationDegrees: () -> Int,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val onEvent: ((Map<String, Any?>) -> Unit)? = null,
    /**
     * `Context` de la app: solo se usa para construir la sesión de
     * grabación por defecto (`RecordingFileStore` en
     * `<filesDir>/recordings/` y `DefaultSegmentVerifier`). Sin él
     * (pruebas sin Android) no hay sesión y `startRecording` devuelve
     * `camera_unavailable`.
     */
    private val context: Context? = null,
    /** Para la comprobación de capacidades del encoder (puede ser `null` en pruebas). */
    private val cameraManager: CameraManager? = null,
    /**
     * Fábrica de la sesión de grabación (costura de pruebas): recibe
     * el envoltorio de eventos (no suspendido; el motor lo emite en el
     * hilo principal, lección de la Fase B de 3.1.1) y devuelve la
     * sesión o `null`. Sin fábrica se construye la real con el
     * `context`; sin `context` ni fábrica no hay sesión.
     */
    private val recordingSessionFactory: (
        (String, Map<String, Any?>) -> Unit,
    ) -> RecordingSession? = { null },
    /** Control del FGS (`null` en pruebas: sin servicio). */
    private val foregroundService: ForegroundServiceControl? = null,
) : StreamEngine {

    private enum class State {
        IDLE,
        STARTING,
        ACTIVE,
        RECOVERING,
    }

    private val mutex = Mutex()

    /**
     * Serializa `startRecording` / `stopRecording` (y las paradas
     * implícitas desde `stopPreview`, la limpieza de superficie y los
     * errores de cámara). Siempre se toma ANTES que el mutex del motor.
     */
    private val recordingMutex = Mutex()
    private var state = State.IDLE
    private var handle: SurfaceProducerHandle? = null
    private var session: PreviewSession? = null
    private var info: PreviewInfo? = null
    private var pendingStart: Deferred<PreviewInfo>? = null

    /**
     * Configuración de vista previa activa y rotación para mostrarla
     * derecha: la grabación usa exactamente la misma configuración
     * (plan §3.1, "misma configuración que la vista previa activa").
     * Se fijan al abrir la sesión y se limpian al cerrarla.
     */
    private var previewConfig: PreviewConfig? = null
    private var rotationDegrees: Int = 90

    /**
     * Sesión de grabación (perezosa: se construye al primer uso, no al
     * crear el motor). Los eventos de la sesión salen por el envoltorio
     * no suspendido: el motor los emite en el hilo principal.
     */
    private val recordingSession: RecordingSession? by lazy {
        val emit: (String, Map<String, Any?>) -> Unit = { type, payload ->
            scope.launch { emitEvent(type, payload) }
        }
        recordingSessionFactory(emit)
            ?: context?.let { ctx ->
                RecordingSession(
                    scope = scope,
                    fileStore = RecordingFileStore(
                        File(ctx.filesDir, "recordings"),
                    ),
                    verifier = DefaultSegmentVerifier(ctx, mainDispatcher),
                    emitEvent = emit,
                )
            }
    }

    /**
     * Etapa del motor antes de que exista sesión (revisión 2 de la
     * Fase A): `createProducer` (crear el productor de superficie),
     * `createSession` (crear la sesión y su callback) o `session`
     * (abrir la sesión). Cuando el fallo es previo a la sesión no hay
     * diagnóstico de sesión que adjuntar a la excepción, así que el
     * manejador usa estos datos como reserva para no inventar la etapa
     * «startPreview». Se reinicia en cada `startPreview()`.
     */
    private var lastEngineStage: String = "startPreview"
    private var lastEngineExceptionClass: String? = null

    /** Diagnóstico de reserva del último intento fallido (ver arriba). */
    fun lastFailureDiagnostics(): Map<String, Any?> {
        return mapOf(
            "stage" to lastEngineStage,
            "cameraCount" to null,
            "selectedCameraId" to null,
            "selectedSize" to null,
            "selectedFps" to null,
            "exceptionClass" to lastEngineExceptionClass,
        )
    }

    /**
     * Ejecuta un paso previo a la sesión registrando su etapa: si
     * falla, la excepción se vuelve a lanzar tal cual (el manejador
     * sigue clasificándola), pero la etapa y la clase quedan guardadas
     * para `lastFailureDiagnostics()`.
     */
    private suspend fun <T> trackEngineStage(
        stage: String,
        block: suspend () -> T,
    ): T {
        lastEngineStage = stage
        return try {
            block()
        } catch (e: Exception) {
            lastEngineExceptionClass = e.javaClass.simpleName
            throw e
        }
    }

    private val surfaceCallback = object : SurfaceProducerCallback {
        override fun onSurfaceAvailable() {
            scope.launch { recoverAfterSurfaceAvailable() }
        }

        override fun onSurfaceCleanup() {
            scope.launch { handleSurfaceCleanup() }
        }
    }

    private val sessionListener = object : PreviewSessionListener {
        override fun onDisconnected() {
            scope.launch {
                teardownAfterError(StreamContract.CAMERA_DISCONNECTED)
            }
        }

        override fun onError(code: String) {
            scope.launch { teardownAfterError(code) }
        }
    }

    override suspend fun startPreview(): PreviewInfo {
        // Revisión 2: el diagnóstico de reserva empieza en
        // «startPreview» en cada intento; los pasos de `doStartPreview`
        // lo van precisando (`createProducer`, `createSession`,
        // `session`). Un intento anterior no contamina el siguiente.
        lastEngineStage = "startPreview"
        lastEngineExceptionClass = null
        if (!cameraPermissionGranted()) {
            throw CameraException(
                StreamContract.PERMISSION_DENIED,
                "Permiso de cámara no concedido.",
            )
        }
        val inFlight = mutex.withLock {
            when {
                state == State.ACTIVE && info != null -> return info!!
                pendingStart != null -> pendingStart
                else -> null
            }
        }
        if (inFlight != null) {
            return inFlight.await()
        }
        // Si se estaba recuperando de una superficie perdida, el
        // productor conservado se libera antes de empezar de nuevo:
        // nunca quedan dos productores vivos a la vez.
        val stale = mutex.withLock {
            val staleHandle = if (state == State.RECOVERING) handle else null
            val staleSession = if (state == State.RECOVERING) session else null
            if (state == State.RECOVERING) {
                handle = null
                session = null
            }
            Pair(staleHandle, staleSession)
        }
        try {
            stale.second?.close()
        } catch (_: Exception) {
        }
        stale.first?.let { releaseHandle(it) }
        val deferred = CompletableDeferred<PreviewInfo>()
        mutex.withLock {
            pendingStart = deferred
            state = State.STARTING
        }
        try {
            val result = doStartPreview()
            mutex.withLock {
                state = State.ACTIVE
                info = result
                pendingStart = null
            }
            deferred.complete(result)
            emitEvent(
                StreamContract.EVENT_PREVIEW_STARTED,
                mapOf(
                    "textureId" to result.textureId,
                    "width" to result.width,
                    "height" to result.height,
                    "fps" to result.fps,
                ),
            )
            return result
        } catch (e: Exception) {
            cleanupAfterFailedStart()
            mutex.withLock {
                state = State.IDLE
                info = null
                pendingStart = null
            }
            deferred.completeExceptionally(e)
            throw e
        }
    }

    private suspend fun doStartPreview(): PreviewInfo {
        Transmiti2Logger.d("startPreview: permiso concedido, creando productor")
        val created = trackEngineStage("createProducer") {
            withContext(mainDispatcher) {
                surfaceProvider.create()
            }
        }
        val createdSession = trackEngineStage("createSession") {
            sessionFactory(created).also {
                it.listener = sessionListener
            }
        }
        trackEngineStage("createSession") {
            created.setCallback(surfaceCallback)
        }
        val config: PreviewConfig
        try {
            config = trackEngineStage("session") {
                createdSession.open()
            }
        } catch (e: Exception) {
            // Fase B: la liberación del productor toca el motor de
            // Flutter (`unregisterTexture` exige el hilo principal, igual
            // que el envío de eventos), así que se hace en el
            // dispatcher principal y no en el hilo del `streamingScope`.
            try {
                withContext(mainDispatcher) {
                    created.setCallback(null)
                    created.release()
                }
            } catch (_: Exception) {
            }
            throw e
        }
        val rotation = (
            config.sensorOrientation - displayRotationDegrees() + 360
            ) % 360
        val result = PreviewInfo(
            textureId = created.textureId,
            width = config.size.width,
            height = config.size.height,
            sensorOrientation = config.sensorOrientation,
            rotationDegrees = rotation,
            fps = config.fpsRange.max,
            lensFacing = if (
                config.lensFacing ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
            ) {
                "front"
            } else {
                "back"
            },
        )
        mutex.withLock {
            handle = created
            session = createdSession
            previewConfig = config
            rotationDegrees = rotation
        }
        return result
    }

    override suspend fun stopPreview() {
        // Grabación activa + `stopPreview` (plan §6.4): primero se
        // detiene la grabación limpiamente y después la vista previa.
        // Se hace ANTES de tomar el mutex del motor (orden de bloqueo:
        // `recordingMutex` antes que el mutex del motor).
        stopRecordingIfActive()
        // Recursos capturados bajo el mutex; el cierre se hace fuera de
        // él para no bloquear otras transiciones de estado.
        val (wasActive, toClose, toRelease) = mutex.withLock {
            val wasActive = state == State.ACTIVE ||
                state == State.STARTING ||
                state == State.RECOVERING
            pendingStart?.cancel()
            pendingStart = null
            val toClose = session
            val toRelease = handle
            session = null
            handle = null
            info = null
            previewConfig = null
            state = State.IDLE
            Triple(wasActive, toClose, toRelease)
        }
        try {
            toClose?.close()
        } catch (_: Exception) {
        }
        toRelease?.let { releaseHandle(it) }
        if (wasActive) {
            emitEvent(StreamContract.EVENT_PREVIEW_STOPPED, emptyMap())
        }
    }

    override suspend fun stopAll() {
        stopPreview()
    }

    override fun startStream(endpoint: RtmpEndpoint) {
        throw UnsupportedOperationException(
            "La transmisión llega en la Fase 3.2.",
        )
    }

    /**
     * Inicia la grabación (Fase B, 3.1.2). Exige vista previa `ACTIVE`
     * (plan §6.2) y el permiso de cámara; arranca el FGS ANTES de la
     * sesión (plan §9.2) y devuelve el `recordingId`. El escenario del
     * FGS es por grabación (3.1.3, plan §6 D8): `WITH_AUDIO` solo si se
     * pidió audio y el micrófono está concedido; en caso contrario
     * `WITHOUT_AUDIO`. La sesión recibe el audio efectivo: un fallo de
     * audio nunca hace fallar el inicio (la sesión degrada a
     * video-only, D9). Si la sesión falla, el servicio se detiene y se
     * propaga el error real (`RecordingException` con su código).
     * Una solicitud duplicada la rechaza la propia sesión con
     * `recording_state_error` (solo desde `IDLE`).
     */
    override suspend fun startRecording(options: RecordingOptions): String {
        return recordingMutex.withLock {
            val activeState: State
            val currentSession: PreviewSession?
            val currentConfig: PreviewConfig?
            val currentRotation: Int
            mutex.withLock {
                activeState = state
                currentSession = session
                currentConfig = previewConfig
                currentRotation = rotationDegrees
            }
            if (activeState != State.ACTIVE ||
                currentSession == null ||
                currentConfig == null
            ) {
                throw CameraException(
                    StreamContract.CAMERA_UNAVAILABLE,
                    "Inicia la vista previa antes de grabar.",
                )
            }
            if (!cameraPermissionGranted()) {
                throw CameraException(
                    StreamContract.PERMISSION_DENIED,
                    "Permiso de cámara no concedido.",
                )
            }
            val recording = recordingSession ?: throw CameraException(
                StreamContract.CAMERA_UNAVAILABLE,
                "Grabación no disponible en este dispositivo.",
            )
            // Solicitud duplicada (plan §6.2): si ya hay una grabación
            // en curso se rechaza ANTES de tocar el FGS. Sin esta
            // guarda, el `catch` de abajo detendría el servicio de la
            // grabación activa al fallar el segundo inicio.
            val currentRecordingState = recording.statusSnapshot().state
            if (currentRecordingState != "idle" &&
                currentRecordingState != "error"
            ) {
                throw RecordingException.stateError(
                    "Ya hay una grabación en curso ($currentRecordingState).",
                    mapOf(
                        "stage" to "startRecording",
                        "state" to currentRecordingState,
                        "recordingId" to recording.statusSnapshot().recordingId,
                    ),
                )
            }
            // Escenario del FGS por grabación (3.1.3, plan §6 D8):
            // WITH_AUDIO solo si se pidió audio Y el micrófono está
            // concedido; en caso contrario WITHOUT_AUDIO. La sesión
            // recibe el audio efectivo: un fallo de audio nunca hace
            // fallar el inicio (la sesión degrada a video-only, D9).
            val effectiveAudio =
                options.audioEnabled && microphonePermissionGranted()
            val scenario = if (effectiveAudio) {
                RecordingScenario.WITH_AUDIO
            } else {
                RecordingScenario.WITHOUT_AUDIO
            }
            val fgs = foregroundService
            if (fgs != null && !fgs.startRecordingService(scenario)) {
                val missing = if (effectiveAudio) {
                    "cámara o de micrófono"
                } else {
                    "cámara"
                }
                throw CameraException(
                    StreamContract.PERMISSION_DENIED,
                    "No se pudo iniciar el servicio en primer plano " +
                        "(permiso de $missing).",
                )
            }
            try {
                val started = recording.start(
                    previewSession = currentSession,
                    previewConfig = currentConfig,
                    rotationDegrees = currentRotation,
                    matchId = options.matchId?.toString(),
                    cameraManager = cameraManager,
                    audioEnabled = effectiveAudio,
                )
                if (fgs != null) {
                    emitEvent(
                        StreamContract.EVENT_SERVICE_STARTED,
                        mapOf("recordingId" to started.recordingId),
                    )
                }
                started.recordingId
            } catch (e: Exception) {
                // La sesión falló con el FGS ya arrancado: se detiene el
                // servicio para no dejarlo huérfano (el evento
                // `recording_error` ya lo emitió la sesión).
                if (fgs != null) {
                    try {
                        fgs.stopRecordingService()
                    } catch (_: Exception) {
                    }
                }
                throw e
            }
        }
    }

    /**
     * Detiene la grabación (Fase B, 3.1.2). Idempotente desde `IDLE`
     * (la sesión devuelve el resultado vacío, `status = "idle"`).
     * Detiene el FGS al terminar (plan §9.2, paso 5) y emite
     * `service_stopped` cuando hubo grabación. `filePath` es el primer
     * segmento (la ruta final fusionada llega en 3.1.4; los segmentos
     * se identifican por nombre, plan §8.1).
     */
    override suspend fun stopRecording(): RecordingInfo {
        val recording = recordingSession
        val result = if (recording == null) {
            RecordingResult.empty()
        } else {
            try {
                recordingMutex.withLock { recording.stop() }
            } finally {
                foregroundService?.let { fgs ->
                    try {
                        fgs.stopRecordingService()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (recording != null && result.status != "idle") {
            emitEvent(
                StreamContract.EVENT_SERVICE_STOPPED,
                mapOf("recordingId" to result.recordingId),
            )
        }
        return RecordingInfo(
            recordingId = result.recordingId,
            filePath = result.segments.firstOrNull()?.filePath ?: "",
            durationMs = result.totalDurationMs,
            sizeBytes = result.totalSizeBytes,
            status = result.status,
            segments = result.segments,
            // Grupo V (V1): el resultado del método lleva los
            // mismos campos de instrumentación de audio que el
            // evento `recording_stopped`; sin ellos Dart
            // sobrescribía los valores reales con los defectos.
            droppedInputBuffers = result.droppedInputBuffers,
            inputBufferCapacityBytes = result.inputBufferCapacityBytes,
            micStateAtStart = result.micStateAtStart,
            micStateAtStop = result.micStateAtStop,
            audioReadCount = result.audioReadCount,
            audioLastReadCode = result.audioLastReadCode,
            audioLastReadOkAgeMs = result.audioLastReadOkAgeMs,
            audioReaderAlive = result.audioReaderAlive,
            audioEos = result.audioEos,
            audioReaderNoData2s = result.audioReaderNoData2s,
            eosTimedOut = result.eosTimedOut,
            quietestSecondPeak = result.quietestSecondPeak,
            quietestSecondNonZero = result.quietestSecondNonZero,
            silencePeak = result.silencePeak,
        )
    }

    /**
     * Instantánea del estado de grabación para `getRecordingStatus`
     * (re-sincronizar la UI al volver a la pantalla). Sin sesión, el
     * estado es `idle`.
     */
    fun recordingStatus(): RecordingStatusSnapshot {
        val recording = recordingSession ?: return RecordingStatusSnapshot(
            state = "idle",
            recordingId = null,
            elapsedMs = 0,
            segmentIndex = 0,
            segmentDurationMs = 0,
            width = null,
            height = null,
            fps = null,
            errorCode = null,
        )
        return recording.statusSnapshot()
    }

    /** Mapa de `recordingStatus()` para el canal de métodos. */
    fun recordingStatusMap(): Map<String, Any?> {
        val snapshot = recordingStatus()
        return mapOf(
            "state" to snapshot.state,
            "recordingId" to snapshot.recordingId,
            "elapsedMs" to snapshot.elapsedMs,
            "segmentIndex" to snapshot.segmentIndex,
            "segmentDurationMs" to snapshot.segmentDurationMs,
            "width" to snapshot.width,
            "height" to snapshot.height,
            "fps" to snapshot.fps,
            "errorCode" to snapshot.errorCode,
            // Audio (3.1.3, plan §8.1): la UI re-sincroniza el estado
            // de audio al volver a la pantalla sin haber visto los
            // eventos (pedido con efecto, activo en el segmento
            // actual, motivo cuando falta o se perdió).
            "audioEnabled" to snapshot.audioEnabled,
            "audioActive" to snapshot.audioActive,
            "audioReason" to snapshot.audioReason,
            // Segmentos verificados hasta ahora (paso 6): la UI los
            // recupera al re-sincronizar sin haber visto los eventos.
            "segments" to snapshot.segments.map { it.toMap() },
        )
    }

    /**
     * Clasifica los `.tmp.mp4` que quedaron de una muerte del proceso
     * (plan §7.3). Nunca borra ni modifica nada; devuelve la lista
     * clasificada para que Dart actualice las filas de `recordings`.
     */
    suspend fun recoverInterruptedRecordings(): List<InterruptedSegmentInfo> {
        return recordingSession?.recoverInterrupted() ?: emptyList()
    }

    /** Mapa de un segmento recuperado para el canal de métodos. */
    fun recoveredSegmentMap(segment: InterruptedSegmentInfo): Map<String, Any?> {
        return mapOf(
            "recordingId" to segment.recordingId,
            "segmentIndex" to segment.index,
            "fileName" to segment.fileName,
            "filePath" to segment.filePath,
            "status" to segment.status.name.lowercase(),
            "durationMs" to segment.durationMs,
            "sizeBytes" to segment.sizeBytes,
            "reason" to segment.reason,
        )
    }

    /**
     * `true` cuando el FGS está disponible para grabar (hay sesión de
     * grabación y control del servicio). Es lo que devuelve
     * `getStatus().foregroundServiceAvailable` desde 3.1.2.
     */
    fun foregroundServiceAvailable(): Boolean {
        return recordingSession != null && foregroundService != null
    }

    /**
     * Detiene la grabación si hay una en curso (idempotente desde
     * `IDLE` o `ERROR`) y detiene el FGS. Nunca lanza: una parada de
     * vista previa o un error de cámara no deben fallar porque la
     * parada de la grabación falle. Con `errorCode`, la parada se
     * registra como error (desconexión de cámara, plan §6.4).
     */
    private suspend fun stopRecordingIfActive(errorCode: String? = null) {
        val recording = recordingSession ?: return
        try {
            recordingMutex.withLock { recording.stop(errorCode) }
        } catch (_: Exception) {
        } finally {
            foregroundService?.let { fgs ->
                try {
                    fgs.stopRecordingService()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * La superficie se limpió (por ejemplo, al ir a segundo plano con
     * memoria baja): con grabación activa se detiene primero la
     * grabación limpiamente (plan §6.4: nunca se cierra la sesión a
     * mitad de un segmento) y después se aplica la recuperación normal
     * de vista previa. Sin grabación, el comportamiento no cambia.
     */
    private suspend fun handleSurfaceCleanup() {
        stopRecordingIfActive()
        val (wasActive, toClose) = mutex.withLock {
            if (state != State.ACTIVE) {
                Pair(false, null)
            } else {
                val toClose = session
                session = null
                previewConfig = null
                state = State.RECOVERING
                Pair(true, toClose)
            }
        }
        try {
            toClose?.close()
        } catch (_: Exception) {
        }
        if (wasActive) {
            emitEvent(StreamContract.EVENT_PREVIEW_SURFACE_LOST, emptyMap())
        }
    }

    /**
     * La superficie volvió a estar disponible: si la vista previa estaba
     * en recuperación, se reabre la sesión con la superficie nueva
     * (siempre se pide de nuevo al productor, nunca se cachea).
     */
    private suspend fun recoverAfterSurfaceAvailable() {
        val shouldRecover = mutex.withLock {
            state == State.RECOVERING && handle != null
        }
        if (!shouldRecover) {
            return
        }
        val currentHandle = mutex.withLock { handle } ?: return
        val newSession = try {
            sessionFactory(currentHandle)
        } catch (e: Exception) {
            Transmiti2Logger.e(
                "etapa=recover ex=${e.javaClass.simpleName} msg=${e.message}",
            )
            mutex.withLock {
                state = State.IDLE
                info = null
                previewConfig = null
                handle = null
            }
            releaseHandle(currentHandle)
            emitError(StreamContract.CAMERA_UNAVAILABLE, e.message)
            return
        }
        newSession.listener = sessionListener
        try {
            val config = newSession.open()
            val rotation = (
                config.sensorOrientation - displayRotationDegrees() + 360
                ) % 360
            val result = PreviewInfo(
                textureId = currentHandle.textureId,
                width = config.size.width,
                height = config.size.height,
                sensorOrientation = config.sensorOrientation,
                rotationDegrees = rotation,
                fps = config.fpsRange.max,
                lensFacing = "back",
            )
            mutex.withLock {
                session = newSession
                info = result
                previewConfig = config
                rotationDegrees = rotation
                state = State.ACTIVE
            }
            emitEvent(
                StreamContract.EVENT_PREVIEW_STARTED,
                mapOf(
                    "textureId" to result.textureId,
                    "width" to result.width,
                    "height" to result.height,
                    "fps" to result.fps,
                ),
            )
        } catch (e: Exception) {
            Transmiti2Logger.e(
                "etapa=recover ex=${e.javaClass.simpleName} msg=${e.message}",
            )
            mutex.withLock {
                state = State.IDLE
                info = null
                session = null
                previewConfig = null
                handle = null
            }
            releaseHandle(currentHandle)
            val cameraError = e as? CameraException
            emitError(
                cameraError?.code ?: StreamContract.CAMERA_UNAVAILABLE,
                e.message,
                cameraError?.details ?: emptyMap(),
            )
        }
    }

    /**
     * Desconexión o error de cámara: con grabación activa se detiene
     * primero la grabación con el código de error (plan §6.4: se
     * finaliza el segmento en curso si es posible y se conservan los
     * cerrados) y después se libera todo y se avisa.
     */
    private suspend fun teardownAfterError(code: String) {
        stopRecordingIfActive(errorCode = code)
        val wasActive = mutex.withLock {
            val wasActive = state == State.ACTIVE ||
                state == State.STARTING ||
                state == State.RECOVERING
            val toClose = session
            val toRelease = handle
            session = null
            handle = null
            info = null
            previewConfig = null
            pendingStart?.cancel()
            pendingStart = null
            state = State.IDLE
            Pair(wasActive, Pair(toClose, toRelease))
        }
        val (active, resources) = wasActive
        val (toClose, toRelease) = resources
        try {
            toClose?.close()
        } catch (_: Exception) {
        }
        toRelease?.let { releaseHandle(it) }
        if (active) {
            emitError(code)
        }
    }

    /** Limpieza tras un inicio fallido: cierra y libera lo creado. */
    private suspend fun cleanupAfterFailedStart() {
        val resources = mutex.withLock {
            val toClose = session
            val toRelease = handle
            session = null
            handle = null
            previewConfig = null
            Pair(toClose, toRelease)
        }
        try {
            resources.first?.close()
        } catch (_: Exception) {
        }
        resources.second?.let { releaseHandle(it) }
    }

    /**
     * Libera el productor en el hilo principal (Fase B):
     * `release()` llega a `FlutterJNI.unregisterTexture`, marcado
     * `@UiThread` en el motor de Flutter 3.50, y en un hilo secundario
     * lanza `RuntimeException` («Methods marked with @UiThread must be
     * executed on the main thread»). Hoy esa excepción se traga aquí
     * mismo, así que no se ve en el diagnóstico, pero la textura queda
     * registrada en el motor (fuga): `releaseInternal()` ya marcó el
     * productor como liberado y un segundo `release()` no reintenta
     * `unregisterTexture`. Solo cambia el hilo en el que se ejecuta;
     * la lógica y la idempotencia no cambian.
     */
    private suspend fun releaseHandle(toRelease: SurfaceProducerHandle) {
        try {
            withContext(mainDispatcher) {
                toRelease.setCallback(null)
                toRelease.release()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Emite un evento al canal de Flutter en el hilo principal
     * (Fase B): `EventChannel.EventSink.success()` está marcado
     * `@UiThread` y termina en `FlutterJNI.dispatchPlatformMessage`,
     * que lanza `RuntimeException` fuera del hilo principal. El
     * `startPreview()` corría en el dispatcher por defecto del
     * `streamingScope`, así que anunciar el éxito de la apertura
     * (`EVENT_PREVIEW_STARTED`) fallaba DESPUÉS de abrir la cámara:
     * el `catch` cerraba la sesión que acababa de abrirse y el teléfono
     * mostraba «Cámara no disponible» con la etapa «session». Solo
     * cambia el hilo de entrega; el contenido y el orden no cambian.
     */
    private suspend fun emitEvent(type: String, payload: Map<String, Any?>) {
        val handler = onEvent ?: return
        val event = mapOf("type" to type) + payload
        withContext(mainDispatcher) {
            handler(event)
        }
    }

    /**
     * Error asíncrono de vista previa. Fase A: el evento lleva también
     * el mensaje técnico (`message`) y los `details` de diagnóstico
     * cuando el motor los tiene; sin ellos, solo el código (es el caso
     * de `teardownAfterError`, que solo recibe el código del
     * `PreviewSessionListener`). El código emitido no cambia.
     */
    private suspend fun emitError(
        code: String,
        message: String? = null,
        details: Map<String, Any?> = emptyMap(),
    ) {
        val payload = mutableMapOf<String, Any?>("code" to code)
        if (message != null) {
            payload["message"] = message
        }
        payload.putAll(details)
        emitEvent(StreamContract.EVENT_PREVIEW_ERROR, payload)
    }
}
