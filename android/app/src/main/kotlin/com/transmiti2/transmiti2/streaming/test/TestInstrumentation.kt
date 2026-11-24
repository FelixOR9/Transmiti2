package com.transmiti2.transmiti2.streaming.test

import android.os.SystemClock
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Reloj inyectable para la infraestructura de pruebas (FASE 2, grupo 2-a).
 *
 * La producción usa [SystemTestClock] (`elapsedRealtime()` para duraciones
 * y orden, `currentTimeMillis()` para fecha legible). Los tests inyectan
 * un reloj falso y controlan el tiempo. Nunca se llama a
 * `SystemClock.elapsedRealtime()` directamente desde código que deba
 * probarse en JVM pura: `android.os.SystemClock` es un stub en los unit
 * tests y lanzaría `RuntimeException`.
 */
interface TestClock {
    /** Milisegundos monotónicos (base `elapsedRealtime()`): ordenar y medir. */
    fun monotonicMs(): Long

    /** Milisegundos wall (`currentTimeMillis()`): fecha legible, caducidad. */
    fun wallMs(): Long
}

/** Reloj real de producción. Solo se instancia en el cableado (grupo 2-c). */
object SystemTestClock : TestClock {
    override fun monotonicMs(): Long = SystemClock.elapsedRealtime()
    override fun wallMs(): Long = System.currentTimeMillis()
}

/** Origen de un evento: observado de verdad o derivado de una ausencia. */
enum class TestEventOrigin(val json: String) {
    OBSERVED("observed"),
    INFERRED("inferred"),
}

/** Resultado de un evento. */
enum class TestEventResult(val json: String) {
    OK("ok"),
    SKIPPED("skipped"),
    FAILED("failed"),
    TIMEOUT("timeout"),
    PENDING("pending"),
}

/**
 * Tipos de evento de la instrumentación (diseño base §4.A + Revisión 1
 * §2/§3 + Revisión 2 §2.3 + §8 del diseño base). `ERROR` es el comodín
 * para fallos sin tipo propio; `ARMED_PAUSE_REPLACED` se registra cuando
 * un armado nuevo sustituye a una intención anterior (Revisión 1 §2.4).
 */
enum class TestEventType {
    SESSION_STARTED,
    SESSION_ENDED,
    RECONCILIATION_STARTED,
    RECONCILIATION_PAUSED,
    RECONCILIATION_RESUMED,
    RECONCILIATION_TIMEOUT,
    RECONCILIATION_FINISHED,
    GROUP_RESERVED,
    GROUP_SKIPPED_ACTIVE,
    GROUP_RELEASED,
    GROUP_FAILED,
    CLAIM_GRANTED,
    CLAIM_REJECTED,
    /** Liberación del claim (diseño base §8.2; grupo 2-c): la vía va en `detail["via"]` (`failStart` | `stop`). */
    CLAIM_RELEASED,
    RECORDING_STARTED,
    RECORDING_STOPPED,
    RECORDING_START_REQUESTED,
    REGISTRY_SNAPSHOT,
    RECORDING_SAMPLE,
    SEGMENT_VERIFIED,
    SEGMENT_RENAMED,
    SEGMENT_PENDING,
    PAUSE_ARMED,
    ARMED_PAUSE_REPLACED,
    INTENT_CONSUMED,
    PAUSE_ENTERED,
    PAUSE_RELEASED,
    PAUSE_TIMEOUT,
    PAUSE_CANCELLED,
    ARMED_PAUSE_CONSUME_FAILED,
    ARMED_PAUSE_EXPIRED,
    PROCESS_INTERRUPTED,
    TEST_RUN_CONTINUED,
    /**
     * Auto-parada por error programada (J3-c, 3.1.4, plan §1.3 paso 6):
     * se emite en `scheduleErrorStop` ANTES de lanzar `stop()`, con el
     * disparador completo en `detail` (`ErrorTrigger.toMap()`: code,
     * message, stage, segmentIndex, exceptionClass, triggerSite,
     * timestampMs, freeBytes).
     */
    ERROR_STOP_TRIGGERED,
    /**
     * Parada del servicio en primer plano (J3-c, 3.1.4, plan §1.3
     * paso 6): la emite el motor en su único punto de parada del FGS,
     * con el origen en `detail["origin"]` (`error_stop` |
     * `normal_stop` | `camera_error` | `notification` |
     * `start_failed`; `detail["reason"]` solo cuando el camino lleva
     * un `reason` de `service_stopped`).
     */
    FGS_STOPPED,
    /**
     * Fallo al escribir la intención de pausa (`armed-pause.json`,
     * J3-c, 3.1.4): lo emite `TestSyncPoints.arm` cuando la escritura
     * falla (el error se relanza: el canal sigue respondiendo
     * `test_error`). `detail` lleva `point`, `testRunId` y
     * `exceptionClass`.
     */
    ARMED_PAUSE_WRITE_FAILED,
    ERROR,
}

