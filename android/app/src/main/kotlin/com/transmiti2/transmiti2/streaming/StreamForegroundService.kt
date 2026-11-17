package com.transmiti2.transmiti2.streaming

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Servicio en primer plano para la transmisión (Fase 3.0: esqueleto).
 *
 * En esta fase NO está registrado en el manifiesto y NO se arranca nunca:
 * no hay cámara, transmisión ni grabación. Existe para que el contrato y
 * la estructura estén preparados.
 *
 * Delta de la Fase 3.1 (documentado, no implementado):
 * - Añadir al manifiesto:
 *   `<service android:name=".streaming.StreamForegroundService"
 *     android:foregroundServiceType="camera|microphone" />`
 * - Añadir los permisos `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`,
 *   `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` y `WAKE_LOCK`.
 * - Arrancarlo solo desde un toque del usuario en una Activity visible,
 *   con CAMERA y RECORD_AUDIO ya concedidos.
 * - `startForeground()` debe ser la primera operación en
 *   `onStartCommand()`, con la notificación ya construida, y dentro de
 *   los 5 segundos siguientes a `startForegroundService()`. Si se supera
 *   ese plazo, el sistema detiene el servicio y declara la app ANR.
 * - No hacer I/O, red ni leer secretos antes de `startForeground()`.
 */
class StreamForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Esqueleto de la Fase 3.0: no hace nada y se detiene.
        stopSelf()
        return START_NOT_STICKY
    }
}
