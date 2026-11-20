package com.transmiti2.transmiti2.streaming.recording

import android.media.MediaCodec
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Trama PCM lista para alimentar al codec AAC (ronda 3 de audio
 * 3.1.3, grupo F): copia del buffer de `AudioRecord.read()` con su
 * PTS ya calculado desde el ancla y su número de muestras. El hilo
 * lector produce tramas; el hilo del callback del codec las consume.
 * El `bytes` es una COPIA: el buffer de lectura se reutiliza en cada
 * `read()`.
 *
 * Grupo W: un trozo hereda el origen de su trama (`originPtsUs`,
 * `originOffsetSamples`) para que su PTS se calcule en absoluto
 * desde el PTS de la trama original, sin acumular el truncado entero
 * de cada frontera (con el cálculo encadenado, 4 trozos de 512
 * muestras quedarían en 21 332 / 31 998 µs en vez de 21 333 /
 * 32 000 µs).
 */
data class PcmFrame(
    val bytes: ByteArray,
    val size: Int,
    val ptsUs: Long,
    val samples: Long,
    /**
     * PTS de la primera muestra de la trama ORIGINAL de la que esta
     * trama/trozo procede (Grupo W). Una trama del lector lleva su
     * propio PTS; un trozo hereda el de su trama.
     */
    val originPtsUs: Long = ptsUs,
    /**
     * Muestras de la trama original ya alimentadas antes de la
     * primera muestra de esta trama/trozo (Grupo W). Una trama del
     * lector lleva 0; cada resto suma las muestras de su trozo.
     */
    val originOffsetSamples: Long = 0,
)

/**
 * Sumidero de buffers de entrada del codec AAC (abstrae
 * `MediaCodec` para que el drenado sea testeable en JVM sin codec
 * real).
 */
interface InputBufferSink {
    fun getInputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(
        index: Int,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        flags: Int,
    )
}

/**
 * Feed de entrada AAC por `onInputBufferAvailable` (ronda 3 de
 * audio 3.1.3, grupo F): la vía asíncrona correcta. `dequeueInputBuffer`
 * lanza `IllegalStateException` en modo asíncrono, así que el hilo
 * lector ya no toca el codec: produce tramas PCM y el drenado, que
 * corre SOLO en el hilo del callback, las entrega con los buffers
 * que el callback pone a disposición.
 *
 * - **Cola PCM** (`pcmQueue`, capacidad 128 tramas ≈ 2,7 s de audio
 *   con trozos de 1024 muestras, Grupo W): si está llena al
 *   encolar, se descarta la trama NUEVA y `droppedInputBuffers++`
 *   (se preserva el orden; la sesión ve el hueco por el salto de
 *   PTS).
 * - **Troceo (Grupo W):** si el buffer de entrada es menor que la
 *   trama PCM, la trama se trocea a la capacidad viva del buffer
 *   (muestras completas) y el resto vuelve al frente de la cola con
 *   su PTS; solo se descarta contada si ni una muestra cabe
 *   (capacidad degenerada). La capacidad se registra en log y en
 *   `inputBufferCapacityBytes` en el primer drenado con buffer no
 *   nulo.
 * - **Pool de buffers libres** (`freeBuffers`): solo lo toca el hilo
 *   del callback. `onInputBufferAvailable` añade el índice y drena.
 * - **EOS:** solo se encola con la cola PCM vacía y una sola vez
 *   (`eosQueued`), así que siempre va después de la última trama.
 *   Con el lector bloqueado (H1) y la cola vacía, el EOS se encola
 *   en cuanto haya un buffer libre y la parada deja de tardar 5 s.
 * - **Sin tramas no se encola nada:** los índices libres quedan en
 *   el pool; no hay giro en vacío ni buffers vacíos sin EOS.
 * - **`getInputBuffer == null`:** se cuenta como descarte
 *   (`droppedInputBuffers++`) y el buffer se devuelve encolándolo
 *   vacío; nunca se deja un índice sin devolver.
 * - **Ronda U — el drenado nunca lanza:** cada iteración va en
 *   `try/catch`. Ante una excepción del sumidero (`getInputBuffer` /
 *   `queueInputBuffer` sobre un codec parado o liberado), la trama se
 *   cuenta como descartada una sola vez, el índice vuelve al pool y
 *   se avisa a `onDrainError`; el drenado de esta ronda se detiene.
 *   Sin esta guarda, la excepción escapaba del `MediaCodec.Callback`
 *   y mataba el proceso (cierre al grabar, ronda 3).
 * - **Ronda U — capacidad del buffer:** la capacidad del buffer de
 *   entrada se registra en log y en `inputBufferCapacityBytes` en
 *   el primer drenado con buffer no nulo. Grupo W: si el buffer es
 *   menor que la trama PCM, la trama se trocea (ver `drain()`), no
 *   se descarta entera; el `BufferOverflowException` sigue siendo
 *   imposible por construcción.
 *
 * Hilos: `offerFrame` / `requestEos` los llama el hilo lector (o
 * quien pide la parada); `onInputBufferAvailable` / `drain` SOLO el
 * hilo del callback. `clear()` lo llama `release()`.
 */
