package com.transmiti2.transmiti2.streaming

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.transmiti2.transmiti2.MainActivity

/**
 * Control del servicio en primer plano, detrás de una interfaz para
 * poder sustituirlo en las pruebas del motor (el servicio real necesita
 * un `Context` y un `startForegroundService()` que no existen en JVM).
 *
 * - `startRecordingService(scenario)`: pre-comprobación de permisos y
 *   arranque del servicio con el escenario dado (`WITH_AUDIO` exige
 *   CAMERA y RECORD_AUDIO; `WITHOUT_AUDIO` solo CAMERA); `false` si
 *   falta algún permiso (el llamador devuelve `permission_denied` y no
 *   graba nada).
 * - `stopRecordingService()`: detiene el servicio (inofensivo si no
 *   está corriendo).
 */
interface ForegroundServiceControl {
    fun startRecordingService(scenario: RecordingScenario): Boolean
    fun stopRecordingService()
}

/** Control real del servicio (producción): delega en el compañero. */
class RealForegroundServiceControl(
    private val context: Context,
) : ForegroundServiceControl {
    override fun startRecordingService(scenario: RecordingScenario): Boolean {
        return StreamForegroundService.start(context, scenario)
    }

    override fun stopRecordingService() {
        StreamForegroundService.stop(context)
    }
}

/**
 * Escenario de grabación del servicio en primer plano.
 *
 * - [WITH_AUDIO]: tipos `camera|microphone`. Requiere CAMERA **y**
 *   RECORD_AUDIO concedidos antes de arrancar.
 * - [WITHOUT_AUDIO]: tipo `camera` solamente. Requiere CAMERA concedido
 *   antes de arrancar.
 */
enum class RecordingScenario(val wireName: String) {
    WITH_AUDIO("with_audio"),
    WITHOUT_AUDIO("without_audio");

    companion object {
        fun fromWireName(value: String?): RecordingScenario? =
            values().firstOrNull { it.wireName == value }
    }
}

/**
 * Pre-comprobaciones del servicio en primer plano (subetapa 3.1.0).
 *
 * Lógica pura y comprobable sin teléfono: qué permisos exige cada
 * escenario, si se puede arrancar y qué máscara de tipos usar. Si falta
 * algún permiso, el servicio NO se arranca y se devuelve
 * `permission_denied` (arrancar sin ellos daría `SecurityException`).
 * El arranque real de una grabación mediante el servicio NO está
 * validado en 3.1.0: se verifica en la subetapa 3.1.2.
 */
object ForegroundServicePrecheck {
    fun requiredPermissions(scenario: RecordingScenario): List<String> =
        when (scenario) {
            RecordingScenario.WITH_AUDIO ->
                listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            RecordingScenario.WITHOUT_AUDIO ->
                listOf(Manifest.permission.CAMERA)
        }

    fun missingPermissions(
        scenario: RecordingScenario,
        isGranted: (String) -> Boolean,
    ): List<String> = requiredPermissions(scenario).filter { !isGranted(it) }

    fun canStart(
        scenario: RecordingScenario,
        isGranted: (String) -> Boolean,
    ): Boolean = missingPermissions(scenario, isGranted).isEmpty()

