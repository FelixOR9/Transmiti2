package com.transmiti2.transmiti2.streaming.test

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Puntos de pausa de la reconciliación (Revisión 1 §4.2 + diseño base §5). */
enum class PausePoint {
    /** Antes del inventario (C2: la grabación nueva sí entra en el inventario). */
    P0,

    /** Entre inventario y bucle de grupos (C1: foto fija anterior a la pausa). */
    P1,

    /** Entre grupos (alternativa a P1). */
    P2,
}

/** Estado observable de la pausa en un punto (Revisión 2 §2.3). */
enum class PauseState {
    NONE,
    ARMED,
    CONSUMED,
    PAUSED,
    RELEASED,
    TIMED_OUT,
    CANCELLED,
}

/**
 * Intención de pausa persistida (`armed-pause.json`, versión 1;
 * Revisión 1 §2.3). De un solo uso y con caducidad de 10 minutos,
 * medida en dos relojes (Revisión 1 §2.5): el monotónico cubre el caso
 * normal y el wall cubre el reinicio del dispositivo (donde el
 * monotónico se resetea).
 */
data class ArmedPauseIntent(
    val version: Int,
    val testRunId: String,
    val sessionId: String,
    val point: PausePoint,
    val armedAtWallMs: Long,
    val armedAtMonotonicMs: Long,
    val expiresAtWallMs: Long,
) {
    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        "version" to version,
        "testRunId" to testRunId,
        "sessionId" to sessionId,
        "point" to point.name,
        "armedAtWallMs" to armedAtWallMs,
        "armedAtMonotonicMs" to armedAtMonotonicMs,
        "expiresAtWallMs" to expiresAtWallMs,
    )

    fun toJson(): String = TestJson.encode(toJsonMap())

    companion object {
        fun fromJson(text: String): ArmedPauseIntent? {
            val obj = TestJson.parseObject(text) ?: return null
            val version = obj.long("version")?.toInt() ?: return null
            val testRunId = obj.str("testRunId") ?: return null
            val sessionId = obj.str("sessionId") ?: return null
            val point = try {
                PausePoint.valueOf(obj.str("point") ?: return null)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val armedAtWallMs = obj.long("armedAtWallMs") ?: return null
            val armedAtMonotonicMs = obj.long("armedAtMonotonicMs") ?: return null
            val expiresAtWallMs = obj.long("expiresAtWallMs") ?: return null
            return ArmedPauseIntent(
                version = version,
                testRunId = testRunId,
                sessionId = sessionId,
                point = point,
                armedAtWallMs = armedAtWallMs,
                armedAtMonotonicMs = armedAtMonotonicMs,
                expiresAtWallMs = expiresAtWallMs,
            )
        }
    }
}

/** Intención ya consumida (archivo borrado, pausa aún no ejecutada). */
data class ConsumedIntent(
    val point: PausePoint,
    val testRunId: String,
    val sessionId: String,
    val armedAtWallMs: Long,
    val armedAtMonotonicMs: Long,
    val expiresAtWallMs: Long,
    /** `runId` de la pasada que consume (para `PAUSE_ENTERED`), si se conoce. */
    val runId: String? = null,
)

/**
 * Acceso al archivo de intención. Seam inyectable: la producción usa el
 * archivo real en `test-diagnostics/`; los tests usan un falso en
 * memoria (necesario para la prueba 1a-1, cuyo borrado se bloquea en un
 * latch). `delete()` retorna `true` si el archivo ya no existe después
 * del intento; `false` si sigue ahí (fallo de E/S → fail-closed).
 */
interface ArmedPauseFileAccess {
    fun read(): String?
    fun write(text: String)
    fun delete(): Boolean
}

/** Acceso real al archivo de intención (solo `java.io`). */
class FileArmedPauseAccess(private val file: File) : ArmedPauseFileAccess {
    override fun read(): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }

    override fun write(text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    override fun delete(): Boolean = try {
        if (!file.exists()) true else file.delete() || !file.exists()
    } catch (_: Exception) {
        false
    }
}

