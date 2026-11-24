package com.transmiti2.transmiti2.streaming.recording

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * Metadatos de un segmento para el sidecar JSON (plan 3.1.2 §5.3):
 * las marcas de inicio/fin de cada segmento se guardan para la fusión
 * futura. El sidecar se escribe al abrir cada segmento y se actualiza
 * al cerrarlo (estado, duración, tamaño).
 */
data class SegmentMetadata(
    val index: Int,
    val fileName: String,
    /** PTS del encoder de la primera muestra; `null` hasta que llega. */
    val firstEncoderPtsUs: Long?,
    val startWallMs: Long,
    val status: String? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    /**
     * Campos de audio (3.1.3, plan §9/D12): `audioExpected` indica si
     * el segmento debía llevar audio; `hasAudio` si lo lleva de
     * verdad; `audioDurationMs`/`audioSampleCount` miden la pista de
     * audio; `audioStartOffsetMs` es el desplazamiento del primer
     * PTS de audio respecto al origen común del segmento; `audioReason`
     * registra el motivo cuando el audio falta o se pierde.
     * Video-only: `audioExpected = false`, `hasAudio = false` y el
     * resto en `null`.
     */
    val audioExpected: Boolean = false,
    val hasAudio: Boolean = false,
    val audioDurationMs: Long? = null,
    val audioSampleCount: Int? = null,
    val audioStartOffsetMs: Long? = null,
    val audioReason: String? = null,
    /**
     * Evidencia de verificación (subetapa 0, grupo 0-d1; plan §2.5
     * E5–E6): instante de la verificación que certificó el segmento,
     * versión del verificador que lo hizo, motivo del verificador y
     * FPS medido. `null` = el segmento aún no se ha verificado (o se
     * verificó antes de la 0, sin marcador).
     */
    val verifiedAtMs: Long? = null,
    val verifierVersion: Int? = null,
    val verificationReason: String? = null,
    val fpsMeasured: Double? = null,
)

/** Metadatos de una grabación para el sidecar JSON (`<id>.json`). */
data class RecordingMetadata(
    val recordingId: String,
    val matchId: String?,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val rotationDegrees: Int,
    val encoderName: String,
    val profile: Int,
    val bitrateMode: Int,
    val segments: List<SegmentMetadata>,
    /**
     * Campos de audio a nivel de grabación (3.1.3, plan §9/D12):
     * configuración AAC elegida cuando el audio está habilitado;
     * `null` cuando la grabación es video-only.
     */
    val audioEnabled: Boolean = false,
    val audioSampleRate: Int? = null,
    val audioChannels: Int? = null,
    val audioEncoderName: String? = null,
) {
    /** JSON manual (sin dependencias): solo los campos de texto se escapan. */
    fun toJson(): String {
        val segmentsJson = segments.joinToString(",") { s ->
            """{"index":${s.index},"fileName":"${escapeJson(s.fileName)}","firstEncoderPtsUs":${s.firstEncoderPtsUs ?: "null"},"startWallMs":${s.startWallMs},"status":${s.status?.let { "\"$it\"" } ?: "null"},"durationMs":${s.durationMs ?: "null"},"sizeBytes":${s.sizeBytes ?: "null"},"audioExpected":${s.audioExpected},"hasAudio":${s.hasAudio},"audioDurationMs":${s.audioDurationMs ?: "null"},"audioSampleCount":${s.audioSampleCount ?: "null"},"audioStartOffsetMs":${s.audioStartOffsetMs ?: "null"},"audioReason":${s.audioReason?.let { "\"${escapeJson(it)}\"" } ?: "null"},"verifiedAtMs":${s.verifiedAtMs ?: "null"},"verifierVersion":${s.verifierVersion ?: "null"},"verificationReason":${s.verificationReason?.let { "\"${escapeJson(it)}\"" } ?: "null"},"fpsMeasured":${s.fpsMeasured ?: "null"}}"""
        }
        return """{"recordingId":"${escapeJson(recordingId)}","matchId":${matchId?.let { "\"${escapeJson(it)}\"" } ?: "null"},"width":$width,"height":$height,"fps":$fps,"bitrateBps":$bitrateBps,"rotationDegrees":$rotationDegrees,"encoderName":"${escapeJson(encoderName)}","profile":$profile,"bitrateMode":$bitrateMode,"audioEnabled":$audioEnabled,"audioSampleRate":${audioSampleRate ?: "null"},"audioChannels":${audioChannels ?: "null"},"audioEncoderName":${audioEncoderName?.let { "\"${escapeJson(it)}\"" } ?: "null"},"segments":[$segmentsJson]}"""
    }
}

