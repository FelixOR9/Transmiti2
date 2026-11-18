// SPIKE 3.1.2 — TEMPORAL
package com.transmiti2.transmiti2.streaming.spike

/**
 * Resultado del parseo de cajas MP4 (plan 3.1.2 §12.2). Puro y
 * comprobable sin dispositivo: trabaja sobre los bytes del archivo.
 *
 * - `moovPresent`: hay caja `moov` (sin ella el archivo no es un MP4
 *   legible: es lo que se espera de un archivo interrumpido).
 * - `durationMs`: duración del `mvhd` (`duration * 1000 / timescale`).
 * - `sampleCount`: número de muestras del `stsz` de la primera pista.
 * - `cttsPresent` / `cttsAllZero`: hay caja `ctts` y si todos sus
 *   offsets de composición son 0 (sin reordenamiento de presentación).
 * - `trackCount`: número de pistas (`trak`) dentro del `moov`.
 */
// SPIKE 3.1.2 — TEMPORAL
data class MoovInfo(
    val moovPresent: Boolean,
    val durationMs: Long?,
    val sampleCount: Int?,
    val cttsPresent: Boolean,
    val cttsAllZero: Boolean?,
    val trackCount: Int,
)

/**
 * Parseo mínimo de cajas MP4. Solo lee cabeceras y las cajas que el
 * spike necesita (`mvhd`, `stsz`, `ctts`); no decodifica muestras.
 *
 * Nunca lanza: un archivo truncado o malformado devuelve
 * `moovPresent = false` (o los campos que sí se pudieron leer).
 * Los tamaños 0 (hasta el final del padre) y 1 (tamaño de 64 bits)
 * están soportados; un tamaño imposible detiene el recorrido de ese
 * nivel en vez de leer fuera del buffer.
 */
// SPIKE 3.1.2 — TEMPORAL
object MoovParser {

    fun parse(bytes: ByteArray): MoovInfo {
        return try {
            parseOrThrow(bytes)
        } catch (_: Exception) {
            MoovInfo(
                moovPresent = false,
                durationMs = null,
                sampleCount = null,
                cttsPresent = false,
                cttsAllZero = null,
                trackCount = 0,
            )
        }
    }

    private fun parseOrThrow(bytes: ByteArray): MoovInfo {
        val reader = Reader(bytes)
        val top = reader.boxes(0, bytes.size)
        val moov = top.firstOrNull { it.type == "moov" }
            ?: return MoovInfo(
                moovPresent = false,
                durationMs = null,
                sampleCount = null,
                cttsPresent = false,
                cttsAllZero = null,
                trackCount = 0,
            )
        val moovChildren = reader.boxes(moov.contentStart, moov.contentEnd)
        var durationMs: Long? = null
        moovChildren.firstOrNull { it.type == "mvhd" }?.let { mvhd ->
            durationMs = reader.mvhdDurationMs(mvhd.contentStart)
        }
        val tracks = moovChildren.filter { it.type == "trak" }
        var sampleCount: Int? = null
        var cttsPresent = false
        var cttsAllZero: Boolean? = null
        tracks.firstOrNull()?.let { trak ->
            val stbl = reader.descend(
                trak.contentStart,
                trak.contentEnd,
                listOf("mdia", "minf", "stbl"),
            )
            if (stbl != null) {
                val stblChildren = reader.boxes(
                    stbl.contentStart,
                    stbl.contentEnd,
                )
                stblChildren.firstOrNull { it.type == "stsz" }?.let { stsz ->
                    sampleCount = reader.stszSampleCount(stsz.contentStart)
                }
                stblChildren.firstOrNull { it.type == "ctts" }?.let { ctts ->
                    cttsPresent = true
                    cttsAllZero = reader.cttsAllZero(ctts.contentStart)
                }
            }
        }
        return MoovInfo(
            moovPresent = true,
            durationMs = durationMs,
            sampleCount = sampleCount,
            cttsPresent = cttsPresent,
            cttsAllZero = cttsAllZero,
            trackCount = tracks.size,
        )
    }

