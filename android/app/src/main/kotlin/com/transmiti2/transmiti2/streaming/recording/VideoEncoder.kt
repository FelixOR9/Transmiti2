package com.transmiti2.transmiti2.streaming.recording

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.nio.ByteBuffer

/**
 * Configuración de codificación de video (plan 3.1.2 §3.1). La
 * configuración objetivo es 1280×720, 30 fps, H.264, 2,5 Mbps, perfil
 * Baseline solicitado y keyframe cada 2 s; la configuración REAL es la
 * de la vista previa activa y se decide en `chooseConfig` tras
 * comprobar las capacidades del dispositivo (nunca en silencio).
 */
data class VideoEncoderConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val bitrateMode: Int =
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
    val profile: Int =
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
    val keyframeIntervalSec: Int = 2,
) {
    /** Valida los valores antes de configurar el codec. */
    fun validate() {
        require(width > 0) { "Ancho de video no válido: $width" }
        require(height > 0) { "Alto de video no válido: $height" }
        require(fps > 0) { "FPS no válido: $fps" }
        require(bitrateBps > 0) { "Bitrate no válido: $bitrateBps" }
        require(keyframeIntervalSec > 0) {
            "Intervalo de keyframes no válido: $keyframeIntervalSec"
        }
    }
}

/**
 * Capacidades reales del codificador H.264 del dispositivo, medidas
 * con `MediaCodecList` y con el mapa de configuración de la cámara
 * (plan 3.1.2 §3.2). Es la entrada de `chooseConfig`, que decide la
 * configuración real que se usará.
 */
data class EncoderCapabilities(
    val encoderName: String,
    val profiles: List<Int>,
    val levels: List<Int>,
    val cbrSupported: Boolean,
    val cameraSupportsSize: Boolean,
) {
    val baselineSupported: Boolean
        get() = profiles.contains(
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        )
}

/**
 * Eventos del encoder. Todos llegan en el hilo del encoder
 * (`Transmiti2VideoEncoder`):
 *
 * - `onOutputFormatChanged`: el formato de salida real (con `csd-0` /
 *   `csd-1`); llega antes del primer sample. Quien drena crea el
 *   muxer aquí y reutiliza ese mismo formato para el `addTrack` de
 *   CADA segmento nuevo (el encoder solo emite el `csd` una vez).
 * - `onSample`: un sample codificado. El `ByteBuffer` solo es válido
 *   durante la llamada: quien lo recibe debe escribirlo en el muxer
 *   de forma síncrona y nunca guardarlo.
 * - `onError`: error del codec; la grabación se detiene con
 *   `encoder_error`.
 * - `onEndOfStream`: el drenado terminó tras `signalEndOfInputStream`.
 *
 * Los buffers `BUFFER_FLAG_CODEC_CONFIG` (`csd`) no se entregan como
 * muestras: el `csd` viaja en el formato de `addTrack`.
 */
interface VideoEncoderListener {
    fun onOutputFormatChanged(format: MediaFormat)
    fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun onError(e: Exception)
    fun onEndOfStream()
}

/**
 * Puerto del encoder que usa `RecordingSession`: `VideoEncoder` lo
 * implementa; en las unitarias se sustituye por un falso (un
 * `MediaCodec` real no existe en JVM). La superficie y el formato son
 * los del encoder real cuando existen.
 */
interface VideoEncoderPort {
    val config: VideoEncoderConfig
    val surface: Surface?
    val outputFormat: MediaFormat?
    var listener: VideoEncoderListener?
    fun configureAndStart()
    fun requestSyncFrame(): Boolean
    fun signalEndOfInputStream()
    fun stopDrain()
    fun post(block: () -> Unit)
    fun release()
}