    /**
     * Máscara de tipos para `startForeground(id, notification, type)`.
     * En API < 29 los tipos de servicio en primer plano no existen: se
     * devuelve 0 y el servicio usa la llamada de dos argumentos.
     */
    fun foregroundServiceType(scenario: RecordingScenario, sdkInt: Int): Int {
        if (sdkInt < 29) return 0
        return when (scenario) {
            RecordingScenario.WITH_AUDIO ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            RecordingScenario.WITHOUT_AUDIO ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
    }
}

/**
 * Servicio en primer plano para la transmisión y la grabación.
 *
 * Subetapa 3.1.0: preparación de la arquitectura. El servicio ya está
 * registrado en el manifiesto con
 * `foregroundServiceType="camera|microphone"`, construye su notificación
 * (canal propio, acción "Detener", tap que abre la app) y cumple la
 * regla de los 5 segundos: `startForeground()` es la primera operación
 * de `onStartCommand()`, con la notificación ya construida. No se hace
 * I/O, red ni se leen secretos antes.
 *
 * Fase B, 3.1.2: arranque real con una grabación. El servicio deja de
 * autodestruirse: vive hasta que la grabación para (el motor lo detiene
 * con `stop()` al terminar `stopRecording`) o hasta que el usuario toca
 * "Detener" en la notificación. Al arrancar se adquiere el wake lock
 * parcial (la grabación continúa con la pantalla apagada) y la
 * notificación muestra el tiempo transcurrido, actualizado cada
 * segundo. La acción "Detener" NO posee el motor (vive en
 * `MainActivity`): envía el broadcast `ACTION_STOP_RECORDING`, que la
 * Activity traduce en `engine.stopRecording()` (el mismo camino que el
 * botón de la UI), y programa una autodestrucción diferida (60 s) por
 * si el broadcast no llega (Activity destruida): en ese caso la
 * grabación queda interrumpida pero clasificada al reabrir (§7.2 del
 * plan); nunca se pierde un segmento ya cerrado. El plazo de 60 s es
 * mayor que el timeout de verificación de un segmento (30 s), así que
 * una parada normal (que detiene el servicio desde el motor) nunca lo
 * alcanza.
 *
 * Sin límite de tiempo: el límite de 6 h de Android 15 aplica solo a
 * `dataSync` y `mediaProcessing`; `camera` y `microphone` no lo tienen.
 */
class StreamForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Inicio de la grabación en curso (reloj monótono) para el tiempo
     * transcurrido de la notificación; 0 si no hay grabación.
     */
    private var recordingStartElapsedMs: Long = 0

    /**
     * Escenario de la grabación en curso (con o sin audio): se fija al
     * arrancar el servicio y `updateNotification()` lo usa en cada
     * refresco, para que el título no vuelva a "sin audio" al primer
     * segundo cuando se graba con audio (3.1.3, plan §6.1). La acción
     * "Detener" llega sin escenario y no lo toca.
     */
    private var currentScenario: RecordingScenario =
        RecordingScenario.WITHOUT_AUDIO

