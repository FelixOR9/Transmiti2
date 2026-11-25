package com.transmiti2.transmiti2.streaming.recording

import android.media.MediaCodec
import android.media.MediaFormat
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `SegmentMerger` (3.1.4, J1-a): fusión en producción con fakes JVM
 * (extractor/muxer/verificador). No toca MP4 reales ni Android: el
 * comportamiento del `MediaMuxerCompat` real ante PTS rebasados con
 * huecos de audio solo se conoce en el S24 Ultra (pruebas físicas
 * J1-d). Los números realistas son los del spike J1-0 (Grupo W).
 *
 * Regla del grupo: se implementa el plan §2.4 TAL COMO ESTÁ ESCRITO
 * (offset de audio sin avanzar en el segmento sin audio; rebase por
 * pista). La alternativa del spike NO se adopta aquí; los tests
 * fijan el comportamiento del plan, incluido su coste (el audio del
 * segmento posterior aterriza ~60 s adelantado y el fusionado es
 * PARTIAL por audio).
 */
class SegmentMergerTest {

    companion object {
        private const val RECORDING_ID = "rec_20261123_143132_7d5b"
        private const val VIDEO_STEP_US = 1_000_000L / 30 // 33 333 µs
        private const val AUDIO_STEP_US = 1024L * 1_000_000L / 48_000 // 21 333 µs
        private const val FLAG_KEYFRAME = MediaCodec.BUFFER_FLAG_KEY_FRAME
        private const val FLAG_CODEC_CONFIG = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        private const val FLAG_END_OF_STREAM = MediaCodec.BUFFER_FLAG_END_OF_STREAM
    }

    private lateinit var dir: File
    private lateinit var store: RecordingFileStore

    @Before
    fun setUp() {
        // `Transmiti2Logger` sin receptor llamaría a `android.util.Log`,
        // que lanza en las unitarias JVM puras (los abortos de fusión
        // registran con `e()`).
        Transmiti2Logger.sink = { _, _ -> }
        dir = File(
            System.getProperty("java.io.tmpdir"),
            "segment-merger-test-${System.nanoTime()}",
        )
        dir.mkdirs()
        store = RecordingFileStore(dir)
    }

    @After
    fun tearDown() {
        Transmiti2Logger.sink = null
        dir.deleteRecursively()
    }

    // --- Datos de prueba (espejo del spike J1-0). ---

    private fun videoFormat(
        profileIdc: Int = 66,
        csd0: ByteArray = byteArrayOf(0x01, 0x42, 0x00, 0x1e),
        csd1: ByteArray = byteArrayOf(0x02),
    ) = MergeVideoFormat(
        mime = "video/avc",
        width = 1280,
        height = 720,
        profileIdc = profileIdc,
        csd0 = csd0,
        csd1 = csd1,
    )

    private fun audioFormat(
        sampleRate: Int = 48_000,
        channels: Int = 1,
        csd0: ByteArray = byteArrayOf(0x11, 0x90.toByte()),
    ) = MergeAudioFormat(
        sampleRate = sampleRate,
        channels = channels,
        profile = 2,
        csd0 = csd0,
    )

    /** Muestras de video a 30 fps desde PTS 0; la primera es keyframe. */
    private fun videoSamples(durationMs: Long): List<ExtractedSample> {
        val samples = mutableListOf<ExtractedSample>()
        var ptsUs = 0L
        while (ptsUs < durationMs * 1_000L) {
            val flags = if (samples.isEmpty()) FLAG_KEYFRAME else 0
            samples += ExtractedSample(
                isVideo = true,
                ptsUs = ptsUs,
                flags = flags,
                data = ByteBuffer.allocate(4),
            )
            ptsUs += VIDEO_STEP_US
        }
        return samples
    }

    /** Tramas AAC desde `firstPtsUs`; una cada 21 333 µs (48 kHz). */
    private fun audioSamples(
        durationMs: Long,
        firstPtsUs: Long,
    ): List<ExtractedSample> {
        val samples = mutableListOf<ExtractedSample>()
        var ptsUs = firstPtsUs
        while (ptsUs < durationMs * 1_000L) {
            samples += ExtractedSample(
                isVideo = false,
                ptsUs = ptsUs,
                flags = 0,
                data = ByteBuffer.allocate(4),
            )
            ptsUs += AUDIO_STEP_US
        }
        return samples
    }

    /** Datos demuxados de un segmento, como los sirve el extractor falso. */
    private data class FakeTrackData(
        val videoFormat: MergeVideoFormat,
        val audioFormat: MergeAudioFormat?,
        val videoSamples: List<ExtractedSample>,
        val audioSamples: List<ExtractedSample>,
    )

    private fun segmentData(
        videoDurationMs: Long,
        audioDurationMs: Long?,
        avOffsetMs: Long = 0L,
        videoProfileIdc: Int = 66,
        audioSampleRate: Int = 48_000,
        videoCsd0: ByteArray = byteArrayOf(0x01, 0x42, 0x00, 0x1e),
        videoSamplesOverride: List<ExtractedSample>? = null,
    ): FakeTrackData = FakeTrackData(
        videoFormat = videoFormat(
            profileIdc = videoProfileIdc,
            csd0 = videoCsd0,
        ),
        audioFormat = if (audioDurationMs == null) {
            null
        } else {
            audioFormat(sampleRate = audioSampleRate)
        },
        videoSamples = videoSamplesOverride ?: videoSamples(videoDurationMs),
        audioSamples = if (audioDurationMs == null) {
            emptyList()
        } else {
            audioSamples(audioDurationMs, avOffsetMs * 1_000L)
        },
    )

    // --- Fakes. ---

    /** Extractor falso: sirve los datos declarados, en orden de archivo. */
    private class FakeSegmentExtractor(
        private val data: FakeTrackData,
    ) : SegmentExtractor {
        override val videoFormat: MergeVideoFormat get() = data.videoFormat
        override val videoMediaFormat: MediaFormat = MediaFormat()
        override val audioFormat: MergeAudioFormat? get() = data.audioFormat
        override val audioMediaFormat: MediaFormat? =
            if (data.audioFormat == null) null else MediaFormat()
        private val queue: ArrayDeque<ExtractedSample> = ArrayDeque(
            (data.videoSamples + data.audioSamples).sortedBy { it.ptsUs },
        )
        var closed = false
            private set

        override fun nextSample(): ExtractedSample? = queue.removeFirstOrNull()
        override fun close() {
            closed = true
        }
    }

