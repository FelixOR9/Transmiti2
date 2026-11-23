package com.transmiti2.transmiti2.streaming.test

import com.transmiti2.transmiti2.BuildConfig
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Canal de pruebas de la infraestructura de pruebas físicas
 * (diseño base §4.F + Revisión 1 §2/§5/§6 + Revisión 2 §3 +
 * Revisión 3 §3/§4; FASE 2, grupo 2-b).
 *
 * Canal `com.transmiti2.transmiti2/streaming/testing`, separado del
 * canal de producción: no se toca el `dispatch` del canal de
 * streaming ni su contrato.
 *
 * Puerta de compilación: con `testMode == false` (el valor de
 * `BuildConfig.TRANSMITI2_TEST_MODE` en producción; inyectado como
 * parámetro de constructor en los tests), TODOS los métodos salvo
 * `getTestMode` responden `test_mode_unavailable` y NO tocan disco
 * ni memoria de pruebas. `getTestMode` responde `false`. Con el flag
 * en `true`, los métodos delegan en las clases del grupo 2-a
 * ([TestInstrumentation], [TestDiagnosticsStore], [TestSyncPoints]).
 *
 * El manejador NO materializa el motor ni toca la grabación: solo
 * instrumentación, almacén y puntos de pausa. Los seams que dependen
 * del motor (`getRegistrySnapshot`, `sampleActiveStagingSize`) son
 * proveedores inyectables que el grupo 2-c cablea; sin proveedor
 * responden `test_not_available` (nunca se inventan datos).
 *
 * El `scope` del sink es independiente (propio, con `SupervisorJob`):
 * sobrevive a la cancelación de la reconciliación (nota 2-a nº 10);
 * NUNCA es el scope del motor. [close] lo cancela (lo llama
 * `MainActivity.onDestroy`).
 *
 * Ningún método lanza hacia el llamador: cada llamada va envuelta en
 * `try/catch` y responde `result.error` con un código del diseño
 * (`test_mode_unavailable`, `test_storage_full`,
 * `test_no_active_session`, `confirmation_required`,
 * `test_path_outside_root`, `session_not_found`,
 * `test_not_available`) o el genérico `test_error` para fallos
 * inesperados. Las respuestas nunca incluyen secretos ni valores de
 * tokens: solo conteos, estados e identificadores de sesión/campaña.
 */
