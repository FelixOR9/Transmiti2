package com.transmiti2.transmiti2.streaming.recording

import android.media.MediaCodec
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drenado de entrada AAC (ronda 3 de audio 3.1.3, grupo F):
 * `AudioInputFeed` puro en JVM, con un `InputBufferSink` falso que
 * simula el callback (`onInputBufferAvailable`). El codec y el
 * `AudioRecord` reales solo se verifican en el teléfono.
 *
 * Grupo W: las tramas PCM se trocean a la capacidad viva del buffer
 * de entrada (antes, ronda U, se descartaban enteras).
 */
class AudioInputFeedTest {

    private data class QueuedInput(
        val index: Int,
        val size: Int,
        val ptsUs: Long,
        val flags: Int,
    )

    private class FakeSink(
        var nullBufferIndexes: Set<Int> = emptySet(),
        /** Capacidad de los buffers de entrada (ronda U). */
        var bufferCapacity: Int = 8192,
        /** El sumidero lanza al pedir el buffer (ronda U). */
        var throwOnGet: Boolean = false,
        /** El sumidero lanza al encolar (ronda U). */
        var throwOnQueue: Boolean = false,
    ) : InputBufferSink {
        val queued = mutableListOf<QueuedInput>()
        val buffers = mutableMapOf<Int, ByteBuffer>()

        override fun getInputBuffer(index: Int): ByteBuffer? {
            if (throwOnGet) throw IllegalStateException("codec parado")
            if (index in nullBufferIndexes) return null
            return ByteBuffer.allocate(bufferCapacity)
                .also { buffers[index] = it }
        }

        override fun queueInputBuffer(
            index: Int,
            offset: Int,
            size: Int,
            presentationTimeUs: Long,
            flags: Int,
        ) {
            if (throwOnQueue) throw IllegalStateException("codec parado")
            queued += QueuedInput(index, size, presentationTimeUs, flags)
        }

        /** Bytes copiados en el buffer del índice (tras encolarlo). */
        fun copiedBytes(index: Int, size: Int): ByteArray {
            val buffer = buffers.getValue(index)
            buffer.flip()
            val out = ByteArray(size)
            buffer.get(out)
            return out
        }
    }

    private fun frame(firstByte: Byte, ptsUs: Long) = PcmFrame(
        bytes = ByteArray(4096) { firstByte },
        size = 4096,
        ptsUs = ptsUs,
        samples = 2048,
    )

    @Before
    fun setUp() {
        // `Transmiti2Logger` sin receptor llamaría a
        // `android.util.Log`, que lanza en las unitarias JVM puras
        // (el drenado registra la capacidad del buffer, ronda U).
        Transmiti2Logger.sink = { _, _ -> }
    }

    @After
    fun tearDown() {
        Transmiti2Logger.sink = null
    }

    @Test
    fun `tramas en orden con su PTS y su contenido`() {
        val sink = FakeSink()
        val feed = AudioInputFeed(sink)
        assertTrue(feed.offerFrame(frame(1, 0L)))
        assertTrue(feed.offerFrame(frame(2, 21_333L)))
        assertTrue(feed.offerFrame(frame(3, 42_666L)))
        feed.onInputBufferAvailable(0)
        feed.onInputBufferAvailable(1)
        feed.onInputBufferAvailable(2)
        assertEquals(
            listOf(
                QueuedInput(0, 4096, 0L, 0),
                QueuedInput(1, 4096, 21_333L, 0),
                QueuedInput(2, 4096, 42_666L, 0),
            ),
            sink.queued,
        )
        assertTrue(sink.copiedBytes(0, 4096).all { it == 1.toByte() })
        assertTrue(sink.copiedBytes(1, 4096).all { it == 2.toByte() })
        assertTrue(sink.copiedBytes(2, 4096).all { it == 3.toByte() })
        assertEquals(0, feed.droppedInputBuffers)
    }

