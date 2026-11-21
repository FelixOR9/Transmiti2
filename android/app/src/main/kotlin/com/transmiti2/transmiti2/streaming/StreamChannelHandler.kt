package com.transmiti2.transmiti2.streaming

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.transmiti2.transmiti2.streaming.recording.RecordingException
import com.transmiti2.transmiti2.streaming.recording.RecordingsDirReplySink
import com.transmiti2.transmiti2.streaming.recording.launchGetRecordingsDir
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

/** Error de inicio de vista previa ya traducido al contrato. */
internal data class MappedPreviewError(
    val code: String,
    val message: String,
    val details: Map<String, Any?>,
)

/**
 * Diferencia un fallo de `startPreview` (Fase A) sin cambiar la lógica
 * de apertura/cierre. El orden es el contrato: `CameraException` ya
 * trae su código; `TimeoutCancellationException` ANTES que
 * `CancellationException` (es su subclase) y que `IllegalStateException`
 * (superclase de `CancellationException`); una cancelación que no es
 * timeout sigue siendo `camera_unavailable` («cancelado»).
 *
 * Los `details` viajan en el `PlatformException` para que Dart muestre
 * la etapa, la cámara y la configuración elegidas. Lo desconocido es
 * `null`: nunca se inventa un valor.
 *
 * Revisión 2: cuando la excepción no trae datos propios (fallo previo
 * a la sesión), `fallbackDetails` aporta la etapa real que registró el
 * motor (`createProducer`, `createSession`, `session`); sin reserva, la
 * etapa es «startPreview». Y el mensaje genérico «No se pudo iniciar
 * la cámara.» solo se usa cuando la excepción no trae mensaje: antes se
 * descartaba siempre el mensaje real, por eso la tarjeta del teléfono
 * solo mostraba el genérico aunque la excepción dijera otra cosa.
 */
internal fun mapStartPreviewError(
    e: Throwable,
    fallbackDetails: Map<String, Any?> = emptyMap(),
): MappedPreviewError {
    val fallbackStage = fallbackDetails["stage"] as? String
    val detailsWithoutSession = mapOf<String, Any?>(
        "stage" to (fallbackStage ?: "startPreview"),
        "cameraCount" to fallbackDetails["cameraCount"],
        "selectedCameraId" to fallbackDetails["selectedCameraId"],
        "selectedSize" to fallbackDetails["selectedSize"],
        "selectedFps" to fallbackDetails["selectedFps"],
        "exceptionClass" to e.javaClass.simpleName,
    )
    return when (e) {
        is CameraException -> MappedPreviewError(
            code = e.code,
            message = e.message ?: "No se pudo iniciar la cámara.",
            details = if (e.details.isEmpty()) {
                detailsWithoutSession
            } else {
                e.details
            },
        )
        is TimeoutCancellationException -> MappedPreviewError(
            code = StreamContract.CAMERA_TIMEOUT,
            message = "Tiempo de espera agotado al iniciar la cámara (5 s).",
            details = detailsWithoutSession,
        )
        is CancellationException -> MappedPreviewError(
            code = StreamContract.CAMERA_UNAVAILABLE,
            message = "Inicio de la vista previa cancelado.",
            details = detailsWithoutSession,
        )
        is IllegalArgumentException -> MappedPreviewError(
            code = StreamContract.CAMERA_CONFIG_ERROR,
            message = "Configuración de cámara no válida: ${e.message}",
            details = detailsWithoutSession,
        )
        is IllegalStateException -> MappedPreviewError(
            code = StreamContract.CAMERA_STATE_ERROR,
            message = "La cámara o la sesión no está en un estado " +
                "válido: ${e.message}",
            details = detailsWithoutSession,
        )
        else -> MappedPreviewError(
            code = StreamContract.CAMERA_UNAVAILABLE,
            message = e.message ?: "No se pudo iniciar la cámara.",
            details = detailsWithoutSession,
        )
    }
}

