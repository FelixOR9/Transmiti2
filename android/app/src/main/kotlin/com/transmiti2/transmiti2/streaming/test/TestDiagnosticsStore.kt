package com.transmiti2.transmiti2.streaming.test

import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Entrada de un intento bloqueado de iniciar sesión (`blocked-starts.jsonl`). */
data class TestBlockedStart(
    val testRunId: String,
    val wallMs: Long,
    val monotonicMs: Long,
    val reason: String,
) {
    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        "testRunId" to testRunId,
        "wallMs" to wallMs,
        "monotonicMs" to monotonicMs,
        "reason" to reason,
    )

    companion object {
        fun fromJson(obj: TestJson.JsonValue.Obj): TestBlockedStart? {
            val testRunId = obj.str("testRunId") ?: return null
            val wallMs = obj.long("wallMs") ?: return null
            val monotonicMs = obj.long("monotonicMs") ?: return null
            val reason = obj.str("reason") ?: return null
            return TestBlockedStart(testRunId, wallMs, monotonicMs, reason)
        }
    }
}

/** Metadatos de una sesión persistida (para la lista de la pantalla de pruebas). */
data class TestSessionInfo(
    val sessionId: String,
    val testRunId: String?,
    val startedAtWallMs: Long?,
    val sizeBytes: Long,
    /** Veredicto si se conoce (del informe exportado o calculado); `null` si no. */
    val verdict: String?,
)

/** Estado de almacenamiento de pruebas (tope de 10 sesiones, Revisión 1 §6). */
data class TestStorageState(
    val sessionCount: Int,
    val limit: Int,
    val full: Boolean,
)

/** Lectura tolerante de una sesión: líneas corruptas se saltan y se cuentan. */
data class TestSessionRead(
    val sessionId: String,
    val events: List<TestEvent>,
    val corruptLines: Int,
)

sealed interface BeginSessionResult {
    data class Started(
        val session: TestSession,
        /** Sesiones anteriores sin `SESSION_ENDED` detectadas al arrancar. */
        val interruptedPrevious: List<InterruptedSessionInfo>,
    ) : BeginSessionResult

    data class Blocked(val reason: String) : BeginSessionResult
}

/** Una sesión anterior sin cierre, con su `PROCESS_INTERRUPTED` ya registrado. */
data class InterruptedSessionInfo(
    val previousSessionId: String,
    val previousTestRunId: String?,
    val lastObservedEvent: String?,
    /** Dónde quedó el evento: en el archivo anterior o en la sesión nueva. */
    val recordedInNewSession: Boolean,
    val recordReason: String?,
)

sealed interface DeleteSessionResult {
    data class Deleted(val sessionId: String) : DeleteSessionResult
    data object ConfirmationRequired : DeleteSessionResult
    data class Failed(val sessionId: String, val reason: String) : DeleteSessionResult
}

sealed interface ExportSessionResult {
    data class Exported(
        val sessionId: String,
        val jsonFile: File,
        val txtFile: File,
    ) : ExportSessionResult

    data class SessionNotFound(val sessionId: String) : ExportSessionResult
}

/**
 * Persistencia de diagnósticos (diseño base §4.C + Revisión 1 §6 +
 * Revisión 2 §3 + Revisión 3 §3/§4).
 *
 * - Raíz inyectada por constructor (un `File`, nunca `filesDir` directo):
 *   el almacén solo opera dentro de esa raíz y TODAS las operaciones
 *   construyen rutas únicamente a través de [resolveInsideRoot].
 * - Un archivo JSONL append-only por sesión: `<sessionId>.jsonl`, con
 *   cabecera `SESSION_STARTED` y pie `SESSION_ENDED` (los emite la
 *   instrumentación; este almacén solo los persiste).
 * - Tope de 1 MB por archivo con línea final `LOG_TRUNCATED` (sin borrar
 *   el principio); tope de 10 sesiones con bloqueo `test_storage_full`
 *   (SIN borrado automático del más antiguo, Revisión 1 §6).
 * - `admin.jsonl` (tope 100 KB, línea `ADMIN_TRUNCATED`) registra los
 *   borrados explícitos; `blocked-starts.jsonl` registra los intentos
 *   bloqueados. Ninguno de los dos cuenta para el tope de 10.
 * - La escritura nunca lanza hacia el flujo de producción: un fallo de
 *   E/S se registra en el log nativo y el evento se pierde, pero la
 *   grabación/reconciliación no falla.
 * - Este almacén NUNCA abre `transmiti2.db` ni lista `recordings/`: solo
 *   toca su propia raíz. La exportación escribe solo en el `exportRoot`
 *   inyectado, nunca dentro del árbol de datos (Revisión 3 §3.2).
 */