    @Test
    fun `EOS solo tras la ultima trama`() {
        val sink = FakeSink()
        val feed = AudioInputFeed(sink)
        feed.offerFrame(frame(1, 0L))
        feed.offerFrame(frame(2, 21_333L))
        feed.requestEos()
        feed.onInputBufferAvailable(0)
        // Solo la primera trama: el EOS espera a que se vacíe la cola.
        assertEquals(listOf(QueuedInput(0, 4096, 0L, 0)), sink.queued)
        feed.onInputBufferAvailable(1)
        assertEquals(QueuedInput(1, 4096, 21_333L, 0), sink.queued[1])
        feed.onInputBufferAvailable(2)
        assertEquals(3, sink.queued.size)
        assertEquals(
            QueuedInput(
                2,
                0,
                0L,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            ),
            sink.queued[2],
        )
        assertEquals(0, feed.droppedInputBuffers)
    }

    @Test
    fun `EOS con la cola vacia se encola en cuanto hay buffer libre`() {
        val sink = FakeSink()
        val feed = AudioInputFeed(sink)
        feed.requestEos()
        feed.onInputBufferAvailable(7)
        assertEquals(
            listOf(
                QueuedInput(
                    7,
                    0,
                    0L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                ),
            ),
            sink.queued,
        )
    }

    @Test
    fun `EOS no se repite`() {
        val sink = FakeSink()
        val feed = AudioInputFeed(sink)
        feed.requestEos()
        feed.onInputBufferAvailable(0)
        feed.onInputBufferAvailable(1)
        feed.onInputBufferAvailable(2)
        assertEquals(1, sink.queued.size)
        assertEquals(
            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            sink.queued[0].flags,
        )
    }

    @Test
    fun `getInputBuffer nulo se cuenta y el buffer se devuelve vacio`() {
        val sink = FakeSink(nullBufferIndexes = setOf(5))
        val feed = AudioInputFeed(sink)
        feed.offerFrame(frame(1, 0L))
        feed.onInputBufferAvailable(5)
        // El índice se devuelve encolándolo vacío, nunca se pierde.
        assertEquals(listOf(QueuedInput(5, 0, 0L, 0)), sink.queued)
        assertEquals(1, feed.droppedInputBuffers)
        // La trama siguiente fluye con normalidad.
        feed.offerFrame(frame(2, 21_333L))
        feed.onInputBufferAvailable(6)
        assertEquals(QueuedInput(6, 4096, 21_333L, 0), sink.queued[1])
        assertEquals(1, feed.droppedInputBuffers)
    }

    @Test
    fun `cola llena descarta la trama nueva y la cuenta`() {
        val sink = FakeSink()
        val feed = AudioInputFeed(sink, maxFrames = 2)
        assertTrue(feed.offerFrame(frame(1, 0L)))
        assertTrue(feed.offerFrame(frame(2, 21_333L)))
        assertFalse(feed.offerFrame(frame(3, 42_666L)))
        assertEquals(1, feed.droppedInputBuffers)
        feed.onInputBufferAvailable(0)
        feed.onInputBufferAvailable(1)
        assertEquals(
            listOf(
                QueuedInput(0, 4096, 0L, 0),
                QueuedInput(1, 4096, 21_333L, 0),
            ),
            sink.queued,
        )
    }

    @Test
    fun `sin tramas no se encola nada`() {
        val sink = FakeSink()
        val feed = AudioInputFeed(sink)
        feed.onInputBufferAvailable(0)
        feed.onInputBufferAvailable(1)
        feed.drain()
        assertTrue(sink.queued.isEmpty())
        assertEquals(0, feed.droppedInputBuffers)
    }

    @Test
    fun `un buffer libre espera a la trama siguiente`() {
        // En producción es el `post` del lector tras encolar la trama
        // el que pide el drenado; aquí se llama a `drain()` directo.
        val sink = FakeSink()
        val feed = AudioInputFeed(sink)
        feed.onInputBufferAvailable(0)
        assertTrue(sink.queued.isEmpty())
        feed.offerFrame(frame(1, 0L))
        feed.drain()
        assertEquals(listOf(QueuedInput(0, 4096, 0L, 0)), sink.queued)
    }

    @Test
    fun `clear vacia la cola y resetea el EOS`() {
        val sink = FakeSink()
        val feed = AudioInputFeed(sink)
        feed.offerFrame(frame(1, 0L))
        feed.requestEos()
        feed.clear()
        assertFalse(feed.eosRequested)
        feed.onInputBufferAvailable(0)
        assertTrue(sink.queued.isEmpty())
        assertEquals(0, feed.droppedInputBuffers)
    }

