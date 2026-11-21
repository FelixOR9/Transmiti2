package com.transmiti2.transmiti2.streaming.recording

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Token de propiedad de un claim o una reserva. Monótono creciente
 * por registro, empezando en 1; el 0 queda reservado como «sin token»
 * y nunca se emite. Los tokens nunca salen del proceso (no cruzan el
 * canal a Dart, no se persisten, no se serializan): lo que se necesita
 * es unicidad dentro del proceso y orden total para el diagnóstico, y
 * un contador monótono lo da por construcción (colisión imposible).
 */
typealias OwnershipToken = Long

/** Resultado de una liberación: solo el dueño libera. */
enum class ReleaseResult {
    RELEASED,
    NOT_OWNER,
    NOT_FOUND,
}

/**
 * Instantánea de una entrada del registro, con su edad (las fugas son
 * visibles por edad anómala; nunca se libera nada automáticamente).
 */
data class RegistryEntrySnapshot(
    val id: String,
    val token: OwnershipToken,
    val owner: String,
    val acquiredAtMs: Long,
    val ageMs: Long,
)

/**
 * Instantánea completa del registro: entradas vivas + contadores de
 * rechazo por motivo. Solo diagnóstico/observabilidad.
 */
data class RegistrySnapshot(
    val activeClaims: Map<String, RegistryEntrySnapshot>,
    val reservedGroups: Map<String, RegistryEntrySnapshot>,
    /** Claims rechazados porque el id ya tenía un claim vivo. */
    val claimRejectedActive: Long,
    /** Claims rechazados porque el id estaba reservado. */
    val claimRejectedReserved: Long,
    /** Reservas rechazadas porque el grupo ya estaba reservado. */
    val reserveRejectedReserved: Long,
    /** Reservas rechazadas porque el id tenía un claim vivo. */
    val reserveRejectedActive: Long,
    /** Liberaciones rechazadas por token ajeno. */
    val foreignReleaseRejected: Long,
)

/**
 * Registro central de coordinación entre grabación y reconciliación
 * (subetapa 0, grupo 0-c; revisión 3 §3.1, plan §2.4).
 *
 * Sin Android, testeable en JVM pura. Propiedad de `RecordingSession`,
 * inyectable por constructor. Es la única fuente de verdad sobre qué
 * id está siendo grabado y qué grupo está siendo reconciliado: ningún
 * camino que toque archivos de un grupo debe hacerlo sin pasar por
 * aquí (claim para grabar, reserva para reconciliar).
 *
 * Invariante triple, garantizado por construcción (demostración de
 * linealización en revisión 3 §3.1):
 * 1. `keys(activeClaims) ∩ keys(reservedGroups) = ∅` (chequeo cruzado).
 * 2. Como mucho una entrada por id y mapa (chequeo del propio lado).
 * 3. Cada token identifica exactamente un par (id, clase) (generación
 *    monótona bajo el mutex, sin reutilización).
 *
 * Reglas de orden:
 * - `coordinationMutex` es hoja: nunca se toma teniendo otro mutex ni
 *   se mantiene mientras se adquiere otro. Las secciones críticas son
 *   solo búsquedas en mapa, una comparación, un incremento y una
 *   inserción/eliminación: sin `suspend` interno, sin E/S, sin esperas.
 *   El punto de linealización de cada operación es su sección crítica
 *   completa.
 * - Sin expiración de tokens: viven hasta la liberación explícita. Una
 *   expiración reintroduciría una carrera por tiempo; las fugas se
 *   detectan por edad en `snapshot()`, nunca se corrigen solas.
 * - Ninguna liberación lanza excepción por el estado del registro: un
 *   id ausente es `NOT_FOUND`, un token ajeno es `NOT_OWNER`, y ambos
 *   se devuelven como valor, no como excepción. (La cancelación de la
 *   corrutina que llama sigue siendo cooperativa como en cualquier
 *   `withLock`: las liberaciones en `finally` deben correr en
 *   `NonCancellable`.)
 */
