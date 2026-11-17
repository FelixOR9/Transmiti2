package com.transmiti2.transmiti2.streaming

import io.flutter.view.TextureRegistry

/**
 * Callback de ciclo de vida de la superficie de vista previa.
 *
 * Es el espejo propio de `TextureRegistry.SurfaceProducer.Callback`:
 * `onSurfaceAvailable` (antes `onSurfaceCreated`, deprecado en Flutter
 * 3.27) y `onSurfaceCleanup` (antes `onSurfaceDestroyed`, deprecado en
 * Flutter 3.28). La superficie puede perderse temporalmente (por
 * ejemplo, al ir a segundo plano con memoria baja) y volver a estar
 * disponible después: el motor debe cerrar la sesión de captura al
 * limpiarse y recrearla al estar disponible, sin liberar el productor.
 */
interface SurfaceProducerCallback {
    fun onSurfaceAvailable()
    fun onSurfaceCleanup()
}

/**
 * Superficie de vista previa gestionada por el motor de Flutter.
 *
 * Envuelve `TextureRegistry.SurfaceProducer` (verificado en el código
 * del motor de Flutter 3.50.0): `createSurfaceProducer()` sin
 * argumentos usa `SurfaceLifecycle.manual` (la última imagen se
 * conserva al ir a segundo plano), el backend es `ImageReader` en API
 * ≥ 29 y `SurfaceTexture` como reserva, y `getSurface()` no debe
 * cachearse: la superficie puede cambiar al redimensionarse o al
 * recrearse, así que se pide cada vez que se necesita.
 *
 * La interfaz existe para poder probar el motor sin Android: la
 * implementación de producción devuelve el `Surface` real y las pruebas
 * usan un centinela opaco.
 */
interface SurfaceProducerHandle {
    /** Identificador de textura de Flutter para el widget `Texture`. */
    val textureId: Long

    /** Tamaño de la textura en píxeles físicos. */
    fun setSize(width: Int, height: Int)

    /**
     * La superficie de dibujo actual, como objeto opaco. En producción
     * es un `android.view.Surface`; en pruebas, un centinela.
     */
    fun surface(): Any?

    fun setCallback(callback: SurfaceProducerCallback?)
    fun release()
}

/** Crea productores de superficie de vista previa. */
fun interface SurfaceProducerProvider {
    fun create(): SurfaceProducerHandle
}

/** Productor real, sobre el `TextureRegistry` del motor de Flutter. */
class TextureRegistrySurfaceProducerProvider(
    private val registry: TextureRegistry,
) : SurfaceProducerProvider {
    override fun create(): SurfaceProducerHandle {
        return FlutterSurfaceProducerHandle(registry.createSurfaceProducer())
    }
}

/** Envoltorio del `SurfaceProducer` real del motor de Flutter. */
class FlutterSurfaceProducerHandle(
    private val producer: TextureRegistry.SurfaceProducer,
) : SurfaceProducerHandle {
    override val textureId: Long get() = producer.id()

    override fun setSize(width: Int, height: Int) {
        producer.setSize(width, height)
    }

    override fun surface(): Any? = producer.surface

    override fun setCallback(callback: SurfaceProducerCallback?) {
        if (callback == null) {
            producer.setCallback(null)
            return
        }
        producer.setCallback(
            object : TextureRegistry.SurfaceProducer.Callback {
                override fun onSurfaceAvailable() {
                    callback.onSurfaceAvailable()
                }

                override fun onSurfaceCleanup() {
                    callback.onSurfaceCleanup()
                }
            },
        )
    }

    override fun release() {
        producer.release()
    }
}
