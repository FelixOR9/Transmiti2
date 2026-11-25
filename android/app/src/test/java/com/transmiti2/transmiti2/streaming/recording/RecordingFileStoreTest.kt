package com.transmiti2.transmiti2.streaming.recording

import com.transmiti2.transmiti2.streaming.StreamContract
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun `U2 colision de id genera un sufijo distinto`() {
        // S3': si el sidecar del id ya existe, se regenera el sufijo.
        val nowMs = 1_792_000_000_000L
        val first = store.newRecordingId(nowMs) { "ab12" }
        File(dir, "$first.json").writeText("{}")
        val suffixes = listOf("ab12", "cd34")
        var index = 0
        val second = store.newRecordingId(nowMs) { suffixes[index++] }
        assertNotEquals(first, second)
        assertTrue(second.endsWith("_cd34"))
    }

    @Test
    fun `U2 diez colisiones seguidas dan error visible sin grabar`() {
        val nowMs = 1_792_000_000_000L
        val colliding = store.newRecordingId(nowMs) { "ab12" }
        File(dir, "$colliding.json").writeText("{}")
        val thrown = assertThrows(RecordingException::class.java) {
            store.newRecordingId(nowMs) { "ab12" }
        }
        assertEquals(StreamContract.RECORDING_STATE_ERROR, thrown.code)
        assertEquals("id_reservation", thrown.details["stage"])
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
    fun `sidecar con audio guarda los campos de audio con sus valores`() {
        val metadata = RecordingMetadata(
            recordingId = "rec_20261119_134500_ab12",
            matchId = "match-1",
            width = 1280,
            height = 720,
            fps = 30,
            bitrateBps = 2_500_000,
            rotationDegrees = 90,
            encoderName = "c2.qti.avc.encoder",
            profile = 1,
            bitrateMode = 2,
            // "OMX.google.aac.encoder" es un nombre de encoder AAC
            // plausible usado solo como valor de prueba, no real.
            audioEnabled = true,
            audioSampleRate = 48000,
            audioChannels = 1,
            audioEncoderName = "OMX.google.aac.encoder",
            segments = listOf(
                SegmentMetadata(
                    index = 0,
                    fileName = "rec_20261119_134500_ab12_seg000.mp4",
                    firstEncoderPtsUs = 123456L,
                    startWallMs = 1_792_000_000_000L,
                    status = "ready",
                    durationMs = 60_000L,
                    sizeBytes = 18_000_000L,
                    audioExpected = true,
                    hasAudio = true,
                    audioDurationMs = 59_800L,
                    audioSampleCount = 2800,
                    audioStartOffsetMs = 150L,
                    audioReason = null,
                ),
            ),
        )
        store.writeSidecar(metadata)
        val json = store.sidecarFile("rec_20261119_134500_ab12").readText()
        assertTrue(json.contains("\"audioEnabled\":true"))
        assertTrue(json.contains("\"audioSampleRate\":48000"))
        assertTrue(json.contains("\"audioChannels\":1"))
        assertTrue(json.contains("\"audioEncoderName\":\"OMX.google.aac.encoder\""))
        assertTrue(json.contains("\"audioExpected\":true"))
        assertTrue(json.contains("\"hasAudio\":true"))
        assertTrue(json.contains("\"audioDurationMs\":59800"))
        assertTrue(json.contains("\"audioSampleCount\":2800"))
        assertTrue(json.contains("\"audioStartOffsetMs\":150"))
        assertTrue(json.contains("\"audioReason\":null"))
    }

    @Test
    fun `sidecar video-only lleva los campos de audio en falso o null`() {
        val metadata = RecordingMetadata(
            recordingId = "rec_20261119_134500_cd34",
            matchId = null,
            width = 1280,
            height = 720,
            fps = 30,
            bitrateBps = 2_500_000,
            rotationDegrees = 0,
            encoderName = "c2.qti.avc.encoder",
            profile = 1,
            bitrateMode = 2,
            segments = listOf(
                SegmentMetadata(
                    index = 0,
                    fileName = "rec_20261119_134500_cd34_seg000.mp4",
                    firstEncoderPtsUs = 123456L,
                    startWallMs = 1_792_000_000_000L,
                    status = "ready",
                    durationMs = 60_000L,
                    sizeBytes = 18_000_000L,
                    audioExpected = false,
                    hasAudio = false,
                    audioDurationMs = null,
                    audioSampleCount = null,
                    audioStartOffsetMs = null,
                    audioReason = null,
                ),
            ),
        )
        store.writeSidecar(metadata)
        val json = store.sidecarFile("rec_20261119_134500_cd34").readText()
        assertTrue(json.contains("\"audioEnabled\":false"))
        assertTrue(json.contains("\"audioSampleRate\":null"))
        assertTrue(json.contains("\"audioChannels\":null"))
        assertTrue(json.contains("\"audioEncoderName\":null"))
        assertTrue(json.contains("\"audioExpected\":false"))
        assertTrue(json.contains("\"hasAudio\":false"))
        assertTrue(json.contains("\"audioDurationMs\":null"))
        assertTrue(json.contains("\"audioSampleCount\":null"))
        assertTrue(json.contains("\"audioStartOffsetMs\":null"))
        assertTrue(json.contains("\"audioReason\":null"))
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

    // --- Subetapa 0, grupo 0-d1: sidecar E1–E6, renombre con guarda,
    // cuarentena, lector tolerante, barrido V4a (segunda sede). ---

    private fun metadataWithVerifiedSegment(id: String): RecordingMetadata {
        return RecordingMetadata(
            recordingId = id,
            matchId = "match-1",
            width = 1280,
            height = 720,
            fps = 30,
            bitrateBps = 2_500_000,
            rotationDegrees = 0,
            encoderName = "c2.qti.avc.encoder",
            profile = 1,
            bitrateMode = 2,
            segments = listOf(
                SegmentMetadata(
                    index = 0,
                    fileName = "${id}_seg000.mp4",
                    firstEncoderPtsUs = 0L,
                    startWallMs = 1_792_000_000_000L,
                    status = "ready",
                    durationMs = 3000L,
                    sizeBytes = 11L,
                    verifiedAtMs = 1_792_000_001_000L,
                    verifierVersion = 1,
                    verificationReason = null,
                    fpsMeasured = 30.0,
                ),
            ),
        )
    }

    @Test
    fun `U5a renombre con destino existente no sobrescribe nada`() {
        val staging = store.segmentStagingFile("rec_1", 0)
        val final = store.segmentFile("rec_1", 0)
        staging.writeText("nuevo")
        final.writeText("existente")
        assertFalse(store.renameStagingToFinal(staging, final))
        assertEquals("nuevo", staging.readText())
        assertEquals("existente", final.readText())
    }

    @Test
    fun `U5b duplicado staging verificado desplaza al final a cuarentena`() {
        val staging = store.segmentStagingFile("rec_1", 0)
        val final = store.segmentFile("rec_1", 0)
        staging.writeText("nuevo")
        final.writeText("viejo")
        val resolution = store.resolveDuplicateDestination(
            staging = staging,
            final = final,
            stagingVerified = true,
            finalVerified = false,
            nowMs = 1_792_000_000_000L,
        )
        assertEquals(DuplicateResolution.STAGING_KEPT, resolution)
        assertEquals("nuevo", final.readText())
        assertFalse(staging.exists())
        val quarantined = dir.listFiles()!!
            .filter { it.name.contains(".quarantine-") }
        assertEquals(1, quarantined.size)
        assertTrue(quarantined[0].name.contains("1792000000000"))
        assertEquals("viejo", quarantined[0].readText())
    }

    @Test
    fun `U5c duplicado final verificado manda el staging a cuarentena`() {
        val staging = store.segmentStagingFile("rec_1", 0)
        val final = store.segmentFile("rec_1", 0)
        staging.writeText("nuevo")
        final.writeText("existente")
        val resolution = store.resolveDuplicateDestination(
            staging = staging,
            final = final,
            stagingVerified = true,
            finalVerified = true,
            nowMs = 1_792_000_000_000L,
        )
        assertEquals(DuplicateResolution.FINAL_KEPT, resolution)
        assertEquals("existente", final.readText())
        assertFalse(staging.exists())
        assertEquals(1, dir.listFiles()!!.count {
            it.name.contains(".quarantine-")
        })
    }

    @Test
    fun `U5d duplicado sin ningun verificado no mueve nada`() {
        val staging = store.segmentStagingFile("rec_1", 0)
        val final = store.segmentFile("rec_1", 0)
        staging.writeText("nuevo")
        final.writeText("existente")
        val resolution = store.resolveDuplicateDestination(
            staging = staging,
            final = final,
            stagingVerified = false,
            finalVerified = false,
            nowMs = 1_792_000_000_000L,
        )
        assertEquals(DuplicateResolution.UNRESOLVED, resolution)
        assertEquals("nuevo", staging.readText())
        assertEquals("existente", final.readText())
        assertEquals(0, dir.listFiles()!!.count {
            it.name.contains(".quarantine-")
        })
    }

    @Test
    fun `lector del sidecar round-trip con los campos E5-E6 y fps`() {
        val id = "rec_20261119_134500_cd34"
        File(dir, "${id}_seg000.mp4").writeText("01234567890")
        store.writeSidecar(metadataWithVerifiedSegment(id))
        val read = store.readSidecar(id)
        assertTrue(read != null)
        assertEquals(id, read!!.recordingId)
        assertEquals(1, read.segments.size)
        val segment = read.segments[0]
        assertEquals("${id}_seg000.mp4", segment.fileName)
        assertEquals("ready", segment.status)
        assertEquals(3000L, segment.durationMs)
        assertEquals(11L, segment.sizeBytes)
        assertEquals(1_792_000_001_000L, segment.verifiedAtMs)
        assertEquals(1, segment.verifierVersion)
        assertEquals(30.0, segment.fpsMeasured)
        assertNull(segment.verificationReason)
    }

    @Test
    fun `lector del sidecar round-trip con los campos de fusion`() {
        // J1-b: el escritor serializa los campos de fusión y el
        // lector los lee de vuelta (sin lector, una reescritura del
        // sidecar los perdería).
        val id = "rec_20261119_134500_cd34"
        File(dir, "${id}_seg000.mp4").writeText("01234567890")
        store.writeSidecar(
            metadataWithVerifiedSegment(id).copy(
                mergedAtMs = 1_792_000_002_000L,
                mergedDurationMs = 11_987L,
                mergedSizeBytes = 3_740_000L,
                mergedStatus = "ready",
                mergedReason = null,
                mergedSegments = listOf(0, 1),
                skippedSegments = emptyList(),
                audioGapSegments = listOf(1),
            ),
        )
        val read = store.readSidecar(id)
        assertTrue(read != null)
        assertEquals(1_792_000_002_000L, read!!.mergedAtMs)
        assertEquals(11_987L, read.mergedDurationMs)
        assertEquals(3_740_000L, read.mergedSizeBytes)
        assertEquals("ready", read.mergedStatus)
        assertNull(read.mergedReason)
        assertEquals(listOf(0, 1), read.mergedSegments)
        assertEquals(emptyList<Int>(), read.skippedSegments)
        assertEquals(listOf(1), read.audioGapSegments)
    }

    @Test
    fun `lector del sidecar sin campos de fusion los deja en null o vacios`() {
        val id = "rec_20261119_134500_cd34"
        File(dir, "${id}_seg000.mp4").writeText("01234567890")
        store.writeSidecar(metadataWithVerifiedSegment(id))
        val read = store.readSidecar(id)
        assertTrue(read != null)
        assertNull(read!!.mergedAtMs)
        assertNull(read.mergedDurationMs)
        assertNull(read.mergedSizeBytes)
        assertNull(read.mergedStatus)
        assertNull(read.mergedReason)
        assertEquals(emptyList<Int>(), read.mergedSegments)
        assertEquals(emptyList<Int>(), read.skippedSegments)
        assertEquals(emptyList<Int>(), read.audioGapSegments)
    }

    @Test
    fun `lector del sidecar JSON corrupto se trata como sin sidecar`() {
        val id = "rec_20261119_134500_cd34"
        store.sidecarFile(id).writeText("{esto no es json")
        assertNull(store.readSidecar(id))
        store.sidecarFile(id).writeText("{\"recordingId\":")
        assertNull(store.readSidecar(id))
        store.sidecarFile(id).writeText("")
        assertNull(store.readSidecar(id))
        assertNull(store.readSidecar("rec_inexistente"))
    }

    @Test
    fun `lector del sidecar tolera campos desconocidos y ausentes`() {
        val id = "rec_20261119_134500_cd34"
        store.sidecarFile(id).writeText(
            """{"recordingId":"$id","campoNuevo":true,"width":1280,"segments":[{"index":0,"fileName":"${id}_seg000.mp4","startWallMs":5,"campoRaro":[1,2]}]}""",
        )
        val read = store.readSidecar(id)
        assertTrue(read != null)
        assertEquals(1280, read!!.width)
        assertEquals(0, read.height)
        assertEquals(1, read.segments.size)
        assertEquals(0, read.segments[0].index)
        assertEquals(5L, read.segments[0].startWallMs)
        assertNull(read.segments[0].status)
    }

    @Test
    fun `barrido V4a segunda sede solo elimina write-test antiguos`() {
        val old = File(dir, ".write-test-aaa.tmp")
        val recent = File(dir, ".write-test-bbb.tmp")
        val video = File(dir, "rec_1_seg000.mp4")
        val sidecar = File(dir, "rec_1.json")
        old.writeText("")
        recent.writeText("")
        video.writeText("v")
        sidecar.writeText("{}")
        old.setLastModified(1_792_000_000_000L - 61_000L)
        recent.setLastModified(1_792_000_000_000L - 10_000L)
        val removed = store.sweepWriteTestOrphans(1_792_000_000_000L)
        assertEquals(1, removed)
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(video.exists())
        assertTrue(sidecar.exists())
    }

    @Test
    fun `listados de inventario separan finales staging y sidecars`() {
        File(dir, "rec_1_seg000.mp4").writeText("a")
        File(dir, "rec_1_seg001.tmp.mp4").writeText("b")
        File(dir, "rec_2_seg000.mp4").writeText("c")
        File(dir, "rec_1.json").writeText("{}")
        File(dir, "rec_1.json.tmp").writeText("{}")
        assertEquals(
            listOf("rec_1_seg000.mp4", "rec_2_seg000.mp4"),
            store.listFinalSegmentFiles().map { it.name },
        )
        assertEquals(
            listOf("rec_1_seg001.tmp.mp4"),
            store.listStagingFiles().map { it.name },
        )
        assertEquals(
            listOf("rec_1.json"),
            store.listSidecarFiles().map { it.name },
        )
    }

    // --- 3.1.4, J1-a: nombres y duplicados del _final fusionado. ---

    @Test
    fun `J1a nombres del final fusionado y su staging`() {
        assertEquals(
            "rec_20261118_153045_ab12_final.mp4",
            store.finalFileName("rec_20261118_153045_ab12"),
        )
        assertEquals(
            File(dir, "rec_20261118_153045_ab12_final.mp4"),
            store.finalFile("rec_20261118_153045_ab12"),
        )
        assertEquals(
            "rec_20261118_153045_ab12_final.tmp.mp4",
            store.finalStagingFileName("rec_20261118_153045_ab12"),
        )
        assertEquals(
            File(dir, "rec_20261118_153045_ab12_final.tmp.mp4"),
            store.finalStagingFile("rec_20261118_153045_ab12"),
        )
    }

    @Test
    fun `J1a listSegmentFiles excluye el final y listFinalSegmentFiles lo incluye`() {
        // Documentado: la fusión enumera por listSegmentFiles(id) o
        // por el sidecar, nunca por listFinalSegmentFiles() sin
        // filtrar — ese inventario incluye el _final.mp4 (plan §2.2)
        // y la reconciliación lo trata como desconocido (nunca lo
        // toca). Un _final.mp4 en el inventario no es un segmento.
        File(dir, "rec_1_seg000.mp4").writeText("a")
        File(dir, "rec_1_seg001.mp4").writeText("b")
        File(dir, "rec_1_final.mp4").writeText("final")
        File(dir, "rec_1_final.tmp.mp4").writeText("tmp")
        assertEquals(
            listOf("rec_1_seg000.mp4", "rec_1_seg001.mp4"),
            store.listSegmentFiles("rec_1").map { it.name },
        )
        assertEquals(
            listOf(
                "rec_1_final.mp4",
                "rec_1_seg000.mp4",
                "rec_1_seg001.mp4",
            ),
            store.listFinalSegmentFiles().map { it.name },
        )
        // El tmp de fusión cae en el staging, como el de segmento.
        assertEquals(
            listOf("rec_1_final.tmp.mp4"),
            store.listStagingFiles().map { it.name },
        )
        // parseSegmentFileName no reconoce el final como segmento.
        assertNull(
            RecordingFileStore.parseSegmentFileName("rec_1_final.mp4"),
        )
    }

    @Test
    fun `J1a duplicado del final tmp verificado desplaza al final a cuarentena`() {
        val staging = store.finalStagingFile("rec_1")
        val final = store.finalFile("rec_1")
        staging.writeText("nuevo")
        final.writeText("viejo")
        val resolution = store.resolveDuplicateDestination(
            staging = staging,
            final = final,
            stagingVerified = true,
            finalVerified = false,
            nowMs = 1_792_000_000_000L,
        )
        assertEquals(DuplicateResolution.STAGING_KEPT, resolution)
        assertEquals("nuevo", final.readText())
        assertFalse(staging.exists())
        val quarantined = dir.listFiles()!!
            .filter { it.name.contains(".quarantine-") }
        assertEquals(1, quarantined.size)
        assertEquals("viejo", quarantined[0].readText())
    }

    @Test
    fun `J1a duplicado del final final verificado manda el tmp a cuarentena`() {
        val staging = store.finalStagingFile("rec_1")
        val final = store.finalFile("rec_1")
        staging.writeText("nuevo")
        final.writeText("existente")
        val resolution = store.resolveDuplicateDestination(
            staging = staging,
            final = final,
            stagingVerified = true,
            finalVerified = true,
            nowMs = 1_792_000_000_000L,
        )
        assertEquals(DuplicateResolution.FINAL_KEPT, resolution)
        assertEquals("existente", final.readText())
        assertFalse(staging.exists())
        assertEquals(1, dir.listFiles()!!.count {
            it.name.contains(".quarantine-")
        })
    }

    @Test
    fun `J1a duplicado del final sin ningun verificado no mueve nada`() {
        val staging = store.finalStagingFile("rec_1")
        val final = store.finalFile("rec_1")
        staging.writeText("nuevo")
        final.writeText("existente")
        val resolution = store.resolveDuplicateDestination(
            staging = staging,
            final = final,
            stagingVerified = false,
            finalVerified = false,
            nowMs = 1_792_000_000_000L,
        )
        assertEquals(DuplicateResolution.UNRESOLVED, resolution)
        assertEquals("nuevo", staging.readText())
        assertEquals("existente", final.readText())
        assertEquals(0, dir.listFiles()!!.count {
            it.name.contains(".quarantine-")
        })
    }

}
