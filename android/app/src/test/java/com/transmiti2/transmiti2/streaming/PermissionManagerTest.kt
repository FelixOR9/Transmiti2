package com.transmiti2.transmiti2.streaming

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias puras (JVM, sin teléfono) de la clasificación de
 * permisos y del historial de solicitudes de la subetapa 3.1.0.
 *
 * La distinción entre primera solicitud y denegación permanente no se
 * puede probar con `shouldShowRequestPermissionRationale()` solo: la
 * prueba cubre la clasificación combinada con el historial persistente.
 * El diálogo real del sistema y la revocación en Ajustes se verifican
 * en el teléfono (checklist manual).
 */
class PermissionManagerTest {

    private class InMemoryHistory : PermissionRequestHistory {
        private val requested = mutableSetOf<PermissionKind>()
        override fun wasRequested(kind: PermissionKind): Boolean =
            kind in requested
        override fun markRequested(kind: PermissionKind) {
            requested += kind
        }
    }

    private fun manager(
        history: PermissionRequestHistory = InMemoryHistory(),
        granted: Set<String> = emptySet(),
        rationale: Set<String> = emptySet(),
        sdkInt: Int = 34,
    ): PermissionManager {
        return PermissionManager(
            history = history,
            isGranted = { it in granted },
            shouldShowRationale = { it in rationale },
            sdkInt = sdkInt,
        )
    }

    @Test
    fun `concedido es GRANTED aunque no se haya pedido antes`() {
        assertEquals(
            PermissionStatus.GRANTED,
            PermissionClassifier.classify(
                granted = true,
                everRequested = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `nunca pedido es NOT_REQUESTED aunque rationale sea falso`() {
        // Este es el caso que rationale por sí solo no distingue de una
        // denegación permanente.
        assertEquals(
            PermissionStatus.NOT_REQUESTED,
            PermissionClassifier.classify(
                granted = false,
                everRequested = false,
                shouldShowRationale = false,
            ),
        )
        assertEquals(
            PermissionStatus.NOT_REQUESTED,
            PermissionClassifier.classify(
                granted = false,
                everRequested = false,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun `pedido antes con rationale es DENIED`() {
        assertEquals(
            PermissionStatus.DENIED,
            PermissionClassifier.classify(
                granted = false,
                everRequested = true,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun `pedido antes sin rationale es PERMANENTLY_DENIED`() {
        assertEquals(
            PermissionStatus.PERMANENTLY_DENIED,
            PermissionClassifier.classify(
                granted = false,
                everRequested = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `el historial marca y recuerda lo pedido`() {
        val history = InMemoryHistory()
        assertFalse(history.wasRequested(PermissionKind.CAMERA))
        history.markRequested(PermissionKind.CAMERA)
        assertTrue(history.wasRequested(PermissionKind.CAMERA))
        assertFalse(history.wasRequested(PermissionKind.MICROPHONE))
        history.markRequested(listOf(PermissionKind.MICROPHONE))
        assertTrue(history.wasRequested(PermissionKind.MICROPHONE))
    }

    @Test
    fun `checkStates combina permiso, historial y rationale`() {
        val history = InMemoryHistory()
        history.markRequested(PermissionKind.CAMERA)
        history.markRequested(PermissionKind.MICROPHONE)
        val m = manager(
            history = history,
            granted = setOf(Manifest.permission.RECORD_AUDIO),
            rationale = setOf(Manifest.permission.CAMERA),
        )
        val states = m.checkStates()
        // Cámara: pedida antes + rationale -> DENIED.
        assertEquals(PermissionStatus.DENIED, states[PermissionKind.CAMERA])
        // Micrófono: concedido -> GRANTED.
        assertEquals(PermissionStatus.GRANTED, states[PermissionKind.MICROPHONE])
        // Notificaciones: nunca pedidas -> NOT_REQUESTED.
        assertEquals(
            PermissionStatus.NOT_REQUESTED,
            states[PermissionKind.NOTIFICATIONS],
        )
    }

    @Test
    fun `una denegacion sin rationale tras pedir es permanente`() {
        val history = InMemoryHistory()
        history.markRequested(PermissionKind.CAMERA)
        val m = manager(history = history)
        assertEquals(
            PermissionStatus.PERMANENTLY_DENIED,
            m.checkState(PermissionKind.CAMERA),
        )
    }

    @Test
    fun `en API menor de 33 las notificaciones no estan bloqueadas`() {
        // En API 32 POST_NOTIFICATIONS no existe como permiso en tiempo
        // de ejecución: se informa GRANTED sin consultar nada.
        val m = manager(sdkInt = 32)
        assertEquals(
            PermissionStatus.GRANTED,
            m.checkState(PermissionKind.NOTIFICATIONS),
        )
        assertTrue(m.androidPermissionsFor(PermissionKind.NOTIFICATIONS).isEmpty())
    }

    @Test
    fun `en API 33 las notificaciones si se piden`() {
        val m = manager(sdkInt = 33)
        assertEquals(
            PermissionStatus.NOT_REQUESTED,
            m.checkState(PermissionKind.NOTIFICATIONS),
        )
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            m.androidPermissionsFor(PermissionKind.NOTIFICATIONS),
        )
    }

    @Test
    fun `requestable excluye concedidos y permanentes`() {
        val history = InMemoryHistory()
        history.markRequested(PermissionKind.CAMERA)
        history.markRequested(PermissionKind.MICROPHONE)
        val m = manager(
            history = history,
            granted = setOf(Manifest.permission.RECORD_AUDIO),
            rationale = setOf(Manifest.permission.CAMERA),
        )
        // Cámara DENIED -> pedible; micrófono GRANTED -> no;
        // notificaciones NOT_REQUESTED -> pedible.
        assertEquals(
            listOf(PermissionKind.CAMERA, PermissionKind.NOTIFICATIONS),
            m.requestable(PermissionKind.values().toList()),
        )
    }

    @Test
    fun `los nombres del canal son estables`() {
        assertEquals("camera", PermissionKind.CAMERA.wireName)
        assertEquals("microphone", PermissionKind.MICROPHONE.wireName)
        assertEquals("notifications", PermissionKind.NOTIFICATIONS.wireName)
        assertEquals(PermissionKind.CAMERA, PermissionKind.fromWireName("camera"))
        assertEquals(null, PermissionKind.fromWireName("otro"))
        assertEquals("notRequested", PermissionStatus.NOT_REQUESTED.wireName)
        assertEquals("granted", PermissionStatus.GRANTED.wireName)
        assertEquals("denied", PermissionStatus.DENIED.wireName)
        assertEquals(
            "permanentlyDenied",
            PermissionStatus.PERMANENTLY_DENIED.wireName,
        )
    }
}
