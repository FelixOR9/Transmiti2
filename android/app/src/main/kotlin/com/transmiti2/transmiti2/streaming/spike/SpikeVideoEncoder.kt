// SPIKE 3.1.2 — TEMPORAL
package com.transmiti2.transmiti2.streaming.spike

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Eventos del encoder del spike. Todos llegan en el hilo del encoder
 * (`Transmiti2SpikeEncoder`):
 *
 * - `onOutputFormatChanged`: el formato de salida real (con `csd-0` /
 *   `csd-1`); llega antes del primer sample. El runner crea el muxer
 *   aquí.
 * - `onSample`: un sample codificado. El `ByteBuffer` solo es válido
 *   durante la llamada: quien lo recibe debe escribirlo en el muxer
 *   de forma síncrona (el runner lo hace) y nunca guardarlo.
 * - `onError`: error del codec; la grabación se detiene con
 *   `encoder_error`.
 * - `onEndOfStream`: el drenado terminó tras `signalEndOfInputStream`.
 *
 * Los buffers `BUFFER_FLAG_CODEC_CONFIG` (`csd`) no se entregan como
 * muestras: el `csd` viaja en el formato de `addTrack`.
 */
// SPIKE 3.1.2 — TEMPORAL
interface SpikeEncoderListener {
    fun onOutputFormatChanged(format: MediaFormat)
    fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun onError(e: Exception)
    fun onEndOfStream()
}

/**
 * Codificador H.264 del spike (prototipo de `VideoEncoder`, plan 3.1.2
 * §2.3): configuración objetivo real (1280×720, 30 fps, 2,5 Mbps,
 * CBR si el dispositivo lo anuncia, perfil Baseline solicitado,
 * keyframe cada 2 s) con superficie de entrada.
 *
 * El drenado es asíncrono (`MediaCodec.Callback`) en un
 * `HandlerThread` propio, distinto del de Camera2 y del principal: el
 * callback escribe directamente en el muxer en ese mismo hilo (regla
 * de mismo hilo del muxer). Nada de esto toca el hilo principal.
 *
 * `release()` es idempotente y seguro desde cualquier hilo.
 */
// SPIKE 3.1.2 — TEMPORAL
class SpikeVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateBps: Int,
    private val bitrateMode: Int =
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
) {
    private val thread = HandlerThread("Transmiti2SpikeEncoder").apply {
        start()
    }
    private val handler = Handler(thread.looper)

    @Volatile
    private var released = false
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    var listener: SpikeEncoderListener? = null

    /** Superficie de entrada del encoder (tras `configureAndStart`). */
    val surface: Surface? get() = inputSurface

    /**
     * Configura y arranca el encoder. Lanza si no hay codificador
     * H.264 o si el dispositivo rechaza la configuración (el runner
     * lo traduce a `encoder_error`). El formato de salida real llega
     * por `onOutputFormatChanged`.
     */
    fun configureAndStart() {
        check(!released) { "Encoder del spike ya liberado." }
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            )
            setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
        }
        val created = MediaCodec.createEncoderByType(
            MediaFormat.MIMETYPE_VIDEO_AVC,
        )
        codec = created
        created.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                ) {
                    // Entrada por superficie: no hay buffers de entrada.
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                ) {
                    handleOutputBuffer(codec, index, info)
                }

                override fun onError(
                    codec: MediaCodec,
                    e: MediaCodec.CodecException,
                ) {
                    listener?.onError(e)
                }

                override fun onOutputFormatChanged(
                    codec: MediaCodec,
                    format: MediaFormat,
                ) {
                    listener?.onOutputFormatChanged(format)
                }
            },
            handler,
        )
        created.configure(
            format,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        inputSurface = created.createInputSurface()
        created.start()
    }

    private fun handleOutputBuffer(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ) {
        try {
            val isConfig = info.flags and
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val isEos = info.flags and
                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (!isConfig && info.size > 0) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null) {
                    listener?.onSample(buffer, info)
                }
            }
            if (isEos) {
                listener?.onEndOfStream()
            }
        } finally {
            try {
                codec.releaseOutputBuffer(index, false)
            } catch (_: Exception) {
            }
        }
    }

    /** Pide el fin de la entrada: el drenado termina con EOS. */
    fun signalEndOfInputStream() {
        codec?.signalEndOfInputStream()
    }

    /** Ejecuta `block` en el hilo del encoder (p. ej. `stop()` del muxer). */
    fun post(block: () -> Unit) {
        handler.post { block() }
    }

    /** Detiene y libera el codec, la superficie y el hilo. Idempotente. */
    fun release() {
        if (released) return
        released = true
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        codec = null
        inputSurface = null
        thread.quitSafely()
    }
}
