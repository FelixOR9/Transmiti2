package com.transmiti2.transmiti2.streaming.recording

import com.transmiti2.transmiti2.streaming.StreamContract
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Resolución de la ruta canónica de grabaciones
 * (`<filesDir>/recordings/`, subetapa 0 de 3.1.4, grupo 0-b).
 *
 * Diseño (revisión 4 §2.3, manda en caso de contradicción): V1 →
 * V2 → V3 → V4a → V4b → V4c, en ese orden exacto, dentro de una sola
 * corrutina `Dispatchers.IO` por intento. El barrido V4a solo tiene
 * sentido dentro de un directorio ya creado y verificado: nunca se
 * ejecuta cuando V1 o V2 fallan.
 *
 * Todo es JVM-puro (sin `Context`, sin Android): el `filesDir` se
 * inyecta como [File] y las operaciones de disco van detrás de
 * [RecordingsDirFs], para que U16–U19/U54–U61 corran en JVM sin
 * Robolectric. El manejador solo adapta `Context` → `File` y
 * `RecordingsDirOutcome` → `MethodChannel.Result`.
 */

/** Antigüedad mínima de un `.write-test-*.tmp` para barrerlo (60 s). */
const val WRITE_TEST_ORPHAN_AGE_MS = 60_000L

/** UUID nuevos que se prueban en V4b ante colisión de nombre. */
const val WRITE_TEST_MAX_UUIDS = 3

/** Intentos de `delete()` en V4c antes de rendirse sin fallar V4. */
const val WRITE_TEST_MAX_DELETE_ATTEMPTS = 3

/** Timeout nativo por intento de `getRecordingsDir()` (2 s). */
const val GET_RECORDINGS_DIR_TIMEOUT_MS = 2_000L

/**
 * Operaciones de disco que usa la resolución V1–V4c. Son `suspend`
 * para que un fake pueda suspender con `delay(...)` (U58a) o bloquear
 * con `Thread.sleep(...)` (U58b): el mecanismo del fake es parte de
 * la especificación, porque `delay` y `Thread.sleep` tienen
 * semánticas de cancelación opuestas (revisión 4 §3.7). La
 * implementación real ([JavaIoRecordingsDirFs]) es `java.io`
 * bloqueante, fiel a una syscall.
 */
interface RecordingsDirFs {
    suspend fun exists(file: File): Boolean
    suspend fun isDirectory(file: File): Boolean
    suspend fun mkdirs(dir: File): Boolean
    suspend fun listFiles(dir: File): Array<File>?
    suspend fun createNewFile(file: File): Boolean
    suspend fun delete(file: File): Boolean
    suspend fun lastModifiedMs(file: File): Long
}

/** Implementación real de [RecordingsDirFs] sobre `java.io`. */
object JavaIoRecordingsDirFs : RecordingsDirFs {
    override suspend fun exists(file: File): Boolean = file.exists()
    override suspend fun isDirectory(file: File): Boolean = file.isDirectory
    override suspend fun mkdirs(dir: File): Boolean = dir.mkdirs()
    override suspend fun listFiles(dir: File): Array<File>? = dir.listFiles()
    override suspend fun createNewFile(file: File): Boolean =
        file.createNewFile()

    override suspend fun delete(file: File): Boolean = file.delete()
    override suspend fun lastModifiedMs(file: File): Long =
        file.lastModified()
}

/** Resultado de la resolución V1–V4c (sin tocar el canal). */
sealed interface RecordingsDirOutcome {
    /**
     * Ruta válida. `writeTestDeleteFailed` es solo un aviso (V4c no
     * hace fallar V4): el archivo quedó en su sitio y lo recoge el
     * barrido V4a del siguiente intento; `writeTestFile` es su nombre
     * único, nunca la ruta completa.
     */
    data class Ok(
        val absolutePath: String,
        val orphanWriteTestFiles: Int = 0,
        val writeTestDeleteFailed: Boolean = false,
        val writeTestFile: String? = null,
    ) : RecordingsDirOutcome

    /**
     * Fallo de directorio. `stage` es la verificación que falló
     * (`context_null` | `mkdirs_failed` | `not_a_directory` |
     * `not_writable`); con `not_writable`, `reason` es `create_failed`
     * o `write_test_collision` y `exceptionClass` el nombre simple de
     * la excepción, cuando lo hay.
     */
    data class Failed(
        val stage: String,
        val reason: String? = null,
        val exceptionClass: String? = null,
        val orphanWriteTestFiles: Int = 0,
    ) : RecordingsDirOutcome
}