    @Test
    fun `trama mayor que el buffer de entrada se trocea en dos`() {
        // Grupo W (T1): la trama PCM de 4096 bytes no cabe en el
        // buffer de entrada AAC de 2048 bytes del S24 Ultra
        // (H1-a: 519 de 520 lecturas descartadas). Sustituye al test
        // de la ronda U, que afirmaba el descarte entero: ese era el
        // comportamiento viejo que el Grupo W elimina (cambio
        // documentado, no debilitamiento). El `put` directo lanzaba
        // `BufferOverflowException` y mataba el proceso; el troceo
        // lo hace imposible por construcción.
        val sink = FakeSink(bufferCapacity = 2048)
        val feed = AudioInputFeed(sink)
        val bytes = ByteArray(4096) { it.toByte() }
        feed.offerFrame(PcmFrame(bytes, 4096, 0L, 2048))
        feed.onInputBufferAvailable(0)
        feed.onInputBufferAvailable(1)
        // Dos trozos de 2048 bytes = 1024 muestras cada uno, con el
        // PTS del segundo trozo 21 333 µs después del primero.
        assertEquals(
            listOf(
                QueuedInput(0, 2048, 0L, 0),
                QueuedInput(1, 2048, 21_333L, 0),
            ),
            sink.queued,
        )
        assertEquals(0, feed.droppedInputBuffers)
        assertEquals(2048, feed.inputBufferCapacityBytes)
        // Contenido: cada trozo es su mitad exacta de la trama.
        assertTrue(
            sink.copiedBytes(0, 2048)
                .contentEquals(bytes.copyOfRange(0, 2048)),
        )
        assertTrue(
            sink.copiedBytes(1, 2048)
                .contentEquals(bytes.copyOfRange(2048, 4096)),
        )
    }

    @Test
    fun `trama parcial menor que la capacidad se alimenta entera`() {
        // Grupo W (T2): es el caso real de la última lectura de la
        // prueba de 20 s (1536 bytes = 768 muestras, la única trama
        // que llegó al codec antes del Grupo W).
        val sink = FakeSink(bufferCapacity = 2048)
        val feed = AudioInputFeed(sink)
        feed.offerFrame(PcmFrame(ByteArray(1536) { 5 }, 1536, 0L, 768))
        feed.onInputBufferAvailable(0)
        assertEquals(listOf(QueuedInput(0, 1536, 0L, 0)), sink.queued)
        assertEquals(0, feed.droppedInputBuffers)
    }

    @Test
    fun `trama igual a la capacidad se alimenta entera`() {
        // Grupo W (T3): sin troceo cuando la trama cabe justa.
        val sink = FakeSink(bufferCapacity = 2048)
        val feed = AudioInputFeed(sink)
        feed.offerFrame(PcmFrame(ByteArray(2048) { 2 }, 2048, 21_333L, 1024))
        feed.onInputBufferAvailable(0)
        assertEquals(listOf(QueuedInput(0, 2048, 21_333L, 0)), sink.queued)
        assertEquals(0, feed.droppedInputBuffers)
        assertEquals(2048, feed.inputBufferCapacityBytes)
    }

    @Test
    fun `capacidad menor que una trama AAC trocea en cuatro`() {
        // Grupo W (T4): capacidad 1024 bytes (512 muestras), trama
        // de 4096 → 4 trozos de 1024 bytes.
        val sink = FakeSink(bufferCapacity = 1024)
        val feed = AudioInputFeed(sink)
        feed.offerFrame(frame(1, 0L))
        feed.onInputBufferAvailable(0)
        feed.onInputBufferAvailable(1)
        feed.onInputBufferAvailable(2)
        feed.onInputBufferAvailable(3)
        assertEquals(
            listOf(
                QueuedInput(0, 1024, 0L, 0),
                QueuedInput(1, 1024, 10_666L, 0),
                QueuedInput(2, 1024, 21_333L, 0),
                QueuedInput(3, 1024, 32_000L, 0),
            ),
            sink.queued,
        )
        assertEquals(0, feed.droppedInputBuffers)
        assertEquals(4096, sink.queued.sumOf { it.size })
    }