    /** Muxer falso: registra llamadas y PTS; crea el tmp al arrancar. */
    private class FakeMergeMuxer(
        private val outputFile: File,
    ) : RecordingMuxer {
        override val name: String = "fake-merge"
        private val guard = MuxerThreadGuard()
        val calls = mutableListOf<String>()
        val writtenVideoPtsUs = mutableListOf<Long>()
        val writtenAudioPtsUs = mutableListOf<Long>()
        val writtenVideoFlags = mutableListOf<Int>()
        val writtenAudioFlags = mutableListOf<Int>()
        var orientationDegrees: Int? = null
            private set
        var videoTrackIndex = -1
            private set
        var audioTrackIndex = -1
            private set
        var started = false
            private set
        var stopped = false
            private set
        var released = false
            private set
        var failOnStop = false

        override fun addTrack(format: MediaFormat): Int {
            guard.check()
            check(!started) { "addTrack despues de start" }
            calls += "addTrack"
            return if (videoTrackIndex < 0) {
                videoTrackIndex = 0
                0
            } else {
                audioTrackIndex = 1
                1
            }
        }

        override fun start() {
            guard.check()
            check(!started) { "start dos veces" }
            started = true
            calls += "start"
            // El tmp existe de verdad para que el renombre funcione.
            outputFile.writeText("fake-merged")
        }

        override fun writeSampleData(
            trackIndex: Int,
            byteBuffer: ByteBuffer,
            bufferInfo: MediaCodec.BufferInfo,
        ) {
            guard.check()
            check(started) { "write antes de start" }
            check(!stopped) { "write despues de stop" }
            if (trackIndex == videoTrackIndex) {
                writtenVideoPtsUs += bufferInfo.presentationTimeUs
                writtenVideoFlags += bufferInfo.flags
            } else {
                writtenAudioPtsUs += bufferInfo.presentationTimeUs
                writtenAudioFlags += bufferInfo.flags
            }
        }

        override fun stop() {
            guard.check()
            check(started) { "stop antes de start" }
            if (failOnStop) throw IllegalStateException("stop simulado")
            stopped = true
            calls += "stop"
        }

        override fun release() {
            released = true
            calls += "release"
        }

        override fun setOrientationHint(degrees: Int) {
            orientationDegrees = degrees
            calls += "orientation"
        }
    }

    /** Verificador falso: resultados fijos por nombre de archivo. */
    private inner class FakeSegmentVerifier : SegmentVerifier {
        var segmentResults: Map<String, SegmentVerification> = emptyMap()
        var finalResult: SegmentVerification? = null
        var throwOnFiles: Set<String> = emptySet()
        val verifiedFiles = mutableListOf<String>()

        override suspend fun verify(
            file: File,
            closedCleanly: Boolean,
            expectedWidth: Int?,
            expectedHeight: Int?,
            expectedFps: Int?,
            expectedAudio: Boolean?,
            expectedSampleRate: Int?,
            expectedChannels: Int?,
        ): SegmentVerification {
            verifiedFiles += file.name
            if (file.name in throwOnFiles) {
                throw IllegalStateException("verificacion simulada")
            }
            if (file.name.contains("_final")) {
                return finalResult
                    ?: SegmentVerification(
                        SegmentStatus.CORRUPT,
                        goodChecks(),
                        "sin_final_fake",
                    )
            }
            return segmentResults[file.name]
                ?: SegmentVerification(
                    SegmentStatus.CORRUPT,
                    goodChecks(),
                    "sin_segmento_fake",
                )
        }
    }

    // --- Constructores de verificaciones y sidecar. ---

    private fun goodChecks(
        sampleCount: Int = 1800,
        audioSampleCount: Int = 0,
        durationMs: Long? = 60_000L,
        audioDurationMs: Long? = null,
        avOffsetMs: Long? = null,
        avDurationDiffMs: Long? = null,
    ) = VerificationChecks(
        extractorOpens = true,
        hasAvcTrack = true,
        width = 1280,
        height = 720,
        sampleCount = sampleCount,
        keyframeCount = 30,
        firstSampleKeyframe = true,
        ptsMonotonic = true,
        durationMs = durationMs,
        fpsMeasured = 30.0,
        ioPhaseMs = 100L,
        profileIdc = 66,
        decodeOk = true,
        decodedFrames = sampleCount,
        exoplayerReady = true,
        exoplayerDurationMs = durationMs,
        hasAudioTrack = audioSampleCount > 0,
        audioSampleCount = audioSampleCount,
        audioDurationMs = audioDurationMs,
        audioSampleRate = if (audioSampleCount > 0) 48_000 else null,
        audioChannels = if (audioSampleCount > 0) 1 else null,
        audioFirstPtsUs = if (audioSampleCount > 0) 120_000L else null,
        audioLastPtsUs = null,
        audioPtsMonotonic = true,
        audioDecodeOk = audioSampleCount > 0,
        audioDecodedFrames = audioSampleCount,
        avOffsetMs = avOffsetMs,
        avDurationDiffMs = avDurationDiffMs,
        referenceDurationMs = durationMs,
    )

    private fun readyVerification(checks: VerificationChecks) =
        SegmentVerification(SegmentStatus.READY, checks, null)

    private fun partialAudioVerification(
        checks: VerificationChecks,
        reason: String,
    ) = SegmentVerification(SegmentStatus.PARTIAL, checks, reason)

    private fun corruptVerification(reason: String) =
        SegmentVerification(SegmentStatus.CORRUPT, goodChecks(), reason)

    private fun createSegmentFile(index: Int, size: Int = 100): File {
        val file = store.segmentFile(RECORDING_ID, index)
        file.writeText("x".repeat(size))
        return file
    }