    /** Una caja: tipo, inicio y fin de su contenido. */
    private data class Box(
        val type: String,
        val contentStart: Int,
        val contentEnd: Int,
    )

    private class Reader(private val bytes: ByteArray) {

        private fun u32(offset: Int): Long {
            require(offset + 4 <= bytes.size) { "u32 fuera del buffer" }
            return ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)
        }

        private fun u64(offset: Int): Long {
            require(offset + 8 <= bytes.size) { "u64 fuera del buffer" }
            var value = 0L
            for (i in 0 until 8) {
                value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
            }
            return value
        }

        private fun typeAt(offset: Int): String {
            require(offset + 4 <= bytes.size) { "tipo fuera del buffer" }
            return String(bytes, offset, 4, Charsets.US_ASCII)
        }

        /**
         * Cajas hijas directas del rango `[start, end)`. Un tamaño de
         * caja imposible (menor que la cabecera o fuera del padre)
         * detiene el recorrido de este nivel.
         */
        fun boxes(start: Int, end: Int): List<Box> {
            val out = mutableListOf<Box>()
            var pos = start
            while (pos + 8 <= end) {
                val size32 = u32(pos)
                val type = typeAt(pos + 4)
                val headerSize: Int
                val contentEnd: Int
                when (size32) {
                    1L -> {
                        // Tamaño de 64 bits tras el tipo.
                        if (pos + 16 > end) break
                        val large = u64(pos + 8)
                        if (large < 16) break
                        headerSize = 16
                        val boxEnd = pos + large
                        if (boxEnd > end || boxEnd < pos) break
                        contentEnd = boxEnd.toInt()
                    }
                    0L -> {
                        // Hasta el final del padre.
                        headerSize = 8
                        contentEnd = end
                    }
                    else -> {
                        if (size32 < 8) break
                        headerSize = 8
                        val boxEnd = pos + size32
                        if (boxEnd > end || boxEnd < pos) break
                        contentEnd = boxEnd.toInt()
                    }
                }
                out.add(Box(type, pos + headerSize, contentEnd))
                pos = contentEnd
            }
            return out
        }

        /** Desciende por cajas contenedoras anidadas (`mdia/minf/stbl`). */
        fun descend(start: Int, end: Int, path: List<String>): Box? {
            var currentStart = start
            var currentEnd = end
            var found: Box? = null
            for (type in path) {
                found = boxes(currentStart, currentEnd)
                    .firstOrNull { it.type == type }
                    ?: return null
                currentStart = found.contentStart
                currentEnd = found.contentEnd
            }
            return found
        }

        /** Duración del `mvhd` en ms (versión 0 y 1). */
        fun mvhdDurationMs(contentStart: Int): Long? {
            if (contentStart + 4 > bytes.size) return null
            val version = bytes[contentStart].toInt() and 0xFF
            return when (version) {
                0 -> {
                    if (contentStart + 20 > bytes.size) return null
                    val timescale = u32(contentStart + 12)
                    val duration = u32(contentStart + 16)
                    if (timescale == 0L) null else duration * 1000 / timescale
                }
                1 -> {
                    if (contentStart + 32 > bytes.size) return null
                    val timescale = u32(contentStart + 20)
                    val duration = u64(contentStart + 24)
                    if (timescale == 0L) null else duration * 1000 / timescale
                }
                else -> null
            }
        }

        /** Número de muestras del `stsz` (`sample_count`). */
        fun stszSampleCount(contentStart: Int): Int? {
            if (contentStart + 12 > bytes.size) return null
            return u32(contentStart + 8).toInt()
        }

        /** `true` si la `ctts` existe y todos sus offsets son 0. */
        fun cttsAllZero(contentStart: Int): Boolean? {
            if (contentStart + 8 > bytes.size) return null
            val entryCount = u32(contentStart + 4).toInt()
            var pos = contentStart + 8
            for (i in 0 until entryCount) {
                if (pos + 8 > bytes.size) return null
                val offset = u32(pos + 4)
                if (offset != 0L) return false
                pos += 8
            }
            return true
        }
    }
}