/**
 * Ejecuta V1–V4c sobre `filesDir` (nulo → `context_null`).
 *
 * Nunca lanza (salvo cancelación de corrutina, que se propaga para
 * que `withTimeout`/el scope la gestionen): cualquier fallo de disco
 * se devuelve como [RecordingsDirOutcome.Failed], y el barrido V4a
 * es best-effort (cualquier fallo se ignora y se cuenta, nunca
 * bloquea ni produce error). El llamador registra el aviso de V4c;
 * el resolver no escribe en el log (puro, sin efectos secundarios).
 */
suspend fun resolveRecordingsDir(
    filesDir: File?,
    fs: RecordingsDirFs = JavaIoRecordingsDirFs,
    nowMs: () -> Long = System::currentTimeMillis,
    newUuid: () -> String = { UUID.randomUUID().toString() },
): RecordingsDirOutcome {
    // V1: validar contexto y obtener filesDir.
    val base = filesDir
        ?: return RecordingsDirOutcome.Failed(stage = "context_null")
    return validateRecordingsDir(
        File(base, "recordings"),
        fs,
        nowMs,
        newUuid,
    )
}

/**
 * Ejecuta V2–V4c sobre un directorio de grabaciones ya conocido
 * (sin V1: el llamador lo tiene por construcción). Lo usa la
 * re-validación de `RecordingSession.start()` sobre
 * `fileStore.recordingsDir`, que ya ES `<filesDir>/recordings/`
 * y no debe derivarse de `parentFile`.
 */
