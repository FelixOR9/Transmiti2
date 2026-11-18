package com.transmiti2.transmiti2.streaming.recording

import android.media.MediaCodec
import android.media.MediaFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.MediaMuxerCompat
import java.nio.ByteBuffer

/**
 * Abstracción mínima sobre el muxer de grabación (plan 3.1.2 §2.3):
 * `addTrack`, `start`, `writeSampleData`, `stop`, `release`,
 * `setOrientationHint`. Un muxer por segmento; la rotación cierra el
 * actual y abre el siguiente.
 *
 * Regla de hilo: todas las operaciones deben llamarse desde el mismo
 * hilo (exigencia documentada de `MediaMuxerCompat`). Quien drena
 * escribe siempre desde el hilo del encoder; aquí se comprueba y se
 * falla con `IllegalStateException` si se incumple, en vez de
 * corromper el archivo en silencio.
 */
interface RecordingMuxer {
    /** `compat` (Media3 `MediaMuxerCompat`, el muxer elegido en 3.1.2). */
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
internal class MuxerThreadGuard {
    private var thread: Thread? = null

    fun check() {
        val current = Thread.currentThread()
        val first = thread
        if (first == null) {
            thread = current
            return
        }
        check(first == current) {
            "RecordingMuxer usado desde otro hilo " +
                "(${first.name} -> ${current.name})"
        }
    }
}

/**
 * `MediaMuxerCompat` de Media3 (1.11.1), el muxer elegido para 3.1.2
 * por la evidencia del spike: en muerte real del proceso conservó 4,2
 * s legibles frente a 0 s del clásico. Es `@UnstableApi`: el `@OptIn`
 * se retira si Media3 lo estabiliza (plan 3.1.2 §13). NO se sustituye
 * sin autorización expresa de Félix.
 */
@OptIn(UnstableApi::class)
class CompatRecordingMuxer(outputPath: String) : RecordingMuxer {
    override val name: String = "compat"
    private val guard = MuxerThreadGuard()
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
