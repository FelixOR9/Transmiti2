// SPIKE 3.1.2 — TEMPORAL
package com.transmiti2.transmiti2.streaming.spike

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.transmiti2.transmiti2.streaming.CameraException
import com.transmiti2.transmiti2.streaming.CameraSession
import com.transmiti2.transmiti2.streaming.PreviewConfig
import com.transmiti2.transmiti2.streaming.StreamContract
import com.transmiti2.transmiti2.streaming.SurfaceProducerHandle
import com.transmiti2.transmiti2.streaming.TextureRegistrySurfaceProducerProvider
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import com.transmiti2.transmiti2.streaming.displayRotationDegrees
import io.flutter.view.TextureRegistry
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Error del spike, con el código del contrato cuando aplica. */
// SPIKE 3.1.2 — TEMPORAL
class SpikeException(val code: String, message: String? = null) :
    Exception(message)

private fun MediaFormat.intOrNull(key: String): Int? {
    return try {
        getInteger(key)
    } catch (_: Exception) {
        null
    }
}

private fun MediaFormat.longOrNull(key: String): Long? {
    return try {
        getLong(key)
    } catch (_: Exception) {
        null
    }
}

/**
 * Descripción de una excepción con su clase: el mensaje solo nunca
 * vuelve a aparecer como `null` (MediaMuxerCompat lanza con mensaje
 * nulo al escribir tras `release()`).
 */
// SPIKE 3.1.2 — TEMPORAL
internal fun describeException(e: Exception): String {
    val message = e.message
    return if (message != null) {
        "${e.javaClass.simpleName}: $message"
    } else {
        e.javaClass.simpleName
    }
}

/**
 * Política de error del muxer tras una pasada: en una interrupción,
 * un error del muxer no desecha el resultado — el archivo se verifica
 * tal cual y el error queda en el campo `muxerError` del mapa. En una
 * grabación normal, el mismo error sí es un fallo: se esperaba un
 * cierre limpio con `stop()`.
 */
// SPIKE 3.1.2 — TEMPORAL
internal fun shouldReportMuxerErrorAsFailure(
    interrupt: Boolean,
    muxerError: Exception?,
): Boolean = !interrupt && muxerError != null

/** Capacidades anunciadas del codificador H.264 del dispositivo. */
// SPIKE 3.1.2 — TEMPORAL
private data class AvcCaps(
    val encoderName: String?,
    val profiles: List<Int>,
    val levels: List<Int>,
    val cbrSupported: Boolean,
)

/** Estadísticas del drenado, escritas en el hilo del encoder. */
// SPIKE 3.1.2 — TEMPORAL
private class DrainStats {
    val sampleCount = AtomicInteger(0)
    val keyframeCount = AtomicInteger(0)
    val maxWriteLatencyMs = AtomicLong(0)

    @Volatile
    var firstSampleKeyframe: Boolean? = null

    @Volatile
    var firstPtsUs: Long? = null

    @Volatile
    var lastPtsUs: Long? = null

    @Volatile
    var ptsMonotonic = true

    @Volatile
    var samplesBeforeMuxer = 0

    @Volatile
    var muxerError: Exception? = null

    @Volatile
    var encoderError: Exception? = null

    @Volatile
    var eosTimeout = false

    /**
     * Guarda de abandono: cuando es `true`, el drenado está detenido
     * (interrupción o fallo del cierre limpio) y las muestras que aún
     * lleguen se ignoran sin tocar el muxer — una muestra tardía no
     * debe escribir sobre un muxer ya liberado.
     */
    @Volatile
    var drainStopped = false
}

/**
 * Orquesta del spike del muxer (plan 3.1.2 §12.3): sonda del
 * codificador, grabación con cada muxer por el camino real de Camera2
 * (vista previa + superficie del encoder en una sola sesión, plantilla
 * `TEMPLATE_RECORD`), interrupción sin `stop()` del muxer y
 * verificación (`MediaExtractor` + ExoPlayer + `MoovParser`).
 *
 * Los archivos del spike viven en `<documents>/recordings/spike/` y
 * no se registran en SQLite ni se muestran como grabaciones.
 *
 * Desviación documentada respecto al plan §12.3 paso 2: `CameraSession`
 * no tiene cierre de solo-sesión (su `close()` cierra sesión Y
 * dispositivo), así que el spike cierra la sesión completa y reabre el
 * dispositivo con una instancia nueva de `CameraSession` para grabar,
 * y lo mismo al restaurar la vista previa. Es el camino que el spike
 * debe ejercer para decidir la variante de producción (§1.3.1): la
 * recreación de solo-sesión queda como trabajo de producción, no del
 * spike. La vista previa parpadea un instante en cada cambio.
 *
 * El productor de superficie se crea y se libera en el hilo principal
 * (Fase B: `SurfaceProducer` toca el motor de Flutter, `@UiThread`).
 * El muxer se usa siempre desde el hilo del encoder (creación en
 * `onOutputFormatChanged`, escrituras en `onSample`, `stop()` /
 * `release()` con `SpikeVideoEncoder.post`).
 */
