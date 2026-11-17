package com.transmiti2.transmiti2.streaming

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
            "foregroundServiceAvailable" to false,
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
            StreamContract.START_STREAM,
            StreamContract.START_RECORDING,
            StreamContract.STOP_RECORDING,
            -> result.error(
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
     */
    private fun startPreview(result: MethodChannel.Result) {
        val engine = previewEngine
        if (engine == null) {
            result.error(
                StreamContract.CAMERA_UNAVAILABLE,
                "Vista previa no disponible en este dispositivo.",
                null,
            )
            return
        }
        scope.launch {
            try {
                val info = engine.startPreview()
                result.success(previewInfoMap(info))
            } catch (e: CameraException) {
                result.error(e.code, e.message, null)
            } catch (e: CancellationException) {
                result.error(
                    StreamContract.CAMERA_UNAVAILABLE,
                    "Inicio de la vista previa cancelado.",
                    null,
                )
            } catch (e: Exception) {
                result.error(
                    StreamContract.CAMERA_UNAVAILABLE,
                    "No se pudo iniciar la cámara.",
                    null,
                )
            }
        }
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