/** Resultado de resolver un duplicado de destino (ver `RecordingFileStore.resolveDuplicateDestination`). */
enum class DuplicateResolution {
    /** El staging pasó a ser el final; el final anterior está en cuarentena. */
    STAGING_KEPT,
    /** El final existente se conserva; el staging está en cuarentena. */
    FINAL_KEPT,
    /** No se movió nada (ninguno verificado, o falló el movimiento). */
    UNRESOLVED,
}

/** Escapa una cadena para JSON (comillas, barra y caracteres de control). */
internal fun escapeJson(value: String): String {
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

/**
 * Lector tolerante del sidecar (subetapa 0, grupo 0-d1; plan §6 A6):
 * si el JSON no parsea, falta un campo o un segmento no se puede
 * mapear, el resultado es `null` (sin sidecar) o ese segmento se
 * omite — nunca lanza. Parser JSON manual mínimo (objetos, arrays,
 * cadenas con escapes, números, literales): `org.json` es Android y
 * rompería los tests JVM si se usara aquí, y esta clase debe seguir
 * siendo `java.io` puro.
 */
private class JsonParseException(message: String) : Exception(message)

/** Valor JSON mínimo para el lector del sidecar. */
private sealed interface JsonValue {
    data class Obj(val map: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val raw: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

private class JsonParser(private val text: String) {
    private var pos = 0

    fun parse(): JsonValue {
        skipWs()
        val value = parseValue()
        skipWs()
        if (pos != text.length) {
            throw JsonParseException("contenido tras el valor raíz")
        }
        return value
    }

    private fun parseValue(): JsonValue {
        if (pos >= text.length) throw JsonParseException("fin inesperado")
        return when (val c = text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> parseLiteral("true", JsonValue.Bool(true))
            'f' -> parseLiteral("false", JsonValue.Bool(false))
            'n' -> parseLiteral("null", JsonValue.Null)
            '-', in '0'..'9' -> parseNumber()
            else -> throw JsonParseException("carácter inesperado '$c'")
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
            if (peek() != '"') {
                throw JsonParseException("clave de objeto esperada")
            }
            val key = parseString()
            skipWs()
            if (peek() != ':') throw JsonParseException("':' esperado")
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
                else -> throw JsonParseException("',' o '}' esperados")
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
                else -> throw JsonParseException("',' o ']' esperados")
            }
        }
    }

    private fun parseString(): String {
        pos++ // '"'
        val out = StringBuilder()
        while (pos < text.length) {
            val c = text[pos++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    if (pos >= text.length) break
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
                            if (pos + 4 > text.length) {
                                throw JsonParseException("escape \\u corto")
                            }
                            val hex = text.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16)
                                ?: throw JsonParseException("escape \\u inválido")
                            pos += 4
                            out.append(code.toChar())
                        }
                        else -> throw JsonParseException("escape inválido '\\$e'")
                    }
                }
                else -> out.append(c)
            }
        }
        throw JsonParseException("cadena sin cerrar")
    }

    private fun parseNumber(): JsonValue.Num {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && text[pos] in "0123456789.eE+-") pos++
        if (pos == start) throw JsonParseException("número inválido")
        return JsonValue.Num(text.substring(start, pos))
    }

    private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
        if (!text.startsWith(literal, pos)) {
            throw JsonParseException("literal inválido")
        }
        pos += literal.length
        return value
    }

    private fun skipWs() {
        while (pos < text.length && text[pos] in " \t\n\r") pos++
    }

    private fun peek(): Char? = text.getOrNull(pos)
}