suspend fun validateRecordingsDir(
    recordingsDir: File,
    fs: RecordingsDirFs = JavaIoRecordingsDirFs,
    nowMs: () -> Long = System::currentTimeMillis,
    newUuid: () -> String = { UUID.randomUUID().toString() },
): RecordingsDirOutcome {
    var stage = "mkdirs_failed"
    var orphans = 0
    try {
        // V2: crear el directorio, con resultado comprobado y
        // existencia posterior. Sin él no hay nada que barrer ni
        // probar: no se ejecuta ningún paso posterior.
        if (!fs.exists(recordingsDir)) {
            val created = try {
                fs.mkdirs(recordingsDir)
            } catch (e: Exception) {
                return RecordingsDirOutcome.Failed(
                    stage = "mkdirs_failed",
                    exceptionClass = e.javaClass.simpleName,
                )
            }
            if (!created || !fs.exists(recordingsDir)) {
                return RecordingsDirOutcome.Failed(stage = "mkdirs_failed")
            }
        }
        // V3: existe y es un directorio válido.
        stage = "not_a_directory"
        val isDir = try {
            fs.exists(recordingsDir) && fs.isDirectory(recordingsDir)
        } catch (e: Exception) {
            return RecordingsDirOutcome.Failed(
                stage = "not_a_directory",
                exceptionClass = e.javaClass.simpleName,
            )
        }
        if (!isDir) {
            return RecordingsDirOutcome.Failed(stage = "not_a_directory")
        }
        // V4a: barrer huérfanos `.write-test-*.tmp` antiguos, dentro
        // del directorio ya validado. Best-effort: nunca falla.
        orphans = sweepWriteTestOrphans(recordingsDir, fs, nowMs())
        // V4b: archivo de prueba único, creación atómica, hasta 3 UUID.
        stage = "not_writable"
        var testFile: File? = null
        for (attempt in 0 until WRITE_TEST_MAX_UUIDS) {
            val candidate =
                File(recordingsDir, ".write-test-${newUuid()}.tmp")
            val created = try {
                fs.createNewFile(candidate)
            } catch (e: Exception) {
                return RecordingsDirOutcome.Failed(
                    stage = "not_writable",
                    reason = "create_failed",
                    exceptionClass = e.javaClass.simpleName,
                    orphanWriteTestFiles = orphans,
                )
            }
            if (created) {
                testFile = candidate
                break
            }
        }
        val file = testFile
            ?: return RecordingsDirOutcome.Failed(
                stage = "not_writable",
                reason = "write_test_collision",
                orphanWriteTestFiles = orphans,
            )
        // V4c: comprobar su eliminación, hasta 3 intentos. Si no se
        // puede eliminar, V4 NO falla: se deja el archivo y se marca
        // el aviso en el diagnóstico.
        var deleted = false
        for (attempt in 0 until WRITE_TEST_MAX_DELETE_ATTEMPTS) {
            val ok = try {
                fs.delete(file)
            } catch (_: Exception) {
                false
            }
            if (ok) {
                deleted = true
                break
            }
        }
        return RecordingsDirOutcome.Ok(
            absolutePath = recordingsDir.absolutePath,
            orphanWriteTestFiles = orphans,
            writeTestDeleteFailed = !deleted,
            writeTestFile = if (deleted) null else file.name,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Guardia: un fallo inesperado en V1–V4c se clasifica con la
        // etapa en curso, nunca deja la llamada sin respuesta.
        return RecordingsDirOutcome.Failed(
            stage = stage,
            exceptionClass = e.javaClass.simpleName,
            orphanWriteTestFiles = orphans,
        )
    }
}

/**
 * Barrido V4a: elimina solo los archivos que casan exactamente con
 * `.write-test-*.tmp` y tienen `lastModifiedMs < ahora − 60 s`.
 * Nunca toca `.mp4`, `.json` ni ningún otro archivo, ni archivos
 * vivos recientes. Devuelve cuántos eliminó; cualquier fallo
 * (listado nulo, borrado fallido, excepción) se ignora.
 */
private suspend fun sweepWriteTestOrphans(
    recordingsDir: File,
    fs: RecordingsDirFs,
    nowMs: Long,
): Int {
    val files = try {
        fs.listFiles(recordingsDir)
    } catch (_: Exception) {
        return 0
    } ?: return 0
    var removed = 0
    for (file in files) {
        if (!file.name.startsWith(".write-test-")) continue
        if (!file.name.endsWith(".tmp")) continue
        val oldEnough = try {
            fs.lastModifiedMs(file) < nowMs - WRITE_TEST_ORPHAN_AGE_MS
        } catch (_: Exception) {
            false
        }
        if (!oldEnough) continue
        val deleted = try {
            fs.delete(file)
        } catch (_: Exception) {
            false
        }
        if (deleted) removed++
    }
    return removed
}

/** Respuesta que `replyOnce` emite por el canal (una sola vez). */
sealed interface GetRecordingsDirReply {
    data class Success(val absolutePath: String) : GetRecordingsDirReply
    data class DirError(
        val failed: RecordingsDirOutcome.Failed,
    ) : GetRecordingsDirReply

    data class Timeout(val attempt: Int) : GetRecordingsDirReply
}

/**
 * Destino de la respuesta de `getRecordingsDir()`, sin depender de
 * Flutter: el manejador lo adapta a `MethodChannel.Result` y las
 * pruebas lo implementan directamente. Es lo que mantiene este
 * archivo JVM-puro (el paso de fidelidad del harness compila los
 * tests de grabación solo contra `android.jar`, sin los stubs de
 * Flutter).
 */
interface RecordingsDirReplySink {
    fun success(path: String)
    fun error(code: String, message: String?, details: Map<String, Any?>?)
}

/**
 * Flujo completo de `getRecordingsDir()` para el manejador del canal
 * (revisión 3 §3.3 + revisión 4 §3.5, especificación final; 0-e lo
 * verifica, no lo reescribe):
 *
 * - Enrutado síncrono en Main (el llamador lee `context.filesDir`
 *   en memoria, sin disco ni `previewEngine`); el trabajo de disco
 *   corre en [ioDispatcher] (`Dispatchers.IO`).
 * - `withTimeout(timeoutMs)` alrededor del bloque IO completo
 *   (V1–V4c). Es cooperativo: no interrumpe una syscall bloqueante;
 *   la respuesta de error llega cuando la syscall retorna. El único
 *   límite garantizado sobre la espera es el timeout Dart (3 s).
 *   Guarda de tiempo adicional: si el bloque completa con un valor
 *   después de superado el presupuesto (la cancelación no se entrega
 *   sin un punto de suspensión posterior; U58b lo demuestra), el
 *   intento se responde como timeout, nunca como éxito tardío.
 * - La respuesta se emite en [mainDispatcher] y exactamente una vez
 *   ([replyOnce]): `compareAndSet` en el momento de responder, guarda
 *   `scope.isActive`, `try/catch` alrededor de `result.success/error`
 *   y ningún efecto secundario (nunca registra la ruta ni toca
 *   estado). Una respuesta tardía es así segura por construcción.
 * - Si el scope se cancela (`onDestroy`), la corrutina no responde
 *   (U57): Dart lo rescata su `Future.timeout`.
 *
 * Devuelve el [Job] para que las pruebas puedan esperarlo.
 */
fun launchGetRecordingsDir(
    scope: CoroutineScope,
    mainDispatcher: CoroutineDispatcher,
    filesDir: File?,
    attempt: Int,
    replySink: RecordingsDirReplySink,
    fs: RecordingsDirFs = JavaIoRecordingsDirFs,
    nowMs: () -> Long = System::currentTimeMillis,
    newUuid: () -> String = { UUID.randomUUID().toString() },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    timeoutMs: Long = GET_RECORDINGS_DIR_TIMEOUT_MS,
): Job {
    val replied = AtomicBoolean(false)
    return scope.launch(ioDispatcher) {
        val reply: GetRecordingsDirReply = try {
            val startedNs = System.nanoTime()
            val resolved = withTimeout(timeoutMs) {
                resolveRecordingsDir(filesDir, fs, nowMs, newUuid)
            }
            // Guarda de tiempo: `withTimeout` es cooperativo y NO
            // interrumpe una syscall bloqueante; si el bloque estaba
            // dentro de una cuando saltó el timeout, completa con su
            // valor en vez de lanzar (U58b lo demuestra: sin esta
            // guarda, un intento de 5 s respondía éxito). Superado el
            // presupuesto, el intento es timeout: nunca se responde
            // un éxito tardío de un intento caducado.
            if (System.nanoTime() - startedNs >= timeoutMs * 1_000_000L) {
                GetRecordingsDirReply.Timeout(attempt)
            } else {
                when (resolved) {
                    is RecordingsDirOutcome.Ok -> {
                        if (resolved.writeTestDeleteFailed) {
                            Transmiti2Logger.e(
                                "getRecordingsDir: no se pudo borrar el " +
                                    "archivo de prueba " +
                                    "${resolved.writeTestFile}; lo recoge " +
                                    "el barrido del siguiente intento",
                            )
                        }
                        GetRecordingsDirReply.Success(resolved.absolutePath)
                    }
                    is RecordingsDirOutcome.Failed ->
                        GetRecordingsDirReply.DirError(resolved)
                }
            }
        } catch (e: TimeoutCancellationException) {
            GetRecordingsDirReply.Timeout(attempt)
        } catch (e: CancellationException) {
            // Scope cancelado (onDestroy): sin respuesta; el Future de
            // Dart completa por su timeout, no queda colgado (U57).
            return@launch
        } catch (e: Exception) {
            // Guardia: un fallo inesperado en V1–V4c nunca deja el
            // Future de Dart colgado: se responde una vez con error.
            GetRecordingsDirReply.DirError(
                RecordingsDirOutcome.Failed(
                    stage = "not_writable",
                    reason = "create_failed",
                    exceptionClass = e.javaClass.simpleName,
                ),
            )
        }
        if (!scope.coroutineContext.isActive) {
            Transmiti2Logger.d(
                "getRecordingsDir: scope inactivo, se descarta la respuesta",
            )
            return@launch
        }
        // La respuesta se emite en Main aunque la corrutina IO se
        // cancelara por timeout: `NonCancellable` la deja correr; la
        // guarda `scope.isActive` de `replyOnce` descarta la tardía
        // tras `onDestroy` (revisión 4 §3.5).
        withContext(NonCancellable + mainDispatcher) {
            replyOnce(replied, scope, replySink, reply)
        }
    }
}

/**
 * Único punto que toca el `Result` (revisión 4 §3.5): `compareAndSet`
 * en el momento de responder (después del `withContext(Main)`), no
 * cuando salta el timeout; guarda `scope.isActive` antes de
 * responder; la invocación va en `try/catch` (cualquier excepción se
 * traga y se registra; nunca propaga, nunca crashea); nunca registra
 * la ruta ni toca ningún estado.
 */
internal fun replyOnce(
    replied: AtomicBoolean,
    scope: CoroutineScope,
    replySink: RecordingsDirReplySink,
    reply: GetRecordingsDirReply,
) {
    if (!replied.compareAndSet(false, true)) return
    if (!scope.coroutineContext.isActive) {
        Transmiti2Logger.d(
            "getRecordingsDir: scope inactivo, se descarta la respuesta",
        )
        return
    }
    try {
        when (reply) {
            is GetRecordingsDirReply.Success ->
                replySink.success(reply.absolutePath)
            is GetRecordingsDirReply.DirError -> {
                val failed = reply.failed
                val details = mutableMapOf<String, Any?>(
                    "stage" to failed.stage,
                )
                if (failed.reason != null) details["reason"] = failed.reason
                if (failed.exceptionClass != null) {
                    details["exceptionClass"] = failed.exceptionClass
                }
                if (failed.orphanWriteTestFiles > 0) {
                    details["orphanWriteTestFiles"] =
                        failed.orphanWriteTestFiles
                }
                replySink.error(
                    StreamContract.RECORDINGS_DIR_UNAVAILABLE,
                    "No se pudo preparar la carpeta de grabaciones.",
                    details,
                )
            }
            is GetRecordingsDirReply.Timeout ->
                replySink.error(
                    StreamContract.RECORDINGS_CHANNEL_TIMEOUT,
                    "Tiempo de espera agotado al preparar la carpeta " +
                        "de grabaciones.",
                    mapOf<String, Any?>(
                        "stage" to "getRecordingsDir",
                        "attempt" to reply.attempt,
                        "exceptionClass" to "TimeoutCancellationException",
                    ),
                )
        }
    } catch (e: Exception) {
        // Responder nunca crashea: canal sin handler o engine
        // destruido. Se traga y se registra.
        Transmiti2Logger.e(
            "getRecordingsDir: no se pudo responder: " +
                e.javaClass.simpleName,
        )
    }
}