/**
 * Mapa del resultado de `stopRecording` para el canal de métodos.
 * Función pura de nivel superior (como `mapStartPreviewError`):
 * lleva los mismos campos que el evento `recording_stopped`
 * (Grupo V, V1). Si falta uno, Dart sobrescribe el valor real del
 * evento con el defecto (diagnóstico ronda 3, §2). Es `internal`
 * para que el test del camino real la ejerza directamente: un campo
 * que se suelte aquí debe romper el test, no solo el teléfono.
 * Un `null` viaja como `null` y Dart lo muestra como `—`: nunca se
 * inventa un valor.
 */
internal fun recordingInfoMap(info: RecordingInfo): Map<String, Any?> {
    return mapOf(
        "recordingId" to info.recordingId,
        "filePath" to info.filePath,
        "durationMs" to info.durationMs,
        "sizeBytes" to info.sizeBytes,
        "status" to info.status,
        // Segmentos verificados (paso 5): la confirmación de la
        // parada ya no depende de los eventos recibidos.
        "segments" to info.segments.map { it.toMap() },
        // Instrumentación de audio 3.1.3 (grupos I/U y Grupo V):
        // los mismos campos que `RecordingResult.toMap()`.
        "droppedInputBuffers" to info.droppedInputBuffers,
        "inputBufferCapacityBytes" to info.inputBufferCapacityBytes,
        "micStateAtStart" to info.micStateAtStart,
        "micStateAtStop" to info.micStateAtStop,
        "audioReadCount" to info.audioReadCount,
        "audioLastReadCode" to info.audioLastReadCode,
        "audioLastReadOkAgeMs" to info.audioLastReadOkAgeMs,
        "audioReaderAlive" to info.audioReaderAlive,
        "audioEos" to info.audioEos,
        "audioReaderNoData2s" to info.audioReaderNoData2s,
        "eosTimedOut" to info.eosTimedOut,
        "quietestSecondPeak" to info.quietestSecondPeak,
        "quietestSecondNonZero" to info.quietestSecondNonZero,
        // Grupo S (S1): el pico del disparo de silencio (esperado
        // 0); `null` si no hubo disparo.
        "silencePeak" to info.silencePeak,
    )
}

/**
 * Manejador del canal `com.transmiti2.transmiti2/streaming`.
 *
 * Fase 3.0: responde `getStatus` y `checkPermissions` (solo lectura, sin
 * mostrar diálogos), guarda/borra la clave cifrada de un destino y
 * devuelve `not_available_in_phase_3_0` para todo lo de video. La clave
 * nunca se registra en logs ni se devuelve a Dart.
 *
 * Subetapa 3.1.0: `checkPermissions` devuelve los estados
 * (`notRequested`, `granted`, `denied`, `permanentlyDenied`) calculados
 * por [PermissionManager] (permiso concedido + historial persistente de
 * solicitudes + `shouldShowRequestPermissionRationale()`),
 * `requestPermissions` lanza el diálogo del sistema a través del
 * [PermissionLauncher] de la Activity y `openAppSettings` abre los
 * ajustes de la aplicación. Los métodos de video siguen devolviendo
 * `not_available_in_phase_3_0`.
 *
 * Subetapa 3.1.1: `startPreview` y `stopPreview` son reales (Camera2 +
 * `SurfaceProducer` a través de [StreamEngineImpl]) y `stopAll` detiene
 * también la vista previa. La grabación sigue devolviendo
 * `not_available_in_phase_3_0`. La cámara nunca se abre sin el permiso
 * concedido: el motor lo comprueba antes de crear nada.
 *
 * Fase A (diagnóstico instrumentado): `startPreview` diferencia los
 * fallos (`camera_timeout`, `camera_config_error`,
 * `camera_state_error`, `camera_disabled_by_system`) y devuelve sus
 * `details` a Dart; solo instrumentación, sin corrección funcional.
 *
 * Fase B, 3.1.2: `startRecording`, `stopRecording`,
 * `getRecordingStatus` y `recoverInterruptedRecordings` son reales
 * (grabación local sin audio, FGS tipo `camera`); `startStream` sigue
 * devolviendo `not_available_in_phase_3_0` (Fase 3.2). En 3.1.3 el
 * audio pasa real al motor (plan §6 D8–D9): el escenario del FGS y el
 * audio efectivo se deciden por grabación según el permiso de
 * micrófono.
 */
class StreamChannelHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val storeFactory: () -> SecureCredentialStore = {
        TinkSecureCredentialStore(context)
    },
    private val permissionManager: PermissionManager,
    private val permissionLauncher: PermissionLauncher? = null,
    private val textureRegistry: TextureRegistry? = null,
    private val cameraManager: CameraManager? = null,
    private val onEvent: ((Map<String, Any?>) -> Unit)? = null,
    /**
     * Dispatcher del hilo principal para responder por el canal
     * (subetapa 0, 3.1.4): `getRecordingsDir()` responde desde Main
     * con `replyOnce`, igual que `StreamEngineImpl` y
     * `SegmentVerifier` ya lo tienen. Inyectable para las pruebas.
     */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : MethodChannel.MethodCallHandler {

    private var store: SecureCredentialStore? = null

    /** Motor de vista previa real (3.1.1), creado perezosamente. */
    private val previewEngine: StreamEngineImpl? by lazy {
        val registry = textureRegistry ?: return@lazy null
        val manager = cameraManager ?: return@lazy null
        StreamEngineImpl(
            cameraPermissionGranted = {
                permissionManager.checkState(PermissionKind.CAMERA) ==
                    PermissionStatus.GRANTED
            },
            // 3.1.3, plan §6 D8: espejo de la cámara; decide el
            // escenario del FGS junto con `audioEnabled`.
            microphonePermissionGranted = {
                permissionManager.checkState(PermissionKind.MICROPHONE) ==
                    PermissionStatus.GRANTED
            },
            surfaceProvider = TextureRegistrySurfaceProducerProvider(registry),
            sessionFactory = { handle ->
                CameraSession(
                    cameraManager = manager,
                    surfaceHandle = handle,
                )
            },
            displayRotationDegrees = { displayRotationDegrees(context) },
            scope = scope,
            onEvent = onEvent,
            // Fase B, 3.1.2: la grabación se construye con el contexto
            // de la app (almacenamiento y verificación) y el control
            // real del servicio en primer plano.
            context = context,
            cameraManager = manager,
            foregroundService = RealForegroundServiceControl(context),
        )
    }

    /** Resultado pendiente de una solicitud de permisos en curso. */
    private var pendingPermissionResult: MethodChannel.Result? = null

    /** Últimos estados emitidos por eventos, para detectar cambios. */
    private var lastEmittedStates: Map<PermissionKind, PermissionStatus>? = null

    private fun storeOrThrow(): SecureCredentialStore {
        return store ?: storeFactory().also { store = it }
    }

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun statusMap(): Map<String, Boolean> {
        return mapOf(
            // Fase B, 3.1.2: el FGS está disponible cuando el motor
            // tiene sesión de grabación y control del servicio.
            "foregroundServiceAvailable" to
                (previewEngine?.foregroundServiceAvailable() ?: false),
            "cameraGranted" to isGranted(Manifest.permission.CAMERA),
            "microphoneGranted" to isGranted(Manifest.permission.RECORD_AUDIO),
            // Desde la 3.1.1 hay vista previa real cuando el motor existe.
            "streamingSupported" to (previewEngine != null),
        )
    }

    private fun statesMap(
        states: Map<PermissionKind, PermissionStatus>,
    ): Map<String, String> {
        return states.entries.associate { (kind, status) ->
            kind.wireName to status.wireName
        }
    }

    private fun emitEvent(type: String, permission: PermissionKind, status: PermissionStatus) {
        onEvent?.invoke(
            mapOf(
                "type" to type,
                "permission" to permission.wireName,
                "status" to status.wireName,
            ),
        )
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            StreamContract.GET_STATUS -> result.success(statusMap())
            StreamContract.CHECK_PERMISSIONS -> checkPermissions(result)
            StreamContract.REQUEST_PERMISSIONS -> requestPermissions(call, result)
            StreamContract.OPEN_APP_SETTINGS -> openAppSettings(result)
            StreamContract.SAVE_STREAM_KEY -> saveStreamKey(call, result)
            StreamContract.DELETE_STREAM_KEY -> deleteStreamKey(call, result)
            StreamContract.HAS_STREAM_KEY -> hasStreamKey(call, result)
            StreamContract.START_PREVIEW -> startPreview(result)
            StreamContract.STOP_PREVIEW -> stopPreview(result)
            StreamContract.STOP_ALL -> stopAll(result)
            StreamContract.START_RECORDING -> startRecording(call, result)
            StreamContract.STOP_RECORDING -> stopRecording(result)
            StreamContract.GET_RECORDING_STATUS -> getRecordingStatus(result)
            StreamContract.RECOVER_INTERRUPTED_RECORDINGS ->
                recoverInterruptedRecordings(result)
            StreamContract.GET_RECORDINGS_DIR -> getRecordingsDir(call, result)
            StreamContract.START_STREAM ->
                result.error(
                    StreamContract.NOT_AVAILABLE_IN_PHASE_3_0,
                    "Función no disponible en la Fase 3.0.",
                    null,
                )
            else -> result.notImplemented()
        }
    }

    /**
     * Abre la cámara y devuelve `{textureId, width, height,
     * sensorOrientation, rotationDegrees, fps, lensFacing}`. La apertura
     * es asíncrona (Camera2): el resultado se responde cuando la sesión
     * está configurada, no antes.
     *
     * Fase A: el fallo se diferencia con [mapStartPreviewError]
     * (`TimeoutCancellationException` ANTES que `CancellationException`)
     * y viaja con sus `details` de diagnóstico; el registro deja la
     * etapa y la clase de la excepción.
     */
    private fun startPreview(result: MethodChannel.Result) {
        val engine = previewEngine
        Transmiti2Logger.d("startPreview: engine=${engine != null}")
        if (engine == null) {
            result.error(
                StreamContract.CAMERA_UNAVAILABLE,
                "Vista previa no disponible en este dispositivo.",
                mapOf<String, Any?>(
                    "stage" to "startPreview",
                    "cameraCount" to null,
                    "selectedCameraId" to null,
                    "selectedSize" to null,
                    "selectedFps" to null,
                    "exceptionClass" to null,
                ),
            )
            return
        }
        scope.launch {
            try {
                val info = engine.startPreview()
                result.success(previewInfoMap(info))
            } catch (e: CameraException) {
                failPreview(result, e, engine.lastFailureDiagnostics())
            } catch (e: TimeoutCancellationException) {
                failPreview(result, e, engine.lastFailureDiagnostics())
            } catch (e: CancellationException) {
                failPreview(result, e, engine.lastFailureDiagnostics())
            } catch (e: IllegalArgumentException) {
                failPreview(result, e, engine.lastFailureDiagnostics())
            } catch (e: IllegalStateException) {
                failPreview(result, e, engine.lastFailureDiagnostics())
            } catch (e: Exception) {
                failPreview(result, e, engine.lastFailureDiagnostics())
            }
        }
    }

    /**
     * Registra el fallo de `startPreview` y lo devuelve con su código.
     * Revisión 2: la reserva de diagnóstico del motor viaja al mapeo
     * para que un fallo previo a la sesión muestre su etapa real en
     * vez de la inventada «startPreview».
     */
    private fun failPreview(
        result: MethodChannel.Result,
        e: Throwable,
        fallbackDetails: Map<String, Any?> = emptyMap(),
    ) {
        val mapped = mapStartPreviewError(e, fallbackDetails)
        Transmiti2Logger.e(
            "etapa=${mapped.details["stage"]} " +
                "ex=${e.javaClass.simpleName} " +
                "msg=${e.message} codigo=${mapped.code}",
        )
        result.error(mapped.code, mapped.message, mapped.details)
    }

    private fun previewInfoMap(info: PreviewInfo): Map<String, Any> {
        return mapOf(
            "textureId" to info.textureId,
            "width" to info.width,
            "height" to info.height,
            "sensorOrientation" to info.sensorOrientation,
            "rotationDegrees" to info.rotationDegrees,
            "fps" to info.fps,
            "lensFacing" to info.lensFacing,
        )
    }

    /**
     * Cierra la vista previa. Idempotente: parar sin vista previa no
     * falla. La respuesta se envía cuando el cierre terminó.
     */
    private fun stopPreview(result: MethodChannel.Result) {
        scope.launch {
            try {
                previewEngine?.stopPreview()
            } catch (_: Exception) {
            }
            result.success(null)
        }
    }

    /**
     * Parada de emergencia: detiene la vista previa (y, en 3.1.2, la
     * grabación y el servicio). Nunca falla por no haber nada que parar.
     */
    private fun stopAll(result: MethodChannel.Result) {
        scope.launch {
            try {
                previewEngine?.stopAll()
            } catch (_: Exception) {
            }
            result.success(null)
        }
    }

    /**
     * Inicia la grabación y devuelve el `recordingId` (Fase B, 3.1.2).
     * El audio se pasa tal cual lo pide Dart (3.1.3, plan §6 D9): el
     * motor decide el escenario del FGS y el audio efectivo según el
     * permiso de micrófono; sin micrófono la grabación arranca
     * video-only, nunca se rechaza por pedir audio. El error nativo
     * viaja con su código, su mensaje y sus `details` (etapa,
     * segmento, archivo) como los errores de vista previa de la
     * Fase A.
     */
    private fun startRecording(call: MethodCall, result: MethodChannel.Result) {
        val engine = previewEngine
        if (engine == null) {
            result.error(
                StreamContract.CAMERA_UNAVAILABLE,
                "Grabación no disponible en este dispositivo.",
                null,
            )
            return
        }
        val audioEnabled = call.argument<Boolean>("audioEnabled") ?: false
        val options = RecordingOptions(
            matchId = (call.argument<Any>("matchId") as? Number)?.toLong(),
            resolution = call.argument<String>("resolution") ?: "720p",
            fps = (call.argument<Any>("fps") as? Number)?.toInt() ?: 30,
            videoBitrateBps =
                (call.argument<Any>("videoBitrateBps") as? Number)?.toInt()
                    ?: 2_500_000,
            audioEnabled = audioEnabled,
        )
        scope.launch {
            try {
                result.success(engine.startRecording(options))
            } catch (e: Exception) {
                failRecording(result, e)
            }
        }
    }

    /**
     * Detiene la grabación y devuelve `{recordingId, filePath,
     * durationMs, sizeBytes, status}` (Fase B, 3.1.2). Idempotente
     * desde `IDLE` (devuelve el resultado vacío, `status = "idle"`).
     */
    private fun stopRecording(result: MethodChannel.Result) {
        val engine = previewEngine
        if (engine == null) {
            result.error(
                StreamContract.CAMERA_UNAVAILABLE,
                "Grabación no disponible en este dispositivo.",
                null,
            )
            return
        }
        scope.launch {
            try {
                result.success(recordingInfoMap(engine.stopRecording()))
            } catch (e: Exception) {
                failRecording(result, e)
            }
        }
    }

    /**
     * Estado actual de la grabación (Fase B, 3.1.2): la UI lo consulta
     * al volver a la pantalla para re-sincronizarse con el motor (la
     * grabación continúa por el FGS aunque la UI se haya ido).
     */
    private fun getRecordingStatus(result: MethodChannel.Result) {
        val engine = previewEngine
        if (engine == null) {
            result.success(
                mapOf<String, Any?>(
                    "state" to "idle",
                    "recordingId" to null,
                    "elapsedMs" to 0,
                    "segmentIndex" to 0,
                    "segmentDurationMs" to 0,
                    "width" to null,
                    "height" to null,
                    "fps" to null,
                    "errorCode" to null,
                    "segments" to emptyList<Any>(),
                ),
            )
            return
        }
        scope.launch {
            result.success(engine.recordingStatusMap())
        }
    }

    /**
     * Ruta canónica de grabaciones (subetapa 0, 3.1.4, grupo 0-b):
     * `<filesDir>/recordings/` o `recordings_dir_unavailable`.
     *
     * Enrutado síncrono en Main: solo se lee `context.filesDir` en
     * memoria (la nulidad es V1) y el número de intento que Dart pasa
     * para el diagnóstico del timeout; sin tocar disco ni
     * `previewEngine` (el motor no se materializa: esta rama no lo
     * necesita). El trabajo V1–V4c va a `Dispatchers.IO` y la
     * respuesta vuelve a Main con `replyOnce`, exactamente una vez
     * (ver [launchGetRecordingsDir]; revisión 4 §3.5).
     */
    private fun getRecordingsDir(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        val attempt = (call.argument<Any>("attempt") as? Number)?.toInt()
            ?: 1
        val filesDir = try {
            context.filesDir
        } catch (_: Exception) {
            null
        }
        // El flujo es JVM-puro y responde por un `sink`; aquí solo se
        // adapta el `MethodChannel.Result` real.
        val replySink = object : RecordingsDirReplySink {
            override fun success(path: String) {
                result.success(path)
            }

            override fun error(
                code: String,
                message: String?,
                details: Map<String, Any?>?,
            ) {
                result.error(code, message, details)
            }
        }
        launchGetRecordingsDir(
            scope = scope,
            mainDispatcher = mainDispatcher,
            filesDir = filesDir,
            attempt = attempt,
            replySink = replySink,
        )
    }

    /**
     * Clasifica los `.tmp.mp4` que quedaron de una muerte del proceso
     * (Fase B, 3.1.2, plan §7.3). Nunca borra ni modifica nada; Dart
     * actualiza las filas de `recordings` con la clasificación.
     */
    private fun recoverInterruptedRecordings(result: MethodChannel.Result) {
        val engine = previewEngine
        if (engine == null) {
            result.success(mapOf<String, Any?>("recovered" to emptyList<Any>()))
            return
        }
        scope.launch {
            try {
                val recovered = engine.recoverInterruptedRecordings()
                result.success(
                    mapOf<String, Any?>(
                        "recovered" to recovered.map {
                            engine.recoveredSegmentMap(it)
                        },
                    ),
                )
            } catch (e: Exception) {
                failRecording(result, e)
            }
        }
    }

    /**
     * Parada de la grabación desde la notificación (plan §9.4): la
     * acción "Detener" llega por broadcast a `MainActivity` y termina
     * aquí. Mismo camino que el botón de la UI; nunca lanza.
     */
    fun stopRecordingFromService() {
        scope.launch {
            try {
                previewEngine?.stopRecording()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Registra el fallo de `startRecording` / `stopRecording` y lo
     * devuelve con su código. `RecordingException` y `CameraException`
     * ya traen su código, su mensaje y sus `details`; lo desconocido
     * es `recording_state_error` (nunca se inventa un código).
     */
    private fun failRecording(result: MethodChannel.Result, e: Throwable) {
        when (e) {
            is RecordingException -> {
                Transmiti2Logger.e(
                    "etapa=${e.details["stage"]} ex=${e.javaClass.simpleName} " +
                        "msg=${e.message} codigo=${e.code}",
                )
                result.error(e.code, e.message, e.details)
            }
            is CameraException -> {
                Transmiti2Logger.e(
                    "ex=${e.javaClass.simpleName} msg=${e.message} " +
                        "codigo=${e.code}",
                )
                result.error(e.code, e.message, e.details)
            }
            else -> {
                Transmiti2Logger.e(
                    "ex=${e.javaClass.simpleName} msg=${e.message} " +
                        "codigo=${StreamContract.RECORDING_STATE_ERROR}",
                )
                result.error(
                    StreamContract.RECORDING_STATE_ERROR,
                    e.message ?: "No se pudo grabar.",
                    null,
                )
            }
        }
    }

    private fun checkPermissions(result: MethodChannel.Result) {
        val states = permissionManager.checkStates()
        val previous = lastEmittedStates
        if (previous != null) {
            for ((kind, status) in states) {
                if (previous[kind] != status) {
                    emitEvent(
                        StreamContract.EVENT_PERMISSION_CHANGED,
                        kind,
                        status,
                    )
                }
            }
        }
        lastEmittedStates = states
        result.success(statesMap(states))
    }

    private fun requestPermissions(call: MethodCall, result: MethodChannel.Result) {
        if (pendingPermissionResult != null) {
            // Ya hay una solicitud en curso: no se lanza otro diálogo del
            // sistema (perdería el resultado pendiente). Se devuelven los
            // estados actuales sin bloquear el futuro de Dart.
            result.success(statesMap(permissionManager.checkStates()))
            return
        }
        val requested = parseKinds(call.argument<List<Any>>("permissions"))
        val toRequest = permissionManager.requestable(requested)
        if (toRequest.isEmpty()) {
            result.success(statesMap(permissionManager.checkStates()))
            return
        }
        val launcher = permissionLauncher
        if (launcher == null) {
            result.error(
                StreamContract.PERMISSION_DENIED,
                "Sin lanzador de permisos en esta Activity.",
                null,
            )
            return
        }
        // Se marca como pedido ANTES de lanzar el diálogo: si el usuario
        // deniega, la siguiente consulta ya distingue la denegación de la
        // primera solicitud.
        permissionManager.markRequested(toRequest)
        pendingPermissionResult = result
        val androidPermissions = toRequest
            .flatMap { permissionManager.androidPermissionsFor(it) }
            .toTypedArray()
        launcher.launch(androidPermissions) {
            pendingPermissionResult = null
            val states = permissionManager.checkStates()
            for (kind in toRequest) {
                val status = states[kind] ?: PermissionStatus.NOT_REQUESTED
                emitEvent(StreamContract.EVENT_PERMISSION_RESULT, kind, status)
            }
            lastEmittedStates = states
            result.success(statesMap(states))
        }
    }

    private fun parseKinds(raw: List<Any>?): List<PermissionKind> {
        if (raw.isNullOrEmpty()) {
            return PermissionKind.values().toList()
        }
        val parsed = raw.mapNotNull { PermissionKind.fromWireName(it as? String) }
        return parsed.ifEmpty { PermissionKind.values().toList() }
    }

    private fun openAppSettings(result: MethodChannel.Result) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        result.success(null)
    }

    private fun saveStreamKey(call: MethodCall, result: MethodChannel.Result) {
        val destinationId = (call.argument<Any>("destinationId") as? Number)
            ?.toLong()
        val key = call.argument<String>("key")
        if (destinationId == null) {
            result.error(StreamContract.DESTINATION_NOT_FOUND, null, null)
            return
        }
        if (key.isNullOrEmpty()) {
            result.error(StreamContract.EMPTY_KEY, null, null)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                storeOrThrow().save(destinationId, key.toByteArray(Charsets.UTF_8))
                result.success(null)
            } catch (e: SecureStoreUnavailableException) {
                result.error(e.code, null, null)
            } catch (e: Exception) {
                result.error(StreamContract.SECURE_STORE_UNAVAILABLE, null, null)
            }
        }
    }

    private fun deleteStreamKey(call: MethodCall, result: MethodChannel.Result) {
        val destinationId = (call.argument<Any>("destinationId") as? Number)
            ?.toLong()
        if (destinationId == null) {
            result.error(StreamContract.DESTINATION_NOT_FOUND, null, null)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                storeOrThrow().delete(destinationId)
                result.success(null)
            } catch (e: SecureStoreUnavailableException) {
                result.error(e.code, null, null)
            } catch (e: Exception) {
                result.error(StreamContract.SECURE_STORE_UNAVAILABLE, null, null)
            }
        }
    }

    private fun hasStreamKey(call: MethodCall, result: MethodChannel.Result) {
        val destinationId = (call.argument<Any>("destinationId") as? Number)
            ?.toLong()
        if (destinationId == null) {
            result.error(StreamContract.DESTINATION_NOT_FOUND, null, null)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                result.success(storeOrThrow().has(destinationId))
            } catch (e: SecureStoreUnavailableException) {
                result.error(e.code, null, null)
            } catch (e: Exception) {
                result.error(StreamContract.SECURE_STORE_UNAVAILABLE, null, null)
            }
        }
    }
}