/** Accesos tolerantes sobre un objeto JSON (ausente o de otro tipo → `null`). */
private fun JsonValue.Obj.str(key: String): String? =
    (map[key] as? JsonValue.Str)?.value

private fun JsonValue.Obj.long(key: String): Long? {
    val raw = (map[key] as? JsonValue.Num)?.raw ?: return null
    return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
}

private fun JsonValue.Obj.int(key: String): Int? {
    val value = long(key) ?: return null
    return if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
}

private fun JsonValue.Obj.double(key: String): Double? =
    (map[key] as? JsonValue.Num)?.raw?.toDoubleOrNull()

private fun JsonValue.Obj.bool(key: String): Boolean? =
    (map[key] as? JsonValue.Bool)?.value

private fun JsonValue.Obj.toSegmentMetadata(): SegmentMetadata? {
    val index = int("index") ?: return null
    return SegmentMetadata(
        index = index,
        fileName = str("fileName") ?: "",
        firstEncoderPtsUs = long("firstEncoderPtsUs"),
        startWallMs = long("startWallMs") ?: 0L,
        status = str("status"),
        durationMs = long("durationMs"),
        sizeBytes = long("sizeBytes"),
        audioExpected = bool("audioExpected") ?: false,
        hasAudio = bool("hasAudio") ?: false,
        audioDurationMs = long("audioDurationMs"),
        audioSampleCount = int("audioSampleCount"),
        audioStartOffsetMs = long("audioStartOffsetMs"),
        audioReason = str("audioReason"),
        verifiedAtMs = long("verifiedAtMs"),
        verifierVersion = int("verifierVersion"),
        verificationReason = str("verificationReason"),
        fpsMeasured = double("fpsMeasured"),
    )
}

private fun JsonValue.Obj.toRecordingMetadata(recordingId: String): RecordingMetadata {
    val segments = (map["segments"] as? JsonValue.Arr)
        ?.items
        ?.mapNotNull { (it as? JsonValue.Obj)?.toSegmentMetadata() }
        ?: emptyList()
    return RecordingMetadata(
        recordingId = recordingId,
        matchId = str("matchId"),
        width = int("width") ?: 0,
        height = int("height") ?: 0,
        fps = int("fps") ?: 0,
        bitrateBps = int("bitrateBps") ?: 0,
        rotationDegrees = int("rotationDegrees") ?: 0,
        encoderName = str("encoderName") ?: "",
        profile = int("profile") ?: 0,
        bitrateMode = int("bitrateMode") ?: 0,
        segments = segments,
        audioEnabled = bool("audioEnabled") ?: false,
        audioSampleRate = int("audioSampleRate"),
        audioChannels = int("audioChannels"),
        audioEncoderName = str("audioEncoderName"),
    )
}

/**
 * Rutas y nombres de la grabación (plan 3.1.2 §5): almacenamiento
 * privado de la app (`<filesDir>/recordings/`), sin MediaStore
 * (es 3.1.4).
 *
 * - Segmento final: `<recordingId>_seg<NNN>.mp4`
 * - Staging: `<recordingId>_seg<NNN>.tmp.mp4`, renombrado solo tras un
 *   `stop()` correcto del muxer.
 * - Final fusionado: `<recordingId>_final.mp4` (`SegmentMerger`,
 *   3.1.4 J1-a); staging de fusión: `<recordingId>_final.tmp.mp4`,
 *   renombrado solo tras verificar el fusionado.
 * - Sidecar: `<recordingId>.json`
 *
 * Nunca sobrescribe: el id de grabación lleva fecha, hora y 4
 * hexadecimales aleatorios, y el índice de segmento es secuencial.
 * Operaciones con `java.io` puro: testeable en JVM sin Android.
 */
class RecordingFileStore(val recordingsDir: File) {

