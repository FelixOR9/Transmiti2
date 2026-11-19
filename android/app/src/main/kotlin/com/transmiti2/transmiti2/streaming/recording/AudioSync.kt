package com.transmiti2.transmiti2.streaming.recording

/**
 * Sincronización audiovisual (plan 3.1.3 §4.2 y §3.1): funciones
 * puras, testeables en JVM, sin `AudioRecord` ni `MediaCodec` reales.
 *
 * Regla central: el origen común de un segmento es el mínimo de los
 * primeros PTS de video y de audio, en µs monótonos
 * (`CLOCK_MONOTONIC`). Ambas pistas se rebajan a ese origen: la
 * primera muestra de una pista queda en PTS 0 y la de la otra en PTS
 * = desplazamiento real. El desplazamiento se conserva en el archivo
 * y se mide en la verificación; nunca se ajusta para que encaje
 * (prohibido restar offsets "para alinear" o reordenar muestras).
 */

/**
 * Origen común de un segmento (µs monótonos): `min(primer PTS de
 * video, primer PTS de audio)`. Con `firstAudioPtsUs == null` (modo
 * video-only) el origen es el primer PTS de video, idéntico a 3.1.2.
 */
fun segmentOrigin(firstVideoPtsUs: Long, firstAudioPtsUs: Long?): Long =
    if (firstAudioPtsUs == null) {
        firstVideoPtsUs
    } else {
        minOf(firstVideoPtsUs, firstAudioPtsUs)
    }

/**
 * Pertenencia de una muestra de audio al segmento actual: `true` si
 * su PTS absoluto es estrictamente menor que la frontera (el PTS
 * absoluto del keyframe de video que dispara la rotación); `false`
 * si va al segmento siguiente. La frontera cae siempre entre tramas
 * AAC (21,33 ms), así que el corte es limpio a granularidad de trama.
 */
fun assignAudioToSegment(ptsUs: Long, boundaryUs: Long): Boolean =
    ptsUs < boundaryUs

/**
 * Offset A/V (ms, puede ser negativo): `primer PTS de audio − primer
 * PTS de video` en el archivo (ambos rebajados al origen común). La
 * división trunca hacia cero; la resolución es 1 ms.
 */
fun avOffsetMs(firstVideoPtsUs: Long, firstAudioPtsUs: Long): Long =
    (firstAudioPtsUs - firstVideoPtsUs) / 1_000L

/**
 * Diferencia de duraciones (ms): `duración de video − duración de
 * audio` en el archivo. Negativa si el audio dura más que el video.
 */
fun avDurationDiffMs(videoDurationMs: Long, audioDurationMs: Long): Long =
    videoDurationMs - audioDurationMs