    @Test
    fun `troceo con 44,1 kHz`() {
        // Grupo W (T4b): la frecuencia real viaja por el constructor;
        // el PTS del segundo trozo es 23 219 µs, no 21 333 µs.
        val sink = FakeSink(bufferCapacity = 2048)
        val feed = AudioInputFeed(sink, sampleRate = 44_100)
        feed.offerFrame(frame(1, 0L))
        feed.onInputBufferAvailable(0)
        feed.onInputBufferAvailable(1)
        assertEquals(
            listOf(
                QueuedInput(0, 2048, 0L, 0),
                QueuedInput(1, 2048, 23_219L, 0),
            ),
            sink.queued,
        )
        assertEquals(0, feed.droppedInputBuffers)
    }

    @Test(timeout = 5000)
    fun `capacidad degenerada descarta contada sin bucle infinito`() {
        // Grupo W (T5): si ni una muestra cabe (capacidad 0), la
        // trama se descarta contada y no se reencola; el drenado
        // retorna y no es un error del sumidero.
        val sink = FakeSink(bufferCapacity = 0)
        val feed = AudioInputFeed(sink)
        val errors = mutableListOf<Exception>()
        feed.onDrainError = { errors += it }
        feed.offerFrame(frame(1, 0L))
        feed.onInputBufferAvailable(0)
        assertEquals(listOf(QueuedInput(0, 0, 0L, 0)), sink.queued)
        assertEquals(1, feed.droppedInputBuffers)
        assertEquals(0, feed.inputBufferCapacityBytes)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `resto espera al siguiente buffer libre`() {
        // Grupo W (T6): con un solo buffer libre solo sale el primer
        // trozo; el resto queda al frente de la cola y se alimenta
        // con el siguiente buffer, antes que la trama posterior.
        val sink = FakeSink(bufferCapacity = 2048)
        val feed = AudioInputFeed(sink)
        feed.offerFrame(frame(1, 0L))
        feed.onInputBufferAvailable(0)
        assertEquals(listOf(QueuedInput(0, 2048, 0L, 0)), sink.queued)
        assertEquals(0, feed.droppedInputBuffers)
        feed.onInputBufferAvailable(1)
        assertEquals(QueuedInput(1, 2048, 21_333L, 0), sink.queued[1])
        feed.offerFrame(frame(2, 42_666L))
        feed.onInputBufferAvailable(2)
        assertEquals(QueuedInput(2, 2048, 42_666L, 0), sink.queued[2])
        feed.onInputBufferAvailable(3)
        assertEquals(QueuedInput(3, 2048, 63_999L, 0), sink.queued[3])
        assertEquals(0, feed.droppedInputBuffers)
    }

    @Test
    fun `EOS va despues del ultimo trozo`() {
        // Grupo W (T7): el EOS espera a que la cola se vacíe, así
        // que nunca se adelanta al resto de una trama troceada.
        val sink = FakeSink(bufferCapacity = 2048)
        val feed = AudioInputFeed(sink)
        feed.offerFrame(frame(1, 0L))
        feed.requestEos()
        feed.onInputBufferAvailable(0)
        assertEquals(listOf(QueuedInput(0, 2048, 0L, 0)), sink.queued)
        feed.onInputBufferAvailable(1)
        assertEquals(QueuedInput(1, 2048, 21_333L, 0), sink.queued[1])
        feed.onInputBufferAvailable(2)
        assertEquals(3, sink.queued.size)
        assertEquals(
            QueuedInput(
                2,
                0,
                0L,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            ),
            sink.queued[2],
        )
        feed.onInputBufferAvailable(3)
        assertEquals(3, sink.queued.size)
        assertEquals(0, feed.droppedInputBuffers)
    }

    @Test
    fun `continuidad de PTS al trocear y con la trama siguiente`() {
        // Grupo W (T8): los PTS son estrictamente crecientes y
        // contiguos, sin duplicados ni huecos. El 63 999 (no 64 000)
        // es el truncado entero esperado del cálculo por trozo.
        val sink = FakeSink(bufferCapacity = 2048)
        val feed = AudioInputFeed(sink)
        feed.offerFrame(frame(1, 0L))
        feed.offerFrame(frame(2, 42_666L))
        feed.onInputBufferAvailable(0)
        feed.onInputBufferAvailable(1)
        feed.onInputBufferAvailable(2)
        feed.onInputBufferAvailable(3)
        assertEquals(
            listOf(
                QueuedInput(0, 2048, 0L, 0),
                QueuedInput(1, 2048, 21_333L, 0),
                QueuedInput(2, 2048, 42_666L, 0),
                QueuedInput(3, 2048, 63_999L, 0),
            ),
            sink.queued,
        )
        assertEquals(8192, sink.queued.sumOf { it.size })
        assertEquals(0, feed.droppedInputBuffers)
    }

    @Test
    fun `varias tramas completas llegan al codec con capacidad 2048`() {
        // Grupo W (T9, a nivel de feed): el nivel encoder no es
        // posible sin codec real — `AudioEncoderTest` solo cubre la
        // configuración y la matemática de PTS. Es el caso real del
        // teléfono: tramas de 4096 bytes, buffer de 2048, 48 kHz.
        val sink = FakeSink(bufferCapacity = 2048)
        val feed = AudioInputFeed(sink, sampleRate = 48_000)
        feed.offerFrame(frame(1, 0L))
        feed.offerFrame(frame(2, 42_666L))
        feed.offerFrame(frame(3, 85_333L))
        repeat(6) { feed.onInputBufferAvailable(it) }
        assertEquals(6, sink.queued.size)
        assertEquals(12_288, sink.queued.sumOf { it.size })
        assertEquals(
            listOf(0L, 21_333L, 42_666L, 63_999L, 85_333L, 106_666L),
            sink.queued.map { it.ptsUs },
        )
        assertEquals(0, feed.droppedInputBuffers)
        assertEquals(2048, feed.inputBufferCapacityBytes)
    }

    @Test
    fun `sumidero que lanza en queueInputBuffer no propaga y avisa`() {
        // Ronda U (H1-b): el drenado nunca lanza; el error llega al
        // handler para degradar a video-only.
        val sink = FakeSink(throwOnQueue = true)
        val feed = AudioInputFeed(sink)
        val errors = mutableListOf<Exception>()
        feed.onDrainError = { errors += it }
        feed.offerFrame(frame(1, 0L))
        feed.onInputBufferAvailable(0)
        assertEquals(1, errors.size)
        assertTrue(errors[0] is IllegalStateException)
        assertEquals(1, feed.droppedInputBuffers)
        assertTrue(sink.queued.isEmpty())
        // Con el sumidero sano, el índice vuelve al pool y drena.
        sink.throwOnQueue = false
        feed.offerFrame(frame(2, 21_333L))
        feed.drain()
        assertEquals(listOf(QueuedInput(0, 4096, 21_333L, 0)), sink.queued)
        assertEquals(1, errors.size)
        assertEquals(1, feed.droppedInputBuffers)
    }

    @Test
    fun `sumidero que lanza en getInputBuffer no propaga y avisa`() {
        val sink = FakeSink(throwOnGet = true)
        val feed = AudioInputFeed(sink)
        val errors = mutableListOf<Exception>()
        feed.onDrainError = { errors += it }
        feed.offerFrame(frame(1, 0L))
        feed.onInputBufferAvailable(0)
        assertEquals(1, errors.size)
        assertTrue(errors[0] is IllegalStateException)
        assertEquals(1, feed.droppedInputBuffers)
        assertTrue(sink.queued.isEmpty())
        sink.throwOnGet = false
        feed.offerFrame(frame(2, 21_333L))
        feed.drain()
        assertEquals(listOf(QueuedInput(0, 4096, 21_333L, 0)), sink.queued)
        assertEquals(1, errors.size)
    }

    @Test
    fun `EOS que lanza no propaga y avisa`() {
        val sink = FakeSink(throwOnQueue = true)
        val feed = AudioInputFeed(sink)
        val errors = mutableListOf<Exception>()
        feed.onDrainError = { errors += it }
        feed.requestEos()
        feed.onInputBufferAvailable(3)
        assertEquals(1, errors.size)
        assertTrue(errors[0] is IllegalStateException)
        assertTrue(sink.queued.isEmpty())
        // Sin EOS encolado: con el sumidero sano se encola al drenar.
        sink.throwOnQueue = false
        feed.drain()
        assertEquals(1, sink.queued.size)
        assertEquals(
            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            sink.queued[0].flags,
        )
        assertEquals(1, errors.size)
    }
}