    /**
     * Crea el directorio si no existe y lo devuelve.
     *
     * Subetapa 0, grupo 0-b (ambigüedad A8 del plan: `ensureDir()`
     * LANZA, la misma convención en sus dos llamadores): `mkdirs()`
     * con resultado comprobado y existencia posterior; si no se puede
     * crear o no es un directorio, lanza
     * `RecordingException.recordingsDirUnavailable` (`mkdirs_failed`
     * o `not_a_directory`). En `RecordingSession.start()` el fallo
     * llega a `failStart()`; en `writeSidecar()` lo recoge el
     * `try/catch` de `writeSidecarSnapshot()` (solo se registra).
     */
    fun ensureDir(): File {
        if (!recordingsDir.exists()) {
            val created = try {
                recordingsDir.mkdirs()
            } catch (e: Exception) {
                throw RecordingException.recordingsDirUnavailable(
                    "mkdirs_failed",
                    exceptionClass = e.javaClass.simpleName,
                )
            }
            if (!created || !recordingsDir.exists()) {
                throw RecordingException.recordingsDirUnavailable(
                    "mkdirs_failed",
                )
            }
        }
        if (!recordingsDir.isDirectory) {
            throw RecordingException.recordingsDirUnavailable(
                "not_a_directory",
            )
        }
        return recordingsDir
    }