/**
 * Un evento de instrumentación. `origin` es obligatorio y sin valor por
 * defecto (Revisión 1 §3.3): lo inferido nunca se presenta como observado
 * y nunca aprueba un criterio. `wasSuspended` solo tiene sentido en
 * `PAUSE_CANCELLED` (Revisión 3 §2.5): `true` si la cancelación llegó
 * estando suspendida, `false` si llegó en el intento de suspensión sin
 * haber suspendido nunca.
 */
data class TestEvent(
    val sessionId: String,
    val testRunId: String,
    val recordingId: String? = null,
    val operation: String = "",
    val event: TestEventType,
    val monotonicMs: Long,
    val wallMs: Long,
    val result: TestEventResult = TestEventResult.OK,
    val errorCode: String? = null,
    val durationMs: Long? = null,
    val detail: Map<String, Any?> = emptyMap(),
    val origin: TestEventOrigin = TestEventOrigin.OBSERVED,
    val wasSuspended: Boolean? = null,
) {
    /** Mapa JSON del evento (claves estables; lo usa el JSONL y el informe). */
    fun toJsonMap(): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>(
            "sessionId" to sessionId,
            "testRunId" to testRunId,
            "recordingId" to recordingId,
            "operation" to operation,
            "event" to event.name,
            "monotonicMs" to monotonicMs,
            "wallMs" to wallMs,
            "result" to result.json,
            "errorCode" to errorCode,
            "durationMs" to durationMs,
            "detail" to detail,
            "origin" to origin.json,
        )
        if (wasSuspended != null) map["wasSuspended"] = wasSuspended
        return map
    }

    companion object {
        /** Reconstruye un evento desde su mapa JSON; `null` si está corrupto. */
        fun fromJsonMap(obj: TestJson.JsonValue.Obj): TestEvent? {
            val sessionId = obj.str("sessionId") ?: return null
            val testRunId = obj.str("testRunId") ?: return null
            val eventName = obj.str("event") ?: return null
            val event = try {
                TestEventType.valueOf(eventName)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val monotonicMs = obj.long("monotonicMs") ?: return null
            val wallMs = obj.long("wallMs") ?: return null
            val result = when (obj.str("result")) {
                "skipped" -> TestEventResult.SKIPPED
                "failed" -> TestEventResult.FAILED
                "timeout" -> TestEventResult.TIMEOUT
                "pending" -> TestEventResult.PENDING
                else -> TestEventResult.OK
            }
            val origin = when (obj.str("origin")) {
                "inferred" -> TestEventOrigin.INFERRED
                else -> TestEventOrigin.OBSERVED
            }
            val detail = (obj.obj("detail")?.toAnyMap() ?: emptyMap())
            return TestEvent(
                sessionId = sessionId,
                testRunId = testRunId,
                recordingId = obj.str("recordingId"),
                operation = obj.str("operation") ?: "",
                event = event,
                monotonicMs = monotonicMs,
                wallMs = wallMs,
                result = result,
                errorCode = obj.str("errorCode"),
                durationMs = obj.long("durationMs"),
                detail = detail,
                origin = origin,
                wasSuspended = obj.bool("wasSuspended"),
            )
        }
    }
}

