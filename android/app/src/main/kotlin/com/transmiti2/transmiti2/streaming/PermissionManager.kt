package com.transmiti2.transmiti2.streaming

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Permiso gestionado por TRANSMITI2, con su nombre en el canal
 * (`camera`, `microphone`, `notifications`) y su permiso Android.
 */
enum class PermissionKind(val wireName: String, val androidPermission: String) {
    CAMERA("camera", Manifest.permission.CAMERA),
    MICROPHONE("microphone", Manifest.permission.RECORD_AUDIO),
    NOTIFICATIONS("notifications", Manifest.permission.POST_NOTIFICATIONS);

    companion object {
        fun fromWireName(value: String?): PermissionKind? =
            values().firstOrNull { it.wireName == value }
    }
}

/** Estado de un permiso, con el mismo nombre que en Dart. */
enum class PermissionStatus(val wireName: String) {
    NOT_REQUESTED("notRequested"),
    GRANTED("granted"),
    DENIED("denied"),
    PERMANENTLY_DENIED("permanentlyDenied");
}

/**
 * Clasificación pura de un estado de permiso.
 *
 * `shouldShowRequestPermissionRationale()` por sí solo NO distingue una
 * primera solicitud de una denegación permanente: en ambos casos
 * devuelve `false` antes de pedir. Por eso la clasificación necesita el
 * historial persistente de solicitudes (`everRequested`):
 * - concedido -> GRANTED
 * - nunca pedido -> NOT_REQUESTED
 * - pedido antes y rationale == true -> DENIED (todavía se puede pedir)
 * - pedido antes y rationale == false -> PERMANENTLY_DENIED
 */
object PermissionClassifier {
    fun classify(
        granted: Boolean,
        everRequested: Boolean,
        shouldShowRationale: Boolean,
    ): PermissionStatus {
        return when {
            granted -> PermissionStatus.GRANTED
            !everRequested -> PermissionStatus.NOT_REQUESTED
            shouldShowRationale -> PermissionStatus.DENIED
            else -> PermissionStatus.PERMANENTLY_DENIED
        }
    }
}

/**
 * Historial persistente de solicitudes de permiso. Solo marca qué
 * permisos se han pedido alguna vez; nunca se guarda en SQLite y nunca
 * guarda estados de permiso (esos se vuelven a consultar en cada uso).
 */
interface PermissionRequestHistory {
    fun wasRequested(kind: PermissionKind): Boolean
    fun markRequested(kind: PermissionKind)
    fun markRequested(kinds: Collection<PermissionKind>) {
        kinds.forEach { markRequested(it) }
    }
}

/** Historial en SharedPreferences (archivo propio, sin cifrar: solo booleanos). */
class SharedPreferencesPermissionHistory(context: Context) :
    PermissionRequestHistory {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun wasRequested(kind: PermissionKind): Boolean =
        prefs.getBoolean(keyFor(kind), false)

    override fun markRequested(kind: PermissionKind) {
        prefs.edit().putBoolean(keyFor(kind), true).apply()
    }

    companion object {
        const val PREFS_NAME = "transmiti2_permission_history"
        fun keyFor(kind: PermissionKind): String = "requested_${kind.wireName}"
    }
}

/**
 * Gestión de permisos en el lado nativo (subetapa 3.1.0).
 *
 * Sin lógica de cámara ni de grabación: solo consulta el estado actual,
 * decide qué permisos todavía se pueden pedir con el diálogo del sistema
 * y registra el historial de solicitudes. El diálogo en sí lo lanza la
 * Activity con un [PermissionLauncher]; este manager no guarda ninguna
 * referencia a la Activity después de crearse.
 *
 * `POST_NOTIFICATIONS` solo existe como permiso en tiempo de ejecución
 * desde API 33: por debajo se informa GRANTED (las notificaciones no
 * están bloqueadas) y nunca se pide.
 */
class PermissionManager(
    private val history: PermissionRequestHistory,
    private val isGranted: (String) -> Boolean,
    private val shouldShowRationale: (String) -> Boolean,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    /** Permisos Android que corresponden a [kind] en este dispositivo. */
    fun androidPermissionsFor(kind: PermissionKind): List<String> {
        if (kind == PermissionKind.NOTIFICATIONS && sdkInt < 33) {
            return emptyList()
        }
        return listOf(kind.androidPermission)
    }

    /** Estado actual de [kind], calculado en cada llamada. */
    fun checkState(kind: PermissionKind): PermissionStatus {
        if (kind == PermissionKind.NOTIFICATIONS && sdkInt < 33) {
            return PermissionStatus.GRANTED
        }
        val granted = isGranted(kind.androidPermission)
        return PermissionClassifier.classify(
            granted = granted,
            everRequested = history.wasRequested(kind),
            shouldShowRationale =
                if (granted) false else shouldShowRationale(kind.androidPermission),
        )
    }

    /** Estados actuales de todos los permisos (o de los indicados). */
    fun checkStates(
        kinds: Collection<PermissionKind> = PermissionKind.values().toList(),
    ): Map<PermissionKind, PermissionStatus> {
        return kinds.associateWith { checkState(it) }
    }

    /**
     * Permisos que todavía se pueden pedir con el diálogo del sistema:
     * ni concedidos ni denegados permanentemente. Los denegados
     * permanentemente no se lanzan: el diálogo del sistema ya no haría
     * nada y la UI debe ofrecer los ajustes de la aplicación.
     */
    fun requestable(kinds: Collection<PermissionKind>): List<PermissionKind> {
        return kinds.filter { kind ->
            when (checkState(kind)) {
                PermissionStatus.NOT_REQUESTED,
                PermissionStatus.DENIED,
                -> true
                PermissionStatus.GRANTED,
                PermissionStatus.PERMANENTLY_DENIED,
                -> false
            }
        }
    }

    /** Registra que los permisos indicados se van a pedir ahora. */
    fun markRequested(kinds: Collection<PermissionKind>) {
        history.markRequested(kinds)
    }

    companion object {
        /** Manager de producción, atado a la Activity visible. */
        fun create(activity: Activity): PermissionManager {
            val appContext = activity.applicationContext
            return PermissionManager(
                history = SharedPreferencesPermissionHistory(appContext),
                isGranted = { permission ->
                    ContextCompat.checkSelfPermission(
                        appContext,
                        permission,
                    ) == PackageManager.PERMISSION_GRANTED
                },
                shouldShowRationale = { permission ->
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        permission,
                    )
                },
            )
        }
    }
}

/** Lanzador del diálogo del sistema, implementado por la Activity. */
fun interface PermissionLauncher {
    /**
     * Lanza el diálogo del sistema para [permissions] y entrega el
     * resultado (permiso Android -> concedido) en [onResult].
     */
    fun launch(permissions: Array<String>, onResult: (Map<String, Boolean>) -> Unit)
}