    /**
     * `rec_<yyyyMMdd_HHmmss>_<4 hex>` (plan §5.1).
     *
     * S3' (subetapa 0, grupo 0-b): comprobación de existencia antes
     * de devolver el id — si ya existe el sidecar `<id>.json` o algún
     * `<id>_seg*` (final o staging), se regenera el sufijo, hasta
     * [MAX_ID_GENERATION_ATTEMPTS] intentos. Si sigue colisionando,
     * lanza `RecordingException` con `recording_state_error` y
     * `stage = "id_reservation"` (error visible, no grabar; en
     * `start()` lo recoge `failStart()`). `suffixSupplier` existe
     * para que las pruebas fijen el sufijo de forma determinista.
     */
    fun newRecordingId(
        nowMs: Long = System.currentTimeMillis(),
        suffixSupplier: () -> String = {
            "%04x".format(Random().nextInt(0x10000))
        },
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date(nowMs))
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val id = "rec_${stamp}_${suffixSupplier()}"
            if (!recordingIdExists(id)) return id
        }
        throw RecordingException.stateError(
            "No se pudo generar un id de grabación libre.",
            mapOf("stage" to "id_reservation"),
        )
    }

    /** `true` si el id ya tiene sidecar o algún segmento en disco. */
    private fun recordingIdExists(id: String): Boolean {
        if (sidecarFile(id).exists()) return true
        if (listSegmentFiles(id).isNotEmpty()) return true
        return listStagingFiles().any { it.name.startsWith("${id}_seg") }
    }

    fun segmentFileName(recordingId: String, index: Int): String =
        "${recordingId}_seg%03d.mp4".format(index)

    fun segmentStagingFileName(recordingId: String, index: Int): String =
        "${recordingId}_seg%03d.tmp.mp4".format(index)

    fun segmentFile(recordingId: String, index: Int): File =
        File(recordingsDir, segmentFileName(recordingId, index))

    fun segmentStagingFile(recordingId: String, index: Int): File =
        File(recordingsDir, segmentStagingFileName(recordingId, index))

    fun finalFileName(recordingId: String): String = "${recordingId}_final.mp4"

    fun finalFile(recordingId: String): File =
        File(recordingsDir, finalFileName(recordingId))

    /**
     * Staging de la fusión (3.1.4, J1-a): `<id>_final.tmp.mp4`. Misma
     * convención que el staging de segmento: el muxer de fusión
     * escribe aquí y solo tras verificar se renombra al final con
     * [renameStagingToFinal] (que rehúsa si el destino existe). Un
     * tmp huérfano de un intento fallido es propio y regenerable: la
     * siguiente fusión del mismo id lo sobrescribe.
     */
    fun finalStagingFileName(recordingId: String): String =
        "${recordingId}_final.tmp.mp4"

    fun finalStagingFile(recordingId: String): File =
        File(recordingsDir, finalStagingFileName(recordingId))

    fun sidecarFile(recordingId: String): File =
        File(recordingsDir, "$recordingId.json")

    /**
     * Escribe el sidecar de forma atómica (`.tmp` + renombrado): un
     * sidecar a medias nunca se presenta como válido.
     */
    fun writeSidecar(metadata: RecordingMetadata) {
        ensureDir()
        val target = sidecarFile(metadata.recordingId)
        val tmp = File(recordingsDir, "${metadata.recordingId}.json.tmp")
        tmp.writeText(metadata.toJson(), Charsets.UTF_8)
        tmp.renameTo(target)
    }

    /**
     * Lee el sidecar de una grabación. Tolerante a JSON corrupto: si
     * el archivo no existe, no se puede leer o no parsea, devuelve
     * `null` (tratar como sin sidecar y verificar); nunca lanza.
     * La autoridad del id es el nombre del archivo, no el campo
     * `recordingId` del JSON.
     */
    fun readSidecar(recordingId: String): RecordingMetadata? {
        val file = sidecarFile(recordingId)
        if (!file.isFile) return null
        return try {
            val parsed = JsonParser(file.readText(Charsets.UTF_8)).parse()
            (parsed as? JsonValue.Obj)?.toRecordingMetadata(recordingId)
        } catch (_: Exception) {
            null
        }
    }

    /** Archivos en staging (`.tmp.mp4`), ordenados por nombre. */
    fun listStagingFiles(): List<File> =
        recordingsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".tmp.mp4") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Segmentos finalizados de una grabación, ordenados por nombre. */
    fun listSegmentFiles(recordingId: String): List<File> =
        recordingsDir.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.startsWith("${recordingId}_seg") &&
                    it.name.endsWith(".mp4") &&
                    !it.name.endsWith(".tmp.mp4")
            }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Todos los segmentos finalizados (`.mp4`, sin `.tmp.mp4`),
     * ordenados por nombre. Inventario de la reconciliación.
     */
    fun listFinalSegmentFiles(): List<File> =
        recordingsDir.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.endsWith(".mp4") &&
                    !it.name.endsWith(".tmp.mp4")
            }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Todos los sidecars (`.json`, sin `.json.tmp`), ordenados por nombre. */
    fun listSidecarFiles(): List<File> =
        recordingsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Barrido V4a (segunda sede, subetapa 0 grupo 0-d1; plan §2.2 y
     * §6 A12): elimina solo los `.write-test-*.tmp` con
     * `lastModifiedMs < ahora − 60 s`. Best-effort: cualquier fallo
     * se ignora; nunca toca `.mp4`, `.json` ni ningún otro archivo.
     * Devuelve cuántos eliminó.
     */
    fun sweepWriteTestOrphans(nowMs: Long = System.currentTimeMillis()): Int {
        val files = try {
            recordingsDir.listFiles()
        } catch (_: Exception) {
            return 0
        } ?: return 0
        var removed = 0
        for (file in files) {
            if (!file.isFile) continue
            if (!file.name.startsWith(".write-test-")) continue
            if (!file.name.endsWith(".tmp")) continue
            val oldEnough = try {
                file.lastModified() < nowMs - WRITE_TEST_ORPHAN_AGE_MS
            } catch (_: Exception) {
                false
            }
            if (!oldEnough) continue
            val deleted = try {
                file.delete()
            } catch (_: Exception) {
                false
            }
            if (deleted) removed++
        }
        return removed
    }

    /**
     * Mueve un archivo a cuarentena dentro del mismo directorio, con
     * nombre con timestamp (`<base>.quarantine-<ms>.mp4`): nunca se
     * sobrescribe ni se borra nada. Devuelve el archivo en cuarentena
     * o `null` si no se pudo mover. Nunca lanza.
     */
    fun quarantineFile(
        file: File,
        nowMs: Long = System.currentTimeMillis(),
    ): File? {
        if (!file.exists()) return null
        val dot = file.name.lastIndexOf('.')
        val base = if (dot > 0) file.name.substring(0, dot) else file.name
        val ext = if (dot > 0) file.name.substring(dot) else ""
        repeat(10) { attempt ->
            val suffix = if (attempt == 0) "" else "-$attempt"
            val target = File(
                recordingsDir,
                "$base.quarantine-$nowMs$suffix$ext",
            )
            if (!target.exists()) {
                return try {
                    if (file.renameTo(target)) target else null
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    /**
     * Renombra el staging al nombre final tras un `stop()` correcto.
     *
     * Guarda de destino existente (subetapa 0, grupo 0-d1): si el
     * `.mp4` ya existe, devuelve `false` sin tocar nada — **nunca
     * sobrescribir**. El llamador resuelve el duplicado con
     * [resolveDuplicateDestination] (verificar ambos, conservar el
     * verificado, el otro a cuarentena).
     */
    fun renameStagingToFinal(staging: File, final: File): Boolean {
        if (final.exists()) return false
        return try {
            staging.renameTo(final)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resuelve un duplicado de destino (subetapa 0, grupo 0-d1; plan
     * §2.5): el `.mp4` final ya existe cuando se quiere renombrar un
     * staging al mismo nombre. `stagingVerified`/`finalVerified`
     * indican si cada archivo está verificado (por sidecar confiable
     * E1–E6 o por verificación fresca); los decide el llamador, que
     * tiene el verificador — el store solo mueve archivos.
     *
     * - Staging verificado y final no → el final va a cuarentena y el
     *   staging pasa a ser el final (`STAGING_KEPT`).
     * - Final verificado (o ambos: el que ya está en su sitio gana,
     *   determinista) → el staging va a cuarentena (`FINAL_KEPT`).
     * - Ninguno verificado → no se mueve nada (`UNRESOLVED`); la
     *   reconciliación lo trata como no verificable.
     * - Sin duplicado real (el final no existe) → renombre directo.
     *
     * Nunca sobrescribe ni borra: el perdedor queda en cuarentena
     * con timestamp, listado en el resumen.
     */
    fun resolveDuplicateDestination(
        staging: File,
        final: File,
        stagingVerified: Boolean,
        finalVerified: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): DuplicateResolution {
        if (!final.exists()) {
            return if (renameStagingToFinal(staging, final)) {
                DuplicateResolution.STAGING_KEPT
            } else {
                DuplicateResolution.UNRESOLVED
            }
        }
        return when {
            stagingVerified && !finalVerified -> {
                if (quarantineFile(final, nowMs) == null) {
                    DuplicateResolution.UNRESOLVED
                } else if (renameStagingToFinal(staging, final)) {
                    DuplicateResolution.STAGING_KEPT
                } else {
                    // El final ya está en cuarentena (sin pérdida);
                    // el staging queda pendiente de reintento.
                    DuplicateResolution.UNRESOLVED
                }
            }
            finalVerified -> {
                if (quarantineFile(staging, nowMs) != null) {
                    DuplicateResolution.FINAL_KEPT
                } else {
                    DuplicateResolution.UNRESOLVED
                }
            }
            else -> DuplicateResolution.UNRESOLVED
        }
    }

    companion object {
        /**
         * Intentos de generación de id en [newRecordingId] (S3'):
         * si los 10 colisionan con archivos existentes, error visible.
         */
        const val MAX_ID_GENERATION_ATTEMPTS = 10

        private val SEGMENT_NAME_REGEX =
            Regex("^(.+)_seg(\\d{3})(\\.tmp)?\\.mp4$")

        /**
         * Extrae `(recordingId, índice)` de un nombre de segmento
         * (final o staging). `null` si el nombre no sigue el formato.
         */
        fun parseSegmentFileName(name: String): Pair<String, Int>? {
            val match = SEGMENT_NAME_REGEX.matchEntire(name) ?: return null
            val index = match.groupValues[2].toIntOrNull() ?: return null
            return match.groupValues[1] to index
        }
    }
}