/** Sesión de pruebas activa: un proceso (`sessionId`) dentro de una campaña. */
data class TestSession(
    val sessionId: String,
    val testRunId: String,
)

/**
 * JSON manual mínimo (escritor + lector tolerante), al estilo de
 * `RecordingFileStore.toJson` / `JsonParser`.
 *
 * Decisión (verificada en el Paso 1): `org.json` es Android y es un stub
 * que rompe los tests JVM puros; no hay `kotlinx.serialization`, Gson ni
 * ninguna otra librería JSON en `build.gradle.kts` (solo JUnit en tests).
 * Añadir una dependencia JSON nueva solo para esto no se justifica: el
 * uso es un JSONL de líneas planas y un archivo de intención diminuto.
 */
/** JSON manual mínimo (público dentro del paquete de pruebas para que
 * los `fromJson*` públicos puedan exponer sus tipos). */
object TestJson {

    fun escape(value: String): String {
        val out = StringBuilder()
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) {
                    out.append("\\u%04x".format(c.code))
                } else {
                    out.append(c)
                }
            }
        }
        return out.toString()
    }

    /** Codifica `null`, `Boolean`, `Number`, `String`, `Map` y `List`. */
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> value.toString()
        is String -> "\"${escape(value)}\""
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${escape(k.toString())}\":${encode(v)}"
        }
        is List<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> "\"${escape(value.toString())}\""
    }

    /** Valor JSON mínimo para el lector tolerante. */
    sealed interface JsonValue {
        data class Obj(val map: Map<String, JsonValue>) : JsonValue {
            fun str(key: String): String? = (map[key] as? Str)?.value
            fun long(key: String): Long? {
                val raw = (map[key] as? Num)?.raw ?: return null
                return raw.toLongOrNull()
                    ?: raw.toDoubleOrNull()?.takeIf { it == it.toLong().toDouble() }?.toLong()
            }
            fun bool(key: String): Boolean? = (map[key] as? Bool)?.value
            fun obj(key: String): Obj? = map[key] as? Obj
            fun arr(key: String): Arr? = map[key] as? Arr

            /** Convierte el objeto a un mapa de valores Kotlin planos. */
            fun toAnyMap(): Map<String, Any?> =
                map.mapValues { (_, v) -> v.toAny() }
        }
        data class Arr(val items: List<JsonValue>) : JsonValue
        data class Str(val value: String) : JsonValue
        data class Num(val raw: String) : JsonValue
        data class Bool(val value: Boolean) : JsonValue
        data object Null : JsonValue

        fun toAny(): Any? = when (this) {
            is Obj -> toAnyMap()
            is Arr -> items.map { it.toAny() }
            is Str -> value
            is Num -> raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
            is Bool -> value
            is Null -> null
        }
    }

    private class ParseException(message: String) : Exception(message)

    private class Parser(private val text: String) {
        private var pos = 0

        fun parse(): JsonValue {
            skipWs()
            val value = parseValue()
            skipWs()
            if (pos != text.length) throw ParseException("contenido tras el valor raíz")
            return value
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun parseValue(): JsonValue {
            if (pos >= text.length) throw ParseException("fin inesperado")
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw ParseException("carácter inesperado '$c'")
            }
        }

        private fun parseLiteral(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, pos)) throw ParseException("literal '$word' esperado")
            pos += word.length
            return value
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            while (peek()?.let { it.isDigit() || it == '.' || it == 'e' || it == 'E' || it == '+' || it == '-' } == true) pos++
            if (pos == start) throw ParseException("número esperado")
            return JsonValue.Num(text.substring(start, pos))
        }

        private fun parseString(): String {
            pos++ // '"'
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) throw ParseException("cadena sin cerrar")
                val c = text[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= text.length) throw ParseException("escape incompleto")
                        when (val e = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw ParseException("escape \\u incompleto")
                                val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw ParseException("escape \\u inválido")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> throw ParseException("escape desconocido '\\$e'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun parseObject(): JsonValue.Obj {
            pos++ // '{'
            val map = mutableMapOf<String, JsonValue>()
            skipWs()
            if (peek() == '}') {
                pos++
                return JsonValue.Obj(map)
            }
            while (true) {
                skipWs()
                if (peek() != '"') throw ParseException("clave de objeto esperada")
                val key = parseString()
                skipWs()
                if (peek() != ':') throw ParseException("':' esperado")
                pos++
                skipWs()
                map[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(map)
                    }
                    else -> throw ParseException("',' o '}' esperados")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            pos++ // '['
            val items = mutableListOf<JsonValue>()
            skipWs()
            if (peek() == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWs()
                items += parseValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }
                    else -> throw ParseException("',' o ']' esperados")
                }
            }
        }
    }

    /** Parsea un documento JSON; `null` si está corrupto (nunca lanza). */
    fun parse(text: String): JsonValue? = try {
        Parser(text).parse()
    } catch (_: Exception) {
        null
    }

    /** Parsea un objeto JSON; `null` si no es un objeto válido. */
    fun parseObject(text: String): JsonValue.Obj? = parse(text) as? JsonValue.Obj
}

