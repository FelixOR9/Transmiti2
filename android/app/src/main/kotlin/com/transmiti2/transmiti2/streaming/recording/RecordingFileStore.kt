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
            """{"index":${s.index},"fileName":"${escapeJson(s.fileName)}","firstEncoderPtsUs":${s.firstEncoderPtsUs ?: "null"},"startWallMs":${s.startWallMs},"status":${s.status?.let { "\"$it\"" } ?: "null"},"durationMs":${s.durationMs ?: "null"},"sizeBytes":${s.sizeBytes ?: "null"},"audioExpected":${s.audioExpected},"hasAudio":${s.hasAudio},"audioDurationMs":${s.audioDurationMs ?: "null"},"audioSampleCount":${s.audioSampleCount ?: "null"},"audioStartOffsetMs":${s.audioStartOffsetMs ?: "null"},"audioReason":${s.audioReason?.let { "\"${escapeJson(it)}\"" } ?: "null"}}"""
        }
        return """{"recordingId":"${escapeJson(recordingId)}","matchId":${matchId?.let { "\"${escapeJson(it)}\"" } ?: "null"},"width":$width,"height":$height,"fps":$fps,"bitrateBps":$bitrateBps,"rotationDegrees":$rotationDegrees,"encoderName":"${escapeJson(encoderName)}","profile":$profile,"bitrateMode":$bitrateMode,"audioEnabled":$audioEnabled,"audioSampleRate":${audioSampleRate ?: "null"},"audioChannels":${audioChannels ?: "null"},"audioEncoderName":${audioEncoderName?.let { "\"${escapeJson(it)}\"" } ?: "null"},"segments":[$segmentsJson]}"""
    }
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
 * Rutas y nombres de la grabación (plan 3.1.2 §5): almacenamiento
 * privado de la app (`<filesDir>/recordings/`), sin MediaStore
 * (es 3.1.4).
 *
 * - Segmento final: `<recordingId>_seg<NNN>.mp4`
 * - Staging: `<recordingId>_seg<NNN>.tmp.mp4`, renombrado solo tras un
 *   `stop()` correcto del muxer.
 * - Final previsto (fusión futura): `<recordingId>_final.mp4`
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

    /** Renombra el staging al nombre final tras un `stop()` correcto. */
    fun renameStagingToFinal(staging: File, final: File): Boolean =
        staging.renameTo(final)

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