    private fun trustedEntry(
        file: File,
        index: Int,
        status: String,
        videoDurationMs: Long,
        audioDurationMs: Long?,
    ) = SegmentMetadata(
        index = index,
        fileName = file.name,
        firstEncoderPtsUs = 0L,
        startWallMs = 1_792_000_000_000L,
        status = status,
        durationMs = videoDurationMs,
        sizeBytes = file.length(),
        audioExpected = audioDurationMs != null,
        hasAudio = audioDurationMs != null,
        audioDurationMs = audioDurationMs,
        audioSampleCount = if (audioDurationMs == null) {
            null
        } else {
            audioSamples(audioDurationMs, 0L).size
        },
        audioStartOffsetMs = if (audioDurationMs == null) null else 120L,
        audioReason = null,
        verifiedAtMs = 1_792_000_001_000L,
        verifierVersion = SegmentVerifier.VERIFIER_VERSION,
        verificationReason = null,
        fpsMeasured = 30.0,
    )

    private fun writeSidecar(
        entries: List<SegmentMetadata>,
        rotationDegrees: Int = 90,
        audioEnabled: Boolean = true,
    ) {
        store.writeSidecar(
            RecordingMetadata(
                recordingId = RECORDING_ID,
                matchId = null,
                width = 1280,
                height = 720,
                fps = 30,
                bitrateBps = 2_500_000,
                rotationDegrees = rotationDegrees,
                encoderName = "c2.qti.avc.encoder",
                profile = 1,
                bitrateMode = 2,
                segments = entries,
                audioEnabled = audioEnabled,
                audioSampleRate = if (audioEnabled) 48_000 else null,
                audioChannels = if (audioEnabled) 1 else null,
                audioEncoderName = null,
            ),
        )
    }

    /**
     * Escenario de N segmentos con sidecar confiable. Devuelve el
     * merger listo y la lista de muxers creados (normalmente uno).
     * `finalResult` es la verificación que el falso devuelve para
     * cualquier archivo `_final` (tmp o final existente): debe
     * construirse con los contadores que el merger escribirá.
     */
    private fun buildMerger(
        trackData: Map<Int, FakeTrackData>,
        verifier: FakeSegmentVerifier,
        freeBytes: Long? = Long.MAX_VALUE,
        failMuxerOnStop: Boolean = false,
    ): Pair<SegmentMerger, MutableList<FakeMergeMuxer>> {
        val muxers = mutableListOf<FakeMergeMuxer>()
        val merger = SegmentMerger(
            fileStore = store,
            verifier = verifier,
            extractorFactory = { file ->
                val index = RecordingFileStore.parseSegmentFileName(file.name)
                    ?.second ?: error("nombre no segmento: ${file.name}")
                FakeSegmentExtractor(trackData.getValue(index))
            },
            muxerFactory = { file ->
                FakeMergeMuxer(file).also {
                    it.failOnStop = failMuxerOnStop
                    muxers += it
                }
            },
            freeSpaceBytes = { freeBytes },
            ioDispatcher = Dispatchers.Unconfined,
        )
        return merger to muxers
    }

    private fun merge(merger: SegmentMerger): MergeResult =
        runBlocking { merger.merge(RECORDING_ID) }

    private fun assertStrictlyIncreasing(ptsUs: List<Long>) {
        for (i in 1 until ptsUs.size) {
            assertTrue(
                "PTS no monotono en $i: ${ptsUs[i - 1]} -> ${ptsUs[i]}",
                ptsUs[i] > ptsUs[i - 1],
            )
        }
    }

    /**
     * Verificación del tmp para un escenario de 2 segmentos de 60 s:
     * duración-suma y muestras-suma exactas, READY.
     */
    private fun tmpReady2x60(
        videoSamplesPerSegment: Int,
        audioSamplesSeg0: Int,
        audioSamplesSeg1: Int,
        videoDurationMs: Long = 120_000L,
        audioDurationMs: Long = 119_870L,
        avOffsetMs: Long = 120L,
        avDurationDiffMs: Long = 130L,
    ) = readyVerification(
        goodChecks(
            sampleCount = videoSamplesPerSegment * 2,
            audioSampleCount = audioSamplesSeg0 + audioSamplesSeg1,
            durationMs = videoDurationMs,
            audioDurationMs = audioDurationMs,
            avOffsetMs = avOffsetMs,
            avDurationDiffMs = avDurationDiffMs,
        ),
    )

    // --- Tests: rebase y orden. ---

    @Test
    fun `rebase por pista con duraciones distintas por segmento`() {
        // Plan §2.4 paso 5: video y audio llevan offsets separados.
        // seg0: video 60 000 ms / audio 59 920 ms (avOffset 120 ms).
        // seg1: video 60 000 ms / audio 59 950 ms (avOffset 90 ms).
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val videoPerSegment = videoSamples(60_000L).size
        val audioSeg0 = audioSamples(59_920L, 120_000L).size
        val audioSeg1 = audioSamples(59_950L, 90_000L).size
        val verifier = FakeSegmentVerifier()
        verifier.finalResult = tmpReady2x60(
            videoPerSegment, audioSeg0, audioSeg1,
        )
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)

        assertTrue(result.reason ?: "fallo", result.success)
        assertNull(result.reason)
        assertEquals(120L, result.mergedAvOffsetMs)
        assertEquals(130L, result.mergedAvDurationDiffMs)
        assertEquals(120_000L, result.totalVideoDurationMs)
        assertEquals(119_870L, result.totalAudioDurationMs)
        assertEquals(120_000L, result.mergedDurationMs)
        assertEquals(listOf(0, 1), result.segmentIndices)
        assertTrue(result.audioGapSegments.isEmpty())
        assertTrue(result.partialSegments.isEmpty())
        assertFalse(result.alreadyMerged)
        assertTrue(store.finalFile(RECORDING_ID).exists())
        assertFalse(store.finalStagingFile(RECORDING_ID).exists())

        val muxer = muxers.single()
        assertEquals(
            listOf("orientation", "addTrack", "addTrack", "start", "stop", "release"),
            muxer.calls,
        )
        assertEquals(90, muxer.orientationDegrees)
        assertTrue(muxer.stopped)
        assertTrue(muxer.released)

        // El video del seg1 empieza en 60 000 000 µs (offset video).
        assertEquals(60_000_000L, muxer.writtenVideoPtsUs[videoPerSegment])
        // El audio del seg1 empieza en 59 920 000 + 90 000 µs
        // (offset audio, distinto del de video).
        assertEquals(60_010_000L, muxer.writtenAudioPtsUs[audioSeg0])

        // §2.5.3: nº de muestras = suma por pista.
        assertEquals(videoPerSegment * 2, result.videoSampleCount)
        assertEquals(audioSeg0 + audioSeg1, result.audioSampleCount)

