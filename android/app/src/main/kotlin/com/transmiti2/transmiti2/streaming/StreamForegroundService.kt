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
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.transmiti2.transmiti2.MainActivity

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
 * Todavía no hay sesión de grabación: si el servicio arranca en 3.1.0
 * se detiene a sí mismo tras promoverse a primer plano. En 3.1.2 aquí
 * arranca la captura y el servicio sigue vivo hasta que la grabación
 * para. El arranque real de una grabación mediante el servicio NO está
 * validado en 3.1.0.
 *
 * Sin límite de tiempo: el límite de 6 h de Android 15 aplica solo a
 * `dataSync` y `mediaProcessing`; `camera` y `microphone` no lo tienen.
 */
class StreamForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

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
            stopSelf()
            return START_NOT_STICKY
        }
        // 3.1.0: sin sesión de grabación todavía; el servicio se detiene.
        // En 3.1.2 aquí arranca la captura.
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("TRANSMITI2")
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
     * Adquiere el wake lock parcial durante la grabación (lo usa 3.1.2).
     * La grabación continúa con la pantalla apagada (teléfono en trípode
     * durante el partido). Sin usar en 3.1.0.
     */
    @Suppress("unused")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TRANSMITI2:recording",
        ).apply { acquire() }
    }

    /** Libera el wake lock al parar la grabación (lo usa 3.1.2). */
    @Suppress("unused")
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
    }
}
