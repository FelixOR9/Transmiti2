package com.transmiti2.transmiti2.streaming.recording

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.transmiti2.transmiti2.streaming.StreamContract
import com.transmiti2.transmiti2.streaming.Transmiti2Logger
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Objetivo de audio (plan 3.1.3 §2 D1): AAC-LC, 48.000 Hz, mono,
 * 128 kbps, fuente MIC. La escalera de reserva es 48 kHz mono →
 * 44,1 kHz mono → error `audio_unavailable`; no hay reserva a
 * estéreo (si el mono falla, el estéreo no lo arregla).
 */
const val AUDIO_TARGET_SAMPLE_RATE = 48_000
const val AUDIO_FALLBACK_SAMPLE_RATE = 44_100
const val AUDIO_CHANNELS = 1
const val AUDIO_BITRATE_BPS = 128_000

/** Muestras PCM por lectura: 2048 muestras = 4096 bytes = exactamente 2 tramas AAC. */
const val AUDIO_READ_SAMPLES = 2048
const val AUDIO_READ_BYTES = AUDIO_READ_SAMPLES * 2

/** Piso del buffer de lectura de `AudioRecord` (16 KB). */
const val AUDIO_MIN_READ_BUFFER_BYTES = 16 * 1024

/**
 * Configuración de codificación de audio (plan 3.1.3 §3.1). La
 * configuración objetivo es AAC-LC, 48 kHz, mono, 128 kbps; la
 * configuración REAL se decide en `chooseAudioConfig` tras comprobar
 * las capacidades del dispositivo (nunca en silencio).
 */
data class AudioEncoderConfig(
    val sampleRate: Int,
    val channels: Int,
    val bitrateBps: Int,
) {
    /** Valida los valores antes de configurar el codec. Solo mono: el estéreo nunca se intenta. */
    fun validate() {
        require(sampleRate > 0) { "Frecuencia de muestreo no válida: $sampleRate" }
        require(channels == AUDIO_CHANNELS) {
            "Solo se admite mono (1 canal), no estéreo: $channels"
        }
        require(bitrateBps > 0) { "Bitrate no válido: $bitrateBps" }
    }
}

/**
 * Capacidades reales de audio del dispositivo, medidas con
 * `AudioRecord.getMinBufferSize` y con el codificador AAC (plan
 * 3.1.3 §2 D1). Es la entrada de `chooseAudioConfig`, que decide la
 * configuración real que se usará.
 *
 * - `minBufferSizeBySampleRate`: frecuencia → resultado de
 *   `getMinBufferSize` para esa frecuencia en mono PCM 16 bits;
 *   `ERROR_BAD_VALUE` (-2) o `ERROR` (-1) significan "no disponible".
 * - `aacSupportedSampleRates`: frecuencias que el codificador AAC
 *   anuncia en `getAudioCapabilities().getSupportedSampleRates()`.
 */
data class AudioCapabilities(
    val minBufferSizeBySampleRate: Map<Int, Int>,
    val aacSupportedSampleRates: Set<Int>,
)

/**
 * Capacidades reales del codificador AAC del dispositivo, medidas
 * con `MediaCodecList` (espejo de `VideoEncoder.checkAvcCapabilities`
 * pero para AAC).
 */
data class AacCapabilities(
    val encoderName: String,
    val supportedSampleRates: List<Int>,
)

/**
 * Mide las capacidades reales de audio del dispositivo: frecuencias
 * anunciadas por el codificador AAC y `getMinBufferSize` para 48 kHz
 * y 44,1 kHz en mono. Lanza `RecordingException` (`encoder_error`)
 * si no hay codificador AAC.
 */