/**
 * Registro de eventos en memoria (diseño base §4.A).
 *
 * Anillo acotado de 500 eventos: al llenarse se descartan los más
 * antiguos y [droppedCount] lo refleja (nunca crece sin límite). Sin
 * sesión activa o con la instrumentación desactivada, [record] es un
 * no-op que retorna `null` (coste: una comprobación de booleano).
 *
 * El [sink] es el consumidor inyectable (en producción, el almacén de
 * diagnósticos; en tests, un capturador). Un sink que lance nunca rompe
 * el flujo que registra: se traga la excepción y se registra en el log
 * nativo. La activación llega por constructor (`enabled`): NO se
 * referencia `BuildConfig` en este grupo; la puerta de compilación se
 * añade en el grupo 2-b y el cableado pasará
 * `BuildConfig.TRANSMITI2_TEST_MODE` en el grupo 2-c.
 */
class TestInstrumentation(
    var enabled: () -> Boolean = { false },
    val clock: TestClock = SystemTestClock,
    var sink: (TestEvent) -> Unit = {},
    /** Versión de la app que se escribe en la cabecera SESSION_STARTED. */
    val appVersion: String = "unknown",
) {
    companion object {
        const val MAX_RING_EVENTS = 500
        const val MAX_TEST_RUN_ID_ATTEMPTS = 10
    }

    private val lock = Any()
    private val ring = ArrayDeque<TestEvent>()

    /** Eventos descartados por llenarse el anillo. */
    var droppedCount: Long = 0L
        private set

    /** Sesión activa en memoria, o `null` si no hay ninguna. */
    var activeSession: TestSession? = null
        private set

    val sessionActive: Boolean get() = activeSession != null
    val currentSessionId: String? get() = activeSession?.sessionId
    val currentTestRunId: String? get() = activeSession?.testRunId

    /**
     * Genera `run_<yyyyMMdd_HHmmss>_<4 hex minúsculas>` (espejo de
     * `newRecordingId`, Revisión 1 §2.2). `isTaken` comprueba la unicidad
     * contra las cabeceras de sesión existentes; hasta 10 intentos y,
     * agotados, lanza (fallo cerrado: nunca se reutiliza un id).
     */
    suspend fun generateTestRunId(
        isTaken: suspend (String) -> Boolean = { false },
        nowWallMs: Long = clock.wallMs(),
        suffixSupplier: () -> String = {
            "%04x".format(SecureRandom().nextInt(0x10000))
        },
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date(nowWallMs))
        repeat(MAX_TEST_RUN_ID_ATTEMPTS) {
            val id = "run_${stamp}_${suffixSupplier()}"
            if (!isTaken(id)) return id
        }
        throw IllegalStateException(
            "No se pudo generar un testRunId libre tras $MAX_TEST_RUN_ID_ATTEMPTS intentos.",
        )
    }

    /**
     * Crea una sesión nueva (ids) sin activarla ni emitir nada: el
     * almacén crea el archivo primero (`beginSession`) y solo después se
     * activa con [startSession], para que la cabecera `SESSION_STARTED`
     * no llegue antes que el archivo. `adoptTestRunId` continúa una
     * campaña existente (Revisión 1 §2.6); sin él se genera uno nuevo.
     */
    suspend fun createSession(
        adoptTestRunId: String? = null,
        isTestRunIdTaken: suspend (String) -> Boolean = { false },
    ): TestSession {
        val testRunId = adoptTestRunId ?: generateTestRunId(isTestRunIdTaken)
        return TestSession(sessionId = UUID.randomUUID().toString(), testRunId = testRunId)
    }

    /**
     * Activa la sesión y emite `SESSION_STARTED`. Falla cerrado si ya
     * hay una sesión activa (nunca se mezclan dos sesiones en memoria).
     */
    fun startSession(session: TestSession): TestSession {
        synchronized(lock) {
            check(activeSession == null) {
                "Ya hay una sesión de pruebas activa (${activeSession?.sessionId})."
            }
            activeSession = session
        }
        record(
            TestEventType.SESSION_STARTED,
            operation = "session",
            detail = mapOf("appVersion" to appVersion),
        )
        return session
    }

    /** Emite `SESSION_ENDED` y desactiva la sesión; `null` si no había. */
    fun endSession(verdict: String? = null): TestSession? {
        val session = synchronized(lock) { activeSession } ?: return null
        record(
            TestEventType.SESSION_ENDED,
            operation = "session",
            detail = if (verdict != null) mapOf("verdict" to verdict) else emptyMap(),
        )
        synchronized(lock) {
            if (activeSession == session) activeSession = null
        }
        return session
    }

    /**
     * Registra `TEST_RUN_CONTINUED` (J3-c, 3.1.4): la sesión activa
     * continúa una campaña existente (`adoptedTestRunId`), tras haber
     * arrancado con `startSession`. Es el emisor que faltaba: el tipo
     * estaba declarado pero nada lo emitía (hueco de trazabilidad de
     * la investigación F7b/P0). Lo llaman los dos caminos de adopción
     * (`TestChannelHandler.startTestSession` con `adoptTestRunId` y
     * `adoptCampaignIfArmed`); una sesión nueva sin adopción nunca lo
     * emite. `previousSessionId` es la sesión anterior interrumpida
     * de la misma campaña, si la hay. No-op sin sesión activa o con
     * la instrumentación desactivada (como `record`).
     */
    fun recordTestRunContinued(
        adoptedTestRunId: String,
        previousSessionId: String? = null,
    ): TestEvent? = record(
        TestEventType.TEST_RUN_CONTINUED,
        operation = "session",
        detail = buildMap {
            put("adoptedTestRunId", adoptedTestRunId)
            if (previousSessionId != null) {
                put("previousSessionId", previousSessionId)
            }
        },
    )

    /**
     * Registra un evento con los relojes del [clock]. No-op (retorna
     * `null`) si la instrumentación está desactivada o no hay sesión
     * activa. Nunca lanza hacia el llamador.
     */
    fun record(
        event: TestEventType,
        operation: String = "",
        recordingId: String? = null,
        result: TestEventResult = TestEventResult.OK,
        errorCode: String? = null,
        durationMs: Long? = null,
        detail: Map<String, Any?> = emptyMap(),
        origin: TestEventOrigin = TestEventOrigin.OBSERVED,
        wasSuspended: Boolean? = null,
    ): TestEvent? {
        val session = synchronized(lock) { activeSession } ?: return null
        if (!enabled()) return null
        val testEvent = TestEvent(
            sessionId = session.sessionId,
            testRunId = session.testRunId,
            recordingId = recordingId,
            operation = operation,
            event = event,
            monotonicMs = clock.monotonicMs(),
            wallMs = clock.wallMs(),
            result = result,
            errorCode = errorCode,
            durationMs = durationMs,
            detail = detail,
            origin = origin,
            wasSuspended = wasSuspended,
        )
        synchronized(lock) {
            if (ring.size >= MAX_RING_EVENTS) {
                ring.removeFirst()
                droppedCount++
            }
            ring.addLast(testEvent)
        }
        try {
            sink(testEvent)
        } catch (e: Exception) {
            // El sink nunca rompe el flujo que registra.
            Transmiti2Logger.e("TestInstrumentation: el sink lanzó ${e.javaClass.simpleName}")
        }
        return testEvent
    }

    /** Copia ordenada de los eventos del anillo (los más antiguos primero). */
    fun events(): List<TestEvent> = synchronized(lock) { ring.toList() }

    /** Eventos de una sesión concreta (nunca se mezclan sesiones, test nº 3). */
    fun eventsOf(sessionId: String): List<TestEvent> =
        synchronized(lock) { ring.filter { it.sessionId == sessionId } }
}