/**
 * Codificador H.264 de producción (Fase B, 3.1.2; adopta
 * `SpikeVideoEncoder` del spike con `requestSyncFrame` y la
 * comprobación de capacidades): configuración desde las capacidades
 * reales (§3.2), superficie de entrada (`createInputSurface()`),
 * callback asíncrono de salida, drenado de buffers, petición de
 * keyframes, `csd-0`/`csd-1` retenidos del formato de salida, parada
 * limpia (EOS) y liberación.
 *
 * El drenado es asíncrono (`MediaCodec.Callback`) en un
 * `HandlerThread` propio, distinto del de Camera2 y del principal: el
 * callback escribe directamente en el muxer en ese mismo hilo (regla
 * de mismo hilo del muxer). Nada de esto toca el hilo principal.
 * Nunca toca el muxer directamente: entrega buffers a quien lo drena.
 *
 * `release()` es idempotente y seguro desde cualquier hilo.
 */
class VideoEncoder(override val config: VideoEncoderConfig) : VideoEncoderPort {
    private val thread = HandlerThread("Transmiti2VideoEncoder").apply {
        start()
    }
    private val handler = Handler(thread.looper)

    @Volatile
    private var released = false
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    override var listener: VideoEncoderListener? = null

    /** Superficie de entrada del encoder (tras `configureAndStart`). */
    override val surface: Surface? get() = inputSurface

    /**
     * Formato de salida real del encoder (con `csd-0`/`csd-1`),
     * retenido tras `INFO_OUTPUT_FORMAT_CHANGED`. Quien drena lo
     * reutiliza para el `addTrack` de cada segmento nuevo.
     */
    @Volatile
    override var outputFormat: MediaFormat? = null
        private set

    /**
     * Configura y arranca el encoder. Lanza si no hay codificador
     * H.264 o si el dispositivo rechaza la configuración (quien llama
     * lo traduce a `encoder_error`). El formato de salida real llega
     * por `onOutputFormatChanged`.
     */
    override fun configureAndStart() {
        check(!released) { "VideoEncoder ya liberado." }
        config.validate()
        val format = buildFormat(config)
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
                    Transmiti2Logger.e(
                        "recording: error del codec: " +
                            "${e.javaClass.simpleName} ${e.message}",
                    )
                    listener?.onError(e)
                }