class ActiveRecordingRegistry(
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    /** Único mutex de coordinación. Hoja: ver reglas de orden arriba. */
    val coordinationMutex = Mutex()

    private data class ClaimEntry(
        val token: OwnershipToken,
        val owner: String,
        val acquiredAtMs: Long,
    )

    private val activeClaims = mutableMapOf<String, ClaimEntry>()
    private val reservedGroups = mutableMapOf<String, ClaimEntry>()

    /** Siguiente token a emitir (0 = «sin token», nunca se emite). */
    private var nextToken: Long = 0

    private var claimRejectedActiveCount: Long = 0
    private var claimRejectedReservedCount: Long = 0
    private var reserveRejectedReservedCount: Long = 0
    private var reserveRejectedActiveCount: Long = 0
    private var foreignReleaseRejectedCount: Long = 0

    /**
     * Grabador: reclama `id`. Éxito ⟺ `id ∉ activeClaims ∧
     * id ∉ reservedGroups` (doble chequeo, misma sección crítica).
     * Devuelve el token del claim, o `null` si fue rechazado (el
     * llamador nunca debe seguir hacia `ensureDir()`/apertura de
     * segmento con un `null`: fail-closed en el call site).
     */
    suspend fun tryClaimRecording(id: String): OwnershipToken? =
        coordinationMutex.withLock {
            if (id in activeClaims) {
                claimRejectedActiveCount++
                null
            } else if (id in reservedGroups) {
                claimRejectedReservedCount++
                null
            } else {
                val token = ++nextToken
                activeClaims[id] = ClaimEntry(
                    token = token,
                    owner = "recording",
                    acquiredAtMs = clockMs(),
                )
                token
            }
        }

    /**
     * Reconciliación: reserva el grupo `id`. Condición simétrica a
     * [tryClaimRecording]. `owner` es `"reconcile:<runId>"` (el `runId`
     * distingue dos reconciliaciones en el diagnóstico; la identidad
     * es el token, nunca el `owner`).
     */
    suspend fun tryReserveGroup(id: String, owner: String): OwnershipToken? =
        coordinationMutex.withLock {
            if (id in reservedGroups) {
                reserveRejectedReservedCount++
                null
            } else if (id in activeClaims) {
                reserveRejectedActiveCount++
                null
            } else {
                val token = ++nextToken
                reservedGroups[id] = ClaimEntry(
                    token = token,
                    owner = owner,
                    acquiredAtMs = clockMs(),
                )
                token
            }
        }

    /**
     * Grabador: libera el claim de `id` solo si `token` es el del
     * dueño. Doble liberación → 2.ª `NOT_FOUND`; token ajeno →
     * `NOT_OWNER` sin efecto sobre la entrada viva.
     */
    suspend fun releaseRecording(id: String, token: OwnershipToken): ReleaseResult =
        coordinationMutex.withLock {
            releaseLocked(activeClaims, id, token)
        }

    /**
     * Reconciliación: libera la reserva del grupo `id`. Simétrica a
     * [releaseRecording] sobre `reservedGroups`. Un token de claim
     * presentado aquí es `NOT_OWNER` (o `NOT_FOUND` si el id no está
     * reservado): las clases no se mezclan.
     */
    suspend fun releaseGroup(id: String, token: OwnershipToken): ReleaseResult =
        coordinationMutex.withLock {
            releaseLocked(reservedGroups, id, token)
        }

    /** Solo diagnóstico/observabilidad: instantánea bajo el mutex. */
    suspend fun snapshot(): RegistrySnapshot =
        coordinationMutex.withLock {
            val nowMs = clockMs()
            RegistrySnapshot(
                activeClaims = activeClaims.mapValues { (id, entry) ->
                    entry.toSnapshot(id, nowMs)
                },
                reservedGroups = reservedGroups.mapValues { (id, entry) ->
                    entry.toSnapshot(id, nowMs)
                },
                claimRejectedActive = claimRejectedActiveCount,
                claimRejectedReserved = claimRejectedReservedCount,
                reserveRejectedReserved = reserveRejectedReservedCount,
                reserveRejectedActive = reserveRejectedActiveCount,
                foreignReleaseRejected = foreignReleaseRejectedCount,
            )
        }

    private fun releaseLocked(
        claims: MutableMap<String, ClaimEntry>,
        id: String,
        token: OwnershipToken,
    ): ReleaseResult {
        val entry = claims[id] ?: return ReleaseResult.NOT_FOUND
        if (entry.token != token) {
            foreignReleaseRejectedCount++
            return ReleaseResult.NOT_OWNER
        }
        claims.remove(id)
        return ReleaseResult.RELEASED
    }

    private fun ClaimEntry.toSnapshot(id: String, nowMs: Long) =
        RegistryEntrySnapshot(
            id = id,
            token = token,
            owner = owner,
            acquiredAtMs = acquiredAtMs,
            ageMs = (nowMs - acquiredAtMs).coerceAtLeast(0L),
        )
}