/**
 * Puntos de sincronización (diseño base §4.B + Revisiones 1–3).
 *
 * La pausa es una suspensión de corrutina, no un bloqueo de hilo ni de
 * mutex, y solo ocurre en P0/P1/P2, donde ningún mutex está tomado y
 * ningún archivo está abierto. La pausa en sí nunca se persiste; lo
 * único persistido es la intención de un solo uso con caducidad.
 *
 * Reglas que implementa este archivo (Revisión 3 §2.3):
 * - A: leer-validar → borrar → escribir `INTENT_CONSUMED` dentro de un
 *   único `withContext(Dispatchers.IO)`, sin punto de suspensión entre
 *   ambas operaciones.
 * - B: [awaitPauseIfArmed] es el ÚNICO punto de entrada que llama la
 *   reconciliación, con `ensureActive()` explícito entre consumo y
 *   suspensión.
 * - C: `PAUSE_CANCELLED` lleva `wasSuspended`; la aprobación exige
 *   además el resumen `DONE` de la pasada (regla del constructor del
 *   informe, no de este archivo).
 *
 * Con la instrumentación desactivada (`enabled() == false`),
 * [awaitPauseIfArmed] retorna `false` de inmediato: no lee el archivo,
 * no lo consume, no actúa (Revisión 2 §4).
 */
