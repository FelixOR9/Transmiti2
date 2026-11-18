// SPIKE 3.1.2 — TEMPORAL
package com.transmiti2.transmiti2.streaming.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Política de la pasada interrumpida y descripción de errores del
 * spike (corrección del fallo de interrupción 5 s × 3, donde AMBOS
 * muxers daban `muxer_error` y no se registraba ningún resultado).
 *
 * El camino completo (cámara + encoder + muxer) no se puede probar en
 * JVM simple: necesita una `CameraSession` real y se verifica en el
 * S24 Ultra. Estas son las piezas puras extraídas de ese camino.
 */
// SPIKE 3.1.2 — TEMPORAL
class SpikeInterruptionPolicyTest {

    @Test
    fun `describeException incluye clase y mensaje`() {
        assertEquals(
            "IllegalStateException: Muxer has been released!",
            describeException(
                IllegalStateException("Muxer has been released!"),
            ),
        )
    }

    @Test
    fun `describeException con mensaje nulo devuelve solo la clase`() {
        // MediaMuxerCompat lanza con mensaje nulo al escribir tras
        // `release()`: el informe mostraba `Error del muxer: null`.
        assertEquals(
            "IllegalStateException",
            describeException(IllegalStateException()),
        )
    }

    @Test
    fun `describeException usa la clase real de la excepcion`() {
        assertEquals(
            "RuntimeException: algo fallo",
            describeException(RuntimeException("algo fallo")),
        )
    }

    @Test
    fun `interrumpida con error del muxer no es un fallo`() {
        assertFalse(
            shouldReportMuxerErrorAsFailure(
                true,
                IllegalStateException("Muxer has been released!"),
            ),
        )
    }

    @Test
    fun `interrumpida sin error del muxer no es un fallo`() {
        assertFalse(shouldReportMuxerErrorAsFailure(true, null))
    }

    @Test
    fun `normal con error del muxer si es un fallo`() {
        assertTrue(
            shouldReportMuxerErrorAsFailure(
                false,
                IllegalStateException("Muxer has been released!"),
            ),
        )
    }

    @Test
    fun `normal sin error del muxer no es un fallo`() {
        assertFalse(shouldReportMuxerErrorAsFailure(false, null))
    }
}
