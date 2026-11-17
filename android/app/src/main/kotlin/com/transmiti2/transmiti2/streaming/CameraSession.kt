package com.transmiti2.transmiti2.streaming

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Apertura de un dispositivo de cámara, inyectable para las pruebas. */
fun interface CameraDeviceOpener {
    @Throws(CameraAccessException::class)
    fun openCamera(
        cameraId: String,
        callback: CameraDevice.StateCallback,
        handler: Handler?,
    )
}

/** Avisos de desconexión o error de una sesión de vista previa. */
interface PreviewSessionListener {
    fun onDisconnected()
    fun onError(code: String)
}

/**
 * Sesión de vista previa Camera2.
 *
 * `open()` recibe la superficie de vista previa como parámetro y, de
 * forma opcional, la superficie de salida de video. En la subetapa
 * 3.1.1 la salida de video es `null` (solo preview); en 3.1.2 será la
 * superficie de entrada del codificador y, cuando llegue la composición
 * EGL, la superficie de entrada del compositor. Ni el codificador ni el
 * compositor se mencionan aquí: la sesión no fija su destino de video.
 */
interface PreviewSession {
    /**
     * Abre la cámara y arranca la repetición de vista previa. Idempotente:
     * si ya está abierta, devuelve la configuración actual sin reabrir.
     */
    suspend fun open(): PreviewConfig

    /** Cierra sesión y dispositivo. Idempotente y seguro desde cualquier hilo. */
    fun close()

    val isOpen: Boolean

    var listener: PreviewSessionListener?
}

/** Traduce `CameraAccessException` al código de error del contrato. */
fun mapCameraAccessException(e: CameraAccessException): CameraException {
    return when (e.reason) {
        CameraAccessException.CAMERA_IN_USE,
        CameraAccessException.MAX_CAMERAS_IN_USE,
        -> CameraException(
            StreamContract.CAMERA_IN_USE,
            "La cámara está en uso por otra aplicación.",
        )
        CameraAccessException.CAMERA_DISABLED ->
            CameraException(
                StreamContract.CAMERA_UNAVAILABLE,
                "La cámara está desactivada en este dispositivo.",
            )
        CameraAccessException.CAMERA_DISCONNECTED ->
            CameraException(
                StreamContract.CAMERA_DISCONNECTED,
                "La cámara se desconectó.",
            )
        else ->
            CameraException(
                StreamContract.CAMERA_UNAVAILABLE,
                "La cámara no está disponible.",
            )
    }
}

/**
 * Sesión real de vista previa con Camera2 (subetapa 3.1.1).
 *
 * - Cámara trasera por defecto: se prefiere la cámara lógica
 *   multi-cámara si el dispositivo la expone, si no la primera trasera.
 *   La cámara frontal queda diferida (no se implementa en 3.1.1).
 * - La configuración (resolución y FPS) se elige con
 *   [PreviewConfigSelector] de las capacidades reales del dispositivo.
 * - Enfoque continuo si el dispositivo lo ofrece (imprescindible para
 *   una vista previa estable); si no, automático, si no, apagado.
 * - Los callbacks de Camera2 corren en un `HandlerThread` propio
 *   ("Transmiti2Camera"), compartido durante toda la vida del proceso.
 * - `close()` es idempotente: cerrar dos veces no falla y no deja
 *   callbacks pendientes sobre superficies destruidas.
 */