fun measureAudioCapabilities(): AudioCapabilities {
    val aac = AudioEncoder.checkAacCapabilities()
    return AudioCapabilities(
        minBufferSizeBySampleRate = mapOf(
            AUDIO_TARGET_SAMPLE_RATE to AudioRecord.getMinBufferSize(
                AUDIO_TARGET_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
            AUDIO_FALLBACK_SAMPLE_RATE to AudioRecord.getMinBufferSize(
                AUDIO_FALLBACK_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
        ),
        aacSupportedSampleRates = aac.supportedSampleRates.toSet(),
    )
}

/**
 * Decide la configuración real a partir de las capacidades medidas
 * (plan 3.1.3 §2 D1, escalera): 48 kHz mono → 44,1 kHz mono → error
 * `audio_unavailable` con el motivo. La comprobación tiene dos patas
 * y ambas deben pasar para cada frecuencia: (a) `getMinBufferSize`
 * no devuelve `ERROR_BAD_VALUE` ni `ERROR`; (b) el codificador AAC
 * anuncia la frecuencia. Sin reserva a estéreo.
 */
fun chooseAudioConfig(caps: AudioCapabilities): AudioEncoderConfig {
    for (rate in listOf(AUDIO_TARGET_SAMPLE_RATE, AUDIO_FALLBACK_SAMPLE_RATE)) {
        val minBuffer = caps.minBufferSizeBySampleRate[rate] ?: continue
        if (minBuffer <= 0) continue
        if (!caps.aacSupportedSampleRates.contains(rate)) continue
        return AudioEncoderConfig(
            sampleRate = rate,
            channels = AUDIO_CHANNELS,
            bitrateBps = AUDIO_BITRATE_BPS,
        )
    }
    throw RecordingException(
        StreamContract.AUDIO_UNAVAILABLE,
        "Micrófono no disponible: ni 48 kHz ni 44,1 kHz mono superan " +
            "la comprobación de AudioRecord y del codificador AAC.",
        mapOf("stage" to "chooseAudioConfig"),
    )
}

/**
 * Tamaño del buffer de lectura de `AudioRecord` (plan 3.1.3 §2 D1):
 * `max(getMinBufferSize(...) × 4, 16 KB)`.
 */
fun audioReadBufferSizeBytes(minBufferSize: Int): Int =
    maxOf(minBufferSize * 4, AUDIO_MIN_READ_BUFFER_BYTES)

/**
 * PTS de una muestra PCM desde el ancla (plan 3.1.3 §4.3), en µs.
 * El ancla es la primera lectura con éxito: la trama PCM `N`
 * corresponde al instante monótono `T` (ns); `samplesFed` es el
 * número de muestras PCM alimentadas desde esa trama ancla (0 para
 * la primera). El PTS de la muestra es
 * `T + samplesFed / sampleRate`. Nunca se usa "ahora" al encolar.
 *
 * Exacto: 1024 muestras = 21.333 µs a 48 kHz (1024/48000 s).
 */
fun audioPtsUs(anchorNs: Long, samplesFed: Long, sampleRate: Int): Long {
    require(sampleRate > 0) { "Frecuencia de muestreo no válida: $sampleRate" }
    require(samplesFed >= 0) { "Muestras alimentadas no válidas: $samplesFed" }
    return anchorNs / 1_000L + samplesFed * 1_000_000L / sampleRate
}

/**
 * Eventos del encoder de audio. Todos llegan en el hilo del encoder
 * de audio (`Transmiti2AudioEncoder`), distinto del hilo del encoder
 * de video y del principal:
 *
 * - `onAudioFormatChanged`: el formato de salida real (con `csd-0`,
 *   el `AudioSpecificConfig`); llega antes de la primera muestra.
 *   Quien drena crea la pista del muxer aquí y reutiliza ese mismo
 *   formato para el `addTrack` de CADA segmento nuevo (el encoder
 *   solo emite el `csd` una vez).
 * - `onAudioSample`: una muestra AAC codificada. El `ByteBuffer`
 *   solo es válido durante la llamada: quien lo recibe debe copiarlo
 *   o escribirlo en el muxer de forma síncrona y nunca guardarlo.
 * - `onAudioError`: error del codec o pérdida del micrófono; la
 *   grabación de video continúa (el audio degrada a video-only).
 * - `onAudioEndOfStream`: el drenado terminó tras
 *   `signalEndOfInputStream`.
 *
 * Los buffers `BUFFER_FLAG_CODEC_CONFIG` (`csd-0`) no se entregan
 * como muestras: el `csd` viaja en el formato de `addTrack`.
 */
interface AudioEncoderListener {
    fun onAudioFormatChanged(format: MediaFormat)
    fun onAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun onAudioError(e: Exception)
    fun onAudioEndOfStream()
}

/**
 * Puerto del encoder de audio que usará `RecordingSession`:
 * `AudioEncoder` lo implementa; en las unitarias se sustituye por un
 * falso (un `AudioRecord` real no existe en JVM). Espejo de
 * `VideoEncoderPort` (sin superficie ni keyframes: la entrada es PCM
 * por buffers, no por superficie).
 */
interface AudioEncoderPort {
    val config: AudioEncoderConfig
    val outputFormat: MediaFormat?
    /** Buffers de entrada descartados porque la cola del codec
     * estaba llena (instrumentación 3.1.3: lo lee la sesión al parar). */
    val droppedInputBuffers: Int
    /**
     * Instrumentación ronda 3 de audio 3.1.3 (grupo I, solo lectura):
     * estado del micrófono y contadores del hilo lector, para
     * distinguir H1-A (arranque nativo fallido en silencio) de H1-B
     * (HAL sin tramas) cuando `read()` nunca devuelve.
     *
     * - `recordingStateAtStart`: `AudioRecord.getRecordingState()`
     *   justo tras `startRecording()` (`RECORDING`=3, `STOPPED`=1);
     *   `null` si nunca se llegó a arrancar.
     * - `recordingState`: lectura en vivo (`-1` sin `AudioRecord`).
     * - `readCount`: llamadas a `read()` que DEVUELVEN (se incrementa
     *   tras cada retorno, no antes: un `read()` bloqueado deja el
     *   contador en 0 y el watchdog lo detecta; `isReaderAlive`
     *   distingue "hilo vivo bloqueado" de "hilo muerto").
     * - `lastReadCode`: último valor de retorno de `read()`.
     * - `lastSuccessfulReadElapsedMs`: `elapsedRealtime()` de la
     *   última lectura `> 0` (`null` si nunca hubo); la sesión lo
     *   convierte en edad al parar.
     * - `isReaderAlive`: el hilo lector sigue vivo al parar.
     */
    val recordingStateAtStart: Int?
    val recordingState: Int
    val readCount: Int
    val lastReadCode: Int?
    val lastSuccessfulReadElapsedMs: Long?
    val isReaderAlive: Boolean
    var listener: AudioEncoderListener?
    fun configureAndStart()
    fun signalEndOfInputStream()
    fun stopDrain()
    fun post(block: () -> Unit)
    fun release()
}

/**
 * Codificador AAC de producción (plan 3.1.3 §2 D2, espejo de
 * `VideoEncoder`): `AudioRecord` (fuente MIC) + `MediaCodec` AAC,
 * con su propio hilo de lectura PCM con prioridad de audio y su
 * propio `HandlerThread` de callback del codec (distinto del hilo
 * del encoder de video y del principal).
 *
 * `AudioRecord.read()` bloquea; nunca corre en el hilo principal ni
 * en el hilo del encoder de video. El ancla de PTS se toma en la
 * primera lectura con éxito con
 * `AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)`; el PTS de cada
 * buffer de entrada se calcula con `audioPtsUs` desde esa ancla,
 * nunca con "ahora" al encolar.
 *
 * `release()` es idempotente y seguro desde cualquier hilo: libera
 * `AudioRecord` + codec + hilos en TODOS los caminos (también si
 * `configureAndStart` falla a mitad).
 */
class AudioEncoder(override val config: AudioEncoderConfig) : AudioEncoderPort {
    private val callbackThread = HandlerThread("Transmiti2AudioEncoder").apply {
        start()
    }
    private val callbackHandler = Handler(callbackThread.looper)

    private val released = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var readerThread: Thread? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var eosRequested = false

    /** Ancla de PTS (ns, `CLOCK_MONOTONIC`): instante de la primera lectura con éxito. */
    @Volatile
    private var anchorNs: Long? = null

    /** Muestras PCM alimentadas al codec desde el ancla. */
    @Volatile
    private var samplesFedSinceAnchor: Long = 0

    /** Buffers de entrada descartados porque la cola del codec estaba llena (diagnóstico). */
    @Volatile
    override var droppedInputBuffers: Int = 0
        private set

    /**
     * Instrumentación ronda 3 (grupo I): estado del micrófono justo
     * tras `startRecording()` (`RECORDING`=3, `STOPPED`=1); `null`
     * si nunca se llegó a arrancar. Lo lee la sesión al parar.
     */
    @Volatile
    override var recordingStateAtStart: Int? = null
        private set

    /** Estado del micrófono en vivo (`-1` sin `AudioRecord`). */
    override val recordingState: Int
        get() = audioRecord?.recordingState ?: -1

    /**
     * Llamadas a `read()` que devolvieron. Se incrementa tras cada
     * retorno (nunca antes): un `read()` bloqueado deja el contador
     * en 0 y el watchdog lo detecta.
     */
    @Volatile
    override var readCount: Int = 0
        private set

    /** Último valor de retorno de `read()` (`null` si nunca volvió). */
    @Volatile
    override var lastReadCode: Int? = null
        private set

    /**
     * `elapsedRealtime()` de la última lectura `> 0` (`null` si
     * nunca hubo). La sesión lo convierte en edad al parar.
     */
    @Volatile
    override var lastSuccessfulReadElapsedMs: Long? = null
        private set

    /** El hilo lector sigue vivo (`false` sin hilo). */
    override val isReaderAlive: Boolean
        get() = readerThread?.isAlive ?: false

    /** Primera muestra AAC ya registrada en log (se resetea por arranque). */
    @Volatile
    private var firstAudioSampleLogged = false

    override var listener: AudioEncoderListener? = null

    /**
     * Formato de salida real del encoder (con `csd-0`),
     * retenido tras `INFO_OUTPUT_FORMAT_CHANGED`. Quien drena lo
     * reutiliza para el `addTrack` de cada segmento nuevo.
     */
    @Volatile
    override var outputFormat: MediaFormat? = null
        private set

    /**
     * Configura y arranca la captura y el encoder. Lanza
     * `RecordingException` (`audio_unavailable`) si el micrófono no
     * está disponible para la configuración elegida, o
     * (`encoder_error`) si no hay codificador AAC o el dispositivo
     * rechaza la configuración. Quien llama degrada a video-only con
     * el motivo; el inicio de la grabación nunca falla por audio.
     * El formato de salida real llega por `onAudioFormatChanged`.
     */
    override fun configureAndStart() {
        check(!released.get()) { "AudioEncoder ya liberado." }
        config.validate()
        firstAudioSampleLogged = false
        // Instrumentación ronda 3 (grupo I): contadores del lector
        // por arranque; `recordingStateAtStart` se fija tras
        // `startRecording()` (abajo) y queda `null` si no se llega.
        readCount = 0
        lastReadCode = null
        lastSuccessfulReadElapsedMs = null
        recordingStateAtStart = null
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer == AudioRecord.ERROR_BAD_VALUE ||
            minBuffer == AudioRecord.ERROR
        ) {
            throw RecordingException(
                StreamContract.AUDIO_UNAVAILABLE,
                "Micrófono no disponible para ${config.sampleRate} Hz mono.",
                mapOf(
                    "stage" to "configureAndStart",
                    "sampleRate" to config.sampleRate,
                ),
            )
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                audioReadBufferSizeBytes(minBuffer),
            )
        } catch (e: Exception) {
            throw RecordingException(
                StreamContract.AUDIO_UNAVAILABLE,
                "No se pudo crear AudioRecord: ${e.message}",
                mapOf(
                    "stage" to "configureAndStart",
                    "exceptionClass" to e.javaClass.simpleName,
                ),
            )
        }
        audioRecord = record
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            cleanupAfterFailedStart()
            throw RecordingException(
                StreamContract.AUDIO_UNAVAILABLE,
                "AudioRecord no inicializado para " +
                    "${config.sampleRate} Hz mono.",
                mapOf(
                    "stage" to "configureAndStart",
                    "sampleRate" to config.sampleRate,
                ),
            )
        }
        try {
            val created = try {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            } catch (e: Exception) {
                throw RecordingException.encoderError(
                    "Sin codificador AAC en este dispositivo: ${e.message}",
                    mapOf("stage" to "configureAndStart"),
                )
            }
            codec = created
            created.setCallback(
                object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                    ) {
                        // Entrada por buffers: la alimenta el hilo de lectura.
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo,
                    ) {
                        handleOutputBuffer(codec, index, info)
                    }

                    override fun onError(
                        codec: MediaCodec,
                        e: MediaCodec.CodecException,
                    ) {
                        Transmiti2Logger.e(
                            "recording: error del codec de audio: " +
                                "${e.javaClass.simpleName} ${e.message}",
                        )
                        listener?.onAudioError(e)
                    }

                    override fun onOutputFormatChanged(
                        codec: MediaCodec,
                        format: MediaFormat,
                    ) {
                        outputFormat = format
                        listener?.onAudioFormatChanged(format)
                    }
                },
                callbackHandler,
            )
            try {
                created.configure(
                    buildFormat(config),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
            } catch (e: Exception) {
                throw RecordingException.encoderError(
                    "El dispositivo rechazó la configuración AAC: " +
                        "${e.message}",
                    mapOf(
                        "stage" to "configureAndStart",
                        "sampleRate" to config.sampleRate,
                    ),
                )
            }
            created.start()
            try {
                record.startRecording()
            } catch (e: Exception) {
                throw RecordingException(
                    StreamContract.AUDIO_UNAVAILABLE,
                    "No se pudo arrancar la captura de audio: ${e.message}",
                    mapOf(
                        "stage" to "configureAndStart",
                        "exceptionClass" to e.javaClass.simpleName,
                    ),
                )
            }
            // Instrumentación ronda 3 (grupo I): `startRecording()`
            // no lanza si el arranque nativo falla (AOSP): el estado
            // queda en `STOPPED` y solo este valor lo distingue de
            // un HAL que no entrega tramas (H1-A vs H1-B).
            recordingStateAtStart = record.recordingState
            val reader = Thread(
                { readLoop() },
                "Transmiti2AudioReader",
            )
            readerThread = reader
            reader.start()
            // Watchdog solo-observador (grupo I): si la primera
            // lectura no vuelve en 2 s, un log, una sola vez. No
            // degrada ni para nada: solo registra.
            callbackHandler.postDelayed({
                if (!released.get() && readCount == 0) {
                    Transmiti2Logger.d(
                        "recording: lector de audio sin datos en 2 s " +
                            "(estadoMic=${recordingStateAtStart ?: recordingState})",
                    )
                }
            }, READER_WATCHDOG_DELAY_MS)
            Transmiti2Logger.d(
                "recording: audio iniciado ${config.sampleRate} Hz " +
                    "mono ${config.bitrateBps} bps",
            )
        } catch (e: RecordingException) {
            cleanupAfterFailedStart()
            throw e
        } catch (e: Exception) {
            cleanupAfterFailedStart()
            throw RecordingException.encoderError(
                "No se pudo arrancar el encoder AAC: ${e.message}",
                mapOf("stage" to "configureAndStart"),
            )
        }
    }

    private fun handleOutputBuffer(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ) {
        try {
            val isConfig = info.flags and
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val isEos = info.flags and
                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (!isConfig && info.size > 0) {
                if (!firstAudioSampleLogged) {
                    firstAudioSampleLogged = true
                    Transmiti2Logger.d(
                        "recording: primera muestra AAC " +
                            "size=${info.size} " +
                            "ptsUs=${info.presentationTimeUs}",
                    )
                }
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null) {
                    listener?.onAudioSample(buffer, info)
                }
            }
            if (isEos) {
                listener?.onAudioEndOfStream()
            }
        } finally {
            try {
                codec.releaseOutputBuffer(index, false)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Hilo de lectura PCM (prioridad de audio). Lee 2048 muestras
     * (4096 bytes = 2 tramas AAC) por `read()` y alimenta el codec
     * con el PTS calculado desde el ancla. `read()` bloquea; este
     * hilo nunca es el principal ni el del encoder de video.
     *
     * Pérdida de audio (plan §6 D7): un código de error de `read()`
     * (`ERROR_DEAD_OBJECT`, `ERROR_INVALID_OPERATION`, `ERROR`) o 10
     * lecturas de 0 seguidas se entregan como `onAudioError` con el
     * motivo en `details["reason"]` (`audio_dead_object`,
     * `audio_invalid_operation`, `audio_read_error`,
     * `audio_silence`); el bucle termina y el video continúa.
     */
    private fun readLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val record = audioRecord ?: return
        val pcm = ByteArray(AUDIO_READ_BYTES)
        var zeroReads = 0
        while (!stopRequested && !released.get()) {
            val read = try {
                record.read(pcm, 0, pcm.size)
            } catch (e: Exception) {
                listener?.onAudioError(
                    RecordingException(
                        StreamContract.AUDIO_UNAVAILABLE,
                        "Lectura de audio fallida: ${e.message}",
                        mapOf(
                            "stage" to "readLoop",
                            "reason" to "audio_read_error",
                            "exceptionClass" to e.javaClass.simpleName,
                        ),
                    ),
                )
                break
            }
            // Instrumentación ronda 3 (grupo I): solo cuentan las
            // lecturas que DEVUELVEN. Un `read()` bloqueado deja
            // `readCount` en 0 y el watchdog lo detecta; `isReaderAlive`
            // distingue "hilo vivo bloqueado" de "hilo muerto".
            readCount++
            lastReadCode = read
            when {
                read > 0 -> {
                    zeroReads = 0
                    lastSuccessfulReadElapsedMs =
                        SystemClock.elapsedRealtime()
                    if (anchorNs == null) {
                        anchorFrom(record)
                    }
                    feedPcm(pcm, read)
                }
                read == 0 -> {
                    zeroReads++
                    if (zeroReads >= SILENCE_ZERO_READS) {
                        listener?.onAudioError(
                            RecordingException(
                                StreamContract.AUDIO_UNAVAILABLE,
                                "Audio en silencio: 10 lecturas de 0 seguidas.",
                                mapOf(
                                    "stage" to "readLoop",
                                    "reason" to "audio_silence",
                                ),
                            ),
                        )
                        break
                    }
                }
                else -> {
                    if (!stopRequested) {
                        listener?.onAudioError(
                            RecordingException(
                                StreamContract.AUDIO_UNAVAILABLE,
                                "Lectura de audio fallida (código $read).",
                                mapOf(
                                    "stage" to "readLoop",
                                    "reason" to readErrorReason(read),
                                    "readCode" to read,
                                ),
                            ),
                        )
                    }
                    break
                }
            }
        }
        if (eosRequested) {
            queueEos()
        }
    }

    private fun readErrorReason(readCode: Int): String = when (readCode) {
        AudioRecord.ERROR_DEAD_OBJECT -> "audio_dead_object"
        AudioRecord.ERROR_INVALID_OPERATION -> "audio_invalid_operation"
        else -> "audio_read_error"
    }

    /**
     * Toma el ancla de PTS en la primera lectura con éxito:
     * `getTimestamp(TIMEBASE_MONOTONIC)` → trama `N` ↔ instante `T`
     * (ns, `CLOCK_MONOTONIC`). La primera muestra PCM alimentada
     * corresponde a ese instante; las siguientes avanzan con
     * `audioPtsUs`. Si `getTimestamp` falla, el ancla es
     * `System.nanoTime()` (también monótono); el desplazamiento real
     * se mide en la verificación, nunca se ajusta.
     */
    private fun anchorFrom(record: AudioRecord) {
        val timestamp = AudioTimestamp()
        val ok = try {
            record.getTimestamp(
                timestamp,
                AudioTimestamp.TIMEBASE_MONOTONIC,
            ) == AudioRecord.SUCCESS
        } catch (_: Exception) {
            false
        }
        anchorNs = if (ok) timestamp.nanoTime else System.nanoTime()
        samplesFedSinceAnchor = 0
    }

    /** Alimenta un buffer PCM al codec con su PTS desde el ancla. */
    private fun feedPcm(pcm: ByteArray, bytesRead: Int) {
        val currentCodec = codec ?: return
        val anchor = anchorNs ?: return
        val ptsUs = audioPtsUs(
            anchor,
            samplesFedSinceAnchor,
            config.sampleRate,
        )
        samplesFedSinceAnchor += (bytesRead / 2).toLong()
        val index = try {
            currentCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        } catch (_: Exception) {
            -1
        }
        if (index < 0) {
            droppedInputBuffers++
            return
        }
        try {
            val input = currentCodec.getInputBuffer(index) ?: return
            input.clear()
            input.put(pcm, 0, bytesRead)
            currentCodec.queueInputBuffer(index, 0, bytesRead, ptsUs, 0)
        } catch (_: Exception) {
            droppedInputBuffers++
        }
    }

    /** Encola un buffer de entrada vacío con flag EOS. */
    private fun queueEos() {
        val currentCodec = codec ?: return
        try {
            val index = currentCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                currentCodec.queueInputBuffer(
                    index,
                    0,
                    0,
                    0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Pide el fin de la entrada: para la captura (el hilo de lectura
     * sale del `read()` bloqueante) y encola el EOS al codec. La
     * entrada por buffers no admite `MediaCodec.signalEndOfInputStream`
     * (solo para entrada por superficie): el EOS se encola como
     * buffer vacío con `BUFFER_FLAG_END_OF_STREAM`.
     */
    override fun signalEndOfInputStream() {
        eosRequested = true
        stopRequested = true
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        val reader = readerThread
        if (reader == null || !reader.isAlive) {
            queueEos()
        }
    }

    /**
     * Detiene el drenado sin liberar el codec, el `AudioRecord` ni
     * los hilos: tras esto no llegan más `onAudioSample`. Espejo de
     * `VideoEncoder.stopDrain`: se usa antes de soltar el muxer en
     * una interrupción, para que ninguna muestra tardía escriba sobre
     * un muxer ya liberado. No sustituye a `release()`.
     */
    override fun stopDrain() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
    }

    /** Ejecuta `block` en el hilo del encoder de audio. */
    override fun post(block: () -> Unit) {
        callbackHandler.post { block() }
    }

    /**
     * Detiene y libera `AudioRecord`, codec e hilos. Idempotente y
     * seguro desde cualquier hilo (incluido el hilo de lectura: en
     * ese caso no se hace `join` sobre sí mismo).
     */
    override fun release() {
        if (!released.compareAndSet(false, true)) return
        stopRequested = true
        eosRequested = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        val reader = readerThread
        if (reader != null && Thread.currentThread() != reader) {
            try {
                reader.join(READER_JOIN_TIMEOUT_MS)
            } catch (_: Exception) {
            }
        }
        readerThread = null
        callbackThread.quitSafely()
    }

    /** Libera `AudioRecord` + codec tras un `configureAndStart` fallido a mitad. */
    private fun cleanupAfterFailedStart() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }

    companion object {
        /** Espera al encolar la entrada de audio (µs). */
        private const val INPUT_TIMEOUT_US = 10_000L

        /** Espera máxima al hilo de lectura en `release()` (ms). */
        private const val READER_JOIN_TIMEOUT_MS = 2_000L

        /** Lecturas de 0 seguidas antes de tratar el audio como perdido (plan §6 D7). */
        private const val SILENCE_ZERO_READS = 10

        /**
         * Retraso del watchdog solo-observador del lector de audio
         * (ronda 3, grupo I): si ninguna lectura devolvió en este
         * tiempo, un único log. Sin degradar ni parar nada.
         */
        private const val READER_WATCHDOG_DELAY_MS = 2_000L

        /**
         * Construye el formato de codificación a partir de la
         * configuración decidida. No verifica nada: la verificación
         * es `checkAacCapabilities` + `chooseAudioConfig`, antes de
         * llamar aquí.
         */
        fun buildFormat(config: AudioEncoderConfig): MediaFormat {
            return MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                config.sampleRate,
                config.channels,
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
            }
        }

        /**
         * Comprueba con `MediaCodecList` (solo codificadores) que el
         * dispositivo tiene un codificador AAC (espejo de
         * `VideoEncoder.checkAvcCapabilities` pero para AAC). Devuelve
         * el nombre del codificador y las frecuencias que anuncia.
         * Lanza `RecordingException` (`encoder_error` con `stage`) si
         * no hay codificador AAC.
         */
        fun checkAacCapabilities(): AacCapabilities {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.contains(
                        MediaFormat.MIMETYPE_AUDIO_AAC,
                    )
                ) {
                    continue
                }
                val caps = info.getCapabilitiesForType(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                )
                val rates = caps.audioCapabilities
                    ?.supportedSampleRates?.toList() ?: emptyList()
                return AacCapabilities(
                    encoderName = info.name,
                    supportedSampleRates = rates,
                )
            }
            throw RecordingException.encoderError(
                "Sin codificador AAC en este dispositivo.",
                mapOf("stage" to "checkAacCapabilities"),
            )
        }
    }
}