    /** Actualización del tiempo de la notificación, cada segundo. */
    private val tickerHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (recordingStartElapsedMs == 0L) return
            updateNotification()
            tickerHandler.postDelayed(this, TICKER_INTERVAL_MS)
        }
    }

    /** Autodestrucción diferida tras la acción "Detener" (ver arriba). */
    private val delayedStop = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // PRIMERA operación: startForeground con la notificación ya
        // construida, dentro de los 5 s desde startForegroundService().
        val scenario =
            RecordingScenario.fromWireName(intent?.getStringExtra(EXTRA_SCENARIO))
                ?: RecordingScenario.WITHOUT_AUDIO
        val notification = buildNotification(scenario)
        val type = ForegroundServicePrecheck.foregroundServiceType(
            scenario,
            Build.VERSION.SDK_INT,
        )
        if (type == 0) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification, type)
        }
        if (intent?.action == ACTION_STOP) {
            // La parada la hace el motor (mismo camino que el botón de
            // la UI): se avisa por broadcast y se programa la
            // autodestrucción diferida por si nadie escucha.
            sendBroadcast(Intent(ACTION_STOP_RECORDING))
            tickerHandler.postDelayed(delayedStop, DELAYED_STOP_MS)
            return START_NOT_STICKY
        }
        // Arranque real de una grabación (3.1.2): el servicio vive
        // hasta que la grabación para; wake lock y ticker en marcha.
        // Se guarda el escenario con el que arrancó (3.1.3): la
        // notificación lo usa en cada refresco.
        currentScenario = scenario
        if (recordingStartElapsedMs == 0L) {
            recordingStartElapsedMs = SystemClock.elapsedRealtime()
        }
        acquireWakeLock()
        tickerHandler.postDelayed(ticker, TICKER_INTERVAL_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tickerHandler.removeCallbacks(ticker)
        tickerHandler.removeCallbacks(delayedStop)
        recordingStartElapsedMs = 0
        currentScenario = RecordingScenario.WITHOUT_AUDIO
        releaseWakeLock()
        super.onDestroy()
    }

    /** Refresca la notificación con el tiempo transcurrido actual. */
    private fun updateNotification() {
        val notification = buildNotification(currentScenario)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(scenario: RecordingScenario): Notification {
        createChannelIfNeeded()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, StreamForegroundService::class.java)
                .setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (scenario) {
            RecordingScenario.WITH_AUDIO -> "Grabando partido (con audio)"
            RecordingScenario.WITHOUT_AUDIO -> "Grabando partido (sin audio)"
        }
        // Tiempo transcurrido (plan §9.3): mientras hay grabación en
        // curso se muestra `mm:ss`; antes de arrancar, el texto fijo.
        val contentText = if (recordingStartElapsedMs == 0L) {
            "TRANSMITI2"
        } else {
            val elapsedMs =
                SystemClock.elapsedRealtime() - recordingStartElapsedMs
            val totalSeconds = (elapsedMs / 1000).toInt()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            "TRANSMITI2 · %02d:%02d".format(minutes, seconds)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stop)
            .setOngoing(true)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Grabación",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description =
                        "Muestra la grabación en curso y permite detenerla."
                },
            )
        }
    }

    /**
     * Adquiere el wake lock parcial durante la grabación (3.1.2). La
     * grabación continúa con la pantalla apagada (teléfono en trípode
     * durante el partido).
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TRANSMITI2:recording",
        ).apply { acquire() }
    }

    /** Libera el wake lock al parar la grabación (3.1.2). */
    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val CHANNEL_ID = "transmiti2_recording"
        const val NOTIFICATION_ID = 1
        const val EXTRA_SCENARIO = "scenario"
        const val ACTION_STOP = "com.transmiti2.transmiti2.streaming.STOP"

        /**
         * Broadcast que el servicio envía al tocar "Detener" en la
         * notificación (plan §9.4): el servicio no posee el motor, así
         * que `MainActivity` lo escucha y lo traduce en
         * `engine.stopRecording()`. Broadcast normal (no ordenado) con
         * receptor no exportado en la Activity.
         */
        const val ACTION_STOP_RECORDING =
            "com.transmiti2.transmiti2.streaming.STOP_RECORDING"

        /** Intervalo de actualización del tiempo de la notificación. */
        const val TICKER_INTERVAL_MS = 1000L

        /**
         * Autodestrucción diferida tras la acción "Detener", por si el
         * broadcast no llega (Activity destruida). Mayor que el timeout
         * de verificación de un segmento (30 s), así que una parada
         * normal nunca lo alcanza.
         */
        const val DELAYED_STOP_MS = 60_000L

        /**
         * Arranca el servicio solo si la pre-comprobación pasa. Devuelve
         * `false` sin arrancar nada si falta algún permiso del escenario
         * (el llamador devuelve `permission_denied`). Solo se llama desde
         * un toque del usuario en una Activity visible; prohibido
         * arrancar desde segundo plano.
         *
         * En 3.1.0 nadie lo llama todavía: el arranque real se ejerce en
         * 3.1.2 con la grabación.
         */
        fun start(context: Context, scenario: RecordingScenario): Boolean {
            val isGranted: (String) -> Boolean = { permission ->
                ContextCompat.checkSelfPermission(
                    context,
                    permission,
                ) == PackageManager.PERMISSION_GRANTED
            }
            if (!ForegroundServicePrecheck.canStart(scenario, isGranted)) {
                return false
            }
            val intent = Intent(context, StreamForegroundService::class.java)
                .putExtra(EXTRA_SCENARIO, scenario.wireName)
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        /**
         * Detiene el servicio (3.1.2: lo llama el motor al terminar
         * `stopRecording`). Inofensivo si el servicio no está
         * corriendo.
         */
        fun stop(context: Context) {
            context.stopService(
                Intent(context, StreamForegroundService::class.java),
            )
        }
    }
}
