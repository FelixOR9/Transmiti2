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
    fun `trama mayor que el buffer de entrada se descarta contada sin lanzar`() {
        // Ronda U (H1-a): el buffer de entrada del encoder puede ser
        // menor que la trama PCM de 4096 bytes; el `put` directo
        // lanzaba `BufferOverflowException` y mataba el proceso.
        val sink = FakeSink(bufferCapacity = 2048)
        val feed = AudioInputFeed(sink)
        feed.offerFrame(frame(1, 0L))
        feed.onInputBufferAvailable(0)
        // El buffer se devuelve vacío, nunca se escribe de más.
        assertEquals(listOf(QueuedInput(0, 0, 0L, 0)), sink.queued)
        assertEquals(1, feed.droppedInputBuffers)
        assertEquals(2048, feed.inputBufferCapacityBytes)
        // Una trama que sí cabe fluye con normalidad.
        feed.offerFrame(PcmFrame(ByteArray(2048) { 2 }, 2048, 21_333L, 1024))
        feed.onInputBufferAvailable(1)
        assertEquals(QueuedInput(1, 2048, 21_333L, 0), sink.queued[1])
        assertEquals(1, feed.droppedInputBuffers)
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