class TestChannelHandler(
    filesDir: File,
    val testMode: Boolean = BuildConfig.TRANSMITI2_TEST_MODE,
    clock: TestClock = SystemTestClock,
    exportRoot: File = File(filesDir, "test-exports"),
    private val registrySnapshotProvider: (() -> Map<String, Any?>)? = null,
    private val stagingSizeProvider: (() -> Long?)? = null,
    appVersion: String = "unknown",
    instrumentation: TestInstrumentation? = null,
    store: TestDiagnosticsStore? = null,
    syncPoints: TestSyncPoints? = null,
) : MethodChannel.MethodCallHandler {

    companion object {
        const val METHOD_CHANNEL = "com.transmiti2.transmiti2/streaming/testing"

        const val GET_TEST_MODE = "getTestMode"
        const val START_TEST_RUN = "startTestRun"
        const val START_TEST_SESSION = "startTestSession"
        const val END_TEST_SESSION = "endTestSession"
        const val GET_TEST_EVENTS = "getTestEvents"
        const val GET_TEST_RUNS = "getTestRuns"
        const val GET_TEST_SESSIONS = "getTestSessions"
        const val GET_STORAGE_STATE = "getStorageState"
        const val DELETE_TEST_SESSION = "deleteTestSession"
        const val EXPORT_TEST_REPORT = "exportTestReport"
        const val EXPORT_TEST_SESSIONS = "exportTestSessions"
        const val ARM_RECONCILIATION_PAUSE = "armReconciliationPause"
        const val RESUME_RECONCILIATION = "resumeReconciliation"
        const val GET_PAUSE_STATE = "getPauseState"
        const val GET_REGISTRY_SNAPSHOT = "getRegistrySnapshot"
        const val SAMPLE_ACTIVE_STAGING_SIZE = "sampleActiveStagingSize"

        const val ERR_TEST_MODE_UNAVAILABLE = "test_mode_unavailable"
        const val ERR_TEST_STORAGE_FULL = "test_storage_full"
        const val ERR_TEST_NO_ACTIVE_SESSION = "test_no_active_session"
        const val ERR_CONFIRMATION_REQUIRED = "confirmation_required"
        const val ERR_TEST_PATH_OUTSIDE_ROOT = "test_path_outside_root"
        const val ERR_SESSION_NOT_FOUND = "session_not_found"
        const val ERR_TEST_NOT_AVAILABLE = "test_not_available"
        const val ERR_TEST_ERROR = "test_error"
    }

    private val diagnosticsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val diagnosticsRoot = File(filesDir, "test-diagnostics")
    private val exportRoot: File = exportRoot

    private val store: TestDiagnosticsStore
    private val instrumentation: TestInstrumentation
    private val syncPoints: TestSyncPoints

    init {
        val resolvedStore = store ?: TestDiagnosticsStore(diagnosticsRoot, clock = clock)
        val resolvedInstrumentation = instrumentation ?: TestInstrumentation(
            enabled = { testMode },
            clock = clock,
            sink = resolvedStore.eventSink(diagnosticsScope),
            appVersion = appVersion,
        )
        val resolvedSyncPoints = syncPoints ?: TestSyncPoints(
            enabled = { testMode },
            instrumentation = resolvedInstrumentation,
            clock = clock,
            intentAccess = FileArmedPauseAccess(
                File(diagnosticsRoot, TestSyncPoints.ARMED_PAUSE_FILE_NAME),
            ),
        )
        this.store = resolvedStore
        this.instrumentation = resolvedInstrumentation
        this.syncPoints = resolvedSyncPoints
    }

    /** Cancela el scope propio del sink (lo llama `MainActivity.onDestroy`). */
    fun close() {
        diagnosticsScope.cancel()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == GET_TEST_MODE) {
            result.success(testMode)
            return
        }
        if (!testMode) {
            result.error(
                ERR_TEST_MODE_UNAVAILABLE,
                "El modo de pruebas no está activo en esta compilación.",
                null,
            )
            return
        }
        try {
            when (call.method) {
                START_TEST_RUN -> startTestRun(result)
                START_TEST_SESSION -> startTestSession(call, result)
                END_TEST_SESSION -> endTestSession(call, result)
                GET_TEST_EVENTS -> getTestEvents(call, result)
                GET_TEST_RUNS -> getTestRuns(result)
                GET_TEST_SESSIONS -> getTestSessions(result)
                GET_STORAGE_STATE -> getStorageState(result)
                DELETE_TEST_SESSION -> deleteTestSession(call, result)
                EXPORT_TEST_REPORT -> exportTestReport(call, result)
                EXPORT_TEST_SESSIONS -> exportTestSessions(result)
                ARM_RECONCILIATION_PAUSE -> armReconciliationPause(call, result)
                RESUME_RECONCILIATION -> resumeReconciliation(call, result)
                GET_PAUSE_STATE -> getPauseState(call, result)
                GET_REGISTRY_SNAPSHOT -> getRegistrySnapshot(result)
                SAMPLE_ACTIVE_STAGING_SIZE -> sampleActiveStagingSize(result)
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            // Nunca se lanza hacia el llamador: el fallo viaja como error.
            val (code, message, details) = mapError(e)
            runCatching { result.error(code, message, details) }
            Transmiti2Logger.e(
                "TestChannelHandler: ${call.method} falló (${e.javaClass.simpleName})",
            )
        }
    }

    // ------------------------------------------------------------------
    // Sesiones y campañas
    // ------------------------------------------------------------------

    /** Genera un `testRunId` libre (sin crear sesión). */
    private fun startTestRun(result: MethodChannel.Result) {
        val testRunId = runBlocking {
            instrumentation.generateTestRunId(isTaken = store::hasTestRunId)
        }
        result.success(mapOf("testRunId" to testRunId))
    }

    /**
     * Arranca una sesión de pruebas. Argumento opcional `adoptTestRunId`
     * (continuación de campaña, Revisión 1 §2.6; la adopción al arrancar
     * con `peekIntent` la hace el cableado del grupo 2-c).
     *
     * Flujo: `createSession` → `beginSession` (crea el archivo primero)
     * → `startSession` (activa y emite `SESSION_STARTED`). Con 10
     * sesiones guardadas responde `test_storage_full` (Revisión 1 §6)
     * sin crear ningún archivo parcial.
     */
    private fun startTestSession(call: MethodCall, result: MethodChannel.Result) {
        if (instrumentation.sessionActive) {
            // Antes de crear ningún archivo: si ya hay sesión activa, la
            // segunda no arranca y no deja un `.jsonl` huérfano que
            // contara para el tope de 10.
            result.error(
                ERR_TEST_ERROR,
                "Ya hay una sesión de pruebas activa.",
                mapOf("reason" to "session_already_active"),
            )
            return
        }
        val adoptTestRunId: String? = call.argument("adoptTestRunId")
        val session = runBlocking {
            instrumentation.createSession(
                adoptTestRunId = adoptTestRunId,
                isTestRunIdTaken = store::hasTestRunId,
            )
        }
        when (val begun = runBlocking { store.beginSession(session) }) {
            is BeginSessionResult.Blocked -> {
                result.error(
                    ERR_TEST_STORAGE_FULL,
                    "Almacenamiento de pruebas lleno (${begun.reason}).",
                    mapOf(
                        "reason" to begun.reason,
                        "testRunId" to session.testRunId,
                    ),
                )
                return
            }
            is BeginSessionResult.Started -> {
                try {
                    instrumentation.startSession(session)
                } catch (e: IllegalStateException) {
                    result.error(
                        ERR_TEST_ERROR,
                        "Ya hay una sesión de pruebas activa.",
                        mapOf("reason" to "session_already_active"),
                    )
                    return
                }
                result.success(
                    mapOf(
                        "sessionId" to session.sessionId,
                        "testRunId" to session.testRunId,
                        "interruptedPrevious" to begun.interruptedPrevious.map {
                            mapOf(
                                "previousSessionId" to it.previousSessionId,
                                "previousTestRunId" to it.previousTestRunId,
                                "lastObservedEvent" to it.lastObservedEvent,
                                "recordedInNewSession" to it.recordedInNewSession,
                                "recordReason" to it.recordReason,
                            )
                        },
                    ),
                )
            }
        }
    }

    /**
     * Termina la sesión activa (`verdict` opcional). Libera las pausas
     * pendientes y borra la intención de su campaña (best-effort).
     * Responde la sesión terminada, o `null` si no había ninguna.
     */
    private fun endTestSession(call: MethodCall, result: MethodChannel.Result) {
        val verdict: String? = call.argument("verdict")
        val ended = instrumentation.endSession(verdict)
        syncPoints.endSession()
        runCatching { runBlocking { syncPoints.disarm() } }
        if (ended == null) {
            result.success(null)
        } else {
            result.success(
                mapOf(
                    "sessionId" to ended.sessionId,
                    "testRunId" to ended.testRunId,
                    "ended" to true,
                ),
            )
        }
    }

    /** Eventos del anillo en memoria; `sessionId` opcional para filtrar. */
    private fun getTestEvents(call: MethodCall, result: MethodChannel.Result) {
        val sessionId: String? = call.argument("sessionId")
        val events = if (sessionId == null) {
            instrumentation.events()
        } else {
            instrumentation.eventsOf(sessionId)
        }
        result.success(events.map { it.toJsonMap() })
    }

    /** Sesiones agrupadas por `testRunId` (campañas). */
    private fun getTestRuns(result: MethodChannel.Result) {
        val sessions = runBlocking { store.listSessions() }
        val grouped = sessions.groupBy { it.testRunId }
        val runs = grouped.entries
            .sortedByDescending { (_, list) ->
                list.maxOfOrNull { it.startedAtWallMs ?: 0L } ?: 0L
            }
            .map { (testRunId, list) ->
                mapOf(
                    "testRunId" to testRunId,
                    "sessionCount" to list.size,
                    "sessions" to list.map { sessionInfoMap(it) },
                )
            }
        result.success(runs)
    }

    /** Lista plana de sesiones persistidas con metadatos. */
    private fun getTestSessions(result: MethodChannel.Result) {
        val sessions = runBlocking { store.listSessions() }
        result.success(sessions.map { sessionInfoMap(it) })
    }

    private fun sessionInfoMap(info: TestSessionInfo): Map<String, Any?> = mapOf(
        "sessionId" to info.sessionId,
        "testRunId" to info.testRunId,
        "startedAtWallMs" to info.startedAtWallMs,
        "sizeBytes" to info.sizeBytes,
        "verdict" to info.verdict,
    )

    /** `{sessionCount, limit, full}` (tope de 10 sesiones). */
    private fun getStorageState(result: MethodChannel.Result) {
        val state = runBlocking { store.storageState() }
        result.success(
            mapOf(
                "sessionCount" to state.sessionCount,
                "limit" to state.limit,
                "full" to state.full,
            ),
        )
    }

    /**
     * Elimina una sesión. Sin `confirmed: true` responde
     * `confirmation_required` SIN borrar nada (Revisión 2 §3); con
     * confirmación delega en el almacén y mapea `Deleted`/`Failed`.
     */
    private fun deleteTestSession(call: MethodCall, result: MethodChannel.Result) {
        val sessionIdArg: String? = call.argument("sessionId")
        if (sessionIdArg == null) {
            result.error(
                ERR_TEST_ERROR,
                "Falta el argumento sessionId.",
                null,
            )
            return
        }
        val sessionId: String = sessionIdArg
        val confirmed = (call.argument<Any>("confirmed") as? Boolean) ?: false
        if (!confirmed) {
            result.error(
                ERR_CONFIRMATION_REQUIRED,
                "La eliminación exige confirmed: true.",
                mapOf("sessionId" to sessionId),
            )
            return
        }
        when (val deleted = runBlocking { store.deleteSession(sessionId, confirmed = true) }) {
            is DeleteSessionResult.Deleted -> result.success(
                mapOf("sessionId" to deleted.sessionId, "deleted" to true),
            )
            is DeleteSessionResult.ConfirmationRequired -> result.error(
                ERR_CONFIRMATION_REQUIRED,
                "La eliminación exige confirmed: true.",
                mapOf("sessionId" to sessionId),
            )
            is DeleteSessionResult.Failed -> result.error(
                ERR_TEST_ERROR,
                "No se pudo eliminar la sesión.",
                mapOf("sessionId" to sessionId, "reason" to deleted.reason),
            )
        }
    }

    // ------------------------------------------------------------------
    // Exportación (dos raíces: el informe nunca se escribe dentro del
    // árbol de datos; Revisión 3 §3)
    // ------------------------------------------------------------------

    /** Exporta el informe de una sesión a `exportRoot` (inyectable). */
    private fun exportTestReport(call: MethodCall, result: MethodChannel.Result) {
        val sessionIdArg: String? = call.argument("sessionId")
        if (sessionIdArg == null) {
            result.error(
                ERR_TEST_ERROR,
                "Falta el argumento sessionId.",
                null,
            )
            return
        }
        val sessionId: String = sessionIdArg
        when (val exported = runBlocking { store.exportSession(sessionId, exportRoot) }) {
            is ExportSessionResult.Exported -> result.success(
                mapOf(
                    "sessionId" to exported.sessionId,
                    "jsonPath" to exported.jsonFile.absolutePath,
                    "txtPath" to exported.txtFile.absolutePath,
                ),
            )
            is ExportSessionResult.SessionNotFound -> result.error(
                ERR_SESSION_NOT_FOUND,
                "Sesión no encontrada: $sessionId.",
                mapOf("sessionId" to sessionId),
            )
        }
    }

    /** Exporta todas las sesiones; responde la lista de resultados. */
    private fun exportTestSessions(result: MethodChannel.Result) {
        val exported = runBlocking { store.exportAll(exportRoot) }
        result.success(
            exported.map {
                when (it) {
                    is ExportSessionResult.Exported -> mapOf(
                        "sessionId" to it.sessionId,
                        "exported" to true,
                        "jsonPath" to it.jsonFile.absolutePath,
                        "txtPath" to it.txtFile.absolutePath,
                    )
                    is ExportSessionResult.SessionNotFound -> mapOf(
                        "sessionId" to it.sessionId,
                        "exported" to false,
                        "error" to ERR_SESSION_NOT_FOUND,
                    )
                }
            },
        )
    }

    // ------------------------------------------------------------------
    // Pausas
    // ------------------------------------------------------------------

    /** Arma la pausa en `point` (P0/P1/P2); exige sesión activa. */
    private fun armReconciliationPause(call: MethodCall, result: MethodChannel.Result) {
        val point = parsePausePoint(call, result) ?: return
        val intent = try {
            runBlocking { syncPoints.arm(point) }
        } catch (e: IllegalStateException) {
            result.error(
                ERR_TEST_NO_ACTIVE_SESSION,
                "No hay sesión de pruebas activa para armar una pausa.",
                null,
            )
            return
        }
        result.success(intent.toJsonMap())
    }

    /**
     * Reanuda la pausa en `point` (`{resumed: bool, point}`); sin
     * punto, reanuda todas (`{resumedCount: n}`).
     */
    private fun resumeReconciliation(call: MethodCall, result: MethodChannel.Result) {
        val pointName: String? = call.argument("point")
        if (pointName == null) {
            result.success(mapOf("resumedCount" to syncPoints.resumeAll()))
            return
        }
        val point = parsePausePoint(call, result) ?: return
        result.success(
            mapOf(
                "point" to point.name,
                "resumed" to syncPoints.resume(point),
            ),
        )
    }

    /**
     * Estado de la pausa en `point` (`{point, state}`); sin punto,
     * los tres (`[{point, state}, ...]`).
     */
    private fun getPauseState(call: MethodCall, result: MethodChannel.Result) {
        val pointName: String? = call.argument("point")
        if (pointName == null) {
            result.success(
                PausePoint.entries.map {
                    mapOf("point" to it.name, "state" to syncPoints.pauseState(it).name)
                },
            )
            return
        }
        val point = parsePausePoint(call, result) ?: return
        result.success(
            mapOf(
                "point" to point.name,
                "state" to syncPoints.pauseState(point).name,
            ),
        )
    }

    private fun parsePausePoint(
        call: MethodCall,
        result: MethodChannel.Result,
    ): PausePoint? {
        val pointName: String? = call.argument("point")
        return try {
            PausePoint.valueOf(pointName ?: "")
        } catch (_: IllegalArgumentException) {
            result.error(
                ERR_TEST_ERROR,
                "Punto de pausa desconocido: $pointName.",
                mapOf("reason" to "unknown_pause_point"),
            )
            null
        }
    }

    // ------------------------------------------------------------------
    // Seams del motor (grupo 2-c): sin proveedor, `test_not_available`
    // ------------------------------------------------------------------

    /** Snapshot del registro de grabación activa (dependencia del motor). */
    private fun getRegistrySnapshot(result: MethodChannel.Result) {
        val provider = registrySnapshotProvider
        if (provider == null) {
            result.error(
                ERR_TEST_NOT_AVAILABLE,
                "El snapshot del registro aún no está cableado.",
                mapOf("reason" to "registry_provider_not_wired"),
            )
            return
        }
        result.success(provider())
    }

    /** Tamaño del `.tmp` activo (diseño base §8; proveedor real en 2-c/2-d). */
    private fun sampleActiveStagingSize(result: MethodChannel.Result) {
        val provider = stagingSizeProvider
        if (provider == null) {
            result.error(
                ERR_TEST_NOT_AVAILABLE,
                "El muestreo del .tmp activo aún no está cableado.",
                mapOf("reason" to "staging_size_provider_not_wired"),
            )
            return
        }
        val size = provider()
        result.success(
            mapOf(
                "tmpSizeBytes" to size,
                "active" to (size != null),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Errores
    // ------------------------------------------------------------------

    /**
     * Mapeo de excepciones a códigos del canal. Una
     * `IllegalArgumentException` de ruta fuera de la raíz (la lanza el
     * almacén con el código en el mensaje) viaja como
     * `test_path_outside_root`; el resto, como `test_error` genérico.
     */
    private fun mapError(e: Exception): Triple<String, String, Any?> = when {
        e is IllegalArgumentException &&
            e.message?.startsWith(TestDiagnosticsStore.PATH_OUTSIDE_ROOT_CODE) == true ->
            Triple(
                ERR_TEST_PATH_OUTSIDE_ROOT,
                e.message ?: "Ruta fuera de la raíz.",
                null,
            )
        else -> Triple(
            ERR_TEST_ERROR,
            "Error interno del canal de pruebas: ${e.javaClass.simpleName}.",
            mapOf("detail" to (e.message ?: "")),
        )
    }
}