                override fun onOutputFormatChanged(
                    codec: MediaCodec,
                    format: MediaFormat,
                ) {
                    outputFormat = format
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

    /**
     * Pide un keyframe al encoder (rotación de segmento, plan §4.2).
     * Devuelve `false` si la petición no se pudo enviar; en ese caso
     * la rotación espera al keyframe natural (≤ 2 s) y, si no llega,
     * se fuerza (el segmento resultante se clasifica después).
     */
    override fun requestSyncFrame(): Boolean {
        val current = codec ?: return false
        return try {
            current.setParameters(
                Bundle().apply {
                    putInt(
                        MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,
                        0,
                    )
                },
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Pide el fin de la entrada: el drenado termina con EOS. */
    override fun signalEndOfInputStream() {
        codec?.signalEndOfInputStream()
    }

    /**
     * Detiene el drenado sin liberar el codec, la superficie ni el
     * hilo: tras esto no llegan más `onSample`. Se usa antes de
     * soltar el muxer en una interrupción, para que ninguna muestra
     * tardía escriba sobre un muxer ya liberado (lección del spike,
     * fix `542b2d95`). No sustituye a `release()`, que sigue siendo el
     * que libera todo. No se usa `release()` aquí porque apaga el
     * hilo del encoder y el `release()` del muxer, publicado en ese
     * hilo, nunca llegaría a ejecutarse.
     */
    override fun stopDrain() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
    }

    /** Ejecuta `block` en el hilo del encoder (p. ej. `stop()` del muxer). */
    override fun post(block: () -> Unit) {
        handler.post { block() }
    }

    /** Detiene y libera el codec, la superficie y el hilo. Idempotente. */
    override fun release() {
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

    companion object {
        /**
         * Construye el formato de codificación a partir de la
         * configuración decidida. No verifica nada: la verificación es
         * `checkAvcCapabilities` + `chooseConfig`, antes de llamar
         * aquí.
         */
        fun buildFormat(config: VideoEncoderConfig): MediaFormat {
            return MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                config.width,
                config.height,
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(
                    MediaFormat.KEY_I_FRAME_INTERVAL,
                    config.keyframeIntervalSec,
                )
                setInteger(MediaFormat.KEY_PROFILE, config.profile)
                setInteger(MediaFormat.KEY_BITRATE_MODE, config.bitrateMode)
            }
        }

        /**
         * Comprueba con `MediaCodecList` (solo codificadores) y con el
         * mapa de configuración de la cámara que la configuración
         * objetivo es viable (plan §3.2). Lanza `RecordingException`
         * (`encoder_error`) si no hay codificador H.264 o si la
         * cámara no puede alimentar al encoder con ese tamaño. El
         * perfil y el modo de bitrate NO lanzan aquí: se deciden en
         * `chooseConfig` (misma resolución con perfil Main, o VBR si
         * no hay CBR), y se documentan en el diagnóstico.
         */
        fun checkAvcCapabilities(
            cameraManager: CameraManager,
            cameraId: String,
            width: Int,
            height: Int,
        ): EncoderCapabilities {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var caps: MediaCodecInfo.CodecCapabilities? = null
            var encoderName: String? = null
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.contains(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                    )
                ) {
                    continue
                }
                caps = info.getCapabilitiesForType(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                )
                encoderName = info.name
                break
            }
            if (caps == null || encoderName == null) {
                throw RecordingException.encoderError(
                    "Sin codificador H.264 en este dispositivo.",
                    mapOf("stage" to "checkCapabilities"),
                )
            }
            val characteristics = try {
                cameraManager.getCameraCharacteristics(cameraId)
            } catch (e: Exception) {
                throw RecordingException.encoderError(
                    "No se pudo leer la cámara para la grabación: " +
                        "${e.message}",
                    mapOf(
                        "stage" to "checkCapabilities",
                        "cameraId" to cameraId,
                        "exceptionClass" to e.javaClass.simpleName,
                    ),
                )
            }
            val map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            )
            val sizes = map?.getOutputSizes(MediaCodec::class.java)
                ?: emptyArray()
            val cameraSupportsSize = sizes.any {
                it.width == width && it.height == height
            }
            if (!cameraSupportsSize) {
                throw RecordingException.encoderError(
                    "La cámara no alimenta al encoder con " +
                        "${width}x$height.",
                    mapOf(
                        "stage" to "checkCapabilities",
                        "cameraId" to cameraId,
                        "width" to width,
                        "height" to height,
                    ),
                )
            }
            return EncoderCapabilities(
                encoderName = encoderName,
                profiles = caps.profileLevels.map { it.profile },
                levels = caps.profileLevels.map { it.level },
                // `encoderCapabilities` es nulable (el codificador
                // puede no anunciar capacidades de encoder): sin él no
                // se puede confirmar CBR, así que se toma como no
                // soportado en vez de fallar.
                cbrSupported = caps.encoderCapabilities
                    ?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    ) == true,
                cameraSupportsSize = true,
            )
        }

        /**
         * Decide la configuración real a partir de las capacidades
         * medidas (plan §3.2): perfil Baseline si el codificador lo
         * anuncia; si no, Main; si tampoco, el primer perfil
         * anunciado. CBR si está soportado; si no, VBR. Lanza
         * `encoder_error` si el codificador no anuncia ningún perfil.
         * La decisión se documenta en el diagnóstico de grabación
         * (nunca se modifica la configuración en silencio).
         */
        fun chooseConfig(
            caps: EncoderCapabilities,
            width: Int,
            height: Int,
            fps: Int,
            bitrateBps: Int,
            keyframeIntervalSec: Int = 2,
        ): VideoEncoderConfig {
            val profile = when {
                caps.baselineSupported ->
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                caps.profiles.contains(
                    MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                ) -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                caps.profiles.isNotEmpty() -> caps.profiles.first()
                else -> throw RecordingException.encoderError(
                    "El codificador H.264 no anuncia ningún perfil.",
                    mapOf("stage" to "chooseConfig"),
                )
            }
            val bitrateMode = if (caps.cbrSupported) {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            } else {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            }
            return VideoEncoderConfig(
                width = width,
                height = height,
                fps = fps,
                bitrateBps = bitrateBps,
                bitrateMode = bitrateMode,
                profile = profile,
                keyframeIntervalSec = keyframeIntervalSec,
            )
        }
    }
}
