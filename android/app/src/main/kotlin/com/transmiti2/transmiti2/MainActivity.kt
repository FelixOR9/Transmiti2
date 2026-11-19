package com.transmiti2.transmiti2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.transmiti2.transmiti2.streaming.PermissionLauncher
import com.transmiti2.transmiti2.streaming.PermissionManager
import com.transmiti2.transmiti2.streaming.StreamChannelHandler
import com.transmiti2.transmiti2.streaming.StreamContract
import com.transmiti2.transmiti2.streaming.StreamForegroundService
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : FlutterFragmentActivity() {

    private val streamingScope = CoroutineScope(SupervisorJob())
    private var streamingChannel: MethodChannel? = null
    private var eventSink: EventChannel.EventSink? = null

    /** Manejador del canal de streaming (para la parada desde la notificación). */
    private var streamHandler: StreamChannelHandler? = null

    /**
     * Receptor de la parada desde la notificación (Fase B, 3.1.2, plan
     * §9.4): el servicio envía `ACTION_STOP_RECORDING` y aquí se
     * traduce en `engine.stopRecording()` (mismo camino que el botón
     * de la UI). No exportado: solo el propio servicio puede enviarlo.
     * Si la Activity está destruida, el broadcast no llega y el
     * servicio se autodestruye por su plazo diferido (60 s).
     */
    private val stopRecordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            streamHandler?.stopRecordingFromService()
        }
    }
    private var stopReceiverRegistered = false

    /** Callback pendiente del diálogo de permisos en curso, si lo hay. */
    private var pendingPermissionCallback: ((Map<String, Boolean>) -> Unit)? = null

    /**
     * Lanzador del diálogo del sistema. Se registra como propiedad (antes
     * de `onCreate`) para que sobreviva a la rotación: el resultado no se
     * pierde al recrear la Activity.
     */
    private val permissionResultLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantedMap ->
        pendingPermissionCallback?.invoke(grantedMap)
        pendingPermissionCallback = null
    }

    private val permissionLauncher = PermissionLauncher { permissions, onResult ->
        pendingPermissionCallback = onResult
        permissionResultLauncher.launch(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // El motor ya está configurado (configureFlutterEngine corre
        // dentro de super.onCreate); el receptor solo reenvía al
        // manejador cuando existe.
        val filter = IntentFilter(StreamForegroundService.ACTION_STOP_RECORDING)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                stopRecordingReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stopRecordingReceiver, filter)
        }
        stopReceiverRegistered = true
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val channel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            StreamContract.METHOD_CHANNEL,
        )
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            StreamContract.EVENT_CHANNEL,
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })
        channel.setMethodCallHandler(
            StreamChannelHandler(
                applicationContext,
                streamingScope,
                permissionManager = PermissionManager.create(this),
                permissionLauncher = permissionLauncher,
                textureRegistry = flutterEngine.renderer,
                cameraManager =
                    getSystemService(Context.CAMERA_SERVICE) as CameraManager,
                onEvent = { event -> eventSink?.success(event) },
            ).also { streamHandler = it },
        )
        streamingChannel = channel
    }

    override fun onDestroy() {
        if (stopReceiverRegistered) {
            try {
                unregisterReceiver(stopRecordingReceiver)
            } catch (_: Exception) {
            }
            stopReceiverRegistered = false
        }
        streamHandler = null
        streamingChannel?.setMethodCallHandler(null)
        streamingScope.cancel()
        super.onDestroy()
    }
}
