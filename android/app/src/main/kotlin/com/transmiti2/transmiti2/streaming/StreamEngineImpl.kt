package com.transmiti2.transmiti2.streaming

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Rotación de la pantalla en grados (0, 90, 180 o 270). En API 30+ se
 * lee de `Context.display`; por debajo, del `WindowManager`. Si no se
 * puede leer, 0. Nunca lanza.
 */
fun displayRotationDegrees(context: Context): Int {
    return try {
        val rotation = if (Build.VERSION.SDK_INT >= 30) {
            context.display?.rotation ?: Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            val windowManager = context.getSystemService(
                Context.WINDOW_SERVICE,
            ) as? WindowManager
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation
                ?: Surface.ROTATION_0
        }
        when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}

/** Fábrica de sesiones de vista previa (la de producción usa Camera2). */
typealias PreviewSessionFactory = (SurfaceProducerHandle) -> PreviewSession

/**
 * Motor de vista previa real (subetapa 3.1.1).
 *
 * Orquesta, en este orden: comprobación del permiso de cámara,
 * creación del productor de superficie (`SurfaceProducer` de Flutter),
 * apertura de la sesión Camera2 y repetición de la vista previa.
 *
 * Máquina de estados:
 * - `IDLE` → `STARTING` → `ACTIVE` (vista previa en pantalla).
 * - `ACTIVE` → `RECOVERING` cuando la superficie se pierde
 *   temporalmente (`onSurfaceCleanup`): se cierra solo la sesión, el
 *   productor se conserva, y al volver la superficie
 *   (`onSurfaceAvailable`) se reabre la sesión con la superficie nueva
 *   y la misma textura. Si la reapertura falla, `IDLE` + error.
 * - Desconexión o error de cámara: se libera todo, `IDLE` y evento
 *   `preview_error`.
 * - `stopPreview` y `stopAll` son idempotentes: parar sin vista previa
 *   no falla. `startPreview` con la vista previa activa devuelve la
 *   información actual sin reabrir; dos inicios simultáneos comparten
 *   el mismo trabajo (un solo `Deferred`).
 *
 * La cámara nunca se abre sin el permiso concedido (se comprueba antes
 * de crear nada) y nunca se mantiene abierta innecesariamente: al parar
 * se cierran sesión y dispositivo y se libera el productor. La
 * grabación no existe en 3.1.1: `startStream`, `startRecording` y
 * `stopRecording` lanzan `UnsupportedOperationException` (el manejador
 * devuelve `not_available_in_phase_3_0` para ellas).
 */
class StreamEngineImpl(
    private val cameraPermissionGranted: () -> Boolean,
    private val surfaceProvider: SurfaceProducerProvider,
    private val sessionFactory: PreviewSessionFactory,
    private val displayRotationDegrees: () -> Int,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val onEvent: ((Map<String, Any?>) -> Unit)? = null,
) : StreamEngine {

    private enum class State {
        IDLE,
        STARTING,
        ACTIVE,
        RECOVERING,
    }

    private val mutex = Mutex()
    private var state = State.IDLE
    private var handle: SurfaceProducerHandle? = null
    private var session: PreviewSession? = null
    private var info: PreviewInfo? = null
    private var pendingStart: Deferred<PreviewInfo>? = null

    /**
     * Etapa del motor antes de que exista sesión (revisión 2 de la
     * Fase A): `createProducer` (crear el productor de superficie),
     * `createSession` (crear la sesión y su callback) o `session`
     * (abrir la sesión). Cuando el fallo es previo a la sesión no hay
     * diagnóstico de sesión que adjuntar a la excepción, así que el
     * manejador usa estos datos como reserva para no inventar la etapa
     * «startPreview». Se reinicia en cada `startPreview()`.
     */
    private var lastEngineStage: String = "startPreview"
    private var lastEngineExceptionClass: String? = null

    /** Diagnóstico de reserva del último intento fallido (ver arriba). */
    fun lastFailureDiagnostics(): Map<String, Any?> {
        return mapOf(
            "stage" to lastEngineStage,
            "cameraCount" to null,
            "selectedCameraId" to null,
            "selectedSize" to null,
            "selectedFps" to null,
            "exceptionClass" to lastEngineExceptionClass,
        )
    }

    /**
     * Ejecuta un paso previo a la sesión registrando su etapa: si
     * falla, la excepción se vuelve a lanzar tal cual (el manejador
     * sigue clasificándola), pero la etapa y la clase quedan guardadas
     * para `lastFailureDiagnostics()`.
     */
    private suspend fun <T> trackEngineStage(
        stage: String,
        block: suspend () -> T,
    ): T {
        lastEngineStage = stage
        return try {
            block()
        } catch (e: Exception) {
            lastEngineExceptionClass = e.javaClass.simpleName
            throw e
        }
    }

    private val surfaceCallback = object : SurfaceProducerCallback {
        override fun onSurfaceAvailable() {
            scope.launch { recoverAfterSurfaceAvailable() }
        }

        override fun onSurfaceCleanup() {
            scope.launch { handleSurfaceCleanup() }
        }
    }

    private val sessionListener = object : PreviewSessionListener {
        override fun onDisconnected() {
            scope.launch {
                teardownAfterError(StreamContract.CAMERA_DISCONNECTED)
            }
        }

        override fun onError(code: String) {
            scope.launch { teardownAfterError(code) }
        }
    }

    override suspend fun startPreview(): PreviewInfo {
        // Revisión 2: el diagnóstico de reserva empieza en
        // «startPreview» en cada intento; los pasos de `doStartPreview`
        // lo van precisando (`createProducer`, `createSession`,
        // `session`). Un intento anterior no contamina el siguiente.
        lastEngineStage = "startPreview"
        lastEngineExceptionClass = null
        if (!cameraPermissionGranted()) {
            throw CameraException(
                StreamContract.PERMISSION_DENIED,
                "Permiso de cámara no concedido.",
            )
        }
        val inFlight = mutex.withLock {
            when {
                state == State.ACTIVE && info != null -> return info!!
                pendingStart != null -> pendingStart
                else -> null
            }
        }
        if (inFlight != null) {
            return inFlight.await()
        }
        // Si se estaba recuperando de una superficie perdida, el
        // productor conservado se libera antes de empezar de nuevo:
        // nunca quedan dos productores vivos a la vez.
        val stale = mutex.withLock {
            val staleHandle = if (state == State.RECOVERING) handle else null
            val staleSession = if (state == State.RECOVERING) session else null
            if (state == State.RECOVERING) {
                handle = null
                session = null
            }
            Pair(staleHandle, staleSession)
        }
        try {
            stale.second?.close()
        } catch (_: Exception) {
        }
        stale.first?.let { releaseHandle(it) }
        val deferred = CompletableDeferred<PreviewInfo>()
        mutex.withLock {
            pendingStart = deferred
            state = State.STARTING
        }
        try {
            val result = doStartPreview()
            mutex.withLock {
                state = State.ACTIVE
                info = result
                pendingStart = null
            }
            deferred.complete(result)
            emitEvent(
                StreamContract.EVENT_PREVIEW_STARTED,
                mapOf(
                    "textureId" to result.textureId,
                    "width" to result.width,
                    "height" to result.height,
                    "fps" to result.fps,
                ),
            )
            return result
        } catch (e: Exception) {
            cleanupAfterFailedStart()
            mutex.withLock {
                state = State.IDLE
                info = null
                pendingStart = null
            }
            deferred.completeExceptionally(e)
            throw e
        }
    }

    private suspend fun doStartPreview(): PreviewInfo {
        Transmiti2Logger.d("startPreview: permiso concedido, creando productor")
        val created = trackEngineStage("createProducer") {
            withContext(mainDispatcher) {
                surfaceProvider.create()
            }
        }
        val createdSession = trackEngineStage("createSession") {
            sessionFactory(created).also {
                it.listener = sessionListener
            }
        }
        trackEngineStage("createSession") {
            created.setCallback(surfaceCallback)
        }
        val config: PreviewConfig
        try {
            config = trackEngineStage("session") {
                createdSession.open()
            }
        } catch (e: Exception) {
            // Fase B: la liberación del productor toca el motor de
            // Flutter (`unregisterTexture` exige el hilo principal, igual
            // que el envío de eventos), así que se hace en el
            // dispatcher principal y no en el hilo del `streamingScope`.
            try {
                withContext(mainDispatcher) {
                    created.setCallback(null)
                    created.release()
                }
            } catch (_: Exception) {
            }
            throw e
        }
        val rotation = (
            config.sensorOrientation - displayRotationDegrees() + 360
            ) % 360
        val result = PreviewInfo(
            textureId = created.textureId,
            width = config.size.width,
            height = config.size.height,
            sensorOrientation = config.sensorOrientation,
            rotationDegrees = rotation,
            fps = config.fpsRange.max,
            lensFacing = if (
                config.lensFacing ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
            ) {
                "front"
            } else {
                "back"
            },
        )
        mutex.withLock {
            handle = created
            session = createdSession
        }
        return result
    }

    override suspend fun stopPreview() {
        // Recursos capturados bajo el mutex; el cierre se hace fuera de
        // él para no bloquear otras transiciones de estado.
        val (wasActive, toClose, toRelease) = mutex.withLock {
            val wasActive = state == State.ACTIVE ||
                state == State.STARTING ||
                state == State.RECOVERING
            pendingStart?.cancel()
            pendingStart = null
            val toClose = session
            val toRelease = handle
            session = null
            handle = null
            info = null
            state = State.IDLE
            Triple(wasActive, toClose, toRelease)
        }
        try {
            toClose?.close()
        } catch (_: Exception) {
        }
        toRelease?.let { releaseHandle(it) }
        if (wasActive) {
            emitEvent(StreamContract.EVENT_PREVIEW_STOPPED, emptyMap())
        }
    }

    override suspend fun stopAll() {
        stopPreview()
    }

    override fun startStream(endpoint: RtmpEndpoint) {
        throw UnsupportedOperationException(
            "La transmisión llega en la Fase 3.2.",
        )
    }

    override fun startRecording(options: RecordingOptions): String {
        throw UnsupportedOperationException(
            "La grabación llega en la subetapa 3.1.2.",
        )
    }

    override fun stopRecording(): RecordingInfo {
        throw UnsupportedOperationException(
            "La grabación llega en la subetapa 3.1.2.",
        )
    }

    /**
     * La superficie se limpió (por ejemplo, al ir a segundo plano con
     * memoria baja): se cierra solo la sesión de captura; el productor
     * y su textura se conservan para la recuperación.
     */
    private suspend fun handleSurfaceCleanup() {
        val (wasActive, toClose) = mutex.withLock {
            if (state != State.ACTIVE) {
                Pair(false, null)
            } else {
                val toClose = session
                session = null
                state = State.RECOVERING
                Pair(true, toClose)
            }
        }
        try {
            toClose?.close()
        } catch (_: Exception) {
        }
        if (wasActive) {
            emitEvent(StreamContract.EVENT_PREVIEW_SURFACE_LOST, emptyMap())
        }
    }

    /**
     * La superficie volvió a estar disponible: si la vista previa estaba
     * en recuperación, se reabre la sesión con la superficie nueva
     * (siempre se pide de nuevo al productor, nunca se cachea).
     */
    private suspend fun recoverAfterSurfaceAvailable() {
        val shouldRecover = mutex.withLock {
            state == State.RECOVERING && handle != null
        }
        if (!shouldRecover) {
            return
        }
        val currentHandle = mutex.withLock { handle } ?: return
        val newSession = try {
            sessionFactory(currentHandle)
        } catch (e: Exception) {
            Transmiti2Logger.e(
                "etapa=recover ex=${e.javaClass.simpleName} msg=${e.message}",
            )
            mutex.withLock {
                state = State.IDLE
                info = null
                handle = null
            }
            releaseHandle(currentHandle)
            emitError(StreamContract.CAMERA_UNAVAILABLE, e.message)
            return
        }
        newSession.listener = sessionListener
        try {
            val config = newSession.open()
            val rotation = (
                config.sensorOrientation - displayRotationDegrees() + 360
                ) % 360
            val result = PreviewInfo(
                textureId = currentHandle.textureId,
                width = config.size.width,
                height = config.size.height,
                sensorOrientation = config.sensorOrientation,
                rotationDegrees = rotation,
                fps = config.fpsRange.max,
                lensFacing = "back",
            )
            mutex.withLock {
                session = newSession
                info = result
                state = State.ACTIVE
            }
            emitEvent(
                StreamContract.EVENT_PREVIEW_STARTED,
                mapOf(
                    "textureId" to result.textureId,
                    "width" to result.width,
                    "height" to result.height,
                    "fps" to result.fps,
                ),
            )
        } catch (e: Exception) {
            Transmiti2Logger.e(
                "etapa=recover ex=${e.javaClass.simpleName} msg=${e.message}",
            )
            mutex.withLock {
                state = State.IDLE
                info = null
                session = null
                handle = null
            }
            releaseHandle(currentHandle)
            val cameraError = e as? CameraException
            emitError(
                cameraError?.code ?: StreamContract.CAMERA_UNAVAILABLE,
                e.message,
                cameraError?.details ?: emptyMap(),
            )
        }
    }

    /** Desconexión o error de cámara: se libera todo y se avisa. */
    private suspend fun teardownAfterError(code: String) {
        val wasActive = mutex.withLock {
            val wasActive = state == State.ACTIVE ||
                state == State.STARTING ||
                state == State.RECOVERING
            val toClose = session
            val toRelease = handle
            session = null
            handle = null
            info = null
            pendingStart?.cancel()
            pendingStart = null
            state = State.IDLE
            Pair(wasActive, Pair(toClose, toRelease))
        }
        val (active, resources) = wasActive
        val (toClose, toRelease) = resources
        try {
            toClose?.close()
        } catch (_: Exception) {
        }
        toRelease?.let { releaseHandle(it) }
        if (active) {
            emitError(code)
        }
    }

    /** Limpieza tras un inicio fallido: cierra y libera lo creado. */
    private suspend fun cleanupAfterFailedStart() {
        val resources = mutex.withLock {
            val toClose = session
            val toRelease = handle
            session = null
            handle = null
            Pair(toClose, toRelease)
        }
        try {
            resources.first?.close()
        } catch (_: Exception) {
        }
        resources.second?.let { releaseHandle(it) }
    }

    /**
     * Libera el productor en el hilo principal (Fase B):
     * `release()` llega a `FlutterJNI.unregisterTexture`, marcado
     * `@UiThread` en el motor de Flutter 3.50, y en un hilo secundario
     * lanza `RuntimeException` («Methods marked with @UiThread must be
     * executed on the main thread»). Hoy esa excepción se traga aquí
     * mismo, así que no se ve en el diagnóstico, pero la textura queda
     * registrada en el motor (fuga): `releaseInternal()` ya marcó el
     * productor como liberado y un segundo `release()` no reintenta
     * `unregisterTexture`. Solo cambia el hilo en el que se ejecuta;
     * la lógica y la idempotencia no cambian.
     */
    private suspend fun releaseHandle(toRelease: SurfaceProducerHandle) {
        try {
            withContext(mainDispatcher) {
                toRelease.setCallback(null)
                toRelease.release()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Emite un evento al canal de Flutter en el hilo principal
     * (Fase B): `EventChannel.EventSink.success()` está marcado
     * `@UiThread` y termina en `FlutterJNI.dispatchPlatformMessage`,
     * que lanza `RuntimeException` fuera del hilo principal. El
     * `startPreview()` corría en el dispatcher por defecto del
     * `streamingScope`, así que anunciar el éxito de la apertura
     * (`EVENT_PREVIEW_STARTED`) fallaba DESPUÉS de abrir la cámara:
     * el `catch` cerraba la sesión que acababa de abrirse y el teléfono
     * mostraba «Cámara no disponible» con la etapa «session». Solo
     * cambia el hilo de entrega; el contenido y el orden no cambian.
     */
    private suspend fun emitEvent(type: String, payload: Map<String, Any?>) {
        val handler = onEvent ?: return
        val event = mapOf("type" to type) + payload
        withContext(mainDispatcher) {
            handler(event)
        }
    }

    /**
     * Error asíncrono de vista previa. Fase A: el evento lleva también
     * el mensaje técnico (`message`) y los `details` de diagnóstico
     * cuando el motor los tiene; sin ellos, solo el código (es el caso
     * de `teardownAfterError`, que solo recibe el código del
     * `PreviewSessionListener`). El código emitido no cambia.
     */
    private suspend fun emitError(
        code: String,
        message: String? = null,
        details: Map<String, Any?> = emptyMap(),
    ) {
        val payload = mutableMapOf<String, Any?>("code" to code)
        if (message != null) {
            payload["message"] = message
        }
        payload.putAll(details)
        emitEvent(StreamContract.EVENT_PREVIEW_ERROR, payload)
    }
}