/**
 * Fachada que el flujo de producción llama (diseño base §4.A): un solo
 * punto de contacto con la instrumentación. La implementación no-op por
 * defecto ([TestHooks.NO_OP]) es lo que usa el flujo normal hasta que el
 * cableado del grupo 2-c instala la real.
 */
interface TestHooks {
    fun record(
        event: TestEventType,
        operation: String = "",
        recordingId: String? = null,
        result: TestEventResult = TestEventResult.OK,
        errorCode: String? = null,
        durationMs: Long? = null,
        detail: Map<String, Any?> = emptyMap(),
        origin: TestEventOrigin = TestEventOrigin.OBSERVED,
        wasSuspended: Boolean? = null,
    ): TestEvent?

    suspend fun awaitPauseIfArmed(point: PausePoint, runId: String? = null): Boolean
    fun resumePause(point: PausePoint): Boolean
    val sessionActive: Boolean

    companion object {
        val NO_OP: TestHooks = NoOpTestHooks
    }
}

private object NoOpTestHooks : TestHooks {
    override fun record(
        event: TestEventType,
        operation: String,
        recordingId: String?,
        result: TestEventResult,
        errorCode: String?,
        durationMs: Long?,
        detail: Map<String, Any?>,
        origin: TestEventOrigin,
        wasSuspended: Boolean?,
    ): TestEvent? = null

