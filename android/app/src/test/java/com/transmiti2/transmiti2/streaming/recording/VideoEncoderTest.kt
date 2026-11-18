package com.transmiti2.transmiti2.streaming.recording

import android.media.MediaCodecInfo
import com.transmiti2.transmiti2.streaming.StreamContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decide la configuración real de codificación a partir de las
 * capacidades medidas (plan 3.1.2 §3.2) y valida la configuración.
 * Lógica pura, sin Android: el codec real y la cámara se prueban en
 * el teléfono (el spike ya midió CBR ✓ y 720p ✓ en el S24 Ultra).
 */
class VideoEncoderTest {

    private fun caps(
        profiles: List<Int> = listOf(
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        ),
        cbrSupported: Boolean = true,
    ) = EncoderCapabilities(
        encoderName = "c2.qti.avc.encoder",
        profiles = profiles,
        levels = listOf(131072),
        cbrSupported = cbrSupported,
        cameraSupportsSize = true,
    )

    @Test
    fun `configuracion valida pasa la validacion`() {
        VideoEncoderConfig(
            width = 1280,
            height = 720,
            fps = 30,
            bitrateBps = 2_500_000,
        ).validate()
    }

    @Test
    fun `ancho no valido es rechazado`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoEncoderConfig(
                width = 0,
                height = 720,
                fps = 30,
                bitrateBps = 2_500_000,
            ).validate()
        }
    }

    @Test
    fun `alto no valido es rechazado`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoEncoderConfig(
                width = 1280,
                height = 0,
                fps = 30,
                bitrateBps = 2_500_000,
            ).validate()
        }
    }

    @Test
    fun `fps no valido es rechazado`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoEncoderConfig(
                width = 1280,
                height = 720,
                fps = 0,
                bitrateBps = 2_500_000,
            ).validate()
        }
    }

    @Test
    fun `bitrate no valido es rechazado`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoEncoderConfig(
                width = 1280,
                height = 720,
                fps = 30,
                bitrateBps = 0,
            ).validate()
        }
    }

    @Test
    fun `intervalo de keyframes no valido es rechazado`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoEncoderConfig(
                width = 1280,
                height = 720,
                fps = 30,
                bitrateBps = 2_500_000,
                keyframeIntervalSec = 0,
            ).validate()
        }
    }

    @Test
    fun `baseline anunciado se elige con CBR`() {
        val config = VideoEncoder.chooseConfig(
            caps(),
            width = 1280,
            height = 720,
            fps = 30,
            bitrateBps = 2_500_000,
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            config.profile,
        )
        assertEquals(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            config.bitrateMode,
        )
        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertEquals(30, config.fps)
        assertEquals(2_500_000, config.bitrateBps)
        assertEquals(2, config.keyframeIntervalSec)
    }

    @Test
    fun `sin baseline se elige Main`() {
        val config = VideoEncoder.chooseConfig(
            caps(
                profiles = listOf(
                    MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                ),
            ),
            width = 1280,
            height = 720,
            fps = 30,
            bitrateBps = 2_500_000,
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
            config.profile,
        )
    }

    @Test
    fun `sin baseline ni Main se elige el primer perfil anunciado`() {
        val config = VideoEncoder.chooseConfig(
            caps(
                profiles = listOf(
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                ),
            ),
            width = 1280,
            height = 720,
            fps = 30,
            bitrateBps = 2_500_000,
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            config.profile,
        )
    }

    @Test
    fun `sin CBR se elige VBR`() {
        val config = VideoEncoder.chooseConfig(
            caps(cbrSupported = false),
            width = 1280,
            height = 720,
            fps = 30,
            bitrateBps = 2_500_000,
        )
        assertEquals(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            config.bitrateMode,
        )
    }

    @Test
    fun `sin perfiles anunciados es encoder_error`() {
        val thrown = assertThrows(RecordingException::class.java) {
            VideoEncoder.chooseConfig(
                caps(profiles = emptyList()),
                width = 1280,
                height = 720,
                fps = 30,
                bitrateBps = 2_500_000,
            )
        }
        assertEquals(StreamContract.ENCODER_ERROR, thrown.code)
        assertTrue(thrown.details["stage"] == "chooseConfig")
    }
}