class TestDiagnosticsStore(
    root: File,
    private val clock: TestClock = SystemTestClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxSessions: Int = MAX_SESSIONS,
    private val maxSessionBytes: Long = MAX_SESSION_BYTES,
    private val maxAdminBytes: Long = MAX_ADMIN_BYTES,
    private val reportBuilder: TestReportBuilder = TestReportBuilder(),
) {
    companion object {
        const val MAX_SESSIONS = 10
        const val MAX_SESSION_BYTES = 1_048_576L
        const val MAX_ADMIN_BYTES = 102_400L
        const val BLOCKED_STARTS_FILE = "blocked-starts.jsonl"
        const val ADMIN_LOG_FILE = "admin.jsonl"
        const val STORAGE_FULL_REASON = "test_storage_full"
        const val PATH_OUTSIDE_ROOT_CODE = "test_path_outside_root"
        const val SESSION_FILE_SUFFIX = ".jsonl"
        const val REPORT_JSON_SUFFIX = "-report.json"
        const val REPORT_TXT_SUFFIX = "-report.txt"

        /**
         * Regla canónica de contención (Revisión 3 §4.1): el objetivo es
         * la propia raíz o empieza por la raíz canónica MÁS el separador.
         * Un `startsWith` desnudo aceptaría hermanos de mismo prefijo
         * (`test-diagnostics-evil/`, `test-diagnostics2/`).
         */
        fun isWithinRoot(canonicalRoot: File, canonicalTarget: File): Boolean =
            canonicalTarget == canonicalRoot ||
                canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
    }

    /** Raíz canónica, guardada en el constructor (Revisión 3 §4.3). */
    private val canonicalRoot: File = root.canonicalFile

    private val writeMutex = Mutex()
    private val truncatedSessions = mutableSetOf<String>()

    /**
     * Valida que [relativePath] resuelva dentro de la raíz del almacén.
     * Lanza `IllegalArgumentException` (código de canal
     * `test_path_outside_root`) si la ruta canónica no está dentro de la
     * raíz canónica. Rechaza absolutos ANTES de resolver (un absoluto
     * ignoraría el padre en `File(parent, child)`).
     */
    fun resolveInsideRoot(relativePath: String): File {
        if (File(relativePath).isAbsolute) {
            throw IllegalArgumentException(
                "$PATH_OUTSIDE_ROOT_CODE: ruta absoluta no permitida: $relativePath",
            )
        }
        val target = File(canonicalRoot, relativePath).canonicalFile
        if (!isWithinRoot(canonicalRoot, target)) {
            throw IllegalArgumentException(
                "$PATH_OUTSIDE_ROOT_CODE: $relativePath resuelve fuera de la raíz",
            )
        }
        return target
    }

    /** Sink de eventos para la instrumentación (grupo 2-c). */
    fun eventSink(scope: CoroutineScope): (TestEvent) -> Unit {
        // Un solo hilo de E/S: los appends conservan el orden de llegada
        // (la cabecera SESSION_STARTED siempre antes que los demás).
        val serialIo = ioDispatcher.limitedParallelism(1)
        return { event ->
            scope.launch(serialIo) { appendEvent(event) }
        }
    }

    // ------------------------------------------------------------------
    // Sesiones
    // ------------------------------------------------------------------

    /**
     * Crea el archivo de la sesión (`<sessionId>.jsonl`). Si ya hay 10
     * sesiones, NO arranca: registra el intento en `blocked-starts.jsonl`
     * y retorna [BeginSessionResult.Blocked] con `test_storage_full`, sin
     * crear ningún archivo parcial. Si el archivo ya existiera, falla
     * cerrado (nunca se sobrescribe un log).
     *
     * Antes de crear el archivo propio, detecta sesiones anteriores sin
     * `SESSION_ENDED` y escribe `PROCESS_INTERRUPTED` en su archivo (o
     * en el nuevo con `recordedInNewSession: true` si no se puede).
     */
    suspend fun beginSession(session: TestSession): BeginSessionResult =
        withContext(ioDispatcher) {
            writeMutex.withLock {
                ensureRootLocked()
                val existing = sessionFilesLocked()
                if (existing.size >= maxSessions) {
                    appendBlockedStartLocked(session.testRunId, STORAGE_FULL_REASON)
                    return@withContext BeginSessionResult.Blocked(STORAGE_FULL_REASON)
                }
                val file = resolveInsideRoot(session.sessionId + SESSION_FILE_SUFFIX)
                if (file.exists()) {
                    return@withContext BeginSessionResult.Blocked("session_file_exists")
                }
                file.createNewFile()
                val interrupted = detectAndRecordInterruptionsLocked(session, existing)
                BeginSessionResult.Started(session, interrupted)
            }
        }

    /** ¿Alguna cabecera de sesión existente lleva este `testRunId`? */
    suspend fun hasTestRunId(testRunId: String): Boolean =
        withContext(ioDispatcher) {
            writeMutex.withLock {
                sessionFilesLocked().any { file ->
                    readHeaderTestRunIdLocked(file) == testRunId
                }
            }
        }

    /**
     * Añade un evento al JSONL de su sesión. Si el archivo no existe, lo
     * crea (la sesión se creó con [beginSession] en el flujo normal; esto
     * solo tolera un orden distinto). Al alcanzar el tope de 1 MB se deja
     * de añadir con una única línea final `LOG_TRUNCATED`.
     */
    suspend fun appendEvent(event: TestEvent) {
        withContext(ioDispatcher) {
            writeMutex.withLock {
                try {
                    val file = resolveInsideRoot(event.sessionId + SESSION_FILE_SUFFIX)
                    appendEventLocked(file, event)
                } catch (e: Exception) {
                    // La persistencia nunca tumba el flujo de producción.
                    Transmiti2Logger.e(
                        "TestDiagnosticsStore: no se pudo persistir ${event.event}: " +
                            e.javaClass.simpleName,
                    )
                }
            }
        }
    }

    /** Lectura tolerante línea a línea de una sesión (nunca lanza). */
    suspend fun readSession(sessionId: String): TestSessionRead =
        withContext(ioDispatcher) {
            writeMutex.withLock {
                val file = resolveInsideRoot(sessionId + SESSION_FILE_SUFFIX)
                readSessionFileLocked(file, sessionId)
            }
        }

    /** Sesiones persistidas con metadatos (fecha, `testRunId`, tamaño, veredicto). */
    suspend fun listSessions(): List<TestSessionInfo> =
        withContext(ioDispatcher) {
            writeMutex.withLock {
                sessionFilesLocked()
                    .sortedByDescending { it.lastModified() }
                    .map { file ->
                        val sessionId = file.name.removeSuffix(SESSION_FILE_SUFFIX)
                        val header = readFirstEventLocked(file)
                        TestSessionInfo(
                            sessionId = sessionId,
                            testRunId = header?.testRunId,
                            startedAtWallMs = header?.wallMs,
                            sizeBytes = file.length(),
                            verdict = readSessionVerdictLocked(sessionId, file),
                        )
                    }
            }
        }

    /** Número de sesiones, límite y si está lleno. */
    suspend fun storageState(): TestStorageState =
        withContext(ioDispatcher) {
            writeMutex.withLock {
                val count = sessionFilesLocked().size
                TestStorageState(count, maxSessions, count >= maxSessions)
            }
        }

    /**
     * Elimina una sesión y sus informes. Sin `confirmed: true` responde
     * [DeleteSessionResult.ConfirmationRequired] y NO borra nada (ni
     * escribe en `admin.jsonl`). Con confirmación, borra el JSONL y sus
     * informes y anota `SESSION_DELETED` en `admin.jsonl`.
     */
    suspend fun deleteSession(
        sessionId: String,
        confirmed: Boolean,
    ): DeleteSessionResult {
        if (!confirmed) return DeleteSessionResult.ConfirmationRequired
        return withContext(ioDispatcher) {
            writeMutex.withLock {
                val file = resolveInsideRoot(sessionId + SESSION_FILE_SUFFIX)
                val sizeBytes = if (file.exists()) file.length() else 0L
                val testRunId = readHeaderTestRunIdLocked(file)
                val removed = !file.exists() || file.delete()
                if (!removed) {
                    return@withContext DeleteSessionResult.Failed(
                        sessionId,
                        "delete_failed",
                    )
                }
                runCatching { resolveInsideRoot(sessionId + REPORT_JSON_SUFFIX).delete() }
                runCatching { resolveInsideRoot(sessionId + REPORT_TXT_SUFFIX).delete() }
                appendAdminLocked(
                    mapOf(
                        "event" to "SESSION_DELETED",
                        "sessionId" to sessionId,
                        "testRunId" to testRunId,
                        "sizeBytes" to sizeBytes,
                        "wallMs" to clock.wallMs(),
                        "monotonicMs" to clock.monotonicMs(),
                        "origin" to "observed",
                        "deletedBy" to "user",
                    ),
                )
                DeleteSessionResult.Deleted(sessionId)
            }
        }
    }

    // ------------------------------------------------------------------
    // Exportación (solo lectura respecto a grabaciones y BD)
    // ------------------------------------------------------------------

    /**
     * Exporta el informe de una sesión a [exportRoot] (inyectado, nunca
     * dentro del árbol de datos: se rechaza un `exportRoot` dentro de la
     * raíz del almacén). Escribe `<sessionId>-report.json` y
     * `<sessionId>-report.txt` en [exportRoot]. No modifica ningún archivo
     * de grabación, segmento, sidecar ni la base de datos (prueba 5a).
     */
    suspend fun exportSession(
        sessionId: String,
        exportRoot: File,
    ): ExportSessionResult {
        val read = readSession(sessionId)
        val fileExists = withContext(ioDispatcher) {
            resolveInsideRoot(sessionId + SESSION_FILE_SUFFIX).exists()
        }
        if (!fileExists) return ExportSessionResult.SessionNotFound(sessionId)
        val report = reportBuilder.buildSessionReport(
            sessionId = sessionId,
            testRunId = read.events.firstOrNull()?.testRunId,
            events = read.events,
            corruptLines = read.corruptLines,
        )
        val exportDir = exportRoot.canonicalFile
        if (isWithinRoot(canonicalRoot, exportDir)) {
            throw IllegalArgumentException(
                "$PATH_OUTSIDE_ROOT_CODE: exportRoot dentro del árbol de datos",
            )
        }
        return withContext(ioDispatcher) {
            exportDir.mkdirs()
            val jsonTarget = File(exportDir, sessionId + REPORT_JSON_SUFFIX).canonicalFile
            val txtTarget = File(exportDir, sessionId + REPORT_TXT_SUFFIX).canonicalFile
            if (!isWithinRoot(exportDir, jsonTarget) || !isWithinRoot(exportDir, txtTarget)) {
                throw IllegalArgumentException(
                    "$PATH_OUTSIDE_ROOT_CODE: destino de exportación fuera de exportRoot",
                )
            }
            jsonTarget.writeText(report.json, Charsets.UTF_8)
            txtTarget.writeText(report.text, Charsets.UTF_8)
            ExportSessionResult.Exported(sessionId, jsonTarget, txtTarget)
        }
    }

    /** Exporta todas las sesiones, un par (`.json` + `.txt`) por sesión, sin mezclar. */
    suspend fun exportAll(exportRoot: File): List<ExportSessionResult> {
        val sessions = listSessions()
        return sessions.map { exportSession(it.sessionId, exportRoot) }
    }

    /** Intentos bloqueados registrados (para agregar la campaña). */
    suspend fun readBlockedStarts(): List<TestBlockedStart> =
        withContext(ioDispatcher) {
            writeMutex.withLock {
                val file = resolveInsideRoot(BLOCKED_STARTS_FILE)
                if (!file.isFile) return@withContext emptyList()
                file.readLines(Charsets.UTF_8)
                    .mapNotNull { line ->
                        TestJson.parseObject(line)?.let { TestBlockedStart.fromJson(it) }
                    }
            }
        }

    /** Líneas crudas de `admin.jsonl` (auditoría de borrados). */
    suspend fun readAdminLines(): List<String> =
        withContext(ioDispatcher) {
            writeMutex.withLock {
                val file = resolveInsideRoot(ADMIN_LOG_FILE)
                if (!file.isFile) emptyList() else file.readLines(Charsets.UTF_8)
            }
        }

    // ------------------------------------------------------------------
    // Internos (se asume el mutex tomado salvo que se indique)
    // ------------------------------------------------------------------

    private fun ensureRootLocked() {
        if (!canonicalRoot.exists()) canonicalRoot.mkdirs()
    }

    /** Archivos `<sessionId>.jsonl` directos de la raíz (sin informes ni admin). */
    private fun sessionFilesLocked(): List<File> =
        canonicalRoot.listFiles()
            ?.filter {
                it.isFile && it.name.endsWith(SESSION_FILE_SUFFIX) &&
                    // Los logs administrativos también terminan en .jsonl
                    // pero NO son sesiones: no cuentan para el tope de 10.
                    it.name != BLOCKED_STARTS_FILE && it.name != ADMIN_LOG_FILE
            }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun sessionFileName(sessionId: String): String = sessionId + SESSION_FILE_SUFFIX

    private fun appendLineLocked(file: File, line: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { stream ->
            stream.write((line + "\n").toByteArray(Charsets.UTF_8))
            stream.flush()
        }
    }

    private fun appendEventLocked(file: File, event: TestEvent) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        if (file.length() >= maxSessionBytes) {
            if (truncatedSessions.add(file.name)) {
                val truncated = mapOf(
                    "event" to "LOG_TRUNCATED",
                    "sessionId" to event.sessionId,
                    "testRunId" to event.testRunId,
                    "wallMs" to clock.wallMs(),
                    "monotonicMs" to clock.monotonicMs(),
                    "origin" to "observed",
                    "detail" to mapOf("truncatedAtBytes" to file.length()),
                )
                runCatching { appendLineLocked(file, TestJson.encode(truncated)) }
            }
            return
        }
        appendLineLocked(file, TestJson.encode(event.toJsonMap()))
    }

    private fun appendBlockedStartLocked(testRunId: String, reason: String) {
        val line = TestBlockedStart(
            testRunId = testRunId,
            wallMs = clock.wallMs(),
            monotonicMs = clock.monotonicMs(),
            reason = reason,
        )
        runCatching {
            appendLineLocked(
                resolveInsideRoot(BLOCKED_STARTS_FILE),
                TestJson.encode(line.toJsonMap()),
            )
        }
    }

    /** Append a `admin.jsonl` con tope de 100 KB (descarta las líneas más antiguas). */
    private fun appendAdminLocked(entry: Map<String, Any?>) {
        val file = resolveInsideRoot(ADMIN_LOG_FILE)
        runCatching { appendLineLocked(file, TestJson.encode(entry)) }
        if (file.length() > maxAdminBytes) {
            runCatching {
                val lines = file.readLines(Charsets.UTF_8).toMutableList()
                // Se conservan las más recientes que quepan; el truncado
                // queda marcado, nunca silencioso (Revisión 2, R23).
                val kept = mutableListOf<String>()
                var size = 0L
                val truncatedMarker = TestJson.encode(
                    mapOf(
                        "event" to "ADMIN_TRUNCATED",
                        "wallMs" to clock.wallMs(),
                        "monotonicMs" to clock.monotonicMs(),
                        "origin" to "observed",
                    ),
                )
                size += truncatedMarker.length + 1
                for (line in lines.asReversed()) {
                    if (size + line.length + 1 > maxAdminBytes) break
                    kept.add(0, line)
                    size += line.length + 1
                }
                kept.add(0, truncatedMarker)
                file.writeText(kept.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            }
        }
    }

    private fun readFirstEventLocked(file: File): TestEvent? {
        if (!file.isFile) return null
        val first = runCatching {
            file.bufferedReader(Charsets.UTF_8).use { it.readLine() }
        }.getOrNull() ?: return null
        return TestJson.parseObject(first)?.let { TestEvent.fromJsonMap(it) }
    }

    private fun readHeaderTestRunIdLocked(file: File): String? =
        readFirstEventLocked(file)?.testRunId

    private fun readSessionFileLocked(file: File, sessionId: String): TestSessionRead {
        if (!file.isFile) return TestSessionRead(sessionId, emptyList(), 0)
        val events = mutableListOf<TestEvent>()
        var corrupt = 0
        runCatching {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val parsed = TestJson.parseObject(line)?.let { TestEvent.fromJsonMap(it) }
                    if (parsed == null) {
                        // LOG_TRUNCATED y ADMIN_TRUNCATED no son eventos:
                        // una línea con event desconocido no es corrupta
                        // si es una marca administrativa conocida.
                        val marker = TestJson.parseObject(line)?.str("event")
                        if (marker == "LOG_TRUNCATED") continue
                        corrupt++
                    } else {
                        events += parsed
                    }
                }
            }
        }
        return TestSessionRead(sessionId, events, corrupt)
    }

    private fun readSessionVerdictLocked(sessionId: String, sessionFile: File): String? {
        // 1) Informe exportado en la raíz, si existe.
        val reportFile = runCatching {
            resolveInsideRoot(sessionId + REPORT_JSON_SUFFIX)
        }.getOrNull()
        if (reportFile?.isFile == true) {
            val verdict = runCatching {
                TestJson.parseObject(reportFile.readText(Charsets.UTF_8))?.str("verdict")
            }.getOrNull()
            if (verdict != null) return verdict
        }
        // 2) Calculado a partir de los eventos (sin informe exportado).
        val read = readSessionFileLocked(sessionFile, sessionId)
        if (read.events.isEmpty()) return null
        return reportBuilder.buildSessionReport(
            sessionId = sessionId,
            testRunId = read.events.firstOrNull()?.testRunId,
            events = read.events,
            corruptLines = read.corruptLines,
        ).verdict.name
    }

    /**
     * Detección de interrupción (Revisión 1 §3.2): por cada sesión
     * anterior sin `SESSION_ENDED`, escribe `PROCESS_INTERRUPTED`
     * (inferido) en SU archivo, con su último evento observado. Si el
     * archivo anterior falta o no admite la línea (tope de 1 MB), se
     * registra en el archivo de la sesión nueva con
     * `recordedInNewSession: true`. Nunca se reescribe el historial.
     */
    private fun detectAndRecordInterruptionsLocked(
        newSession: TestSession,
        previousFiles: List<File>,
    ): List<InterruptedSessionInfo> {
        val found = mutableListOf<InterruptedSessionInfo>()
        val newFile = resolveInsideRoot(sessionFileName(newSession.sessionId))
        for (previousFile in previousFiles) {
            val previousSessionId = previousFile.name.removeSuffix(SESSION_FILE_SUFFIX)
            if (previousSessionId == newSession.sessionId) continue
            val read = readSessionFileLocked(previousFile, previousSessionId)
            if (read.events.any { it.event == TestEventType.SESSION_ENDED }) continue
            val last = read.events.lastOrNull()
            val detail = linkedMapOf<String, Any?>(
                "previousSessionId" to previousSessionId,
                "detectedBySessionId" to newSession.sessionId,
                "detectedAtMonotonicMs" to clock.monotonicMs(),
                "detectedAtWallMs" to clock.wallMs(),
            )
            if (last != null) {
                detail["lastObservedEvent"] = last.event.name
                detail["lastObservedMonotonicMs"] = last.monotonicMs
                detail["lastObservedWallMs"] = last.wallMs
            }
            val interrupted = TestEvent(
                sessionId = previousSessionId,
                testRunId = read.events.firstOrNull()?.testRunId
                    ?: last?.testRunId
                    ?: newSession.testRunId,
                event = TestEventType.PROCESS_INTERRUPTED,
                monotonicMs = clock.monotonicMs(),
                wallMs = clock.wallMs(),
                detail = detail,
                origin = TestEventOrigin.INFERRED,
            )
            val appendedToPrevious = runCatching {
                if (!previousFile.exists()) return@runCatching false
                if (previousFile.length() >= maxSessionBytes) return@runCatching false
                appendEventLocked(previousFile, interrupted)
                true
            }.getOrDefault(false)
            if (!appendedToPrevious) {
                val fallback = interrupted.copy(
                    sessionId = newSession.sessionId,
                    detail = detail + mapOf(
                        "recordedInNewSession" to true,
                        "recordReason" to "previous_file_unwritable",
                    ),
                )
                runCatching { appendEventLocked(newFile, fallback) }
            }
            found += InterruptedSessionInfo(
                previousSessionId = previousSessionId,
                previousTestRunId = interrupted.testRunId,
                lastObservedEvent = last?.event?.name,
                recordedInNewSession = !appendedToPrevious,
                recordReason = if (appendedToPrevious) null else "previous_file_unwritable",
            )
        }
        return found
    }
}