// SPIKE 3.1.2 — TEMPORAL
class MuxerSpikeRunner(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val textureRegistry: TextureRegistry,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val muxerFactory: (kind: String, path: String) -> SpikeMuxer =
        ::createSpikeMuxer,
    private val encoderFactory: (
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        bitrateMode: Int,
    ) -> SpikeVideoEncoder = { w, h, f, b, m ->
        SpikeVideoEncoder(w, h, f, b, m)
    },
) {
    private val mutex = Mutex()
    private var previewHandle: SurfaceProducerHandle? = null
    private var previewSession: CameraSession? = null
    private var previewConfig: PreviewConfig? = null
    private var previewInfo: Map<String, Any?>? = null
    private var recordingInProgress = false

    /** Directorio del spike (`<documents>/recordings/spike/`). */
    fun spikeDir(): File {
        val dir = File(File(context.filesDir, "recordings"), SPIKE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Vista previa en vivo del spike (productor y sesión propios,
     * independientes del motor de streaming). Devuelve `textureId`,
     * tamaño, rotación y FPS para dibujarla con `Texture`.
     * Idempotente: si ya está activa, devuelve la información actual.
     */
    suspend fun startPreview(): Map<String, Any?> {
        mutex.withLock { previewInfo }?.let { return it }
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw CameraException(
                StreamContract.PERMISSION_DENIED,
                "Permiso de cámara no concedido.",
            )
        }
        val handle = withContext(mainDispatcher) {
            TextureRegistrySurfaceProducerProvider(textureRegistry).create()
        }
        val session = CameraSession(
            cameraManager = cameraManager,
            surfaceHandle = handle,
        )
        try {
            val config = session.open()
            val info = previewInfoMap(handle, config)
            mutex.withLock {
                previewHandle = handle
                previewSession = session
                previewConfig = config
                previewInfo = info
            }
            return info
        } catch (e: Exception) {
            try {
                session.close()
            } catch (_: Exception) {
            }
            withContext(mainDispatcher) {
                try {
                    handle.release()
                } catch (_: Exception) {
                }
            }
            throw e
        }
    }

    /** Cierra la vista previa del spike. Idempotente. */
    suspend fun stopPreview() {
        val (session, handle) = mutex.withLock {
            val s = previewSession
            val h = previewHandle
            previewSession = null
            previewHandle = null
            previewConfig = null
            previewInfo = null
            Pair(s, h)
        }
        try {
            session?.close()
        } catch (_: Exception) {
        }
        if (handle != null) {
            withContext(mainDispatcher) {
                try {
                    handle.setCallback(null)
                    handle.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun previewInfoMap(
        handle: SurfaceProducerHandle,
        config: PreviewConfig,
    ): Map<String, Any?> {
        val rotation = (
            config.sensorOrientation -
                displayRotationDegrees(context) + 360
            ) % 360
        return mapOf(
            "textureId" to handle.textureId,
            "width" to config.size.width,
            "height" to config.size.height,
            "sensorOrientation" to config.sensorOrientation,
            "rotationDegrees" to rotation,
            "fps" to config.fpsRange.max,
            "lensFacing" to "back",
        )
    }

    /**
     * Sonda del codificador (sin grabar): nombre del codificador
     * H.264, perfiles/niveles anunciados, ¿1280×720 en
     * `getOutputSizes(MediaCodec)` de la cámara trasera?, ¿CBR
     * soportado?, formato de salida real tras configurar (perfil y
     * nivel aceptados), `csd-0`/`csd-1` presentes y `profile_idc` /
     * `level_idc` del SPS (`csd-0`, registro avcC).
     */
    suspend fun probeEncoder(): Map<String, Any?> {
        val caps = avcEncoderCaps()
        val backCamera = backCameraId()
        val supports720p = backCamera?.let { id ->
            try {
                val map = cameraManager.getCameraCharacteristics(id)
                    .get(
                        CameraCharacteristics
                            .SCALER_STREAM_CONFIGURATION_MAP,
                    )
                map?.getOutputSizes(MediaCodec::class.java)?.any {
                    it.width == TARGET_WIDTH && it.height == TARGET_HEIGHT
                } ?: false
            } catch (_: Exception) {
                false
            }
        } ?: false
        var outputProfile: Int? = null
        var outputLevel: Int? = null
        var hasCsd0 = false
        var hasCsd1 = false
        var profileIdc: Int? = null
        var levelIdc: Int? = null
        var encoderError: String? = null
        if (caps.encoderName == null) {
            encoderError = "Sin codificador H.264 en este dispositivo."
        } else {
            val formatDeferred = CompletableDeferred<MediaFormat>()
            val encoder = encoderFactory(
                TARGET_WIDTH,
                TARGET_HEIGHT,
                TARGET_FPS,
                TARGET_BITRATE_BPS,
                if (caps.cbrSupported) {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                },
            )
            encoder.listener = object : SpikeEncoderListener {
                override fun onOutputFormatChanged(format: MediaFormat) {
                    formatDeferred.complete(format)
                }

                override fun onSample(
                    buffer: ByteBuffer,
                    info: MediaCodec.BufferInfo,
                ) {
                }

                override fun onError(e: Exception) {
                    formatDeferred.completeExceptionally(e)
                }

                override fun onEndOfStream() {}
            }
            try {
                encoder.configureAndStart()
                val format = withTimeout(OPERATION_TIMEOUT_MS) {
                    formatDeferred.await()
                }
                outputProfile = format.intOrNull(MediaFormat.KEY_PROFILE)
                outputLevel = format.intOrNull(MediaFormat.KEY_LEVEL)
                val csd0 = format.getByteBuffer("csd-0")
                val csd1 = format.getByteBuffer("csd-1")
                hasCsd0 = csd0 != null && csd0.remaining() > 0
                hasCsd1 = csd1 != null && csd1.remaining() > 0
                // La comprobación explícita de nulidad es necesaria:
                // `hasCsd0` es un booleano aparte y no produce smart
                // cast sobre `csd0` (`ByteBuffer?` de getByteBuffer).
                if (csd0 != null && hasCsd0) {
                    val bytes = ByteArray(csd0.remaining())
                    csd0.duplicate().get(bytes)
                    // Registro avcC: [1] = AVCProfileIndication
                    // (profile_idc), [3] = AVCLevelIndication.
                    if (bytes.size >= 4) {
                        profileIdc = bytes[1].toInt() and 0xFF
                        levelIdc = bytes[3].toInt() and 0xFF
                    }
                }
            } catch (e: TimeoutCancellationException) {
                encoderError = "El codificador no entregó su formato " +
                    "de salida en 5 s."
            } catch (e: Exception) {
                encoderError = e.message
                    ?: "No se pudo configurar el codificador."
            } finally {
                encoder.release()
            }
        }
        return mapOf(
            "encoderName" to caps.encoderName,
            "profiles" to caps.profiles,
            "levels" to caps.levels,
            "cbrSupported" to caps.cbrSupported,
            "supports720pForMediaCodec" to supports720p,
            "outputProfile" to outputProfile,
            "outputLevel" to outputLevel,
            "hasCsd0" to hasCsd0,
            "hasCsd1" to hasCsd1,
            "profileIdc" to profileIdc,
            "levelIdc" to levelIdc,
            "encoderError" to encoderError,
        )
    }

    /**
     * Grabación normal con `kind` (`classic` o `compat`) durante
     * `durationMs`: EOS, drenado final, `stop()` del muxer y renombrado
     * a final. Devuelve las medidas (§12.4).
     */
    suspend fun recordWithMuxer(
        kind: String,
        durationMs: Long,
    ): Map<String, Any?> {
        return runRecording(kind, durationMs, interrupt = false)
    }

    /**
     * Interrupción con `kind` durante `durationMs`: se abandona el
     * muxer sin `stop()` (se libera el encoder y se suelta el muxer
     * sin finalizarlo), como simula la app una muerte del proceso.
     * El archivo queda como `*.tmp.mp4` y se inspecciona tal cual.
     */
    suspend fun interruptWithMuxer(
        kind: String,
        durationMs: Long,
    ): Map<String, Any?> {
        return runRecording(kind, durationMs, interrupt = true)
    }

    private suspend fun runRecording(
        kind: String,
        durationMs: Long,
        interrupt: Boolean,
    ): Map<String, Any?> {
        if (kind != "classic" && kind != "compat") {
            throw SpikeException(
                "spike_error",
                "Muxer del spike desconocido: $kind",
            )
        }
        val claimed = mutex.withLock {
            if (recordingInProgress) {
                false
            } else {
                recordingInProgress = true
                true
            }
        }
        if (!claimed) {
            throw SpikeException(
                "spike_error",
                "Ya hay una grabación del spike en curso.",
            )
        }
        try {
            return runRecordingLocked(kind, durationMs, interrupt)
        } finally {
            mutex.withLock { recordingInProgress = false }
        }
    }

    private suspend fun runRecordingLocked(
        kind: String,
        durationMs: Long,
        interrupt: Boolean,
    ): Map<String, Any?> {
        // La vista previa debe estar activa: la grabación usa su misma
        // configuración (un solo tamaño para las dos superficies).
        mutex.withLock { previewInfo } ?: startPreview()
        val config = mutex.withLock { previewConfig }
            ?: throw SpikeException(
                "spike_error",
                "Sin configuración de vista previa.",
            )
        val handle = mutex.withLock { previewHandle }
            ?: throw SpikeException(
                "spike_error",
                "Sin superficie de vista previa.",
            )
        val rotation = (config.sensorOrientation -
            displayRotationDegrees(context) + 360) % 360
        val fps = config.fpsRange.max
        // La cámara debe poder alimentar al encoder con este tamaño.
        val encoderSizeSupported = try {
            val map = cameraManager
                .getCameraCharacteristics(config.cameraId)
                .get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                )
            map?.getOutputSizes(MediaCodec::class.java)?.any {
                it.width == config.size.width &&
                    it.height == config.size.height
            } ?: false
        } catch (_: Exception) {
            false
        }
        if (!encoderSizeSupported) {
            throw SpikeException(
                StreamContract.ENCODER_ERROR,
                "La cámara no alimenta al encoder con " +
                    "${config.size.width}x${config.size.height}.",
            )
        }
        val caps = avcEncoderCaps()
        if (caps.encoderName == null) {
            throw SpikeException(
                StreamContract.ENCODER_ERROR,
                "Sin codificador H.264 en este dispositivo.",
            )
        }
        val bitrateMode = if (caps.cbrSupported) {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        } else {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        }
        val stamp = System.currentTimeMillis()
        val suffix = (1000..9999).random()
        val tmpFile = File(
            spikeDir(),
            "spike_${kind}_${stamp}_$suffix.tmp.mp4",
        )
        val stats = DrainStats()
        val eosDeferred = CompletableDeferred<Unit>()
        var muxer: SpikeMuxer? = null
        var trackIndex = -1
        val encoder = encoderFactory(
            config.size.width,
            config.size.height,
            fps,
            TARGET_BITRATE_BPS,
            bitrateMode,
        )
        encoder.listener = object : SpikeEncoderListener {
            override fun onOutputFormatChanged(format: MediaFormat) {
                // Guarda de abandono: un formato tardío no crea un
                // muxer nuevo tras soltar el anterior.
                if (stats.drainStopped) {
                    return
                }
                try {
                    val created = muxerFactory(kind, tmpFile.absolutePath)
                    created.setOrientationHint(rotation)
                    trackIndex = created.addTrack(format)
                    created.start()
                    muxer = created
                } catch (e: Exception) {
                    stats.muxerError = e
                }
            }

            override fun onSample(
                buffer: ByteBuffer,
                info: MediaCodec.BufferInfo,
            ) {
                // Guarda de abandono: una muestra tardía se ignora sin
                // tocar el muxer ni registrar error.
                if (stats.drainStopped) {
                    return
                }
                val current = muxer
                if (current == null || trackIndex < 0) {
                    stats.samplesBeforeMuxer++
                    return
                }
                val pts = info.presentationTimeUs
                if (stats.firstPtsUs == null) {
                    stats.firstPtsUs = pts
                    stats.firstSampleKeyframe = info.flags and
                        MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                }
                val last = stats.lastPtsUs
                if (last != null && pts <= last) {
                    stats.ptsMonotonic = false
                }
                stats.lastPtsUs = pts
                if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                    stats.keyframeCount.incrementAndGet()
                }
                val started = System.nanoTime()
                try {
                    current.writeSampleData(trackIndex, buffer, info)
                } catch (e: Exception) {
                    stats.muxerError = e
                    return
                }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                stats.maxWriteLatencyMs.updateAndGet { maxOf(it, elapsedMs) }
                stats.sampleCount.incrementAndGet()
            }

            override fun onError(e: Exception) {
                stats.encoderError = e
                eosDeferred.completeExceptionally(e)
            }

            override fun onEndOfStream() {
                eosDeferred.complete(Unit)
            }
        }
        var recordingSession: CameraSession? = null
        var stoppedCleanly = false
        try {
            try {
                encoder.configureAndStart()
            } catch (e: Exception) {
                throw SpikeException(
                    StreamContract.ENCODER_ERROR,
                    "No se pudo configurar el codificador: ${e.message}",
                )
            }
            val encoderSurface = encoder.surface
                ?: throw SpikeException(
                    StreamContract.ENCODER_ERROR,
                    "El codificador no entregó su superficie de entrada.",
                )
            val oldSession = mutex.withLock { previewSession }
            try {
                oldSession?.close()
            } catch (_: Exception) {
            }
            val session = CameraSession(
                cameraManager = cameraManager,
                surfaceHandle = handle,
                videoOutputSurface = encoderSurface,
                captureTemplate = CameraDevice.TEMPLATE_RECORD,
            )
            recordingSession = session
            // Si `open()` falla, la restauración la hace el `finally`
            // de fuera: no se restaura aquí para no crear dos sesiones.
            val recordConfig = session.open()
            mutex.withLock {
                previewSession = session
                previewConfig = recordConfig
            }
            delay(durationMs)
            if (!interrupt) {
                encoder.signalEndOfInputStream()
                try {
                    withTimeout(OPERATION_TIMEOUT_MS) {
                        eosDeferred.await()
                    }
                } catch (e: TimeoutCancellationException) {
                    stats.eosTimeout = true
                } catch (_: Exception) {
                    stats.eosTimeout = true
                }
                if (!stats.eosTimeout && stats.muxerError == null) {
                    // MediaMuxerCompat: la duración del último sample
                    // se fija con un buffer vacío EOS y el PTS adecuado
                    // (documentado en su referencia). Al clásico no se
                    // le escribe: su duración es la del último sample.
                    if (kind == "compat") {
                        val lastPts = stats.lastPtsUs
                        if (lastPts != null) {
                            val frameUs = 1_000_000L / fps
                            val eosInfo = MediaCodec.BufferInfo().apply {
                                set(
                                    0,
                                    0,
                                    lastPts + frameUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                            }
                            runOnEncoderThread(encoder) {
                                muxer?.writeSampleData(
                                    trackIndex,
                                    ByteBuffer.allocate(0),
                                    eosInfo,
                                )
                            }
                        }
                    }
                    try {
                        runOnEncoderThread(encoder) {
                            muxer?.stop()
                        }
                        stoppedCleanly = true
                    } catch (e: Exception) {
                        stats.muxerError = e
                    }
                }
            }
            if (interrupt || !stoppedCleanly) {
                // Abandono (interrupción o fallo del cierre limpio):
                // el muxer se suelta sin `stop()`. Antes hay que
                // cerrar el grifo, en este orden:
                // 1. Guarda: descarta las muestras ya en cola.
                // 2. `stopDrain()`: detiene el codec para que no
                //    lleguen más muestras (no se usa `release()`:
                //    apaga el hilo del encoder y el `release()` del
                //    muxer, que se publica en ese hilo, nunca
                //    llegaría a ejecutarse).
                // Sin esto, la siguiente muestra escribía sobre el
                // muxer ya liberado (`Muxer has been released!` en
                // el clásico, mensaje nulo en compat) y la pasada
                // se perdía antes de verificarse.
                stats.drainStopped = true
                encoder.stopDrain()
                try {
                    runOnEncoderThread(encoder) {
                        muxer?.release()
                    }
                } catch (_: Exception) {
                }
            }
        } finally {
            // Limpieza no cancelable: si la corrutina se cancela a mitad
            // de la grabación, los recursos se liberan igualmente y la
            // vista previa se restaura.
            withContext(NonCancellable) {
                try {
                    recordingSession?.close()
                } catch (_: Exception) {
                }
                try {
                    encoder.release()
                } catch (_: Exception) {
                }
                restorePreviewOnly()
            }
        }
        stats.encoderError?.let { e ->
            throw SpikeException(
                StreamContract.ENCODER_ERROR,
                "Error del codificador: ${e.message}",
            )
        }
        // En una interrupción, un error del muxer no desecha la
        // pasada: el archivo existe y se verifica tal cual; el error
        // queda en el campo `muxerError` del resultado. En una
        // grabación normal, el mismo error sí es un fallo.
        if (shouldReportMuxerErrorAsFailure(interrupt, stats.muxerError)) {
            stats.muxerError?.let { e ->
                throw SpikeException(
                    StreamContract.MUXER_ERROR,
                    "Error del muxer: ${describeException(e)}",
                )
            }
        }
        val finalFile: File
        if (!interrupt && stoppedCleanly) {
            val renamed = File(
                tmpFile.parentFile,
                tmpFile.name.replace(".tmp.mp4", ".mp4"),
            )
            finalFile = if (tmpFile.renameTo(renamed)) renamed else tmpFile
        } else {
            finalFile = tmpFile
        }
        if (!finalFile.exists()) {
            throw SpikeException(
                StreamContract.MUXER_ERROR,
                "El muxer no creó el archivo.",
            )
        }
        val verification = verifyFileInternal(finalFile)
        val measuredDurationMs = verification["durationMs"] as? Long
        val samples = verification["sampleCount"] as? Int
        val fpsMeasured =
            if (measuredDurationMs != null && measuredDurationMs > 0 &&
                samples != null
            ) {
                samples * 1000.0 / measuredDurationMs
            } else {
                null
            }
        val sizeBytes = finalFile.length()
        val avgBitrateBps =
            if (measuredDurationMs != null && measuredDurationMs > 0) {
                sizeBytes * 8 * 1000 / measuredDurationMs
            } else {
                null
            }
        val readableDurationMs = measuredDurationMs
        val lossMs = if (interrupt) {
            if (readableDurationMs != null) {
                durationMs - readableDurationMs
            } else {
                durationMs
            }
        } else {
            null
        }
        return mapOf(
            "muxer" to kind,
            "interrupted" to interrupt,
            "fileName" to finalFile.name,
            "filePath" to finalFile.absolutePath,
            "sizeBytes" to sizeBytes,
            "durationTargetMs" to durationMs,
            "durationMs" to measuredDurationMs,
            "width" to (verification["width"] as? Int),
            "height" to (verification["height"] as? Int),
            "fpsMeasured" to fpsMeasured,
            "sampleCount" to samples,
            "keyframeCount" to (verification["keyframeCount"] as? Int),
            "firstSampleKeyframe" to
                (verification["firstSampleKeyframe"] as? Boolean),
            "ptsMonotonic" to (verification["ptsMonotonic"] as? Boolean),
            "moovPresent" to (verification["moovPresent"] as? Boolean),
            "extractorOpens" to (verification["extractorOpens"] as? Boolean),
            "exoplayerReady" to (verification["exoplayerReady"] as? Boolean),
            "exoplayerDurationMs" to
                (verification["exoplayerDurationMs"] as? Long),
            "maxWriteLatencyMs" to stats.maxWriteLatencyMs.get(),
            "avgBitrateBps" to avgBitrateBps,
            "profileIdc" to (verification["profileIdc"] as? Int),
            "cttsPresent" to (verification["cttsPresent"] as? Boolean),
            "cttsAllZero" to (verification["cttsAllZero"] as? Boolean),
            "readableDurationMs" to readableDurationMs,
            "lossMs" to lossMs,
            "encoderName" to caps.encoderName,
            "templateUsed" to "TEMPLATE_RECORD",
            "configWidth" to config.size.width,
            "configHeight" to config.size.height,
            "configFps" to fps,
            "samplesBeforeMuxer" to stats.samplesBeforeMuxer,
            "muxerError" to (stats.muxerError?.let { describeException(it) }),
            "eosTimeout" to stats.eosTimeout,
        )
    }

    /** Ejecuta `block` en el hilo del encoder y espera a que termine. */
    private suspend fun runOnEncoderThread(
        encoder: SpikeVideoEncoder,
        block: () -> Unit,
    ) {
        val done = CompletableDeferred<Unit>()
        encoder.post {
            try {
                block()
                done.complete(Unit)
            } catch (e: Exception) {
                done.completeExceptionally(e)
            }
        }
        withTimeout(OPERATION_TIMEOUT_MS) {
            done.await()
        }
    }

    /**
     * Restaura la vista previa de solo vista previa (`TEMPLATE_PREVIEW`,
     * mismo productor y misma textura). Si falla, la vista previa queda
     * sin restaurar y se registra: la pantalla lo mostrará al siguiente
     * intento.
     */
    private suspend fun restorePreviewOnly() {
        val handle = mutex.withLock { previewHandle } ?: return
        try {
            val session = CameraSession(
                cameraManager = cameraManager,
                surfaceHandle = handle,
            )
            val config = session.open()
            val info = previewInfoMap(handle, config)
            mutex.withLock {
                previewSession = session
                previewConfig = config
                previewInfo = info
            }
        } catch (e: Exception) {
            Transmiti2Logger.e(
                "spike: no se pudo restaurar la vista previa: " +
                    "${e.javaClass.simpleName} ${e.message}",
            )
            mutex.withLock {
                previewSession = null
                previewConfig = null
                previewInfo = null
            }
        }
    }

    /**
     * Verifica un archivo del spike por nombre: `MediaExtractor` (lee
     * TODAS las muestras), ExoPlayer (prepara y confirma duración) y
     * `MoovParser`. No lanza por un archivo ilegible: lo mide.
     */
    suspend fun verifyFile(fileName: String): Map<String, Any?> {
        val file = spikeFile(fileName)
        val verification = verifyFileInternal(file)
        return mapOf(
            "muxer" to "verify",
            "interrupted" to false,
            "fileName" to file.name,
            "filePath" to file.absolutePath,
            "sizeBytes" to file.length(),
            "durationTargetMs" to 0L,
            "durationMs" to (verification["durationMs"] as? Long),
            "width" to (verification["width"] as? Int),
            "height" to (verification["height"] as? Int),
            "fpsMeasured" to null,
            "sampleCount" to (verification["sampleCount"] as? Int),
            "keyframeCount" to (verification["keyframeCount"] as? Int),
            "firstSampleKeyframe" to
                (verification["firstSampleKeyframe"] as? Boolean),
            "ptsMonotonic" to (verification["ptsMonotonic"] as? Boolean),
            "moovPresent" to (verification["moovPresent"] as? Boolean),
            "extractorOpens" to (verification["extractorOpens"] as? Boolean),
            "exoplayerReady" to (verification["exoplayerReady"] as? Boolean),
            "exoplayerDurationMs" to
                (verification["exoplayerDurationMs"] as? Long),
            "maxWriteLatencyMs" to null,
            "avgBitrateBps" to null,
            "profileIdc" to (verification["profileIdc"] as? Int),
            "cttsPresent" to (verification["cttsPresent"] as? Boolean),
            "cttsAllZero" to (verification["cttsAllZero"] as? Boolean),
            "readableDurationMs" to (verification["durationMs"] as? Long),
            "lossMs" to null,
            "encoderName" to null,
            "templateUsed" to null,
            "configWidth" to null,
            "configHeight" to null,
            "configFps" to null,
            "samplesBeforeMuxer" to null,
            "muxerError" to null,
            "eosTimeout" to false,
        )
    }

    /** Archivos del spike (nombre, tamaño, última modificación). */
    fun listSpikeFiles(): List<Map<String, Any?>> {
        val dir = spikeDir()
        if (!dir.exists()) {
            return emptyList()
        }
        return dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.map {
                mapOf(
                    "name" to it.name,
                    "sizeBytes" to it.length(),
                    "lastModifiedMs" to it.lastModified(),
                )
            }
            ?: emptyList()
    }

    /** Borra todos los archivos del spike; devuelve cuántos borró. */
    fun deleteSpikeFiles(): Map<String, Any?> {
        val dir = spikeDir()
        var deleted = 0
        dir.listFiles()
            ?.filter { it.isFile }
            ?.forEach { if (it.delete()) deleted++ }
        return mapOf("deleted" to deleted)
    }

    private fun spikeFile(fileName: String): File {
        val dir = spikeDir()
        val file = File(dir, fileName)
        val canonicalDir = dir.canonicalPath
        val canonicalFile = file.canonicalPath
        require(canonicalFile.startsWith(canonicalDir + File.separator)) {
            "Nombre de archivo no válido."
        }
        require(file.isFile) {
            "Archivo no encontrado: $fileName"
        }
        return file
    }

    private fun avcEncoderCaps(): AvcCaps {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                continue
            }
            val caps = info.getCapabilitiesForType(
                MediaFormat.MIMETYPE_VIDEO_AVC,
            )
            return AvcCaps(
                encoderName = info.name,
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
            )
        }
        return AvcCaps(null, emptyList(), emptyList(), false)
    }

    private fun backCameraId(): String? {
        val ids = try {
            cameraManager.cameraIdList
        } catch (_: Exception) {
            return null
        }
        for (id in ids) {
            val characteristics = try {
                cameraManager.getCameraCharacteristics(id)
            } catch (_: Exception) {
                continue
            }
            if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                return id
            }
        }
        return null
    }

    /**
     * Verificación de un archivo cerrado (o interrumpido):
     * `MediaExtractor` abre y lee TODAS las muestras (nunca un
     * muestreo); ExoPlayer lo prepara y confirma su duración;
     * `MoovParser` informa del `moov` y de la `ctts`.
     */
    private suspend fun verifyFileInternal(
        file: File,
    ): Map<String, Any?> {
        var extractorOpens = false
        var durationMs: Long? = null
        var width: Int? = null
        var height: Int? = null
        var sampleCount: Int? = null
        var keyframeCount: Int? = null
        var firstSampleKeyframe: Boolean? = null
        var ptsMonotonic: Boolean? = null
        var profileIdc: Int? = null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            extractorOpens = true
            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME) ==
                    MediaFormat.MIMETYPE_VIDEO_AVC
                ) {
                    videoTrack = i
                    break
                }
            }
            if (videoTrack >= 0) {
                val format = extractor.getTrackFormat(videoTrack)
                width = format.intOrNull(MediaFormat.KEY_WIDTH)
                height = format.intOrNull(MediaFormat.KEY_HEIGHT)
                val durationUs = format.longOrNull(MediaFormat.KEY_DURATION)
                durationMs = durationUs?.div(1000)
                val csd0 = format.getByteBuffer("csd-0")
                if (csd0 != null && csd0.remaining() >= 4) {
                    val bytes = ByteArray(csd0.remaining())
                    csd0.duplicate().get(bytes)
                    profileIdc = bytes[1].toInt() and 0xFF
                }
                extractor.selectTrack(videoTrack)
                val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
                var count = 0
                var keys = 0
                var firstKey: Boolean? = null
                var monotonic = true
                var prevPts: Long? = null
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val flags = extractor.sampleFlags
                    val pts = extractor.sampleTime
                    val isKey = flags and
                        MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    if (firstKey == null) firstKey = isKey
                    if (isKey) keys++
                    if (prevPts != null && pts <= prevPts) {
                        monotonic = false
                    }
                    prevPts = pts
                    count++
                    extractor.advance()
                }
                sampleCount = count
                keyframeCount = keys
                firstSampleKeyframe = firstKey
                ptsMonotonic = monotonic
            }
        } catch (_: Exception) {
            extractorOpens = false
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
        val (playerReady, playerDurationMs) = verifyWithPlayer(file)
        val moov = try {
            MoovParser.parse(file.readBytes())
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
        return mapOf(
            "durationMs" to durationMs,
            "width" to width,
            "height" to height,
            "sampleCount" to sampleCount,
            "keyframeCount" to keyframeCount,
            "firstSampleKeyframe" to firstSampleKeyframe,
            "ptsMonotonic" to ptsMonotonic,
            "moovPresent" to moov.moovPresent,
            "extractorOpens" to extractorOpens,
            "exoplayerReady" to playerReady,
            "exoplayerDurationMs" to playerDurationMs,
            "profileIdc" to profileIdc,
            "cttsPresent" to moov.cttsPresent,
            "cttsAllZero" to moov.cttsAllZero,
        )
    }

    /**
     * ExoPlayer prepara el archivo y confirma su duración. Se crea y
     * se usa en el hilo principal (sus listeners llegan ahí) sin
     * bloquearlo: la espera es una suspensión con timeout de 10 s.
     */
    private suspend fun verifyWithPlayer(
        file: File,
    ): Pair<Boolean, Long?> {
        return withContext(mainDispatcher) {
            var ready = false
            var durationMs: Long? = null
            var player: ExoPlayer? = null
            try {
                val done = CompletableDeferred<Unit>()
                player = ExoPlayer.Builder(context).build()
                player.addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                ready = true
                                done.complete(Unit)
                            }
                        }

                        override fun onPlayerError(
                            error: PlaybackException,
                        ) {
                            if (!done.isCompleted) {
                                done.complete(Unit)
                            }
                        }
                    },
                )
                player.setMediaItem(
                    MediaItem.fromUri(Uri.fromFile(file)),
                )
                player.prepare()
                withTimeoutOrNull(PLAYER_TIMEOUT_MS) {
                    done.await()
                }
                if (ready) {
                    val d = player.duration
                    durationMs = if (d == C.TIME_UNSET) null else d
                }
            } catch (_: Exception) {
                ready = false
            } finally {
                try {
                    player?.release()
                } catch (_: Exception) {
                }
            }
            Pair(ready, durationMs)
        }
    }

    companion object {
        const val SPIKE_DIR_NAME = "spike"
        const val TARGET_WIDTH = 1280
        const val TARGET_HEIGHT = 720
        const val TARGET_FPS = 30
        const val TARGET_BITRATE_BPS = 2_500_000
        const val OPERATION_TIMEOUT_MS = 5_000L
        const val PLAYER_TIMEOUT_MS = 10_000L
    }
}
