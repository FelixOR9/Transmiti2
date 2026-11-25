package com.transmiti2.transmiti2.streaming

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.transmiti2.transmiti2.BuildConfig
import com.transmiti2.transmiti2.streaming.recording.DefaultSegmentVerifier
import com.transmiti2.transmiti2.streaming.recording.ErrorTrigger
import com.transmiti2.transmiti2.streaming.recording.MergeProgress
import com.transmiti2.transmiti2.streaming.recording.MergeResult
import com.transmiti2.transmiti2.streaming.recording.MergeStage
import com.transmiti2.transmiti2.streaming.recording.OwnershipToken
import com.transmiti2.transmiti2.streaming.recording.RecordingException
import com.transmiti2.transmiti2.streaming.recording.RecordingFileStore
import com.transmiti2.transmiti2.streaming.recording.RecordingResult
import com.transmiti2.transmiti2.streaming.recording.RecordingSession
import com.transmiti2.transmiti2.streaming.recording.RecordingStatusSnapshot
import com.transmiti2.transmiti2.streaming.recording.ReconciledSegmentOutcome
import com.transmiti2.transmiti2.streaming.recording.ReconciliationSummary
import com.transmiti2.transmiti2.streaming.recording.RegistryEntrySnapshot
import com.transmiti2.transmiti2.streaming.recording.SegmentMerger
import com.transmiti2.transmiti2.streaming.recording.SegmentStatus
import com.transmiti2.transmiti2.streaming.recording.mergeFailureIsRetryable
import com.transmiti2.transmiti2.streaming.test.TestEventResult
import com.transmiti2.transmiti2.streaming.test.TestEventType
import com.transmiti2.transmiti2.streaming.test.TestHooks
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * Estado de la puerta única de reconciliación por proceso (subetapa 0,
 * grupo 0-d2; plan §2.6). `NOT_STARTED` → `RUNNING` → `DONE` | `FAILED`.
 */
enum class ReconciliationGateState {
    NOT_STARTED,
    RUNNING,
    DONE,
    FAILED,
}

/** Motivo de una puerta en `FAILED` (código + mensaje; ambos viajan a Dart). */
data class ReconciliationFailure(
    val code: String,
    val message: String,
)

/**
 * La pasada de reconciliación de la puerta falló (o no pudo arrancar).
 * El manejador la traduce a payload `FAILED` y Dart sigue reconciliando
 * las filas desde `interrupted()` (U11/U34: nunca una lista falsa ni
 * vacía como final).
 */
