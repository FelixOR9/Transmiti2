package com.transmiti2.transmiti2.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias puras (JVM, sin teléfono) de la lógica de cifrado y
 * validación de la Fase 3.0. La integración con Android Keystore se
 * verifica en el teléfono (ver docs/ETAPA3_FASE30.md, checklist manual).
 */
class StreamAadTest {

    @Test
    fun `el AAD canonico ata la clave a su destino`() {
        val aad1 = StreamAad.streamKeyAad(1).toString(Charsets.UTF_8)
        val aad2 = StreamAad.streamKeyAad(2).toString(Charsets.UTF_8)
        assertEquals("transmiti2:stream-key:v1:1", aad1)
        assertEquals("transmiti2:stream-key:v1:2", aad2)
        assertNotEquals(aad1, aad2)
    }

    @Test
    fun `la URL de ingesta debe ser RTMPS`() {
        assertTrue(
            RtmpUrl.isValidIngestUrl("rtmps://live-api-s.facebook.com:443/rtmp/"),
        )
        assertFalse(
            RtmpUrl.isValidIngestUrl("rtmp://live-api-s.facebook.com:443/rtmp/"),
        )
        assertFalse(RtmpUrl.isValidIngestUrl("https://example.com/live"))
        assertFalse(RtmpUrl.isValidIngestUrl(""))
        assertFalse(RtmpUrl.isValidIngestUrl("rtmps://"))
    }

    @Test
    fun `el guarda de Keystore falla cerrado si Keystore no esta activo`() {
        assertThrows(SecureStoreUnavailableException::class.java) {
            KeystoreGuard.requireKeystoreActive(false)
        }
        // Con Keystore activo no lanza nada.
        KeystoreGuard.requireKeystoreActive(true)
    }
}
