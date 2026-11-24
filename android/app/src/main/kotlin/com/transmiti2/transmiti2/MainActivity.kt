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
import com.transmiti2.transmiti2.streaming.test.TestChannelHandler
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

    /** Canal y manejador de pruebas (FASE 2, grupo 2-b; inertes sin el flag). */
    private var testingChannel: MethodChannel? = null
    private var testHandler: TestChannelHandler? = null

    /**
     * Receptor de la parada desde la notificación (Fase B, 3.1.2, plan
     * §9.4): el servicio envía `ACTION_STOP_RECORDING` y aquí se
     * traduce en `engine.stopRecordingFromNotification()` (mismo camino
     * que el botón de la UI; J3-c: la instrumentación distingue el
     * origen `notification`). No exportado: solo el propio servicio
     * puede enviarlo. Si la Activity está destruida, el broadcast no
     * llega y el servicio se autodestruye por su plazo diferido (60 s).
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

        // Canal de pruebas (FASE 2, grupo 2-b): se registra junto al de
        // streaming, con su propio manejador. Sin el flag de compilación
        // (`BuildConfig.TRANSMITI2_TEST_MODE == false`) todos sus
        // métodos salvo `getTestMode` responden `test_mode_unavailable`.
        // El manejador construye su instrumentación con el `filesDir` de
        // la app; los seams del motor (`getRegistrySnapshot`,
        // `sampleActiveStagingSize`, `injectTestError` en J3-c) se
        // cablean aquí (grupo 2-c) con lambdas que leen el motor a
        // través del manejador de streaming: MainActivity nunca toca
        // archivos de grabación directamente. Los proveedores NO
        // materializan el motor (el manejador responde «no
        // disponible» si aún no existe).
        val testingChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            TestChannelHandler.METHOD_CHANNEL,
        )
        val testHandlerInstance = TestChannelHandler(
            applicationContext.filesDir,
            appVersion = BuildConfig.VERSION_NAME,
            registrySnapshotProvider = {
                streamHandler?.testRegistrySnapshot()
                    ?: mapOf("available" to false, "reason" to "engine_not_started")
            },
            stagingSizeProvider = { streamHandler?.testActiveStagingSize() },
            // J3-c: la inyección de error solo-para-pruebas llega al
            // motor por el mismo seam que los otros proveedores (el
            // manejador de pruebas nunca toca la grabación
            // directamente). Sin motor, el proveedor responde
            // `engine_not_started` sin materializarlo.
            errorInjectionProvider = { kind ->
                streamHandler?.testInjectError(kind)
                    ?: mapOf(
                        "injected" to false,
                        "kind" to kind,
                        "reason" to "engine_not_started",
                    )
            },
        ).also { testHandler = it }
        testingChannel.setMethodCallHandler(testHandlerInstance)
        this.testingChannel = testingChannel

        // Cableado 2-c: los ganchos del canal de pruebas llegan al
        // manejador de streaming (y de ahí al motor y a
        // RecordingSession). Sin el flag de compilación los ganchos
        // son inertes (el handler con testMode=false no registra
        // nada y los puntos de pausa retornan de inmediato).
        streamHandler?.testHooks = testHandlerInstance.hooks
        // Adopción de campaña al arrancar (2-c; Revisión 1 §2.6): si
        // hay una intención válida sin consumir, auto-activa la
        // campaña en memoria antes de que la primera reconciliación
        // llegue a P0/P1. Síncrono a propósito: la adopción debe
        // completarse antes de que el motor se materialice.
        testHandlerInstance.adoptCampaignIfArmed()
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
        testHandler?.close()
        testHandler = null
        testingChannel?.setMethodCallHandler(null)
        testingChannel = null
        streamingScope.cancel()
        super.onDestroy()
    }
}
