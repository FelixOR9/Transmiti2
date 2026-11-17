package com.transmiti2.transmiti2.streaming

import android.Manifest
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias puras (JVM, sin teléfono) de las pre-comprobaciones
 * del servicio en primer plano de la subetapa 3.1.0.
 *
 * Cubren la matriz de escenarios por permisos faltantes (sin intento de
 * arranque cuando falta alguno) y la compatibilidad de los tipos de
 * servicio por versión de Android. El arranque real del servicio con
 * una grabación se verifica en el teléfono en la subetapa 3.1.2.
 */
class ForegroundServicePrecheckTest {

    private fun isGranted(granted: Set<String>): (String) -> Boolean =
        { it in granted }

    @Test
    fun `con audio exige camara y microfono, sin audio solo camara`() {
        assertEquals(
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            ForegroundServicePrecheck.requiredPermissions(
                RecordingScenario.WITH_AUDIO,
            ),
        )
        assertEquals(
            listOf(Manifest.permission.CAMERA),
            ForegroundServicePrecheck.requiredPermissions(
                RecordingScenario.WITHOUT_AUDIO,
            ),
        )
    }

    @Test
    fun `con audio no arranca si falta el microfono`() {
        val granted = isGranted(setOf(Manifest.permission.CAMERA))
        assertFalse(
            ForegroundServicePrecheck.canStart(
                RecordingScenario.WITH_AUDIO,
                granted,
            ),
        )
        assertEquals(
            listOf(Manifest.permission.RECORD_AUDIO),
            ForegroundServicePrecheck.missingPermissions(
                RecordingScenario.WITH_AUDIO,
                granted,
            ),
        )
    }

    @Test
    fun `con audio no arranca si falta la camara`() {
        val granted = isGranted(setOf(Manifest.permission.RECORD_AUDIO))
        assertFalse(
            ForegroundServicePrecheck.canStart(
                RecordingScenario.WITH_AUDIO,
                granted,
            ),
        )
        assertEquals(
            listOf(Manifest.permission.CAMERA),
            ForegroundServicePrecheck.missingPermissions(
                RecordingScenario.WITH_AUDIO,
                granted,
            ),
        )
    }

    @Test
    fun `con audio arranca con los dos permisos`() {
        val granted = isGranted(
            setOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
        )
        assertTrue(
            ForegroundServicePrecheck.canStart(
                RecordingScenario.WITH_AUDIO,
                granted,
            ),
        )
        assertTrue(
            ForegroundServicePrecheck.missingPermissions(
                RecordingScenario.WITH_AUDIO,
                granted,
            ).isEmpty(),
        )
    }

    @Test
    fun `sin audio arranca solo con la camara`() {
        val granted = isGranted(setOf(Manifest.permission.CAMERA))
        assertTrue(
            ForegroundServicePrecheck.canStart(
                RecordingScenario.WITHOUT_AUDIO,
                granted,
            ),
        )
    }

    @Test
    fun `sin audio no arranca sin la camara`() {
        val granted = isGranted(emptySet())
        assertFalse(
            ForegroundServicePrecheck.canStart(
                RecordingScenario.WITHOUT_AUDIO,
                granted,
            ),
        )
        assertEquals(
            listOf(Manifest.permission.CAMERA),
            ForegroundServicePrecheck.missingPermissions(
                RecordingScenario.WITHOUT_AUDIO,
                granted,
            ),
        )
    }

    @Test
    fun `en API menor de 29 no hay tipos de servicio`() {
        assertEquals(
            0,
            ForegroundServicePrecheck.foregroundServiceType(
                RecordingScenario.WITH_AUDIO,
                28,
            ),
        )
        assertEquals(
            0,
            ForegroundServicePrecheck.foregroundServiceType(
                RecordingScenario.WITHOUT_AUDIO,
                28,
            ),
        )
    }

    @Test
    fun `en API 29 la mascara es camara y microfono con audio`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            ForegroundServicePrecheck.foregroundServiceType(
                RecordingScenario.WITH_AUDIO,
                29,
            ),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            ForegroundServicePrecheck.foregroundServiceType(
                RecordingScenario.WITHOUT_AUDIO,
                29,
            ),
        )
    }

    @Test
    fun `en API 34 la mascara es la misma que en API 29`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            ForegroundServicePrecheck.foregroundServiceType(
                RecordingScenario.WITH_AUDIO,
                34,
            ),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            ForegroundServicePrecheck.foregroundServiceType(
                RecordingScenario.WITHOUT_AUDIO,
                34,
            ),
        )
    }

    @Test
    fun `el escenario se reconoce por su nombre del canal`() {
        assertEquals(
            RecordingScenario.WITH_AUDIO,
            RecordingScenario.fromWireName("with_audio"),
        )
        assertEquals(
            RecordingScenario.WITHOUT_AUDIO,
            RecordingScenario.fromWireName("without_audio"),
        )
        assertNull(RecordingScenario.fromWireName("otro"))
        assertNull(RecordingScenario.fromWireName(null))
    }

    @Test
    fun `las constantes del servicio son estables`() {
        assertEquals("transmiti2_recording", StreamForegroundService.CHANNEL_ID)
        assertEquals(1, StreamForegroundService.NOTIFICATION_ID)
        assertEquals("scenario", StreamForegroundService.EXTRA_SCENARIO)
    }
}