class ReconciliationFailedException(
    val code: String,
    message: String,
) : Exception(message)

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
    /**
     * Ganchos de la infraestructura de pruebas físicas (FASE 2, grupo
     * 2-c; diseño base §4.A). Por defecto [TestHooks.NO_OP]: con él,
     * las transiciones de la puerta no se registran y la sesión se
     * construye sin ganchos. El cableado (MainActivity → manejador)
     * instala los ganchos reales solo en builds de pruebas. Último
     * parámetro con defecto: las construcciones existentes no cambian.
     */
    private val testHooks: TestHooks = TestHooks.NO_OP,
    /**
     * Ejecutor de la fusión (3.1.4, J1-b; costura de pruebas): recibe
     * el id y el callback de progreso y devuelve el [MergeResult].
     * Por defecto `null`: el motor construye el [SegmentMerger] real
     * con el `context` (misma carpeta y verificador que la sesión).
     * Sin `context` ni ejecutor no hay fusión (las unitarias del
     * motor sin Android no fusionan: el stop se comporta como antes).
     * Las pruebas del motor pasan aquí un ejecutor enlatado (con
     * puerta controlable) sin extractor/muxer reales.
     */
    private val mergeRunner: (
        suspend (
            recordingId: String,
            onProgress: ((MergeProgress) -> Unit)?,
        ) -> MergeResult
    )? = null,
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

    /**
     * Guarda de parada del FGS por grabación (J3-b, plan §1.3 paso
     * 3): `true` cuando el FGS de la grabación en curso ya se paró
     * (por cualquier vía). La parada del FGS por error se ejecuta
     * UNA SOLA VEZ por grabación aunque lleguen dos avisos de
     * error-stop o la parada normal y la de error compitan: la
     * primera vía que llega consume la guarda y las demás son no-op
     * (sin segundo `stopRecordingService()` ni segundo
     * `service_stopped`). Se resetea en `startRecording`, antes de
     * arrancar el FGS de la grabación nueva.
     */
    private val fgsStopRequested = AtomicBoolean(false)

    /**
     * Id de la grabación activa (J3-b): se fija al arrancar una
     * grabación y se limpia al pararla. Un aviso de error-stop
     * tardío de una grabación anterior (id distinto al activo) se
     * ignora: sin esta comprobación, el aviso podría parar el FGS
     * de la grabación nueva tras el reseteo de la guarda en su
     * `startRecording`. `null` sin grabación activa (o si la
     * grabación terminó y su parada ya limpió el id).
     */
    private val activeRecordingId = AtomicReference<String?>(null)

    /**
     * Fusiones en curso (3.1.4, J1-b; J0.2): número de fusiones
     * asíncronas vivas. Mientras es > 0 el FGS NO se detiene: la
     * última fusión que termina lo detiene vía el helper único
     * (con el `reason`/`origin` de su parada). Sin fusiones en
     * curso, la parada detiene el FGS como antes. El contador (y
     * no un booleano) permite dos fusiones de ids distintos a la
     * vez; la de un mismo id nunca se duplica (`mergeTriggeredIds`
     * + reserva del grupo).
     */
    private val mergeInFlightCount = AtomicInteger(0)

    /**
     * Ids para los que ya se disparó una fusión en este proceso
     * (3.1.4, J1-b): `stop()` devuelve el MISMO resultado en
     * llamadas repetidas (el ganador lo cachea), así que sin esta
     * marca un segundo `stop()` tras el primero —o un
     * `stopRecordingIfActive()` tras una parada normal— volvería a
     * fusionar el mismo id. La reanudación (J1-c) limpia la marca
     * con [clearMergeTriggered] antes de reintentar un id.
     */
    private val mergeTriggeredIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Fusionador real (3.1.4, J1-b): perezoso, con el mismo
     * `context` que la sesión (misma carpeta `<filesDir>/recordings/`
     * y mismo verificador). `null` sin `context` (pruebas sin
     * Android): sin fusionador no hay fusión. Solo se usa cuando no
     * hay [mergeRunner] inyectado.
     */
    private val segmentMergerLazy = lazy {
        context?.let { ctx ->
            SegmentMerger(
                fileStore = RecordingFileStore(
                    File(ctx.filesDir, "recordings"),
                ),
                verifier = DefaultSegmentVerifier(ctx, mainDispatcher),
            )
        }
    }

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
     *
     * El `Lazy` se guarda aparte (2-c) para que los accesores de
     * pruebas puedan saber si la sesión ya existe sin construirla:
     * una consulta de diagnóstico no debe materializar la sesión.
     */
    private val recordingSessionLazy = lazy {
        val emit: (String, Map<String, Any?>) -> Unit = { type, payload ->
            scope.launch { emitEvent(type, payload) }
        }
        val created = recordingSessionFactory(emit)
            ?: context?.let { ctx ->
                RecordingSession(
                    scope = scope,
                    fileStore = RecordingFileStore(
                        File(ctx.filesDir, "recordings"),
                    ),
                    verifier = DefaultSegmentVerifier(ctx, mainDispatcher),
                    emitEvent = emit,
                    testHooks = testHooks,
                    // J3-c: la puerta del hook de inyección de error
                    // es el flag de compilación de pruebas (falso en
                    // producción): sin él, `injectTestError` es inerte.
                    testErrorInjectionEnabled = {
                        BuildConfig.TRANSMITI2_TEST_MODE
                    },
                )
            }
        // J3-b: la sesión avisa al motor cuando completa una parada
        // con error; el motor —no la sesión— para el FGS (la sesión
        // no conoce el `Context`). Se instala aquí para cubrir las
        // dos vías de construcción (fábrica de pruebas y real).
        created?.onSessionErrorStop = { code, trigger, recordingId ->
            onSessionErrorStop(code, trigger, recordingId)
        }
        created
    }
    private val recordingSession: RecordingSession?
        get() = recordingSessionLazy.value

    /**
     * Puerta única de reconciliación por proceso (subetapa 0, grupo
     * 0-d2; plan §2.6, revisión 1 §4.2 con el papel reducido de la
     * revisión 2 §2.4).
     *
     * Decisión de ubicación (plan §6 A1/A3): la puerta vive en el
     * MOTOR, no en la sesión. La sesión es perezosa y su ciclo de
     * vida es del motor; una puerta en la sesión se evaporaría si la
     * sesión se recreara alguna vez (R-N4: registro nuevo vacío).
     * El motor es el único dueño del `scope` y de la fábrica de la
     * sesión, y el manejador ya lo tiene en la mano para el ancla A.
     * La sesión sigue haciendo el trabajo (`reconcileRecordings()`);
     * el motor solo posee el estado «una vez por proceso».
     *
     * Papel honesto: la puerta garantiza una ejecución por proceso y
     * que quien la espera vea `DONE` (o `FAILED` con motivo). NO es
     * la guarda de la carrera: la seguridad frente a una grabación
     * activa la da el registro con tokens (0-c), no la puerta.
     * Iniciar una grabación NO espera a la puerta.
     */
    private val _reconciliationState =
        MutableStateFlow(ReconciliationGateState.NOT_STARTED)

    /** Estado observable de la puerta (para la biblioteca futura). */
    val reconciliationState: StateFlow<ReconciliationGateState>
        get() = _reconciliationState

    /**
     * Serializa las transiciones de la puerta. Nunca se mantiene
     * durante la reconciliación (la pasada corre sin el mutex tomado).
     */
    private val reconciliationGateMutex = Mutex()

    /**
     * Pasada en curso o última pasada. Se sustituye solo al reintentar
     * desde `FAILED`: quien observaba la pasada fallida sigue viendo
     * su fallo; quien llega después ve la nueva pasada.
     */
    private var reconciliationDeferred =
        CompletableDeferred<ReconciliationSummary>()

    /** Resumen de la última pasada que llegó a `DONE` (`null` antes). */
    private var lastReconciliationSummary: ReconciliationSummary? = null

    /** Motivo de la última pasada que llegó a `FAILED` (`null` si no). */
    private var reconciliationFailure: ReconciliationFailure? = null

    /**
     * La pasada que espera quien llama a `reconcileRecordings()`
     * (plan §2.6: `reconciliationDone`). Antes del primer disparo no
     * completa nunca: nadie debe esperarla sin disparar antes (el
     * disparo es `triggerReconciliation()` o la propia
     * `reconcileRecordings()`).
     */
    val reconciliationDone: Deferred<ReconciliationSummary>
        get() = reconciliationDeferred

    /**
     * Transición de la puerta bajo el mutex. Devuelve `true` solo si
     * quien llama debe ejecutar la pasada: `NOT_STARTED` → `RUNNING`
     * (primer disparo) y `FAILED` → `RUNNING` (reintento desde cero;
     * el progreso parcial ya está en disco —sidecars— y la pasada
     * converge). `RUNNING`/`DONE` → `false`: la pasada en curso o ya
     * hecha es la que vale; no hay segunda reconciliación.
     */
    private fun transitionGateLocked(): Boolean {
        return when (_reconciliationState.value) {
            ReconciliationGateState.NOT_STARTED -> {
                _reconciliationState.value = ReconciliationGateState.RUNNING
                // 2-c: transición de la puerta al log de pruebas
                // (no-op con los ganchos desactivados). `record()` es
                // síncrono y no suspende: seguro bajo el mutex.
                testHooks.record(
                    TestEventType.RECONCILIATION_STARTED,
                    operation = "reconcile",
                    detail = mapOf(
                        "from" to "NOT_STARTED",
                        "to" to "RUNNING",
                        "source" to "gate",
                    ),
                )
                true
            }
            ReconciliationGateState.FAILED -> {
                reconciliationDeferred = CompletableDeferred()
                reconciliationFailure = null
                lastReconciliationSummary = null
                _reconciliationState.value = ReconciliationGateState.RUNNING
                // 2-c: reintento desde FAILED (misma transición, otro
                // origen).
                testHooks.record(
                    TestEventType.RECONCILIATION_STARTED,
                    operation = "reconcile",
                    detail = mapOf(
                        "from" to "FAILED",
                        "to" to "RUNNING",
                        "source" to "gate",
                    ),
                )
                true
            }
            ReconciliationGateState.RUNNING,
            ReconciliationGateState.DONE,
            -> false
        }
    }

    /**
     * Dispara la reconciliación una sola vez por proceso (ancla A: el
     * manejador la llama al materializar el motor; ancla B: la
     * biblioteca futura la llama al abrirse). No espera: devuelve en
     * cuanto la pasada está lanzada (o ya corría, o ya terminó).
     * Nunca lanza: si no se puede ni lanzar la corrutina, la puerta
     * queda en `FAILED` con motivo (guard_new_execution_path: el
     * camino nuevo degrada al último comportamiento que funcionaba —
     * motor sin reconciliar — en vez de romper la vista previa).
     */
    fun triggerReconciliation() {
        try {
            scope.launch {
                val shouldRun = reconciliationGateMutex.withLock {
                    transitionGateLocked()
                }
                if (shouldRun) {
                    runReconciliation()
                }
            }
        } catch (e: Exception) {
            Transmiti2Logger.e(
                "reconciliation: no se pudo lanzar: " +
                    e.javaClass.simpleName,
            )
            failGate(
                reconciliationDeferred,
                ReconciliationFailure(
                    code = StreamContract.RECONCILIATION_FAILED,
                    message = "No se pudo lanzar la reconciliación.",
                ),
            )
        }
    }

    /**
     * Ejecuta la pasada y publica su resultado en la puerta. Nunca
     * lanza fuera: cualquier excepción —incluida la cancelación del
     * `scope`— deja la puerta en `FAILED` con motivo, para que nadie
     * espere eternamente (rev. 1 R19–R23: la puerta que nunca llega
     * a `DONE` es un fallo con reintento, no un cuelgue). La
     * cancelación no se re-lanza: el `scope` ya la gestiona y la
     * puerta debe quedar en estado terminal observable.
     */
    private suspend fun runReconciliation() {
        // La pasada que completa este `run` es la vigente en el
        // momento de arrancar: ninguna otra transición puede
        // sustituirla mientras el estado es `RUNNING`.
        val deferred = reconciliationDeferred
        try {
            val session = recordingSession
            if (session == null) {
                failGate(
                    deferred,
                    ReconciliationFailure(
                        code = StreamContract.RECONCILIATION_UNAVAILABLE,
                        message = "No hay sesión de grabación con la " +
                            "que reconciliar (sin cámara ni textura).",
                    ),
                )
                return
            }
            val summary = session.reconcileRecordings()
            lastReconciliationSummary = summary
            _reconciliationState.value = ReconciliationGateState.DONE
            // 2-c: la puerta llegó a DONE (la sesión ya registró su
            // propio RECONCILIATION_FINISHED con el resumen; este es
            // la transición de la puerta, con el mismo runId).
            testHooks.record(
                TestEventType.RECONCILIATION_FINISHED,
                operation = "reconcile",
                detail = mapOf(
                    "runId" to summary.runId,
                    "durationMs" to (summary.finishedAtMs - summary.startedAtMs),
                    "source" to "gate",
                    "gateState" to "DONE",
                ),
            )
            deferred.complete(summary)
        } catch (e: CancellationException) {
            failGate(
                deferred,
                ReconciliationFailure(
                    code = StreamContract.RECONCILIATION_FAILED,
                    message = "Reconciliación cancelada: " +
                        (e.message ?: "cancelada"),
                ),
            )
        } catch (e: Exception) {
            Transmiti2Logger.e(
                "reconciliation: la pasada falló: " +
                    e.javaClass.simpleName,
            )
            failGate(
                deferred,
                ReconciliationFailure(
                    code = StreamContract.RECONCILIATION_FAILED,
                    message = e.message ?: "La reconciliación falló.",
                ),
            )
        }
    }

    private fun failGate(
        deferred: CompletableDeferred<ReconciliationSummary>,
        failure: ReconciliationFailure,
    ) {
        reconciliationFailure = failure
        _reconciliationState.value = ReconciliationGateState.FAILED
        // 2-c: la puerta llegó a FAILED con su código y motivo (la
        // cancelación también pasa por aquí: Revisión 3 §2.2).
        testHooks.record(
            TestEventType.RECONCILIATION_FINISHED,
            operation = "reconcile",
            result = TestEventResult.FAILED,
            errorCode = failure.code,
            detail = mapOf(
                "source" to "gate",
                "gateState" to "FAILED",
                "errorMessage" to failure.message,
            ),
        )
        deferred.completeExceptionally(
            ReconciliationFailedException(failure.code, failure.message),
        )
    }

    /**
     * Única entrada a la reconciliación (0-d2; sustituye a
     * `recoverInterruptedRecordings()`). Dispara la pasada si no ha
     * empezado (o reintenta si falló) y espera a la MISMA puerta que
     * el ancla A: nunca hay dos pasadas. En `FAILED` lanza
     * `ReconciliationFailedException` con el motivo (el manejador lo
     * traduce a payload `FAILED`; Dart sigue reconciliando las filas
     * desde `interrupted()`).
     */
    suspend fun reconcileRecordings(): ReconciliationSummary {
        val shouldRun = reconciliationGateMutex.withLock {
            transitionGateLocked()
        }
        if (shouldRun) {
            runReconciliation()
        }
        return reconciliationDeferred.await()
    }

    /** Mapa de la puerta para `getReconciliationState` (0-d2). */
    fun reconciliationStateMap(): Map<String, Any?> {
        val state = _reconciliationState.value
        val failure = reconciliationFailure
        return mapOf(
            "state" to state.name,
            "summary" to lastReconciliationSummary?.let {
                reconciliationSummaryMap(it)
            },
            "errorCode" to failure?.code,
            "errorMessage" to failure?.message,
        )
    }

    // --- Accesores solo de pruebas (FASE 2, grupo 2-c) ------------

    /**
     * Snapshot del registro de coordinación para el canal de pruebas.
     * Sin sesión no hay registro: responde no disponible (nunca
     * construye la sesión: una consulta de diagnóstico no debe
     * materializarla). Los valores de los tokens nunca salen del
     * proceso: solo ids, dueños, edades y contadores.
     *
     * `snapshot()` es `suspend` y el proveedor del canal es
     * síncrono: el `runBlocking` es seguro aquí porque el
     * `coordinationMutex` es hoja (sin `suspend` interno, sin E/S,
     * sin esperas): quien lo tiene lo suelta sin ceder, así que el
     * bloqueo es breve y no puede deadlockearse con el hilo que
     * llama.
     */
    internal fun testRegistrySnapshot(): Map<String, Any?> {
        if (!recordingSessionLazy.isInitialized()) {
            return mapOf("available" to false, "reason" to "no_recording_session")
        }
        val session = recordingSession
            ?: return mapOf("available" to false, "reason" to "no_recording_session")
        val snapshot = runBlocking { session.testRegistrySnapshot() }
        return mapOf(
            "available" to true,
            "activeClaims" to snapshot.activeClaims.values.map { entryMap(it) },
            "reservedGroups" to snapshot.reservedGroups.values.map { entryMap(it) },
            "claimRejectedActive" to snapshot.claimRejectedActive,
            "claimRejectedReserved" to snapshot.claimRejectedReserved,
            "reserveRejectedReserved" to snapshot.reserveRejectedReserved,
            "reserveRejectedActive" to snapshot.reserveRejectedActive,
            "foreignReleaseRejected" to snapshot.foreignReleaseRejected,
        )
    }

    private fun entryMap(entry: RegistryEntrySnapshot): Map<String, Any?> =
        mapOf(
            "id" to entry.id,
            "owner" to entry.owner,
            "acquiredAtMs" to entry.acquiredAtMs,
            "ageMs" to entry.ageMs,
        )

    /**
     * Tamaño del `.tmp` activo para el muestreador de pruebas
     * (diseño base §8): `File.length()`, solo lectura. `null` sin
     * sesión o sin grabación activa. Nunca construye la sesión.
     */
    internal fun testActiveStagingSize(): Long? {
        if (!recordingSessionLazy.isInitialized()) return null
        val file = recordingSession?.activeStagingFile() ?: return null
        return if (file.isFile) file.length() else null
    }

    /**
     * Inyección de error solo-para-pruebas (J3-c, 3.1.4; autorización
     * J0.3): delega en `RecordingSession.injectTestError`, que es
     * quien guarda por `testMode` (inerte en producción). `kind` es
     * `encoder_error` o `drain_write_error` (validados en el canal);
     * cualquier otro valor llega a la sesión y retorna `false`.
     * Respuesta: `{injected, kind, reason}` (`reason` es
     * `engine_not_started` sin motor/sesión, o `not_injected` cuando
     * la sesión no programó la parada: puerta cerrada, sin grabación
     * activa o `kind` desconocido). Nunca materializa el motor ni
     * arranca una grabación: sin sesión no hay nada que inyectar.
     * Pública (no `internal`) como los demás seams de pruebas del
     * manejador de streaming: los tests unitarios la llaman
     * directamente, igual que `testRegistrySnapshot` /
     * `testActiveStagingSize` se exponen para el canal.
     */
    fun testInjectError(kind: String): Map<String, Any?> {
        if (!recordingSessionLazy.isInitialized()) {
            return mapOf(
                "injected" to false,
                "kind" to kind,
                "reason" to "engine_not_started",
            )
        }
        val session = recordingSession
            ?: return mapOf(
                "injected" to false,
                "kind" to kind,
                "reason" to "engine_not_started",
            )
        val injected = session.injectTestError(kind)
        return mapOf(
            "injected" to injected,
            "kind" to kind,
            "reason" to if (injected) null else "not_injected",
        )
    }

    /**
     * Mapa del resultado de `reconcileRecordings` para el canal
     * (0-d2; forma fijada en ambos lados a la vez, plan §6 A4).
     * `segments` lleva TODOS los segmentos del inventario con su
     * estado certificado (`ready`/`partial`/`corrupt`) o `pending`
     * (`PENDING_VERIFICATION`, C6: nunca utilizable ni corrupto).
     * Los `PENDING_VERIFICATION` van también en
     * `summary.segmentsPending` para reintento.
     * `orphansReconstructed` no lo conoce Kotlin (las filas las
     * inserta Dart): no viaja en este mapa; Dart lo rellena al
     * reconciliar las filas.
     */
    fun reconciliationResultMap(
        summary: ReconciliationSummary,
    ): Map<String, Any?> {
        return mapOf(
            "state" to "DONE",
            "summary" to reconciliationSummaryMap(summary),
            "segments" to summary.segments.map { reconciledSegmentMap(it) },
            "groupsSkippedActive" to summary.groupsSkippedActive,
            "groupsFailed" to summary.groupsFailed,
            "unknownFiles" to summary.unknownFiles,
            "missingFiles" to summary.missingFiles,
            "errorCode" to null,
            "errorMessage" to null,
        )
    }

    /** Mapa `FAILED` para `reconcileRecordings` cuando la puerta falla. */
    fun reconciliationFailedMap(
        failure: ReconciliationFailure,
    ): Map<String, Any?> {
        return mapOf(
            "state" to "FAILED",
            "summary" to null,
            "segments" to emptyList<Any>(),
            "groupsSkippedActive" to emptyList<Any>(),
            "groupsFailed" to emptyList<Any>(),
            "unknownFiles" to emptyList<Any>(),
            "missingFiles" to emptyList<Any>(),
            "errorCode" to failure.code,
            "errorMessage" to failure.message,
        )
    }

    /**
     * Resumen en la forma del contrato Dart (`ReconciliationSummary`):
     * los contadores nunca se inventan y las claves son las que
     * `fromMap` lee. `claimToken` no existe a este nivel (el token
     * del claim del grabador es privado de la sesión y la puerta no
     * graba): los tokens viajan como mapa grupo → token
     * (`reservationTokens`).
     */
    fun reconciliationSummaryMap(
        summary: ReconciliationSummary,
    ): Map<String, Any?> {
        return mapOf(
            "runId" to summary.runId,
            "state" to "DONE",
            "startedAtMs" to summary.startedAtMs,
            "finishedAtMs" to summary.finishedAtMs,
            "durationMs" to summary.durationMs,
            "groupsTotal" to summary.groupsTotal,
            "recordingsReconciled" to summary.groupsReconciled,
            "segmentsRenamed" to summary.segmentsRenamed,
            "segmentsClassified" to
                summary.segmentsTrusted + summary.segmentsVerified,
            "segmentsTrusted" to summary.segmentsTrusted,
            "segmentsVerified" to summary.segmentsVerified,
            "duplicatesQuarantined" to summary.segmentsQuarantined,
            "pending" to summary.segmentsPending.size,
            "segmentsPending" to summary.segmentsPending.map {
                mapOf(
                    "recordingId" to it.recordingId,
                    "segmentIndex" to it.index,
                    "fileName" to it.fileName,
                    "reason" to it.reason,
                )
            },
            "skipped_active" to summary.groupsSkippedActive.size,
            "groupsSkippedActive" to summary.groupsSkippedActive,
            "groupsFailed" to summary.groupsFailed,
            "reservationTokens" to summary.reservationTokens,
            "foreignReleaseRejected" to summary.foreignReleaseRejected,
            "orphanWriteTestFiles" to summary.orphanWriteTestFilesSwept,
            "conflicts" to summary.conflicts,
        )
    }

    /** Mapa de un segmento reconciliado para el canal (0-d2). */
    fun reconciledSegmentMap(
        segment: ReconciledSegmentOutcome,
    ): Map<String, Any?> {
        return mapOf(
            "recordingId" to segment.recordingId,
            "segmentIndex" to segment.index,
            "fileName" to segment.fileName,
            "filePath" to segment.filePath,
            "status" to when (segment.status) {
                SegmentStatus.READY -> "ready"
                SegmentStatus.PARTIAL -> "partial"
                SegmentStatus.CORRUPT -> "corrupt"
                null -> "pending"
            },
            "durationMs" to segment.durationMs,
            "sizeBytes" to segment.sizeBytes,
            "reason" to segment.reason,
            "case" to segment.case.name,
            "verified" to segment.verified,
            "trusted" to segment.trusted,
            "renamed" to segment.renamed,
            "wasStaging" to segment.wasStaging,
        )
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
            // J3-b: nueva grabación, nueva guarda de parada del FGS.
            // Se resetea ANTES de arrancar el servicio (y después
            // del rechazo de inicio duplicado de arriba: un segundo
            // inicio fallido no debe re-armar ni tocar el FGS de la
            // grabación activa).
            fgsStopRequested.set(false)
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
                // J3-b: la grabación activa es esta; su aviso de
                // error-stop (si llega) podrá parar su FGS.
                activeRecordingId.set(started.recordingId)
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
                // `recording_error` ya lo emitió la sesión). Sin evento
                // `service_stopped`: la grabación nunca arrancó y Dart
                // nunca vio `service_started` (J3-b: converge en el
                // mismo helper, sin evento). J3-c: la instrumentación
                // sí registra la parada (`FGS_STOPPED`,
                // origen `start_failed`).
                stopForegroundServiceOnce(
                    reason = null,
                    recordingId = null,
                    fgsStopOrigin = "start_failed",
                )
                throw e
            }
        }
    }

    /**
     * Detiene la grabación (Fase B, 3.1.2). Idempotente desde `IDLE`
     * (la sesión devuelve el resultado vacío, `status = "idle"`).
     * Detiene el FGS al terminar (plan §9.2, paso 5) y emite
     * `service_stopped` cuando hubo grabación (J3-b: con
     * `reason=normal_stop`; si ganó la parada por error, el evento
     * ya salió con `reason=error_stop` y no se duplica).
     * `filePath` es el primer segmento; la ruta final fusionada
     * llega en el evento `recording_merged` (J1-b, 3.1.4) y la fila
     * queda en `finalizing` hasta entonces.
     * J1-b: tras un stop con ≥1 segmento utilizable se lanza la
     * fusión asíncrona (el stop sigue siendo rápido) y el FGS
     * permanece vivo durante ella (J0.2), con notificación de
     * progreso; lo detiene la terminación de la fusión.
     */
    override suspend fun stopRecording(): RecordingInfo =
        stopRecordingInternal(fgsStopOrigin = null)

    /**
     * Parada desde la acción "Detener" de la notificación (J3-c,
     * 3.1.4): mismo camino que `stopRecording()` (el botón de la UI
     * y la notificación convergen: MainActivity → `StreamChannelHandler`
     * → aquí), pero el `FGS_STOPPED` de instrumentación lleva origen
     * `notification` en vez de `normal_stop`. Pública (no `internal`):
     * la usan `StreamChannelHandler.stopRecordingFromService()` y los
     * tests unitarios directamente. El origen viaja como parámetro
     * local (sin estado compartido): no hay carrera con una parada
     * simultánea desde la UI.
     */
    suspend fun stopRecordingFromNotification(): RecordingInfo =
        stopRecordingInternal(fgsStopOrigin = "notification")

    private suspend fun stopRecordingInternal(
        fgsStopOrigin: String?,
    ): RecordingInfo {
        val recording = recordingSession
        // Resultado de la parada, capturado antes del `finally` para
        // decidir si hay evento `service_stopped` (J3-b: el evento
        // vive ahora en el helper único de parada del FGS).
        var stoppedResult: RecordingResult? = null
        val result = if (recording == null) {
            RecordingResult.empty()
        } else {
            try {
                recordingMutex.withLock { recording.stop() }
                    .also { stoppedResult = it }
            } finally {
                val stopped = stoppedResult
                // J3-b: converge en el helper único. Si ganó la
                // parada por error, su aviso ya paró el FGS y emitió
                // `service_stopped` (error_stop): la guarda bloquea
                // el duplicado. Sin grabación (`idle`) o si `stop()`
                // lanzó, se para el FGS sin evento (comportamiento
                // anterior). J3-c: el origen de `FGS_STOPPED` es el
                // de esta llamada (`notification` desde la
                // notificación, `normal_stop` en el resto).
                // J1-b: si arranca una fusión, el FGS sigue vivo
                // durante ella (J0.2) y lo detiene su terminación;
                // si no hay fusión en curso, se para como antes.
                // `maybeStartMergeAfterStop` nunca lanza salvo
                // cancelación (degrada a no fusionar).
                val mergeStarted = try {
                    stopped?.let {
                        maybeStartMergeAfterStop(
                            result = it,
                            stopReason = "normal_stop",
                            fgsStopOrigin = fgsStopOrigin,
                        )
                    } ?: false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!mergeStarted && mergeInFlightCount.get() == 0) {
                    stopForegroundServiceOnce(
                        reason = "normal_stop",
                        recordingId = stopped?.takeIf { it.status != "idle" }
                            ?.recordingId,
                        fgsStopOrigin = fgsStopOrigin,
                    )
                }
                stopped?.let {
                    activeRecordingId.compareAndSet(it.recordingId, null)
                }
            }
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
     * Limpia la marca de fusión disparada para un id (3.1.4, J1-b):
     * la reanudación (J1-c) lo llama antes de reintentar la fusión
     * de un id cuyo intento anterior quedó en `finalizing`.
     */
    internal fun clearMergeTriggered(recordingId: String) {
        mergeTriggeredIds.remove(recordingId)
    }

    /**
     * Reanudación idempotente de la fusión tras kill/reapertura
     * (3.1.4, J1-c; plan §2.4.9). La pide Dart (`resumeMerge`) tras
     * reconciliar, solo para grupos cuya fila está en
     * `finalizing`/`recording` con segmentos utilizables. El motor
     * solo la lanza si la ÚLTIMA pasada de reconciliación certificó
     * ese grupo con ≥1 segmento utilizable: sin ese resumen no se
     * fusiona a ciegas (fail-closed; la fila queda en `finalizing`
     * y la siguiente reapertura reintenta).
     *
     * Los tres estados del plan se resuelven dentro del fusionador
     * (`SegmentMerger.merge`, J1-a/J1-b) y del evento que emite esta
     * orquestación, sin ramificar aquí:
     * - tmp huérfano (`<id>_final.tmp.mp4`): la reconciliación lo
     *   lista como desconocido y no lo toca; la fusión de reanudación
     *   lo sobrescribe (es propio y regenerable).
     * - final existente + fila `finalizing`: el fusionador verifica
     *   el final y completa sin re-fusionar (`alreadyMerged`).
     * - sin final + segmentos utilizables: re-fusión desde cero.
     *
     * Antes de reintentar se limpia la marca de fusión disparada de
     * este proceso (`clearMergeTriggered`, J1-b dejó la marca para
     * esto): un intento anterior en este mismo proceso (stop → fusión
     * reintentable) no bloquea el reintento. La guarda real contra
     * duplicados es la reserva del grupo: si la fusión anterior
     * sigue en curso, la reserva la retiene y este intento no
     * arranca (devuelve `false`).
     *
     * Sin FGS durante la reanudación: al reabrir, la app está en
     * primer plano y el servicio no corre (el proceso murió o la
     * parada anterior lo detuvo); arrancar un FGS solo para
     * reanudar sería un cambio mayor fuera de J1-c. La fusión corre
     * asíncrona y emite `recording_merged` como tras el stop; si el
     * proceso muere a mitad, la siguiente reapertura reintenta de
     * forma idempotente. Por eso `stopReason`/`fgsStopOrigin` son
     * `null`: el cierre NO emite `service_stopped` por un servicio
     * que nunca corrió en este proceso (la guarda del helper único
     * y la comprobación de grabación activa protegen igualmente el
     * FGS de una grabación nueva en curso).
     *
     * Devuelve `true` si se lanzó la fusión. `false` sin fusionar
     * si: el id está vacío, el grupo no está en el último resumen
     * de reconciliación o no tiene segmentos utilizables, el id ya
     * fusionó en este proceso (marca, sin reintento previo), no hay
     * fusionador, el grupo está activo/reservado, o lanzar falló.
     * Nunca lanza salvo cancelación.
     */
    internal suspend fun resumeMergeAfterReconcile(
        recordingId: String,
    ): Boolean {
        if (recordingId.isEmpty()) return false
        val reconciled = lastReconciliationSummary?.segments
            ?.filter { it.recordingId == recordingId }
            ?: return false
        val usable = reconciled.filter { it.usable }
        if (usable.isEmpty()) return false
        clearMergeTriggered(recordingId)
        return maybeStartMerge(
            recordingId = recordingId,
            segmentIndices = usable.map { it.index },
            segmentCount = usable.size,
            stopReason = null,
            fgsStopOrigin = null,
        )
    }

    /**
     * Orquestación de la fusión tras el stop (3.1.4, J1-b; J0.1/J0.2;
     * plan §2.4.1 y §2.4.10). Tras un `stop()` exitoso con ≥1 segmento
     * utilizable, reserva el grupo y lanza la fusión ASÍNCRONA: el
     * stop sigue siendo rápido (no bloquea `recording_stopped` con
     * una fusión que puede tardar minutos) y el FGS permanece vivo
     * durante ella, con notificación de progreso; al terminar, la
     * fusión emite `recording_merged` y detiene el FGS vía el helper
     * único (con el `stopReason`/`fgsStopOrigin` de esta parada).
     *
     * Devuelve `true` si se lanzó una fusión (el llamador NO debe
     * parar el FGS). Devuelve `false` sin fusionar —y el llamador
     * para el FGS como antes— si: el resultado es `idle`/vacío, no
     * hay segmentos utilizables, el id ya fusionó en este proceso,
     * no hay fusionador (sin `context` ni `mergeRunner`), el grupo
     * está activo/reservado, o lanzar falló. Nunca lanza salvo
     * cancelación: cualquier fallo interno degrada a no fusionar
     * (guard_new_execution_path).
     */
    private suspend fun maybeStartMergeAfterStop(
        result: RecordingResult,
        stopReason: String?,
        fgsStopOrigin: String?,
    ): Boolean {
        val recordingId = result.recordingId
        if (result.status == "idle" || recordingId.isEmpty()) return false
        val usable = result.segments.any {
            it.status != SegmentStatus.CORRUPT
        }
        if (!usable) return false
        return maybeStartMerge(
            recordingId = recordingId,
            segmentIndices = result.segments.map { it.index },
            segmentCount = result.segments.size,
            stopReason = stopReason,
            fgsStopOrigin = fgsStopOrigin,
        )
    }

    /**
     * Lanzador común de la fusión asíncrona (J1-b tras el stop,
     * J1-c en reanudación tras reconciliar): resuelve el ejecutor,
     * marca el id, reserva el grupo vía `ActiveRecordingRegistry`
     * (mismo mecanismo que la reconciliación) y lanza
     * [runMergeAndFinish] en el scope del motor. `segmentIndices` /
     * `segmentCount` son los índices que la orquestación intentó
     * fusionar: solo se usan como reserva cuando el fusionador no
     * los trae (final ya existente verificado, `alreadyMerged`) y
     * para el `segmentCount` del evento. Nunca lanza salvo
     * cancelación.
     */
    private suspend fun maybeStartMerge(
        recordingId: String,
        segmentIndices: List<Int>,
        segmentCount: Int,
        stopReason: String?,
        fgsStopOrigin: String?,
    ): Boolean {
        val runner: suspend (
            String,
            ((MergeProgress) -> Unit)?,
        ) -> MergeResult = mergeRunner ?: segmentMergerLazy.value?.let { merger ->
            { id: String, onProgress: ((MergeProgress) -> Unit)? ->
                merger.merge(id, onProgress)
            }
        } ?: return false
        if (!mergeTriggeredIds.add(recordingId)) {
            Transmiti2Logger.d(
                "merge: $recordingId ya fusionó en este proceso; " +
                    "no se re-dispara",
            )
            return false
        }
        val session = recordingSession
        if (session == null) {
            mergeTriggeredIds.remove(recordingId)
            return false
        }
        val token: OwnershipToken = try {
            session.tryReserveGroupForMerge(recordingId)
                ?: run {
                    Transmiti2Logger.d(
                        "merge: grupo $recordingId activo o reservado; " +
                            "no se fusiona",
                    )
                    mergeTriggeredIds.remove(recordingId)
                    return false
                }
        } catch (e: CancellationException) {
            mergeTriggeredIds.remove(recordingId)
            throw e
        } catch (_: Exception) {
            mergeTriggeredIds.remove(recordingId)
            return false
        }
        mergeInFlightCount.incrementAndGet()
        try {
            scope.launch {
                runMergeAndFinish(
                    recordingId = recordingId,
                    stopSegmentIndices = segmentIndices,
                    stopSegmentCount = segmentCount,
                    token = token,
                    session = session,
                    runner = runner,
                    stopReason = stopReason,
                    fgsStopOrigin = fgsStopOrigin,
                )
            }
        } catch (e: CancellationException) {
            // El scope se está destruyendo: la reserva queda
            // retenida hasta la muerte del proceso (el registro es
            // por proceso y el motor ya no vive).
            mergeInFlightCount.decrementAndGet()
            throw e
        } catch (_: Exception) {
            mergeInFlightCount.decrementAndGet()
            try {
                scope.launch {
                    session.releaseGroupForMerge(recordingId, token)
                }
            } catch (_: Exception) {
            }
            return false
        }
        return true
    }

    /**
     * Ejecuta una fusión lanzada por [maybeStartMergeAfterStop] o por
     * [resumeMergeAfterReconcile] y cierra su ciclo (J1-b): fusiona,
     * registra el resultado en el sidecar, emite `recording_merged`
     * y —solo cuando es la última fusión en curso— detiene el FGS vía
     * el helper único. En reanudación (`stopReason`/`fgsStopOrigin`
     * `null`) ese cierre no emite `service_stopped`: el servicio no
     * corría en este proceso tras la reapertura.
     * La reserva del grupo se libera siempre (también al cancelar).
     * Nunca lanza fuera salvo cancelación: un fallo inesperado del ejecutor
     * emite `recording_merged` con `status=corrupt` y motivo
     * `muxer_error` para que Dart no espere eternamente; la
     * emisión del evento tampoco tumba el cierre.
     */
    private suspend fun runMergeAndFinish(
        recordingId: String,
        stopSegmentIndices: List<Int>,
        stopSegmentCount: Int,
        token: OwnershipToken,
        session: RecordingSession,
        runner: suspend (
            String,
            ((MergeProgress) -> Unit)?,
        ) -> MergeResult,
        stopReason: String?,
        fgsStopOrigin: String?,
    ) {
        try {
            var runnerFailed = false
            val result: MergeResult? = try {
                runner(recordingId) { progress ->
                    reportMergeProgress(progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Transmiti2Logger.e(
                    "merge: $recordingId falló el ejecutor: " +
                        e.javaClass.simpleName,
                )
                runnerFailed = true
                null
            }
            val effective = result ?: MergeResult(
                success = false,
                reason = "muxer_error",
                detailReason = "merge_runner_failed",
                recordingId = recordingId,
                finalFile = null,
                status = null,
                segmentCount = 0,
                segmentIndices = emptyList(),
                audioGapSegments = emptyList(),
                partialSegments = emptyList(),
                affectedIndices = stopSegmentIndices,
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
            try {
                if (!runnerFailed) {
                    session.recordMergeResult(effective, stopSegmentIndices)
                }
                emitEvent(
                    StreamContract.EVENT_RECORDING_MERGED,
                    recordingMergedPayload(effective, stopSegmentCount),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // La emisión del evento no tumba el cierre: el FGS
                // se detiene en el `finally` de abajo igualmente.
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    session.releaseGroupForMerge(recordingId, token)
                } catch (_: Exception) {
                }
                if (mergeInFlightCount.decrementAndGet() == 0) {
                    stopForegroundServiceOnce(
                        reason = stopReason,
                        recordingId = recordingId,
                        fgsStopOrigin = fgsStopOrigin,
                    )
                }
            }
        }
    }

    /** Progreso de la fusión a la notificación del FGS (J1-b, J0.2). */
    private fun reportMergeProgress(progress: MergeProgress) {
        val fgs = foregroundService ?: return
        try {
            fgs.updateMergeProgress(mergeProgressText(progress))
        } catch (_: Exception) {
        }
    }

    /** Texto de progreso de la fusión para la notificación. */
    private fun mergeProgressText(progress: MergeProgress): String =
        when (progress.stage) {
            MergeStage.VALIDATING -> "validando segmentos"
            MergeStage.COPYING ->
                "segmento ${progress.segmentsDone} " +
                    "de ${progress.segmentCount}"
            MergeStage.VERIFYING -> "verificando fusionado"
        }

    /**
     * Payload del evento `recording_merged` (J1-b; plan §2.4.10):
     * `status` es `ready` en éxito (el fusionado PARTIAL colapsa a
     * `ready`, como la fila v2), `finalizing` si el aborto es
     * reintentable (la fila queda pendiente de reanudación, J1-c) y
     * `corrupt` si el fracaso es definitivo. `segmentCount` son los
     * segmentos fusionados (o los del stop si el fusionador no los
     * trae, p. ej. final ya existente); `skippedSegments` son los
     * índices afectados por el aborto.
     */
    private fun recordingMergedPayload(
        result: MergeResult,
        stopSegmentCount: Int,
    ): Map<String, Any?> {
        val status = when {
            result.success -> "ready"
            mergeFailureIsRetryable(result.reason) -> "finalizing"
            else -> "corrupt"
        }
        return mapOf(
            "recordingId" to result.recordingId,
            "filePath" to result.finalFile?.absolutePath,
            "status" to status,
            "totalDurationMs" to result.mergedDurationMs,
            "totalSizeBytes" to result.mergedSizeBytes,
            "segmentCount" to if (result.segmentIndices.isNotEmpty()) {
                result.segmentIndices.size
            } else {
                stopSegmentCount
            },
            "skippedSegments" to result.affectedIndices,
            "audioGapSegments" to result.audioGapSegments,
            "reason" to result.reason,
            "alreadyMerged" to result.alreadyMerged,
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
     * La reconciliación vive en la puerta (0-d2): `reconcileRecordings()`
     * más arriba dispara o espera la única pasada del proceso y
     * `reconciliationResultMap()` construye su payload. El método
     * antiguo `recoverInterruptedRecordings()` y `recoveredSegmentMap()`
     * se eliminaron con el nombre de canal antiguo: no hay dos caminos.
     */

    /**
     * `true` cuando el FGS está disponible para grabar (hay sesión de
     * grabación y control del servicio). Es lo que devuelve
     * `getStatus().foregroundServiceAvailable` desde 3.1.2.
     */
    fun foregroundServiceAvailable(): Boolean {
        return recordingSession != null && foregroundService != null
    }

    /**
     * Único punto de parada del FGS (J3-b, plan §1.3 pasos 2-4):
     * la parada normal, la de cámara/superficie, la de inicio
     * fallido y la de error-stop convergen aquí; no hay un
     * `stopRecordingService()` fuera de este helper. La primera
     * llamada por grabación para el servicio; las demás son no-op
     * (guarda `fgsStopRequested`). Un aviso de error-stop de una
     * grabación anterior (id distinto al activo) se ignora para no
     * matar el FGS de la grabación nueva.
     *
     * `reason` es el motivo del evento `service_stopped`
     * (`normal_stop` | `error_stop`); `null` = parar el servicio
     * sin emitir el evento (parada de inicio fallido y redes de
     * seguridad de `stopRecordingIfActive`, que conservan su
     * comportamiento anterior). Sin `recordingId` no se emite el
     * evento (no hay grabación a la que atribuirlo). Devuelve
     * `true` si esta llamada paró el servicio.
     *
     * J3-c (3.1.4, plan §1.3 paso 6): cuando esta llamada para el
     * servicio de verdad (guarda ganada), además emite el evento de
     * instrumentación `FGS_STOPPED` con el origen en `detail`
     * (`error_stop` | `normal_stop` | `camera_error` |
     * `notification` | `start_failed`). El origen explícito del
     * llamador (`fgsStopOrigin`) manda: solo lo pasan los caminos
     * que saben que el servicio corría (`start_failed` tras
     * arrancar el FGS; `camera_error`/`normal_stop` de
     * `stopRecordingIfActive` tras detener una grabación real;
     * `notification` desde `stopRecordingFromNotification()`). Sin
     * origen explícito, se deriva de `reason` (`error_stop`/
     * `normal_stop`) SOLO cuando hay grabación a la que atribuir el
     * evento (`recordingId != null`, igual que `service_stopped`):
     * una parada en `idle` (sin grabación ni servicio en marcha) no
     * emite `FGS_STOPPED`. `detail["reason"]` solo se incluye cuando
     * el camino lleva un `reason` de `service_stopped`. Sin sesión
     * de pruebas activa o con ganchos NO_OP, el registro es no-op.
     */
    private fun stopForegroundServiceOnce(
        reason: String?,
        recordingId: String?,
        fgsStopOrigin: String? = null,
    ): Boolean {
        if (recordingId != null) {
            val active = activeRecordingId.get()
            if (active != null && active != recordingId) {
                Transmiti2Logger.d(
                    "recording: parada del FGS ignorada: aviso " +
                        "tardío de $recordingId con $active activa",
                )
                return false
            }
        }
        if (!fgsStopRequested.compareAndSet(false, true)) return false
        foregroundService?.let { fgs ->
            try {
                fgs.stopRecordingService()
            } catch (_: Exception) {
            }
        }
        if (reason != null && recordingId != null) {
            scope.launch {
                emitEvent(
                    StreamContract.EVENT_SERVICE_STOPPED,
                    mapOf(
                        "recordingId" to recordingId,
                        "reason" to reason,
                    ),
                )
            }
        }
        val origin = fgsStopOrigin ?: if (recordingId != null) {
            when (reason) {
                "error_stop" -> "error_stop"
                "normal_stop" -> "normal_stop"
                else -> null
            }
        } else {
            null
        }
        if (origin != null) {
            testHooks.record(
                TestEventType.FGS_STOPPED,
                operation = "fgs",
                recordingId = recordingId,
                detail = buildMap {
                    put("origin", origin)
                    if (reason != null) put("reason", reason)
                },
            )
        }
        return true
    }

    /**
     * La sesión completó una parada con error (J3-b, plan §1.3 paso
     * 2). Orden: (f) `recording_error` ya se emitió dentro de
     * `doStop` antes de avisar (o, si `doStop` lanzó, el claim ya
     * está liberado y no hay evento que preservar: plan §1.5 punto
     * 5) → (g) parada del FGS → (h) `service_stopped` con
     * `reason=error_stop`. El orden (f) antes de (g) preserva el
     * diagnóstico aunque el proceso muera entre ambos. Se ejecuta
     * una sola vez por grabación (guarda de
     * `stopForegroundServiceOnce`).
     */
    private fun onSessionErrorStop(
        code: String,
        trigger: ErrorTrigger?,
        recordingId: String,
    ) {
        Transmiti2Logger.d(
            "recording: error-stop completado (code=$code " +
                "stage=${trigger?.stage} segmento=" +
                "${trigger?.segmentIndex} id=$recordingId): " +
                "parando el FGS",
        )
        if (stopForegroundServiceOnce(
                reason = "error_stop",
                recordingId = recordingId,
            )
        ) {
            activeRecordingId.compareAndSet(recordingId, null)
        }
    }

    /**
     * Detiene la grabación si hay una en curso (idempotente desde
     * `IDLE` o `ERROR`) y detiene el FGS. Nunca lanza: una parada de
     * vista previa o un error de cámara no deben fallar porque la
     * parada de la grabación falle. Con `errorCode`, la parada se
     * registra como error (desconexión de cámara, plan §6.4).
     * J3-b: la parada del FGS converge en
     * `stopForegroundServiceOnce`; con `errorCode`, el aviso de
     * error-stop de la sesión ya emitió `service_stopped` con
     * `reason=error_stop` y el `finally` no lo duplica.
     */
    private suspend fun stopRecordingIfActive(errorCode: String? = null) {
        val recording = recordingSession ?: return
        var stoppedResult: RecordingResult? = null
        try {
            stoppedResult = recordingMutex.withLock { recording.stop(errorCode) }
        } catch (_: Exception) {
        } finally {
            // J3-b: converge en el helper único, sin evento
            // `service_stopped`: si la parada llevaba `errorCode`,
            // el aviso de error-stop de la sesión ya paró el FGS y
            // emitió `service_stopped` (error_stop); este `finally`
            // es la red de seguridad para los casos sin aviso (sesión
            // ya en IDLE/ERROR, o `stop()` que lanzó) y conserva el
            // comportamiento anterior de no emitir `service_stopped`
            // en este camino.
            // J3-c: la instrumentación SÍ registra la parada cuando
            // este `finally` detuvo una grabación real (`status !=
            // "idle"`): origen `camera_error` con `errorCode`
            // (desconexión de cámara), `normal_stop` sin él
            // (cierre de vista previa / superficie). Con `idle` o si
            // `stop()` lanzó (resultado desconocido), no se emite
            // `FGS_STOPPED`: no se puede atribuir el origen sin
            // inventarlo. Cuando la sesión ya avisó (`error_stop`),
            // la guarda bloquea el duplicado.
            // J1-b: si arranca una fusión, el FGS sigue vivo durante
            // ella (J0.2) y lo detiene su terminación, con el mismo
            // `reason` (`null`, sin evento) y el mismo origen que la
            // parada directa de este camino.
            val stopped = stoppedResult
            val mergeStarted = try {
                stopped?.let {
                    maybeStartMergeAfterStop(
                        result = it,
                        stopReason = null,
                        fgsStopOrigin =
                            if (it.status != "idle") {
                                if (errorCode != null) {
                                    "camera_error"
                                } else {
                                    "normal_stop"
                                }
                            } else {
                                null
                            },
                    )
                } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!mergeStarted && mergeInFlightCount.get() == 0) {
                stopForegroundServiceOnce(
                    reason = null,
                    recordingId = null,
                    fgsStopOrigin = if (stopped != null && stopped.status != "idle") {
                        if (errorCode != null) "camera_error" else "normal_stop"
                    } else {
                        null
                    },
                )
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
