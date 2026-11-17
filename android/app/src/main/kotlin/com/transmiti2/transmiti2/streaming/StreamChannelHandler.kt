package com.transmiti2.transmiti2.streaming

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
 */
class StreamChannelHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val storeFactory: () -> SecureCredentialStore = {
        TinkSecureCredentialStore(context)
    },
) : MethodChannel.MethodCallHandler {

    private var store: SecureCredentialStore? = null

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

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            StreamContract.GET_STATUS -> result.success(statusMap())
            StreamContract.CHECK_PERMISSIONS -> result.success(
                mapOf(
                    "cameraGranted" to isGranted(Manifest.permission.CAMERA),
                    "microphoneGranted" to
                        isGranted(Manifest.permission.RECORD_AUDIO),
                ),
            )
            StreamContract.REQUEST_PERMISSIONS -> result.error(
                StreamContract.PERMISSION_REQUEST_DEFERRED,
                "Los permisos se piden en la Fase 3.1.",
                null,
            )
            StreamContract.SAVE_STREAM_KEY -> saveStreamKey(call, result)
            StreamContract.DELETE_STREAM_KEY -> deleteStreamKey(call, result)
            StreamContract.HAS_STREAM_KEY -> hasStreamKey(call, result)
            StreamContract.START_PREVIEW,
            StreamContract.START_STREAM,
            StreamContract.START_RECORDING,
            StreamContract.STOP_ALL,
            -> result.error(
                StreamContract.NOT_AVAILABLE_IN_PHASE_3_0,
                "Función no disponible en la Fase 3.0.",
                null,
            )
            else -> result.notImplemented()
        }
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
