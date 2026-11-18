// SPIKE 3.1.2 — TEMPORAL
package com.transmiti2.transmiti2.streaming.spike

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.MediaMuxerCompat
import java.nio.ByteBuffer

/**
 * Abstracción mínima sobre el muxer, común a los dos candidatos del
 * spike (plan 3.1.2 §12.2). Misma API en ambos: `addTrack`, `start`,
 * `writeSampleData`, `stop`, `release`, `setOrientationHint`.
 *
 * Regla de hilo: todas las operaciones deben llamarse desde el mismo
 * hilo (exigencia documentada de `MediaMuxerCompat` y práctica
 * obligada también para `MediaMuxer`). El runner escribe siempre
 * desde el hilo del encoder; aquí se comprueba y se falla con
 * `IllegalStateException` si se incumple, en vez de corromper el
 * archivo en silencio.
 */
// SPIKE 3.1.2 — TEMPORAL
interface SpikeMuxer {
    /** `classic` o `compat`, como llega de Dart. */
    val name: String
    fun addTrack(format: MediaFormat): Int
    fun start()
    fun writeSampleData(
        trackIndex: Int,
        byteBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
    )
    fun stop()
    fun release()
    fun setOrientationHint(degrees: Int)
}

/** Guarda de mismo hilo: la primera llamada fija el hilo permitido. */
// SPIKE 3.1.2 — TEMPORAL
internal class SameThreadGuard {
    private var thread: Thread? = null

    fun check() {
        val current = Thread.currentThread()
        val first = thread
        if (first == null) {
            thread = current
            return
        }
        check(first == current) {
            "SpikeMuxer usado desde otro hilo " +
                "(${first.name} -> ${current.name})"
        }
    }
}

/** `MediaMuxer` clásico de Android. */
// SPIKE 3.1.2 — TEMPORAL
class ClassicSpikeMuxer(outputPath: String) : SpikeMuxer {
    override val name: String = "classic"
    private val guard = SameThreadGuard()
    private val muxer = MediaMuxer(
        outputPath,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
    )

    override fun addTrack(format: MediaFormat): Int {
        guard.check()
        return muxer.addTrack(format)
    }

    override fun start() {
        guard.check()
        muxer.start()
    }

    override fun writeSampleData(
        trackIndex: Int,
        byteBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
    ) {
        guard.check()
        muxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
    }

    override fun stop() {
        guard.check()
        muxer.stop()
    }

    override fun release() {
        guard.check()
        muxer.release()
    }

    override fun setOrientationHint(degrees: Int) {
        guard.check()
        muxer.setOrientationHint(degrees)
    }
}

/**
 * `MediaMuxerCompat` de Media3 (1.11.1). Es `@UnstableApi`: el
 * `@OptIn` se retira si Media3 lo estabiliza (plan 3.1.2 §13).
 */
@OptIn(UnstableApi::class)
// SPIKE 3.1.2 — TEMPORAL
class CompatSpikeMuxer(outputPath: String) : SpikeMuxer {
    override val name: String = "compat"
    private val guard = SameThreadGuard()
    private val muxer = MediaMuxerCompat(
        outputPath,
        MediaMuxerCompat.OUTPUT_FORMAT_MP4,
    )

    override fun addTrack(format: MediaFormat): Int {
        guard.check()
        return muxer.addTrack(format)
    }

    override fun start() {
        guard.check()
        muxer.start()
    }

    override fun writeSampleData(
        trackIndex: Int,
        byteBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
    ) {
        guard.check()
        muxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
    }

    override fun stop() {
        guard.check()
        muxer.stop()
    }

    override fun release() {
        guard.check()
        muxer.release()
    }

    override fun setOrientationHint(degrees: Int) {
        guard.check()
        muxer.setOrientationHint(degrees)
    }
}

/** Crea el muxer del spike por su nombre (`classic` o `compat`). */
fun createSpikeMuxer(kind: String, outputPath: String): SpikeMuxer {
    return when (kind) {
        "classic" -> ClassicSpikeMuxer(outputPath)
        "compat" -> CompatSpikeMuxer(outputPath)
        else -> throw IllegalArgumentException(
            "Muxer del spike desconocido: $kind",
        )
    }
}
