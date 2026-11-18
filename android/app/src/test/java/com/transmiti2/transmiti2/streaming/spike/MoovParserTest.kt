// SPIKE 3.1.2 — TEMPORAL
package com.transmiti2.transmiti2.streaming.spike

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// SPIKE 3.1.2 — TEMPORAL
class MoovParserTest {

    private fun u32(value: Long): ByteArray {
        return ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(value.toInt())
            .array()
    }

    private fun u64(value: Long): ByteArray {
        return ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(value)
            .array()
    }

    private fun box(type: String, content: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(8 + content.size)
            .order(ByteOrder.BIG_ENDIAN)
        out.putInt(8 + content.size)
        out.put(type.toByteArray(Charsets.US_ASCII))
        out.put(content)
        return out.array()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteBuffer.allocate(total)
        parts.forEach { out.put(it) }
        return out.array()
    }

    /** mvhd versión 0: timescale 1000, duración 10000 -> 10 s. */
    private fun mvhdV0(): ByteArray {
        return box(
            "mvhd",
            concat(
                byteArrayOf(0, 0, 0, 0), // versión 0 + flags
                u32(0), // creation_time
                u32(0), // modification_time
                u32(1000), // timescale
                u32(10000), // duration
            ),
        )
    }

    /** mvhd versión 1: timescale 1000, duración 25000 -> 25 s. */
    private fun mvhdV1(): ByteArray {
        return box(
            "mvhd",
            concat(
                byteArrayOf(1, 0, 0, 0), // versión 1 + flags
                u64(0), // creation_time
                u64(0), // modification_time
                u32(1000), // timescale
                u64(25000), // duration
            ),
        )
    }

    private fun stsz(sampleCount: Int): ByteArray {
        return box(
            "stsz",
            concat(
                byteArrayOf(0, 0, 0, 0), // versión + flags
                u32(0), // sample_size (0 = tamaños por muestra)
                u32(sampleCount.toLong()), // sample_count
            ),
        )
    }

    private fun ctts(offsets: List<Long>): ByteArray {
        val entries = offsets.map { offset ->
            concat(u32(1), u32(offset))
        }.toTypedArray()
        return box(
            "ctts",
            concat(
                byteArrayOf(0, 0, 0, 0), // versión + flags
                u32(offsets.size.toLong()), // entry_count
                *entries,
            ),
        )
    }

    private fun track(vararg stblChildren: ByteArray): ByteArray {
        val stbl = box("stbl", concat(*stblChildren))
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        return box("trak", mdia)
    }

    private fun fileWithMoov(vararg moovChildren: ByteArray): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray(Charsets.US_ASCII))
        val moov = box("moov", concat(*moovChildren))
        return concat(ftyp, moov)
    }

    @Test
    fun `sin moov no hay nada que leer`() {
        val bytes = box("ftyp", "isom".toByteArray(Charsets.US_ASCII))
        val info = MoovParser.parse(bytes)
        assertFalse(info.moovPresent)
        assertNull(info.durationMs)
        assertNull(info.sampleCount)
        assertFalse(info.cttsPresent)
        assertNull(info.cttsAllZero)
        assertEquals(0, info.trackCount)
    }

    @Test
    fun `moov completo con mvhd v0, stsz y ctts a cero`() {
        val bytes = fileWithMoov(
            mvhdV0(),
            track(stsz(300), ctts(listOf(0, 0))),
        )
        val info = MoovParser.parse(bytes)
        assertTrue(info.moovPresent)
        assertEquals(10000L, info.durationMs)
        assertEquals(300, info.sampleCount)
        assertTrue(info.cttsPresent)
        assertEquals(true, info.cttsAllZero)
        assertEquals(1, info.trackCount)
    }

    @Test
    fun `mvhd v1 calcula la duracion con 64 bits`() {
        val bytes = fileWithMoov(mvhdV1(), track(stsz(10)))
        val info = MoovParser.parse(bytes)
        assertTrue(info.moovPresent)
        assertEquals(25000L, info.durationMs)
        assertEquals(10, info.sampleCount)
    }

    @Test
    fun `ctts con un offset distinto de cero no es todo cero`() {
        val bytes = fileWithMoov(
            mvhdV0(),
            track(stsz(300), ctts(listOf(0, 40))),
        )
        val info = MoovParser.parse(bytes)
        assertTrue(info.cttsPresent)
        assertEquals(false, info.cttsAllZero)
    }

    @Test
    fun `sin ctts no hay reordenamiento que informar`() {
        val bytes = fileWithMoov(mvhdV0(), track(stsz(300)))
        val info = MoovParser.parse(bytes)
        assertTrue(info.moovPresent)
        assertFalse(info.cttsPresent)
        assertNull(info.cttsAllZero)
        assertEquals(300, info.sampleCount)
    }

    @Test
    fun `archivo truncado dentro del moov no inventa datos`() {
        val full = fileWithMoov(mvhdV0(), track(stsz(300)))
        // Se corta a mitad del moov: la caja queda incompleta.
        val truncated = full.copyOf(full.size - 20)
        val info = MoovParser.parse(truncated)
        assertFalse(info.moovPresent)
        assertNull(info.durationMs)
    }

    @Test
    fun `dos pistas se cuentan y se lee la primera`() {
        val bytes = fileWithMoov(
            mvhdV0(),
            track(stsz(300)),
            track(stsz(50)),
        )
        val info = MoovParser.parse(bytes)
        assertEquals(2, info.trackCount)
        assertEquals(300, info.sampleCount)
    }
}
