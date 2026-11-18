package com.transmiti2.transmiti2.streaming

import android.util.Log

/**
 * Registro de diagnóstico (Fase A) con la etiqueta única
 * "Transmiti2Camera". Sin datos sensibles: nunca claves, URLs ni
 * contenido de imágenes.
 *
 * Envoltorio mínimo sobre `android.util.Log`: en las pruebas unitarias
 * JVM puras (JUnit sin Robolectric) cualquier llamada a `Log` lanza
 * `RuntimeException` ("Method ... in android.util.Log not mocked"), así
 * que los tests instalan un receptor (`sink`) que recibe los registros
 * en vez de Logcat. En el dispositivo el receptor es nulo y todo va a
 * Logcat con el mismo tag, los mismos mensajes y el mismo nivel que
 * antes: el comportamiento en producción no cambia.
 */
object Transmiti2Logger {
    private const val TAG = "Transmiti2Camera"

    /**
     * Receptor opcional instalado solo desde tests. Recibe (nivel,
     * mensaje); nivel "d" o "e". Si es no nulo, `android.util.Log` no
     * se toca nunca. En producción es siempre nulo.
     */
    @Volatile
    var sink: ((level: String, message: String) -> Unit)? = null

    fun d(message: String) {
        val receiver = sink
        if (receiver != null) {
            receiver("d", message)
            return
        }
        Log.d(TAG, message)
    }

    fun e(message: String) {
        val receiver = sink
        if (receiver != null) {
            receiver("e", message)
            return
        }
        Log.e(TAG, message)
    }
}
