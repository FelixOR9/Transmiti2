// SPIKE 3.1.2 — TEMPORAL
package com.transmiti2.transmiti2.streaming.spike

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Muxer falso: registra el orden de llamadas y guarda los PTS
 * escritos, sin tocar Android. Es el que ejercita la abstracción
 * `SpikeMuxer` en las unitarias (el encoder, la cámara y ExoPlayer se
 * prueban en el teléfono).
 */
// SPIKE 3.1.2 — TEMPORAL
private class FakeSpikeMuxer : SpikeMuxer {
    override val name: String = "fake"
    private val guard = SameThreadGuard()
    val calls = mutableListOf<String>()
    val writtenPts = mutableListOf<Long>()
    var started = false
    var stopped = false
    var released = false

    override fun addTrack(format: MediaFormat): Int {
        guard.check()
        calls += "addTrack"
        return 0
    }

    override fun start() {
        guard.check()
        check(!started) { "start dos veces" }
        started = true
        calls += "start"
    }

    override fun writeSampleData(
        trackIndex: Int,
        byteBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
    ) {
        guard.check()
        check(started) { "write antes de start" }
        writtenPts += bufferInfo.presentationTimeUs
        calls += "write"
    }

    override fun stop() {
        guard.check()
        check(started) { "stop antes de start" }
        stopped = true
        calls += "stop"
    }

    override fun release() {
        guard.check()
        released = true
        calls += "release"
    }

    override fun setOrientationHint(degrees: Int) {
        guard.check()
        calls += "orientation"
    }
}

// SPIKE 3.1.2 — TEMPORAL
class SpikeMuxerTest {

    private fun sample(ptsUs: Long): MediaCodec.BufferInfo {
        // Campos públicos directos: `set()` es un método del stub de
        // Android y lanzaría en las unitarias JVM.
        return MediaCodec.BufferInfo().apply {
            offset = 0
            size = 10
            presentationTimeUs = ptsUs
            flags = 0
        }
    }

    @Test
    fun `orden de llamadas normal`() {
        val muxer = FakeSpikeMuxer()
        muxer.setOrientationHint(90)
        assertEquals(0, muxer.addTrack(MediaFormat()))
        muxer.start()
        muxer.writeSampleData(0, ByteBuffer.allocate(10), sample(0))
        muxer.writeSampleData(0, ByteBuffer.allocate(10), sample(33333))
        muxer.stop()
        muxer.release()
        assertEquals(
            listOf(
                "orientation",
                "addTrack",
                "start",
                "write",
                "write",
                "stop",
                "release",
            ),
            muxer.calls,
        )
        assertEquals(listOf(0L, 33333L), muxer.writtenPts)
        assertTrue(muxer.stopped)
        assertTrue(muxer.released)
    }

    @Test
    fun `escribir antes de start falla`() {
        val muxer = FakeSpikeMuxer()
        muxer.addTrack(MediaFormat())
        assertThrows(IllegalStateException::class.java) {
            muxer.writeSampleData(0, ByteBuffer.allocate(10), sample(0))
        }
    }

    @Test
    fun `cierre sin stop no pierde lo ya escrito`() {
        val muxer = FakeSpikeMuxer()
        muxer.addTrack(MediaFormat())
        muxer.start()
        muxer.writeSampleData(0, ByteBuffer.allocate(10), sample(0))
        muxer.writeSampleData(0, ByteBuffer.allocate(10), sample(33333))
        // Interrupción: release() sin stop(), como el spike.
        muxer.release()
        assertFalse(muxer.stopped)
        assertTrue(muxer.released)
        assertEquals(listOf(0L, 33333L), muxer.writtenPts)
    }

    @Test
    fun `otro hilo es rechazado`() {
        val muxer = FakeSpikeMuxer()
        muxer.addTrack(MediaFormat())
        muxer.start()
        val error = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                muxer.writeSampleData(
                    0,
                    ByteBuffer.allocate(10),
                    sample(0),
                )
            } catch (e: Throwable) {
                error.set(e)
            }
        }
        thread.start()
        thread.join(5000)
        val thrown = error.get()
        assertTrue(
            "Se esperaba IllegalStateException, llegó $thrown",
            thrown is IllegalStateException,
        )
    }

    @Test
    fun `la factoria rechaza un muxer desconocido`() {
        assertThrows(IllegalArgumentException::class.java) {
            createSpikeMuxer("inexistente", "/tmp/spike.mp4")
        }
    }
}