class AudioInputFeed(
    private val sink: InputBufferSink,
    /**
     * Frecuencia de muestreo (Grupo W): el drenado calcula el PTS
     * de cada trozo como `frame.ptsUs + muestras × 1e6 / sampleRate`.
     * En producción siempre es `config.sampleRate`; el valor por
     * defecto (48 kHz) solo existe para que los tests actuales sigan
     * compilando.
     */
    private val sampleRate: Int = AUDIO_TARGET_SAMPLE_RATE,
    private val maxFrames: Int = MAX_PCM_FRAMES,
) {
    private val pcmQueue = ArrayDeque<PcmFrame>()
    private val freeBuffers = ArrayDeque<Int>()

    /** Se pidió el fin de la entrada (`signalEndOfInputStream`). */
    @Volatile
    var eosRequested = false
        private set

    /** El EOS ya se encoló (una sola vez). Solo hilo del callback. */
    private var eosQueued = false

    /**
     * Entradas descartadas: trama nueva con la cola llena, `getInputBuffer
     * == null`, trama que no cabe ni una muestra en el buffer de
     * entrada (capacidad degenerada, Grupo W), o error del sumidero
     * al drenar (ronda U). Lo lee la sesión al parar (mismo contador
     * que antes alimentaba `feedPcm`).
     */
    private val dropped = AtomicInteger(0)
    val droppedInputBuffers: Int get() = dropped.get()

    /**
     * Handler de errores de drenado (ronda U): el encoder lo conecta
     * a su `reportInputError` para degradar a video-only. Sin él, la
     * excepción solo se traga (el drenado no propaga nunca).
     */
    var onDrainError: ((Exception) -> Unit)? = null

    /**
     * Capacidad del buffer de entrada AAC en bytes (ronda U), fijada
     * en el primer drenado con buffer no nulo; `null` si aún no se
     * drenó ninguno. Lo lee la sesión al parar (diagnóstico visible).
     */
    @Volatile
    var inputBufferCapacityBytes: Int? = null
        private set

    /** Capacidad ya registrada en log (una sola vez). Solo hilo del callback. */
    private var inputBufferCapacityLogged = false

    /**
     * Encola una trama PCM (hilo lector). Devuelve `false` si la
     * cola estaba llena: la trama nueva se descarta y se cuenta.
     */
    fun offerFrame(frame: PcmFrame): Boolean {
        synchronized(pcmQueue) {
            if (pcmQueue.size >= maxFrames) {
                dropped.incrementAndGet()
                return false
            }
            pcmQueue.addLast(frame)
            return true
        }
    }

    /** Pide el fin de la entrada; el EOS se encola al vaciarse la cola. */
    fun requestEos() {
        eosRequested = true
    }

    /**
     * Un buffer de entrada quedó libre (hilo del callback): se
     * guarda en el pool y se drena.
     */
    fun onInputBufferAvailable(index: Int) {
        freeBuffers.addLast(index)
        drain()
    }

    /**
     * Drena el feed. SOLO en el hilo del callback: mientras haya
     * buffers libres, encola la siguiente trama o el EOS (si se
     * pidió y la cola está vacía). Sin tramas y sin EOS pendiente,
     * los índices quedan en el pool y se sale.
     *
     * Ronda U: el drenado nunca lanza. Cada iteración va en
     * `try/catch`: ante una excepción del sumidero, la trama se
     * cuenta como descartada una sola vez, el índice vuelve al pool,
     * se avisa a `onDrainError` y el drenado de esta ronda se
     * detiene (el codec está probablemente parado; la sesión
     * libera o recrea el encoder tras `onAudioError`).
     */
    fun drain() {
        while (true) {
            val index = freeBuffers.firstOrNull() ?: return
            val queueEmpty = synchronized(pcmQueue) { pcmQueue.isEmpty() }
            if (eosRequested && queueEmpty && !eosQueued) {
                freeBuffers.removeFirst()
                try {
                    sink.queueInputBuffer(
                        index,
                        0,
                        0,
                        0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                } catch (e: Exception) {
                    freeBuffers.addFirst(index)
                    onDrainError?.invoke(e)
                    return
                }
                eosQueued = true
                return
            }
            val frame = synchronized(pcmQueue) {
                pcmQueue.removeFirstOrNull()
            } ?: return
            freeBuffers.removeFirst()
            var countedAsDropped = false
            try {
                val buffer = sink.getInputBuffer(index)
                if (buffer == null) {
                    dropped.incrementAndGet()
                    countedAsDropped = true
                    sink.queueInputBuffer(index, 0, 0, 0, 0)
                    continue
                }
                buffer.clear()
                if (!inputBufferCapacityLogged) {
                    inputBufferCapacityLogged = true
                    inputBufferCapacityBytes = buffer.capacity()
                    Transmiti2Logger.d(
                        "recording: capacidad buffer entrada AAC=" +
                            "${buffer.capacity()} bytes " +
                            "(trama PCM=${frame.size} bytes)",
                    )
                }
                if (buffer.remaining() < frame.size) {
                    // Troceo (Grupo W): la trama no cabe entera en el
                    // buffer de entrada del encoder; se alimenta el
                    // trozo que cabe (muestras completas, PCM 16 bits)
                    // y el resto vuelve al FRENTE de la cola con su
                    // PTS, antes que cualquier trama posterior. La
                    // capacidad usada es la viva de este buffer, no
                    // la registrada. Si ni una muestra cabe
                    // (capacidad degenerada), se descarta contada y
                    // no se reencola: sin bucle infinito.
                    val chunkBytes = (buffer.remaining() / 2) * 2
                    if (chunkBytes < 2) {
                        dropped.incrementAndGet()
                        countedAsDropped = true
                        sink.queueInputBuffer(index, 0, 0, 0, 0)
                        continue
                    }
                    val chunkSamples = chunkBytes / 2
                    buffer.put(frame.bytes, 0, chunkBytes)
                    sink.queueInputBuffer(
                        index,
                        0,
                        chunkBytes,
                        frame.ptsUs,
                        0,
                    )
                    // El PTS del resto se calcula en absoluto desde el
                    // origen de la trama (plan §4.2), no encadenado
                    // desde el PTS de este trozo: con el cálculo
                    // encadenado el truncado entero se acumularía en
                    // cada frontera (21 332 / 31 998 µs en vez de
                    // 21 333 / 32 000 µs con 4 trozos de 512 muestras).
                    val restOffsetSamples =
                        frame.originOffsetSamples + chunkSamples
                    val restFrame = PcmFrame(
                        bytes = frame.bytes.copyOfRange(
                            chunkBytes,
                            frame.size,
                        ),
                        size = frame.size - chunkBytes,
                        ptsUs = frame.originPtsUs +
                            restOffsetSamples * 1_000_000L / sampleRate,
                        samples = frame.samples - chunkSamples,
                        originPtsUs = frame.originPtsUs,
                        originOffsetSamples = restOffsetSamples,
                    )
                    synchronized(pcmQueue) { pcmQueue.addFirst(restFrame) }
                    continue
                }
                buffer.put(frame.bytes, 0, frame.size)
                sink.queueInputBuffer(index, 0, frame.size, frame.ptsUs, 0)
            } catch (e: Exception) {
                if (!countedAsDropped) dropped.incrementAndGet()
                freeBuffers.addFirst(index)
                onDrainError?.invoke(e)
                return
            }
        }
    }

    /** Vacía la cola PCM y el pool, y resetea el EOS (`release()`). */
    fun clear() {
        synchronized(pcmQueue) { pcmQueue.clear() }
        freeBuffers.clear()
        eosRequested = false
        eosQueued = false
    }

    companion object {
        /**
         * Capacidad de la cola PCM (128 tramas ≈ 2,7 s de audio con
         * trozos de 1024 muestras, Grupo W: antes 64 tramas con
         * tramas de 2048 muestras; el presupuesto de tiempo es el
         * mismo, la memoria máxima 128 × 2048 B = 256 KB).
         */
        const val MAX_PCM_FRAMES = 128
    }
}
