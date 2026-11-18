package com.transmiti2.transmiti2

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.activity.result.contract.ActivityResultContracts
import com.transmiti2.transmiti2.streaming.PermissionLauncher
import com.transmiti2.transmiti2.streaming.PermissionManager
import com.transmiti2.transmiti2.streaming.StreamChannelHandler
import com.transmiti2.transmiti2.streaming.StreamContract
import com.transmiti2.transmiti2.streaming.spike.MuxerSpikeChannel
import com.transmiti2.transmiti2.streaming.spike.MuxerSpikeRunner
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
    // SPIKE 3.1.2 — TEMPORAL: canal propio del spike del muxer; se
    // elimina antes de cerrar 3.1.2.
    private var spikeChannel: MethodChannel? = null

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
            ),
        )
        streamingChannel = channel
        // SPIKE 3.1.2 — TEMPORAL: canal propio del spike del muxer
        // (separado del de streaming; se elimina antes de cerrar
        // 3.1.2).
        val spike = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            MuxerSpikeChannel.CHANNEL,
        )
        spike.setMethodCallHandler(
            MuxerSpikeChannel(
                MuxerSpikeRunner(
                    applicationContext,
                    getSystemService(Context.CAMERA_SERVICE) as CameraManager,
                    flutterEngine.renderer,
                ),
                streamingScope,
            ),
        )
        spikeChannel = spike
    }

    override fun onDestroy() {
        streamingChannel?.setMethodCallHandler(null)
        spikeChannel?.setMethodCallHandler(null)
        streamingScope.cancel()
        super.onDestroy()
    }
}