        // Monotonicidad de los PTS rebasados, por pista.
        assertStrictlyIncreasing(muxer.writtenVideoPtsUs)
        assertStrictlyIncreasing(muxer.writtenAudioPtsUs)

        // La deriva del seg1 es −80 ms = −(video−audio del seg0).
        assertEquals(0L, result.avOffsetDriftMsBySegment.getValue(0))
        assertEquals(-80L, result.avOffsetDriftMsBySegment.getValue(1))
    }

    @Test
    fun `merge emite progreso por fases para la notificacion del FGS`() {
        // J1-b: VALIDATING → COPYING 0/2 → 1/2 → 2/2 → VERIFYING 2/2.
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val videoPerSegment = videoSamples(60_000L).size
        val audioSeg0 = audioSamples(59_920L, 120_000L).size
        val audioSeg1 = audioSamples(59_950L, 90_000L).size
        val verifier = FakeSegmentVerifier()
        verifier.finalResult = tmpReady2x60(
            videoPerSegment, audioSeg0, audioSeg1,
        )
        val (merger, _) = buildMerger(data, verifier)
        val seen = mutableListOf<MergeProgress>()
        val result = runBlocking {
            merger.merge(RECORDING_ID) { progress -> seen += progress }
        }

        assertTrue(result.success)
        assertEquals(
            listOf(
                MergeProgress(MergeStage.VALIDATING, 0, 0),
                MergeProgress(MergeStage.COPYING, 0, 2),
                MergeProgress(MergeStage.COPYING, 1, 2),
                MergeProgress(MergeStage.COPYING, 2, 2),
                MergeProgress(MergeStage.VERIFYING, 2, 2),
            ),
            seen,
        )
    }

    @Test
    fun `entrada con sidecar desordenado se fusiona en orden canonico`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        // El sidecar lista los segmentos al revés: el merger ordena
        // por índice (el inventario ya viene ordenado por nombre).
        writeSidecar(
            listOf(
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val verifier = FakeSegmentVerifier()
        verifier.finalResult = tmpReady2x60(
            videoSamples(60_000L).size,
            audioSamples(59_920L, 120_000L).size,
            audioSamples(59_950L, 90_000L).size,
        )
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)
        assertTrue(result.success)
        assertEquals(0L, muxers.single().writtenVideoPtsUs.first())
        assertEquals(
            60_000_000L,
            muxers.single().writtenVideoPtsUs[videoSamples(60_000L).size],
        )
    }

    @Test
    fun `offset AV intra-segmento se conserva en el seg0 y deriva despues`() {
        // Datos reales del Grupo W: video 24 967 ms / audio 24 981 ms.
        val seg0File = createSegmentFile(0, size = 50)
        val seg1File = createSegmentFile(1, size = 50)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 24_967L, 24_981L),
                trustedEntry(seg1File, 1, "ready", 24_967L, 24_981L),
            ),
        )
        val data = mapOf(
            0 to segmentData(24_967L, 24_981L, avOffsetMs = 103L),
            1 to segmentData(24_967L, 24_981L, avOffsetMs = 102L),
        )
        val videoPerSegment = videoSamples(24_967L).size
        val audioPerSegment = audioSamples(24_981L, 103_000L).size
        val verifier = FakeSegmentVerifier()
        verifier.finalResult = readyVerification(
            goodChecks(
                sampleCount = videoPerSegment * 2,
                audioSampleCount = audioPerSegment * 2,
                durationMs = 49_934L,
                audioDurationMs = 49_962L,
                avOffsetMs = 103L,
                avDurationDiffMs = -28L,
            ),
        )
        val (merger, _) = buildMerger(data, verifier)
        val result = merge(merger)
        assertTrue(result.success)
        // En el seg0 el offset se conserva exacto: 103 ms.
        assertEquals(103L, result.mergedAvOffsetMs)
        assertEquals(0L, result.avOffsetDriftMsBySegment.getValue(0))
        // En el seg1 deriva +14 ms (audio 14 ms más largo que video
        // en el seg0): queda en 116 ms, dentro de ±500 ms.
        assertEquals(14L, result.avOffsetDriftMsBySegment.getValue(1))
        assertEquals(-28L, result.mergedAvDurationDiffMs)
    }

    // --- Tests: rechazo de entrada. ---

    @Test
    fun `hueco en los indices aborta sin fusionar`() {
        createSegmentFile(0)
        createSegmentFile(2)
        writeSidecar(
            listOf(
                trustedEntry(store.segmentFile(RECORDING_ID, 0), 0, "ready", 60_000L, 59_920L),
                trustedEntry(store.segmentFile(RECORDING_ID, 2), 2, "ready", 60_000L, 59_950L),
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            2 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val verifier = FakeSegmentVerifier()
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)

        assertFalse(result.success)
        assertEquals("merge_skipped_gap", result.reason)
        assertEquals(listOf(1), result.affectedIndices)
        // No se llegó ni a abrir el muxer ni a crear tmp/final.
        assertTrue(muxers.isEmpty())
        assertFalse(store.finalStagingFile(RECORDING_ID).exists())
        assertFalse(store.finalFile(RECORDING_ID).exists())
        assertTrue(store.segmentFile(RECORDING_ID, 0).exists())
        assertTrue(store.segmentFile(RECORDING_ID, 2).exists())
    }

    @Test
    fun `sin segmentos aborta`() {
        writeSidecar(emptyList())
        val verifier = FakeSegmentVerifier()
        val (merger, muxers) = buildMerger(emptyMap(), verifier)
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("merge_sin_segmentos", result.reason)
        assertTrue(muxers.isEmpty())
    }

    @Test
    fun `nombre no canonico aborta`() {
        val bad = File(dir, "${RECORDING_ID}_segXYZ.mp4")
        bad.writeText("x")
        createSegmentFile(0)
        val verifier = FakeSegmentVerifier()
        val (merger, muxers) = buildMerger(
            mapOf(0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L)),
            verifier,
        )
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("merge_nombre_no_canonico", result.reason)
        assertTrue(muxers.isEmpty())
        assertTrue(bad.exists())
    }

    @Test
    fun `segmento corrupto en el sidecar aborta con motivo exacto`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                // El corrupto no es "confiable" (E1), pero su estado
                // manda: no se fusiona nada.
                SegmentMetadata(
                    index = 1,
                    fileName = seg1File.name,
                    firstEncoderPtsUs = 0L,
                    startWallMs = 1_792_000_000_000L,
                    status = "corrupt",
                    durationMs = 60_000L,
                    sizeBytes = seg1File.length(),
                    verifiedAtMs = 1_792_000_001_000L,
                    verifierVersion = SegmentVerifier.VERIFIER_VERSION,
                ),
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val verifier = FakeSegmentVerifier()
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("merge_skipped_corrupt", result.reason)
        assertEquals(listOf(1), result.affectedIndices)
        assertTrue(muxers.isEmpty())
        assertFalse(store.finalFile(RECORDING_ID).exists())
    }

    @Test
    fun `verificacion fresca corrupta aborta con motivo exacto`() {
        // Sin sidecar confiable: la verificación fresca decide.
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                SegmentMetadata(
                    index = 0,
                    fileName = seg0File.name,
                    firstEncoderPtsUs = 0L,
                    startWallMs = 1_792_000_000_000L,
                    status = "ready",
                    durationMs = 60_000L,
                    sizeBytes = seg0File.length(),
                    // Sin evidencia de verificación: no confiable.
                    verifiedAtMs = null,
                    verifierVersion = null,
                ),
                SegmentMetadata(
                    index = 1,
                    fileName = seg1File.name,
                    firstEncoderPtsUs = 0L,
                    startWallMs = 1_792_000_000_000L,
                    status = "ready",
                    durationMs = 60_000L,
                    sizeBytes = seg1File.length(),
                    verifiedAtMs = null,
                    verifierVersion = null,
                ),
            ),
        )
        val verifier = FakeSegmentVerifier()
        verifier.segmentResults = mapOf(
            seg0File.name to readyVerification(
                goodChecks(
                    sampleCount = 1800,
                    audioSampleCount = 2800,
                    durationMs = 60_000L,
                    audioDurationMs = 59_920L,
                ),
            ),
            seg1File.name to corruptVerification("primer_sample_no_keyframe"),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("merge_skipped_corrupt", result.reason)
        assertEquals("primer_sample_no_keyframe", result.detailReason)
        assertEquals(listOf(1), result.affectedIndices)
        assertTrue(muxers.isEmpty())
    }

    @Test
    fun `segmento no verificable aborta como pendiente sin fusionar`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        // Sin sidecar: todo pasa por verificación fresca.
        val verifier = FakeSegmentVerifier()
        verifier.segmentResults = mapOf(
            seg0File.name to readyVerification(
                goodChecks(
                    sampleCount = 1800,
                    audioSampleCount = 2800,
                    durationMs = 60_000L,
                    audioDurationMs = 59_920L,
                ),
            ),
        )
        verifier.throwOnFiles = setOf(seg1File.name)
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("merge_skipped_pending", result.reason)
        assertEquals(listOf(1), result.affectedIndices)
        assertTrue(muxers.isEmpty())
    }

    @Test
    fun `formato distinto entre segmentos aborta`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val verifier = FakeSegmentVerifier()
        verifier.finalResult = tmpReady2x60(
            videoSamples(60_000L).size,
            audioSamples(59_920L, 120_000L).size,
            audioSamples(59_950L, 90_000L).size,
        )
        // Perfil de video distinto (Baseline 66 vs Main 77).
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(
                60_000L, 59_950L, avOffsetMs = 90L,
                videoProfileIdc = 77,
            ),
        )
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("merge_formato_distinto", result.reason)
        // La pasada A no crea muxer: no hay tmp ni final.
        assertTrue(muxers.isEmpty())
        assertFalse(store.finalStagingFile(RECORDING_ID).exists())
        assertFalse(store.finalFile(RECORDING_ID).exists())
    }

    @Test
    fun `frecuencia de audio distinta aborta`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val verifier = FakeSegmentVerifier()
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(
                60_000L, 59_950L, avOffsetMs = 90L,
                audioSampleRate = 44_100,
            ),
        )
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("merge_formato_distinto", result.reason)
        assertTrue(muxers.isEmpty())
    }

    // --- Tests: espacio, PARTIAL, hueco de audio, video-only. ---

    @Test
    fun `espacio insuficiente aborta sin tocar nada`() {
        val seg0File = createSegmentFile(0, size = 100)
        val seg1File = createSegmentFile(1, size = 100)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val verifier = FakeSegmentVerifier()
        // Libre < suma (200 bytes) + 200 MB.
        val (merger, muxers) = buildMerger(data, verifier, freeBytes = 1_000L)
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("storage_full", result.reason)
        assertTrue(muxers.isEmpty())
        assertFalse(store.finalStagingFile(RECORDING_ID).exists())
        assertFalse(store.finalFile(RECORDING_ID).exists())
    }

    @Test
    fun `espacio no medible aborta sin tocar nada`() {
        val seg0File = createSegmentFile(0, size = 100)
        writeSidecar(
            listOf(trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L)),
        )
        val data = mapOf(0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L))
        val verifier = FakeSegmentVerifier()
        val (merger, muxers) = buildMerger(data, verifier, freeBytes = null)
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("storage_check_unavailable", result.reason)
        assertTrue(muxers.isEmpty())
    }

    @Test
    fun `segmento PARTIAL si se fusiona`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "partial", 60_000L, 59_950L),
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val verifier = FakeSegmentVerifier()
        verifier.finalResult = tmpReady2x60(
            videoSamples(60_000L).size,
            audioSamples(59_920L, 120_000L).size,
            audioSamples(59_950L, 90_000L).size,
        )
        val (merger, _) = buildMerger(data, verifier)
        val result = merge(merger)
        assertTrue(result.success)
        assertEquals(listOf(1), result.partialSegments)
        assertTrue(store.finalFile(RECORDING_ID).exists())
    }

    @Test
    fun `segmento sin audio deja hueco registrado y el audio no avanza`() {
        // Plan §2.4 paso 5 tal como está escrito: el offset de audio
        // NO avanza en el segmento sin audio. Consecuencia: NO queda
        // un hueco de PTS — el audio del seg2 aterriza ~60 s
        // adelantado respecto a su video y el fusionado es PARTIAL
        // por audio (duracion_audio_no_coincide), no READY.
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        val seg2File = createSegmentFile(2)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                // Segmento sin audio: audioExpected/hasAudio en falso.
                trustedEntry(seg1File, 1, "ready", 60_000L, null),
                trustedEntry(seg2File, 2, "ready", 60_000L, 59_900L),
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, null),
            2 to segmentData(60_000L, 59_900L, avOffsetMs = 100L),
        )
        val videoPerSegment = videoSamples(60_000L).size
        val audioSeg0 = audioSamples(59_920L, 120_000L).size
        val audioSeg2 = audioSamples(59_900L, 100_000L).size
        val verifier = FakeSegmentVerifier()
        // El fusionado mide video 180 000 ms y audio 119 820 ms:
        // PARTIAL por duracion_audio_no_coincide (60 180 ms > 500).
        verifier.finalResult = partialAudioVerification(
            goodChecks(
                sampleCount = videoPerSegment * 3,
                audioSampleCount = audioSeg0 + audioSeg2,
                durationMs = 180_000L,
                audioDurationMs = 119_820L,
                avOffsetMs = 120L,
                avDurationDiffMs = 60_180L,
            ),
            "duracion_audio_no_coincide",
        )
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)

        assertTrue(result.reason ?: "fallo", result.success)
        assertEquals(SegmentStatus.PARTIAL, result.status)
        assertEquals(listOf(1), result.audioGapSegments)
        assertEquals(180_000L, result.totalVideoDurationMs)
        assertEquals(119_820L, result.totalAudioDurationMs)
        assertEquals(60_180L, result.mergedAvDurationDiffMs)

        val muxer = muxers.single()
        // El audio del seg2 empieza en 59 920 000 + 100 000 µs: el
        // offset de audio no avanzó por el seg1.
        assertEquals(60_020_000L, muxer.writtenAudioPtsUs[audioSeg0])
        // El video del seg2 sí empieza en 120 000 000 µs.
        assertEquals(120_000_000L, muxer.writtenVideoPtsUs[videoPerSegment * 2])
        // La deriva del seg2 es −60 080 ms.
        assertEquals(-60_080L, result.avOffsetDriftMsBySegment.getValue(2))
        assertEquals(videoPerSegment * 3, result.videoSampleCount)
        assertEquals(audioSeg0 + audioSeg2, result.audioSampleCount)
    }

    @Test
    fun `video-only sin audio en ningun segmento se fusiona`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, null),
                trustedEntry(seg1File, 1, "ready", 60_000L, null),
            ),
            audioEnabled = false,
        )
        val data = mapOf(
            0 to segmentData(60_000L, null),
            1 to segmentData(60_000L, null),
        )
        val videoPerSegment = videoSamples(60_000L).size
        val verifier = FakeSegmentVerifier()
        verifier.finalResult = readyVerification(
            goodChecks(
                sampleCount = videoPerSegment * 2,
                audioSampleCount = 0,
                durationMs = 120_000L,
                audioDurationMs = null,
            ),
        )
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)
        assertTrue(result.success)
        assertTrue(muxers.single().writtenAudioPtsUs.isEmpty())
        assertEquals(0, result.audioSampleCount)
        assertNull(result.mergedAvOffsetMs)
        assertNull(result.mergedAvDurationDiffMs)
        assertNull(result.totalAudioDurationMs)
        assertEquals(listOf(0, 1), result.audioGapSegments)
        // Sin audio no hay segunda pista: un único addTrack.
        assertEquals(
            listOf("orientation", "addTrack", "start", "stop", "release"),
            muxers.single().calls,
        )
    }

    @Test
    fun `duracion incoherente con las muestras rompe la monotonicidad`() {
        // seg0 declara 60 000 ms de video pero sus muestras llegan a
        // 60 099 399 µs: el primer PTS rebasado del seg1 (60 000 000)
        // no es mayor que el último escrito → aborto, tmp descartado.
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val data = mapOf(
            0 to segmentData(
                60_000L, 59_920L, avOffsetMs = 120L,
                videoSamplesOverride = videoSamples(60_100L),
            ),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val verifier = FakeSegmentVerifier()
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("merge_pts_no_monotonos", result.reason)
        // Aborto a mitad: sin stop(), con release() (tmp descartado).
        val muxer = muxers.single()
        assertFalse(muxer.stopped)
        assertTrue(muxer.released)
        assertFalse(store.finalStagingFile(RECORDING_ID).exists())
        assertFalse(store.finalFile(RECORDING_ID).exists())
    }

    @Test
    fun `muestras codec-config no se escriben y EOS solo va al final`() {
        val base0 = videoSamples(60_000L).mapIndexed { i, sample ->
            if (i == 5) {
                sample.copy(flags = sample.flags or FLAG_END_OF_STREAM)
            } else {
                sample
            }
        }
        val config = ExtractedSample(
            isVideo = true,
            ptsUs = 0L,
            flags = FLAG_CODEC_CONFIG,
            data = ByteBuffer.allocate(4),
        )
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val base1 = videoSamples(60_000L).mapIndexed { i, sample ->
            if (i == videoSamples(60_000L).size - 1) {
                sample.copy(flags = sample.flags or FLAG_END_OF_STREAM)
            } else {
                sample
            }
        }
        val data = mapOf(
            0 to segmentData(
                60_000L, 59_920L, avOffsetMs = 120L,
                videoSamplesOverride = listOf(config) + base0,
            ),
            1 to segmentData(
                60_000L, 59_950L, avOffsetMs = 90L,
                videoSamplesOverride = base1,
            ),
        )
        val verifier = FakeSegmentVerifier()
        verifier.finalResult = tmpReady2x60(
            videoSamples(60_000L).size,
            audioSamples(59_920L, 120_000L).size,
            audioSamples(59_950L, 90_000L).size,
        )
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)
        assertTrue(result.success)
        val muxer = muxers.single()
        // La muestra de config no se escribió.
        assertEquals(videoSamples(60_000L).size * 2, result.videoSampleCount)
        assertTrue(
            muxer.writtenVideoFlags.none {
                (it and FLAG_CODEC_CONFIG) != 0
            },
        )
        // EOS solo en la última muestra escrita.
        val eosWritten = muxer.writtenVideoFlags.filter {
            (it and FLAG_END_OF_STREAM) != 0
        }
        assertEquals(1, eosWritten.size)
        assertEquals(muxer.writtenVideoFlags.last(), eosWritten.single())
        // El EOS intermedio del seg0 se quitó.
        assertEquals(0, muxer.writtenVideoFlags[5] and FLAG_END_OF_STREAM)
    }

    // --- Tests: tmp, renombre, final existente, idempotencia. ---

    @Test
    fun `tmp huerfano se reutiliza y el renombre es atomico`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        // Tmp huérfano de un intento anterior.
        store.finalStagingFile(RECORDING_ID).writeText("viejo")
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val verifier = FakeSegmentVerifier()
        verifier.finalResult = tmpReady2x60(
            videoSamples(60_000L).size,
            audioSamples(59_920L, 120_000L).size,
            audioSamples(59_950L, 90_000L).size,
        )
        val (merger, _) = buildMerger(data, verifier)
        val result = merge(merger)
        assertTrue(result.success)
        assertFalse(store.finalStagingFile(RECORDING_ID).exists())
        val final = store.finalFile(RECORDING_ID)
        assertTrue(final.exists())
        assertEquals("fake-merged", final.readText())
    }

    @Test
    fun `final existente verificado no re-fusiona`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val final = store.finalFile(RECORDING_ID)
        final.writeText("existente")
        val verifier = FakeSegmentVerifier()
        verifier.finalResult = readyVerification(
            goodChecks(
                sampleCount = 3600,
                audioSampleCount = 5600,
                durationMs = 120_000L,
                audioDurationMs = 119_870L,
                avOffsetMs = 120L,
                avDurationDiffMs = 130L,
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)
        assertTrue(result.success)
        assertTrue(result.alreadyMerged)
        assertEquals(SegmentStatus.READY, result.status)
        // No se abrió el muxer: no hay re-fusión.
        assertTrue(muxers.isEmpty())
        assertEquals("existente", final.readText())
        assertFalse(store.finalStagingFile(RECORDING_ID).exists())
    }

    @Test
    fun `final existente no verificado pierde contra el tmp verificado`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val final = store.finalFile(RECORDING_ID)
        final.writeText("viejo")
        val videoPerSegment = videoSamples(60_000L).size
        val audioSeg0 = audioSamples(59_920L, 120_000L).size
        val audioSeg1 = audioSamples(59_950L, 90_000L).size
        // La primera verificación (final existente) dice CORRUPT; la
        // segunda (tmp) dice READY con los contadores de la fusión.
        val verifier = object : SegmentVerifier {
            override suspend fun verify(
                file: File,
                closedCleanly: Boolean,
                expectedWidth: Int?,
                expectedHeight: Int?,
                expectedFps: Int?,
                expectedAudio: Boolean?,
                expectedSampleRate: Int?,
                expectedChannels: Int?,
            ): SegmentVerification {
                return if (file.name.endsWith("_final.tmp.mp4")) {
                    tmpReady2x60(videoPerSegment, audioSeg0, audioSeg1)
                } else {
                    corruptVerification("duracion_no_valida")
                }
            }
        }
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val merger = SegmentMerger(
            fileStore = store,
            verifier = verifier,
            extractorFactory = { file ->
                val index = RecordingFileStore.parseSegmentFileName(file.name)
                    ?.second ?: error("nombre no segmento: ${file.name}")
                FakeSegmentExtractor(data.getValue(index))
            },
            muxerFactory = { file -> FakeMergeMuxer(file) },
            freeSpaceBytes = { Long.MAX_VALUE },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val result = runBlocking { merger.merge(RECORDING_ID) }
        assertTrue(result.reason ?: "fallo", result.success)
        assertFalse(result.alreadyMerged)
        assertEquals(DuplicateResolution.STAGING_KEPT, result.duplicateResolution)
        assertEquals("fake-merged", final.readText())
        val quarantined = dir.listFiles()!!
            .filter { it.name.contains(".quarantine-") }
        assertEquals(1, quarantined.size)
        assertEquals("viejo", quarantined[0].readText())
    }

    @Test
    fun `final existente verificado gana y el tmp va a cuarentena`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val final = store.finalFile(RECORDING_ID)
        final.writeText("existente")
        val videoPerSegment = videoSamples(60_000L).size
        val audioSeg0 = audioSamples(59_920L, 120_000L).size
        val audioSeg1 = audioSamples(59_950L, 90_000L).size
        // La primera verificación (final existente) dice CORRUPT para
        // forzar la fusión; la del tmp dice READY; la del final en el
        // renombre dice READY (el archivo "cambió" entre tanto: el
        // duplicado lo gana el final).
        val calls = mutableListOf<String>()
        val verifier = object : SegmentVerifier {
            override suspend fun verify(
                file: File,
                closedCleanly: Boolean,
                expectedWidth: Int?,
                expectedHeight: Int?,
                expectedFps: Int?,
                expectedAudio: Boolean?,
                expectedSampleRate: Int?,
                expectedChannels: Int?,
            ): SegmentVerification {
                calls += file.name
                return when {
                    file.name.endsWith("_final.tmp.mp4") ->
                        tmpReady2x60(videoPerSegment, audioSeg0, audioSeg1)
                    calls.count { it == file.name } == 1 ->
                        corruptVerification("duracion_no_valida")
                    else -> readyVerification(
                        goodChecks(
                            sampleCount = videoPerSegment * 2,
                            audioSampleCount = audioSeg0 + audioSeg1,
                            durationMs = 120_000L,
                            audioDurationMs = 119_870L,
                            avOffsetMs = 120L,
                            avDurationDiffMs = 130L,
                        ),
                    )
                }
            }
        }
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val merger = SegmentMerger(
            fileStore = store,
            verifier = verifier,
            extractorFactory = { file ->
                val index = RecordingFileStore.parseSegmentFileName(file.name)
                    ?.second ?: error("nombre no segmento: ${file.name}")
                FakeSegmentExtractor(data.getValue(index))
            },
            muxerFactory = { file -> FakeMergeMuxer(file) },
            freeSpaceBytes = { Long.MAX_VALUE },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val result = runBlocking { merger.merge(RECORDING_ID) }
        assertTrue(result.reason ?: "fallo", result.success)
        assertTrue(result.alreadyMerged)
        assertEquals(DuplicateResolution.FINAL_KEPT, result.duplicateResolution)
        assertEquals("existente", final.readText())
        val quarantined = dir.listFiles()!!
            .filter { it.name.contains(".quarantine-") }
        assertEquals(1, quarantined.size)
        assertEquals("fake-merged", quarantined[0].readText())
    }

    // --- Tests: fallos del muxer y de la verificación del tmp. ---

    @Test
    fun `fallo de stop del muxer descarta el tmp`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val verifier = FakeSegmentVerifier()
        val (merger, muxers) = buildMerger(
            data, verifier, failMuxerOnStop = true,
        )
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("muxer_stop_failed", result.reason)
        assertFalse(muxers.single().stopped)
        assertTrue(muxers.single().released)
        assertFalse(store.finalStagingFile(RECORDING_ID).exists())
        assertFalse(store.finalFile(RECORDING_ID).exists())
        assertTrue(store.segmentFile(RECORDING_ID, 0).exists())
    }

    @Test
    fun `tmp que no verifica se descarta sin final`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val verifier = FakeSegmentVerifier()
        verifier.finalResult = corruptVerification("duracion_no_coincide")
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("merge_verify_failed", result.reason)
        assertEquals("duracion_no_coincide", result.detailReason)
        assertTrue(muxers.single().stopped)
        assertFalse(store.finalStagingFile(RECORDING_ID).exists())
        assertFalse(store.finalFile(RECORDING_ID).exists())
    }

    @Test
    fun `tmp PARTIAL por timeout no es aceptable`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val verifier = FakeSegmentVerifier()
        // PARTIAL con motivo de timeout (classifyIncomplete), no de
        // audio: la verificación no terminó de establecer el archivo.
        verifier.finalResult = partialAudioVerification(
            goodChecks(
                sampleCount = videoSamples(60_000L).size * 2,
                audioSampleCount =
                    audioSamples(59_920L, 120_000L).size +
                        audioSamples(59_950L, 90_000L).size,
                durationMs = 120_000L,
                audioDurationMs = 119_870L,
            ),
            "verification_timeout",
        )
        val (merger, _) = buildMerger(data, verifier)
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("merge_verify_failed", result.reason)
        assertFalse(store.finalFile(RECORDING_ID).exists())
    }

    @Test
    fun `duracion del tmp fuera de tolerancia falla`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val verifier = FakeSegmentVerifier()
        // Tolerancia con N = 2: 100 + 33 × 2 = 166 ms. El tmp mide
        // 120 500 ms de video frente a 120 000 ms de suma: fuera.
        verifier.finalResult = readyVerification(
            goodChecks(
                sampleCount = videoSamples(60_000L).size * 2,
                audioSampleCount =
                    audioSamples(59_920L, 120_000L).size +
                        audioSamples(59_950L, 90_000L).size,
                durationMs = 120_500L,
                audioDurationMs = 119_870L,
            ),
        )
        val (merger, _) = buildMerger(data, verifier)
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("merge_verify_failed", result.reason)
        assertEquals("duracion_video_no_coincide", result.detailReason)
        assertFalse(store.finalFile(RECORDING_ID).exists())
    }

    @Test
    fun `conteo de muestras del tmp distinto falla`() {
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        writeSidecar(
            listOf(
                trustedEntry(seg0File, 0, "ready", 60_000L, 59_920L),
                trustedEntry(seg1File, 1, "ready", 60_000L, 59_950L),
            ),
        )
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val verifier = FakeSegmentVerifier()
        verifier.finalResult = readyVerification(
            goodChecks(
                // Una muestra de video menos que las escritas.
                sampleCount = videoSamples(60_000L).size * 2 - 1,
                audioSampleCount =
                    audioSamples(59_920L, 120_000L).size +
                        audioSamples(59_950L, 90_000L).size,
                durationMs = 120_000L,
                audioDurationMs = 119_870L,
            ),
        )
        val (merger, _) = buildMerger(data, verifier)
        val result = merge(merger)
        assertFalse(result.success)
        assertEquals("merge_verify_failed", result.reason)
        assertEquals("muestras_video_no_coinciden", result.detailReason)
        assertFalse(store.finalFile(RECORDING_ID).exists())
    }

    @Test
    fun `tolerancia de duracion del fusionado es 100 ms mas 33 ms por segmento`() {
        // Plan §2.5.2, con N = 2: tolerancia 166 ms.
        assertTrue(mergedDurationWithinTolerance(120_000L, 120_000L, 2))
        assertTrue(mergedDurationWithinTolerance(120_166L, 120_000L, 2))
        assertFalse(mergedDurationWithinTolerance(120_167L, 120_000L, 2))
        assertTrue(mergedDurationWithinTolerance(119_834L, 120_000L, 2))
        assertFalse(mergedDurationWithinTolerance(119_833L, 120_000L, 2))
    }

    @Test
    fun `sin sidecar usa verificacion fresca de cada segmento`() {
        // Sin sidecar: rotación 0, esperados nulos, verificación
        // fresca por segmento.
        val seg0File = createSegmentFile(0)
        val seg1File = createSegmentFile(1)
        val data = mapOf(
            0 to segmentData(60_000L, 59_920L, avOffsetMs = 120L),
            1 to segmentData(60_000L, 59_950L, avOffsetMs = 90L),
        )
        val verifier = FakeSegmentVerifier()
        verifier.segmentResults = mapOf(
            seg0File.name to readyVerification(
                goodChecks(
                    sampleCount = 1800,
                    audioSampleCount = 2800,
                    durationMs = 60_000L,
                    audioDurationMs = 59_920L,
                ),
            ),
            seg1File.name to readyVerification(
                goodChecks(
                    sampleCount = 1800,
                    audioSampleCount = 2800,
                    durationMs = 60_000L,
                    audioDurationMs = 59_950L,
                ),
            ),
        )
        verifier.finalResult = tmpReady2x60(
            videoSamples(60_000L).size,
            audioSamples(59_920L, 120_000L).size,
            audioSamples(59_950L, 90_000L).size,
        )
        val (merger, muxers) = buildMerger(data, verifier)
        val result = merge(merger)
        assertTrue(result.success)
        // Sin sidecar, orientación 0.
        assertEquals(0, muxers.single().orientationDegrees)
        assertTrue(verifier.verifiedFiles.contains(seg0File.name))
        assertTrue(verifier.verifiedFiles.contains(seg1File.name))
    }
}
