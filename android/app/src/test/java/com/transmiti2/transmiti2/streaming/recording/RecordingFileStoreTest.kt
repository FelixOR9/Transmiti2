package com.transmiti2.transmiti2.streaming.recording

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Rutas, nombres y sidecar de la grabación (plan 3.1.2 §5). Solo
 * `java.io`: sin Android.
 */
class RecordingFileStoreTest {

    private lateinit var dir: File
    private lateinit var store: RecordingFileStore

    @Before
    fun setUp() {
        dir = File(
            System.getProperty("java.io.tmpdir"),
            "recording-store-test-${System.nanoTime()}",
        )
        dir.mkdirs()
        store = RecordingFileStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `el id de grabacion sigue el formato rec_fecha_hora_hex`() {
        val id = store.newRecordingId(1_792_000_000_000L)
        assertTrue(id, id.matches(Regex("rec_\\d{8}_\\d{6}_[0-9a-f]{4}")))
    }

    @Test
    fun `dos ids generados seguidos son distintos`() {
        val first = store.newRecordingId()
        val second = store.newRecordingId()
        // Mismo segundo: el sufijo aleatorio los distingue.
        assertTrue(first != second)
    }

    @Test
    fun `nombres de segmento con indice de tres digitos`() {
        assertEquals(
            "rec_20261118_153045_ab12_seg000.mp4",
            store.segmentFileName("rec_20261118_153045_ab12", 0),
        )
        assertEquals(
            "rec_20261118_153045_ab12_seg007.mp4",
            store.segmentFileName("rec_20261118_153045_ab12", 7),
        )
        assertEquals(
            "rec_20261118_153045_ab12_seg007.tmp.mp4",
            store.segmentStagingFileName("rec_20261118_153045_ab12", 7),
        )
        assertEquals(
            "rec_20261118_153045_ab12_final.mp4",
            store.finalFileName("rec_20261118_153045_ab12"),
        )
    }

    @Test
    fun `renombrar staging a final mueve el archivo`() {
        val staging = store.segmentStagingFile("rec_1", 0)
        staging.writeText("mp4")
        val final = store.segmentFile("rec_1", 0)
        assertTrue(store.renameStagingToFinal(staging, final))
        assertFalse(staging.exists())
        assertTrue(final.exists())
        assertEquals("mp4", final.readText())
    }

    @Test
    fun `parsear nombre de segmento final y staging`() {
        assertEquals(
            "rec_20261118_153045_ab12" to 3,
            RecordingFileStore.parseSegmentFileName(
                "rec_20261118_153045_ab12_seg003.mp4",
            ),
        )
        assertEquals(
            "rec_20261118_153045_ab12" to 3,
            RecordingFileStore.parseSegmentFileName(
                "rec_20261118_153045_ab12_seg003.tmp.mp4",
            ),
        )
        assertNull(
            RecordingFileStore.parseSegmentFileName("rec_1_final.mp4"),
        )
        assertNull(
            RecordingFileStore.parseSegmentFileName("rec_1_seg3.mp4"),
        )
        assertNull(
            RecordingFileStore.parseSegmentFileName("spike_classic_1.mp4"),
        )
    }

    @Test
    fun `listar staging y segmentos finalizados`() {
        store.segmentStagingFile("rec_1", 0).writeText("a")
        store.segmentStagingFile("rec_1", 1).writeText("b")
        store.segmentFile("rec_1", 0).writeText("c")
        store.segmentFile("rec_2", 0).writeText("d")
        assertEquals(
            listOf(
                "rec_1_seg000.tmp.mp4",
                "rec_1_seg001.tmp.mp4",
            ),
            store.listStagingFiles().map { it.name },
        )
        assertEquals(
            listOf("rec_1_seg000.mp4"),
            store.listSegmentFiles("rec_1").map { it.name },
        )
    }

    @Test
    fun `sidecar se escribe atomico con los metadatos`() {
        val metadata = RecordingMetadata(
            recordingId = "rec_20261118_153045_ab12",
            matchId = "match-1",
            width = 1280,
            height = 720,
            fps = 30,
            bitrateBps = 2_500_000,
            rotationDegrees = 90,
            encoderName = "c2.qti.avc.encoder",
            profile = 1,
            bitrateMode = 2,
            segments = listOf(
                SegmentMetadata(
                    index = 0,
                    fileName = "rec_20261118_153045_ab12_seg000.mp4",
                    firstEncoderPtsUs = 123456L,
                    startWallMs = 1_792_000_000_000L,
                    status = "ready",
                    durationMs = 60_000L,
                    sizeBytes = 18_000_000L,
                ),
            ),
        )
        store.writeSidecar(metadata)
        val file = store.sidecarFile("rec_20261118_153045_ab12")
        assertTrue(file.exists())
        assertFalse(File(dir, "rec_20261118_153045_ab12.json.tmp").exists())
        val json = file.readText()
        assertTrue(json.contains("\"recordingId\":\"rec_20261118_153045_ab12\""))
        assertTrue(json.contains("\"encoderName\":\"c2.qti.avc.encoder\""))
        assertTrue(json.contains("\"firstEncoderPtsUs\":123456"))
        assertTrue(json.contains("\"status\":\"ready\""))
        assertTrue(json.contains("\"durationMs\":60000"))
    }

    @Test
    fun `sidecar escapa comillas en los textos`() {
        val metadata = RecordingMetadata(
            recordingId = "rec_\"x\"",
            matchId = null,
            width = 1280,
            height = 720,
            fps = 30,
            bitrateBps = 2_500_000,
            rotationDegrees = 0,
            encoderName = "enc\"oder",
            profile = 1,
            bitrateMode = 2,
            segments = emptyList(),
        )
        assertTrue(metadata.toJson().contains("rec_\\\"x\\\""))
        assertTrue(metadata.toJson().contains("enc\\\"oder"))
    }
}