class TestSyncPoints(
    var enabled: () -> Boolean = { false },
    private val instrumentation: TestInstrumentation,
    private val clock: TestClock = SystemTestClock,
    private val intentAccess: ArmedPauseFileAccess,
    /** Timeout de pausa; 120 s en producción, corto e inyectado en tests. */
    val pauseTimeoutMs: Long = PAUSE_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val PAUSE_TIMEOUT_MS = 120_000L
        const val INTENT_TTL_MS = 600_000L
        const val INTENT_VERSION = 1
        const val ARMED_PAUSE_FILE_NAME = "armed-pause.json"
    }

    private val lock = Any()
    private val pendingDeferreds = mutableMapOf<PausePoint, CompletableDeferred<Unit>>()
    private val states = mutableMapOf<PausePoint, PauseState>()

    /** Estado observable de la pausa en un punto (`NONE` si nunca se armó). */
    fun pauseState(point: PausePoint): PauseState =
        synchronized(lock) { states[point] ?: PauseState.NONE }

    /** ¿Hay una pausa suspendida ahora mismo en ese punto? */
    fun isPaused(point: PausePoint): Boolean =
        synchronized(lock) { pendingDeferreds[point]?.let { !it.isCompleted } } ?: false

    /**
     * ÚNICO punto de entrada del gancho (una línea en P0 y una en P1 en
     * la reconciliación). Retorna `true` si la corrutina se pausó y fue
     * liberada (por reanudación o por timeout), `false` si no había
     * intención que consumir. Lanza `CancellationException` si el `Job`
     * se canceló antes o durante la pausa (no la traga: pertenece al
     * scope que cancela).
     */
    suspend fun awaitPauseIfArmed(point: PausePoint, runId: String? = null): Boolean {
        if (!enabled()) return false
        val consumed = tryConsumeIntent(point, runId) ?: return false
        // Punto de entrega explícito (corrección B): si el Job ya fue
        // cancelado durante el bloque de E/S, se lanza aquí, antes de
        // que se escriba PAUSE_ENTERED.
        currentCoroutineContext().ensureActive()
        return awaitPause(consumed)
    }

    /**
     * Lee y valida la intención y, si es válida, la borra y registra
     * `INTENT_CONSUMED`. Todo dentro de un ÚNICO `withContext(IO)`
     * (corrección A): dentro del bloque no hay punto de suspensión, así
     * que la cancelación solo se entrega al retornar, cuando borrar y
     * escribir ya están hechos.
     *
     * Retorna `null` (sin pausar) si: no hay intención, está corrupta
     * (se trata como ausente), es de otro punto, es de otra campaña, ha
     * caducado (evento `ARMED_PAUSE_EXPIRED` + borrado best-effort), no
     * hay sesión activa, o el borrado falló (`ARMED_PAUSE_CONSUME_FAILED`,
     * fail-closed).
     */
    suspend fun tryConsumeIntent(
        point: PausePoint,
        runId: String? = null,
    ): ConsumedIntent? = withContext(ioDispatcher) {
        val raw = intentAccess.read() ?: return@withContext null
        val intent = ArmedPauseIntent.fromJson(raw) ?: return@withContext null
        if (intent.version != INTENT_VERSION) return@withContext null
        if (intent.point != point) return@withContext null
        val activeTestRunId = instrumentation.currentTestRunId ?: return@withContext null
        if (intent.testRunId != activeTestRunId) return@withContext null
        if (isExpired(intent)) {
            // Borrado best-effort: la caducidad se deriva de comparar
            // relojes, así que el evento es `inferred` (Revisión 1 §3.3).
            runCatching { intentAccess.delete() }
            instrumentation.record(
                TestEventType.ARMED_PAUSE_EXPIRED,
                operation = "reconcile",
                detail = mapOf(
                    "point" to point.name,
                    "testRunId" to intent.testRunId,
                ),
                origin = TestEventOrigin.INFERRED,
            )
            return@withContext null
        }
        if (!intentAccess.delete()) {
            instrumentation.record(
                TestEventType.ARMED_PAUSE_CONSUME_FAILED,
                operation = "reconcile",
                detail = mapOf(
                    "point" to point.name,
                    "testRunId" to intent.testRunId,
                ),
            )
            return@withContext null
        }
        instrumentation.record(
            TestEventType.INTENT_CONSUMED,
            operation = "reconcile",
            detail = mapOf(
                "point" to point.name,
                "testRunId" to intent.testRunId,
                "consumedMonotonicMs" to clock.monotonicMs(),
            ),
        )
        synchronized(lock) { states[point] = PauseState.CONSUMED }
        ConsumedIntent(
            point = intent.point,
            testRunId = intent.testRunId,
            sessionId = intent.sessionId,
            armedAtWallMs = intent.armedAtWallMs,
            armedAtMonotonicMs = intent.armedAtMonotonicMs,
            expiresAtWallMs = intent.expiresAtWallMs,
            runId = runId,
        )
    }

    /** Caducidad por dos relojes (Revisión 1 §2.5): caduca si CUALQUIERA dice que caducó. */
    fun isExpired(intent: ArmedPauseIntent): Boolean {
        if (clock.wallMs() > intent.expiresAtWallMs) return true
        val nowMono = clock.monotonicMs()
        // Reinicio del dispositivo: el monotónico guardado deja de tener
        // sentido; solo vale el wall (ya comprobado arriba).
        if (nowMono < intent.armedAtMonotonicMs) return false
        return nowMono - intent.armedAtMonotonicMs > INTENT_TTL_MS
    }

    /**
     * Suspende la corrutina hasta `resume()` o hasta el timeout.
     * Escribe `PAUSE_ENTERED` inmediatamente antes de la ÚNICA llamada
     * de suspensión. Si el timeout expira, registra `PAUSE_TIMEOUT` y
     * retorna `false` (la pasada continúa). Si se cancela estando
     * suspendida, registra `PAUSE_CANCELLED` (con `wasSuspended`) y
     * re-lanza la cancelación.
     */
    suspend fun awaitPause(consumed: ConsumedIntent): Boolean {
        currentCoroutineContext().ensureActive()
        val deferred = CompletableDeferred<Unit>()
        synchronized(lock) {
            pendingDeferreds[consumed.point] = deferred
            states[consumed.point] = PauseState.PAUSED
        }
        val enteredAtMono = clock.monotonicMs()
        instrumentation.record(
            TestEventType.PAUSE_ENTERED,
            operation = "reconcile",
            detail = buildMap {
                put("point", consumed.point.name)
                put("testRunId", consumed.testRunId)
                if (consumed.runId != null) put("runId", consumed.runId)
                put("enteredMonotonicMs", enteredAtMono)
            },
        )
        // Segundo ensureActive: si el Job se canceló entre la escritura
        // de PAUSE_ENTERED y la suspensión, se lanza aquí y el
        // PAUSE_CANCELLED lleva wasSuspended=false (corrección C).
        var suspensionAttempted = false
        try {
            currentCoroutineContext().ensureActive()
            suspensionAttempted = true
            val resumed = withTimeoutOrNull(pauseTimeoutMs) { deferred.await() }
            if (resumed == null) {
                synchronized(lock) { states[consumed.point] = PauseState.TIMED_OUT }
                instrumentation.record(
                    TestEventType.PAUSE_TIMEOUT,
                    operation = "reconcile",
                    result = TestEventResult.TIMEOUT,
                    detail = mapOf(
                        "point" to consumed.point.name,
                        "testRunId" to consumed.testRunId,
                        "pausedDurationMs" to (clock.monotonicMs() - enteredAtMono),
                    ),
                )
                return false
            }
            synchronized(lock) { states[consumed.point] = PauseState.RELEASED }
            instrumentation.record(
                TestEventType.PAUSE_RELEASED,
                operation = "reconcile",
                detail = mapOf(
                    "point" to consumed.point.name,
                    "testRunId" to consumed.testRunId,
                    "pausedDurationMs" to (clock.monotonicMs() - enteredAtMono),
                ),
            )
            return true
        } catch (e: CancellationException) {
            synchronized(lock) { states[consumed.point] = PauseState.CANCELLED }
            // Escritura síncrona (en memoria + sink): no suspende, así
            // que llega aunque el Job esté cancelado. La persistencia
            // del evento depende del sink del cableado (grupo 2-c): su
            // scope debe sobrevivir a la cancelación de la reconciliación.
            instrumentation.record(
                TestEventType.PAUSE_CANCELLED,
                operation = "reconcile",
                result = TestEventResult.FAILED,
                detail = mapOf(
                    "point" to consumed.point.name,
                    "testRunId" to consumed.testRunId,
                    "pausedDurationMs" to (clock.monotonicMs() - enteredAtMono),
                ),
                wasSuspended = suspensionAttempted,
            )
            throw e
        } finally {
            synchronized(lock) {
                if (pendingDeferreds[consumed.point] === deferred) {
                    pendingDeferreds.remove(consumed.point)
                }
            }
        }
    }

    /**
     * Arma la pausa para [point] escribiendo la intención (una sola
     * intención a la vez; un armado nuevo sustituye al anterior y
     * registra `ARMED_PAUSE_REPLACED`). Exige sesión activa.
     */
    suspend fun arm(point: PausePoint): ArmedPauseIntent {
        val session = instrumentation.activeSession
            ?: throw IllegalStateException("No hay sesión de pruebas activa para armar una pausa.")
        val nowWall = clock.wallMs()
        val intent = ArmedPauseIntent(
            version = INTENT_VERSION,
            testRunId = session.testRunId,
            sessionId = session.sessionId,
            point = point,
            armedAtWallMs = nowWall,
            armedAtMonotonicMs = clock.monotonicMs(),
            expiresAtWallMs = nowWall + INTENT_TTL_MS,
        )
        val replaced = withContext(ioDispatcher) {
            val previousRaw = intentAccess.read()
            val previous = previousRaw?.let { ArmedPauseIntent.fromJson(it) }
            intentAccess.write(intent.toJson())
            previous
        }
        if (replaced != null) {
            instrumentation.record(
                TestEventType.ARMED_PAUSE_REPLACED,
                operation = "reconcile",
                detail = mapOf(
                    "point" to replaced.point.name,
                    "replacedTestRunId" to replaced.testRunId,
                    "testRunId" to intent.testRunId,
                ),
            )
        }
        instrumentation.record(
            TestEventType.PAUSE_ARMED,
            operation = "reconcile",
            detail = mapOf(
                "point" to point.name,
                "testRunId" to intent.testRunId,
                "sessionId" to intent.sessionId,
                "expiresAtWallMs" to intent.expiresAtWallMs,
            ),
        )
        synchronized(lock) { states[point] = PauseState.ARMED }
        return intent
    }

    /** Lee la intención sin consumirla (para adoptar la campaña al arrancar, grupo 2-c). */
    fun peekIntent(): ArmedPauseIntent? {
        val raw = try {
            intentAccess.read()
        } catch (_: Exception) {
            null
        } ?: return null
        return ArmedPauseIntent.fromJson(raw)
    }

    /** Borra la intención de la campaña actual (fin de sesión normal, Revisión 1 §2.4). */
    suspend fun disarm(): Boolean = withContext(ioDispatcher) {
        try {
            intentAccess.delete()
        } catch (_: Exception) {
            false
        }
    }

    /** Reanuda la pausa suspendida en [point]; `false` si no había ninguna. */
    fun resume(point: PausePoint): Boolean {
        val deferred = synchronized(lock) { pendingDeferreds[point] } ?: return false
        return deferred.complete(Unit)
    }

    /** Reanuda todas las pausas pendientes; retorna cuántas había. */
    fun resumeAll(): Int {
        val deferreds = synchronized(lock) { pendingDeferreds.values.toList() }
        var resumed = 0
        for (deferred in deferreds) {
            if (deferred.complete(Unit)) resumed++
        }
        return resumed
    }

    /**
     * Liberación segura al terminar la sesión: completa todos los
     * deferred pendientes para que ninguna pasada quede atascada en
     * `RUNNING` (diseño base §4.B).
     */
    fun endSession() {
        resumeAll()
        synchronized(lock) {
            pendingDeferreds.clear()
        }
    }
}