class CameraSession(
    private val cameraManager: CameraManager,
    private val surfaceHandle: SurfaceProducerHandle,
    private val videoOutputSurface: Surface? = null,
    private val opener: CameraDeviceOpener = CameraDeviceOpener { id, callback, handler ->
        cameraManager.openCamera(id, callback, handler)
    },
    private val handler: Handler = defaultCameraHandler(),
) : PreviewSession {

    private val lock = Any()
    private var device: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var currentConfig: PreviewConfig? = null

    @Volatile
    override var isOpen: Boolean = false
        private set

    override var listener: PreviewSessionListener? = null

    override suspend fun open(): PreviewConfig {
        synchronized(lock) {
            currentConfig?.let { if (isOpen) return it }
        }
        val cameraId = selectBackCamera()
            ?: throw CameraException(
                StreamContract.CAMERA_UNAVAILABLE,
                "Este dispositivo no tiene cámara trasera.",
            )
        val characteristics = try {
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (e: CameraAccessException) {
            throw mapCameraAccessException(e)
        }
        val config = selectConfig(cameraId, characteristics)
            ?: throw CameraException(
                StreamContract.CAMERA_UNAVAILABLE,
                "Sin configuración de vista previa compatible.",
            )
        val opened = openDevice(cameraId)
        try {
            // El tamaño se fija ANTES de pedir la superficie: el
            // productor crea su ImageReader al primer getSurface() y
            // después ya no se puede cambiar el tamaño de la superficie
            // que usa la sesión.
            surfaceHandle.setSize(config.size.width, config.size.height)
            val previewSurface = surfaceHandle.surface() as? Surface
                ?: throw CameraException(
                    StreamContract.CAMERA_UNAVAILABLE,
                    "Sin superficie de vista previa.",
                )
            // La superficie se pide UNA vez y es la misma para la sesión
            // y para la petición de repetición: Camera2 exige que el
            // destino de la petición sea una de las superficies de la
            // sesión. (El productor puede recrear su superficie, pero
            // dentro de una sesión abierta siempre es la misma.)
            val session = createCaptureSession(opened, config, previewSurface)
            startRepeating(
                opened,
                session,
                characteristics,
                config,
                previewSurface,
            )
            synchronized(lock) {
                device = opened
                captureSession = session
                currentConfig = config
                isOpen = true
            }
            return config
        } catch (e: Exception) {
            try {
                opened.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    override fun close() {
        synchronized(lock) {
            try {
                captureSession?.close()
            } catch (_: Exception) {
            }
            try {
                device?.close()
            } catch (_: Exception) {
            }
            captureSession = null
            device = null
            currentConfig = null
            isOpen = false
        }
    }

    /**
     * Cámara trasera: primero la lógica multi-cámara (si el dispositivo
     * la expone), si no la primera trasera. Nunca ultra-angular ni tele
     * por defecto.
     */
    private fun selectBackCamera(): String? {
        val ids = try {
            cameraManager.cameraIdList
        } catch (e: CameraAccessException) {
            return null
        }
        var fallback: String? = null
        for (id in ids) {
            val characteristics = try {
                cameraManager.getCameraCharacteristics(id)
            } catch (_: Exception) {
                continue
            }
            if (characteristics.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                continue
            }
            val capabilities = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            ) ?: IntArray(0)
            if (capabilities.contains(
                    CameraCharacteristics
                        .REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
                )
            ) {
                return id
            }
            if (fallback == null) {
                fallback = id
            }
        }
        return fallback
    }

    private fun selectConfig(
        cameraId: String,
        characteristics: CameraCharacteristics,
    ): PreviewConfig? {
        val map = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        ) ?: return null
        // La superficie del productor es ImageReader en API ≥ 29 y
        // SurfaceTexture como reserva: el tamaño elegido debe estar en
        // ambas listas cuando las dos existen.
        val textureSizes = map.getOutputSizes(SurfaceTexture::class.java)
            ?.map { VideoSize(it.width, it.height) }
            ?: emptyList()
        val readerSizes = try {
            map.getOutputSizes(ImageReader::class.java)
                ?.map { VideoSize(it.width, it.height) }
                ?: emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        val sizes = when {
            textureSizes.isNotEmpty() && readerSizes.isNotEmpty() ->
                textureSizes.intersect(readerSizes.toSet()).toList()
            textureSizes.isNotEmpty() -> textureSizes
            else -> readerSizes
        }
        val ranges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
        )?.map { FpsRange(it.lower, it.upper) } ?: emptyList()
        return PreviewConfigSelector.select(
            cameraId = cameraId,
            sensorOrientation = characteristics.get(
                CameraCharacteristics.SENSOR_ORIENTATION,
            ) ?: 90,
            lensFacing = characteristics.get(
                CameraCharacteristics.LENS_FACING,
            ) ?: CameraCharacteristics.LENS_FACING_BACK,
            outputSizes = sizes,
            fpsRanges = ranges,
        )
    }

    private suspend fun openDevice(cameraId: String): CameraDevice {
        return try {
            withTimeout(OPEN_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val callback = object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if (cont.isActive) {
                                cont.resume(camera)
                            } else {
                                // El inicio se canceló mientras se abría:
                                // no queda nadie que use el dispositivo.
                                try {
                                    camera.close()
                                } catch (_: Exception) {
                                }
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    CameraException(
                                        StreamContract.CAMERA_DISCONNECTED,
                                        "La cámara se desconectó al abrirla.",
                                    ),
                                )
                            } else {
                                close()
                                listener?.onDisconnected()
                            }
                            try {
                                camera.close()
                            } catch (_: Exception) {
                            }
                        }

                        override fun onError(
                            camera: CameraDevice,
                            error: Int,
                        ) {
                            val code = when (error) {
                                ERROR_CAMERA_IN_USE,
                                ERROR_MAX_CAMERAS_IN_USE,
                                -> StreamContract.CAMERA_IN_USE
                                else -> StreamContract.CAMERA_UNAVAILABLE
                            }
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    CameraException(code, "Error al abrir la cámara."),
                                )
                            } else {
                                close()
                                listener?.onError(code)
                            }
                            try {
                                camera.close()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    opener.openCamera(cameraId, callback, handler)
                    cont.invokeOnCancellation {
                        // Si se cancela la espera, no queda nada abierto:
                        // el dispositivo aún no se entregó.
                    }
                }
            }
        } catch (e: CameraAccessException) {
            throw mapCameraAccessException(e)
        } catch (e: SecurityException) {
            throw CameraException(
                StreamContract.PERMISSION_DENIED,
                "Permiso de cámara no concedido.",
            )
        }
    }

    private suspend fun createCaptureSession(
        opened: CameraDevice,
        config: PreviewConfig,
        previewSurface: Surface,
    ): CameraCaptureSession {
        val surfaces = buildList {
            add(previewSurface)
            videoOutputSurface?.let { add(it) }
        }
        return withTimeout(SESSION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(
                        session: CameraCaptureSession,
                    ) {
                        if (cont.isActive) {
                            cont.resume(session)
                        } else {
                            // El inicio se canceló mientras se configuraba.
                            try {
                                session.close()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    override fun onConfigureFailed(
                        session: CameraCaptureSession,
                    ) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                CameraException(
                                    StreamContract.CAMERA_UNAVAILABLE,
                                    "No se pudo configurar la sesión de cámara.",
                                ),
                            )
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    val executor = Executor { command ->
                        handler.post(command)
                    }
                    val sessionConfig = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        surfaces.map { OutputConfiguration(it) },
                        executor,
                        callback,
                    )
                    opened.createCaptureSession(sessionConfig)
                } else {
                    @Suppress("DEPRECATION")
                    opened.createCaptureSession(surfaces, callback, handler)
                }
            }
        }
    }

    private fun startRepeating(
        opened: CameraDevice,
        session: CameraCaptureSession,
        characteristics: CameraCharacteristics,
        config: PreviewConfig,
        previewSurface: Surface,
    ) {
        val builder = opened.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW,
        ).apply {
            addTarget(previewSurface)
            videoOutputSurface?.let { addTarget(it) }
            set(
                CaptureRequest.CONTROL_AF_MODE,
                afMode(characteristics),
            )
            val ranges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
            )
            if (!ranges.isNullOrEmpty()) {
                set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(config.fpsRange.min, config.fpsRange.max),
                )
            }
        }
        session.setRepeatingRequest(builder.build(), null, handler)
    }

    private fun afMode(characteristics: CameraCharacteristics): Int {
        val modes = characteristics.get(
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
        ) ?: return CaptureRequest.CONTROL_AF_MODE_OFF
        return when {
            modes.contains(
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            ) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            modes.contains(
                CaptureRequest.CONTROL_AF_MODE_AUTO,
            ) -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
    }

    companion object {
        const val OPEN_TIMEOUT_MS = 5_000L
        const val SESSION_TIMEOUT_MS = 5_000L

        private val cameraThread: HandlerThread by lazy {
            HandlerThread("Transmiti2Camera").also { it.start() }
        }

        fun defaultCameraHandler(): Handler = Handler(cameraThread.looper)
    }
}