    override suspend fun awaitPauseIfArmed(point: PausePoint, runId: String?): Boolean = false
    override fun resumePause(point: PausePoint): Boolean = false
    override val sessionActive: Boolean = false
}

/** Fachada real: delega en la instrumentación y en los puntos de pausa. */
class DefaultTestHooks(
    private val instrumentation: TestInstrumentation,
    private val syncPoints: TestSyncPoints,
) : TestHooks {
    override fun record(
        event: TestEventType,
        operation: String,
        recordingId: String?,
        result: TestEventResult,
        errorCode: String?,
        durationMs: Long?,
        detail: Map<String, Any?>,
        origin: TestEventOrigin,
        wasSuspended: Boolean?,
    ): TestEvent? = instrumentation.record(
        event = event,
        operation = operation,
        recordingId = recordingId,
        result = result,
        errorCode = errorCode,
        durationMs = durationMs,
        detail = detail,
        origin = origin,
        wasSuspended = wasSuspended,
    )

    override suspend fun awaitPauseIfArmed(point: PausePoint, runId: String?): Boolean =
        syncPoints.awaitPauseIfArmed(point, runId)

    override fun resumePause(point: PausePoint): Boolean = syncPoints.resume(point)
    override val sessionActive: Boolean get() = instrumentation.sessionActive
}
