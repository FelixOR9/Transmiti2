package com.transmiti2.transmiti2.streaming

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
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
 */
class StreamChannelHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val storeFactory: () -> SecureCredentialStore = {
        TinkSecureCredentialStore(context)
    },
    private val permissionManager: PermissionManager,
    private val permissionLauncher: PermissionLauncher? = null,
    private val onEvent: ((Map<String, Any?>) -> Unit)? = null,
) : MethodChannel.MethodCallHandler {

    private var store: SecureCredentialStore? = null

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
            "streamingSupported" to false,
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
            StreamContract.START_PREVIEW,
            StreamContract.STOP_PREVIEW,
            StreamContract.START_STREAM,
            StreamContract.START_RECORDING,
            StreamContract.STOP_RECORDING,
            StreamContract.STOP_ALL,
            -> result.error(
                StreamContract.NOT_AVAILABLE_IN_PHASE_3_0,
                "Función no disponible en la Fase 3.0.",
                null,
            )
            else -> result.notImplemented()
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
