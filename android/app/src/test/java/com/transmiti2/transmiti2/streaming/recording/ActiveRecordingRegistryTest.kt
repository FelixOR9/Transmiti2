package com.transmiti2.transmiti2.streaming.recording

import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ActiveRecordingRegistry` (subetapa 0, grupo 0-c; revisión 3 §4.2).
 * Unitarias puras en JVM: sin `RecordingSession`, sin disco real, sin
 * Android, sin cámara ni verificador. Estilo existente (`runBlocking`
 * + hilos reales + barrera `CountDownLatch`); no hay
 * `kotlinx-coroutines-test` en el proyecto, así que no se usa
 * `runTest`.
 *
 * Las pruebas concurrentes afirman un conteo exacto («exactamente un
 * éxito») que vale en todos los planificamientos, no «normalmente»:
 * son pruebas de invariante, no de temporizado.
 */
class ActiveRecordingRegistryTest {

    /**
     * Lanza [n] corrutinas en `Dispatchers.Default` que esperan en la
     * misma barrera y luego ejecutan [block]; devuelve sus resultados
     * en orden de lanzamiento. La barrera la abre el hilo del test
     * (no una corrutina del pool), así que no hay interbloqueo aunque
     * el pool tenga pocos hilos.
     */
    private fun <T> runConcurrently(
        n: Int,
        block: suspend (index: Int) -> T,
    ): List<T> = runBlocking {
        val results = arrayOfNulls<Any?>(n)
        val gate = CountDownLatch(1)
        val jobs = (0 until n).map { index ->
            launch(Dispatchers.Default) {
                gate.await()
                results[index] = block(index)
            }
        }
        gate.countDown()
        jobs.forEach { it.join() }
        @Suppress("UNCHECKED_CAST")
        results.toList() as List<T>
    }

    @Test
    fun `U43 doble claim concurrente, exactamente un token no-nulo por repeticion`() =
        runBlocking {
            val registry = ActiveRecordingRegistry()
            val winners = mutableListOf<OwnershipToken>()
            repeat(100) { rep ->
                val id = "rec_u43_$rep"
                val results = runConcurrently(50) {
                    registry.tryClaimRecording(id)
                }
                val nonNull = results.filterNotNull()
                assertEquals(
                    "rep $rep: exactamente un claim con exito",
                    1,
                    nonNull.size,
                )
                winners += nonNull.single()
                val snapshot = registry.snapshot()
                val entry = snapshot.activeClaims[id]
                assertNotNull("rep $rep: el id aparece una sola vez", entry)
                assertEquals(nonNull.single(), entry!!.token)
                assertEquals("recording", entry.owner)
            }
            assertEquals(
                "tokens distintos entre repeticiones",
                winners.size,
                winners.toSet().size,
            )
            assertTrue(
                "ningun token es 0 (reservado como sin token)",
                winners.all { it != 0L },
            )
            val snapshot = registry.snapshot()
            assertEquals(100, snapshot.activeClaims.size)
            assertTrue(snapshot.reservedGroups.isEmpty())
        }

    @Test
    fun `U44 doble reserva concurrente, exactamente una con exito`() =
        runBlocking {
            val registry = ActiveRecordingRegistry()
            val winners = mutableListOf<OwnershipToken>()
            repeat(100) { rep ->
                val id = "rec_u44_$rep"
                val results = runConcurrently(50) {
                    registry.tryReserveGroup(id, "reconcile:run-$rep")
                }
                val nonNull = results.filterNotNull()
                assertEquals(
                    "rep $rep: exactamente una reserva con exito",
                    1,
                    nonNull.size,
                )
                winners += nonNull.single()
                val entry = registry.snapshot().reservedGroups[id]
                assertNotNull(entry)
                assertEquals(nonNull.single(), entry!!.token)
                assertEquals("reconcile:run-$rep", entry.owner)
            }
            assertEquals(
                "tokens distintos entre repeticiones",
                winners.size,
                winners.toSet().size,
            )
            val snapshot = registry.snapshot()
            assertEquals(100, snapshot.reservedGroups.size)
            assertTrue(snapshot.activeClaims.isEmpty())
        }

    @Test
    fun `U45 claim sobre id ya activo, segundo null y token sin cambiar`() =
        runBlocking {
            val registry = ActiveRecordingRegistry()
            val id = "rec_u45"
            val first = registry.tryClaimRecording(id)
            assertNotNull(first)
            val second = registry.tryClaimRecording(id)
            assertNull("segundo claim del mismo id", second)
            val entry = registry.snapshot().activeClaims[id]
            assertNotNull(entry)
            assertEquals(
                "el segundo claim no reemplaza la entrada",
                first,
                entry!!.token,
            )
            assertEquals(1L, registry.snapshot().claimRejectedActive)
        }

    @Test
    fun `U46 reserva sobre grupo ya reservado, segunda null y token intacto`() =
        runBlocking {
            val registry = ActiveRecordingRegistry()
            val id = "rec_u46"
            val first = registry.tryReserveGroup(id, "reconcile:run-1")
            assertNotNull(first)
            val second = registry.tryReserveGroup(id, "reconcile:run-2")
            assertNull("segunda reserva del mismo grupo", second)
            val entry = registry.snapshot().reservedGroups[id]
            assertNotNull(entry)
            assertEquals(
                "la segunda reserva no reemplaza la entrada",
                first,
                entry!!.token,
            )
            assertEquals("reconcile:run-1", entry.owner)
            assertEquals(1L, registry.snapshot().reserveRejectedReserved)
        }

    @Test
    fun `U47 liberacion con token ajeno no libera el id`() = runBlocking {
        val registry = ActiveRecordingRegistry()
        val id = "rec_u47"
        val otherId = "rec_u47_otro"
        val token = registry.tryClaimRecording(id)
        assertNotNull(token)
        val otherToken = registry.tryClaimRecording(otherId)
        assertNotNull(otherToken)

        // Token inventado (nunca emitido por este registro).
        assertEquals(
            ReleaseResult.NOT_OWNER,
            registry.releaseRecording(id, 9_999L),
        )
        // Token de otro id.
        assertEquals(
            ReleaseResult.NOT_OWNER,
            registry.releaseRecording(id, otherToken!!),
        )
        // Token de claim presentado a la operacion de grupo.
        assertEquals(
            ReleaseResult.NOT_FOUND,
            registry.releaseGroup(id, token!!),
        )
        assertEquals(2L, registry.snapshot().foreignReleaseRejected)

        // El id sigue reclamado: ni reserva ni segundo claim pasan.
        assertNull(registry.tryReserveGroup(id, "reconcile:run-1"))
        assertNull(registry.tryClaimRecording(id))
        val entry = registry.snapshot().activeClaims[id]
        assertNotNull(entry)
        assertEquals(token, entry!!.token)

        // Limpieza: el dueno si libera.
        assertEquals(
            ReleaseResult.RELEASED,
            registry.releaseRecording(id, token),
        )
        assertEquals(
            ReleaseResult.RELEASED,
            registry.releaseRecording(otherId, otherToken),
        )
    }

    @Test
    fun `U48 doble liberacion y liberacion tras re-claim`() = runBlocking {
        val registry = ActiveRecordingRegistry()
        val id = "rec_u48"

        // (i) Doble liberacion: la 2.ª es NOT_FOUND, no lanza.
        val first = registry.tryClaimRecording(id)
        assertNotNull(first)
        assertEquals(
            ReleaseResult.RELEASED,
            registry.releaseRecording(id, first!!),
        )
        assertEquals(
            ReleaseResult.NOT_FOUND,
            registry.releaseRecording(id, first),
        )

        // (ii) Liberacion tras re-claim: el token obsoleto no toca
        // el claim nuevo.
        val second = registry.tryClaimRecording(id)
        assertNotNull(second)
        assertNotEquals("los tokens nunca se reutilizan", first, second)
        assertEquals(
            ReleaseResult.NOT_OWNER,
            registry.releaseRecording(id, first),
        )
        assertEquals(1L, registry.snapshot().foreignReleaseRejected)
        // El claim T2 sigue vivo: ni reserva ni claim pasan.
        assertNull(registry.tryReserveGroup(id, "reconcile:run-1"))
        assertNull(registry.tryClaimRecording(id))
        val entry = registry.snapshot().activeClaims[id]
        assertNotNull(entry)
        assertEquals(second, entry!!.token)
        assertEquals(
            ReleaseResult.RELEASED,
            registry.releaseRecording(id, second!!),
        )
    }

    @Test
    fun `U38 atomicidad claim vs reserva, exactamente un no-nulo por repeticion`() =
        runBlocking {
            val registry = ActiveRecordingRegistry()
            val winnerTokens = mutableListOf<OwnershipToken>()
            repeat(100) { rep ->
                val id = "rec_u38_$rep"
                val results = runConcurrently(50) { index ->
                    if (index % 2 == 0) {
                        registry.tryClaimRecording(id)
                    } else {
                        registry.tryReserveGroup(id, "reconcile:run-$rep")
                    }
                }
                val nonNull = results.filterNotNull()
                assertEquals(
                    "rep $rep: exactamente un ganador entre claim y reserva",
                    1,
                    nonNull.size,
                )
                winnerTokens += nonNull.single()
            }
            assertEquals(
                "tokens distintos entre repeticiones",
                winnerTokens.size,
                winnerTokens.toSet().size,
            )
            // Invariante triple en el snapshot final.
            val snapshot = registry.snapshot()
            assertTrue(
                "claves disjuntas",
                snapshot.activeClaims.keys
                    .intersect(snapshot.reservedGroups.keys).isEmpty(),
            )
            assertEquals(100, snapshot.activeClaims.size + snapshot.reservedGroups.size)
            val allTokens =
                snapshot.activeClaims.values.map { it.token } +
                    snapshot.reservedGroups.values.map { it.token }
            assertEquals(
                "cada token identifica exactamente un par (id, clase)",
                allTokens.size,
                allTokens.toSet().size,
            )
            assertTrue(
                "todas las entradas tienen edad >= 0",
                (snapshot.activeClaims.values +
                    snapshot.reservedGroups.values).all { it.ageMs >= 0 },
            )
        }

    @Test
    fun `U42 reserva liberada siempre en finally, doble liberacion tolerada`() =
        runBlocking {
            val registry = ActiveRecordingRegistry()
            val id = "rec_u42"

            // Patron de la reconciliacion (0-d): reserva, procesar,
            // `releaseGroup` en `finally` siempre. El `finally` puede
            // correr dos veces (doble via de salida) sin romper nada.
            val token = registry.tryReserveGroup(id, "reconcile:run-1")
            assertNotNull(token)
            try {
                assertNull(
                    "grupo reservado: el claim no pasa",
                    registry.tryClaimRecording(id),
                )
            } finally {
                assertEquals(
                    ReleaseResult.RELEASED,
                    registry.releaseGroup(id, token!!),
                )
            }
            // Segundo `finally`: doble liberacion tolerada.
            assertEquals(
                ReleaseResult.NOT_FOUND,
                registry.releaseGroup(id, token),
            )
            // Liberacion con token incorrecto desde un `finally` no libera.
            val second = registry.tryReserveGroup(id, "reconcile:run-2")
            assertNotNull(second)
            assertEquals(
                ReleaseResult.NOT_OWNER,
                registry.releaseGroup(id, token),
            )
            assertNull(
                "la reserva sigue viva tras el intento ajeno",
                registry.tryClaimRecording(id),
            )
            assertEquals(
                ReleaseResult.RELEASED,
                registry.releaseGroup(id, second!!),
            )
            val snapshot = registry.snapshot()
            assertTrue(snapshot.activeClaims.isEmpty())
            assertTrue(snapshot.reservedGroups.isEmpty())
        }
}
