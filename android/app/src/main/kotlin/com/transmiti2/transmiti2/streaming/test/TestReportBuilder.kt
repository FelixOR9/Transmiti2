package com.transmiti2.transmiti2.streaming.test

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Constructor de informes (diseño base §4.D + Revisión 1 §2.6/§6.3 +
 * Revisión 2 §2.5–§2.8 + Revisión 3 §2.5).
 *
 * Lógica pura, sin E/S: recibe eventos y devuelve el informe JSON + el
 * resumen `.txt`. Sin contenido multimedia ni claves.
 *
 * Reglas de veredicto (fail-closed: la ausencia de evidencia nunca se lee
 * como evidencia de ausencia):
 * - `INCOMPLETE`: la pausa no ocurrió o su resultado es desconocido
 *   (motivos de la taxonomía de Revisión 2 §2.5 + `intent_not_consumed`
 *   de Revisión 3 §2.5). Nunca aprobado ni fallido por ese hecho.
 * - `INCONCLUSIVE`: la pausa SÍ ocurrió (`PAUSE_ENTERED`) pero el
 *   solapamiento exigido no se produjo.
 * - `FAILED`: un criterio evaluado falló (o la pasada falló por un
 *   motivo propio de la reconciliación).
 * - `PASSED`: todos los criterios evaluados pasaron.
 * - C1/C2 exigen `PAUSE_ENTERED` con el punto correcto, el `runId` de la
 *   pasada y orden monotónico estricto; sin él quedan `not_evaluable`.
 * - Lo inferido nunca aprueba: cualquier evidencia de aprobación con
 *   `origin: inferred` deja el criterio en `INCONCLUSIVE` con la marca
 *   «inferido».
 */
class TestReportBuilder {
    enum class Verdict {
        PASSED,
        FAILED,
        INCONCLUSIVE,
        INCOMPLETE,
    }

    enum class CriterionStatus {
        PASSED,
        FAILED,
        INCONCLUSIVE,
        NOT_EVALUABLE,
    }

    data class CriterionEvaluation(
        val name: String,
        val status: CriterionStatus,
        val evidence: List<String>,
        val note: String? = null,
    ) {
        fun toJsonMap(): Map<String, Any?> = linkedMapOf(
            "name" to name,
            "status" to status.name,
            "evidence" to evidence,
            "note" to note,
        )
    }

    /**
     * Resumen de una pasada de reconciliación, tal como lo observó la
     * sesión (lo aporta el llamador: el constructor no lee SQLite ni
     * `recordings/`). `processedRecordingIds` son los ids que aparecen en
     * los segmentos procesados de esa pasada.
     */
    data class ReconciliationEvidence(
        val runId: String,
        val state: String,
        val groupsSkippedActive: List<String> = emptyList(),
        val processedRecordingIds: List<String> = emptyList(),
        val verifiedRecordingIds: List<String> = emptyList(),
        val renamedRecordingIds: List<String> = emptyList(),
        val conflicts: Int = 0,
        val foreignReleaseRejected: Long = 0,
    )

    data class BuiltReport(
        val sessionId: String?,
        val testRunId: String?,
        val verdict: Verdict,
        val reason: String?,
        val criteria: List<CriterionEvaluation>,
        val flags: List<String>,
        val events: List<TestEvent>,
        val recordings: List<String>,
        val json: String,
        val text: String,
    )

    companion object {
        /** Motivos `INCOMPLETE` de la pausa (Revisión 2 §2.5 + Revisión 3 §2.5). */
        const val REASON_INTENT_NOT_CONSUMED = "intent_not_consumed"
        const val REASON_INTENT_CONSUMED_WITHOUT_PAUSE = "intent_consumed_without_pause"
        const val REASON_INTENT_OUTCOME_UNKNOWN = "intent_outcome_unknown"
        const val REASON_PAUSED_THEN_INTERRUPTED = "paused_then_interrupted"
        const val REASON_PAUSE_CANCELLED = "pause_cancelled"
        const val REASON_INTENT_CONSUME_FAILED = "intent_consume_failed"
        const val REASON_INTENT_EXPIRED = "intent_expired"
        const val REASON_STORAGE_FULL = "storage_full"

        /** Adiciones propias (documentadas en el informe de FASE 2, grupo 2-a). */
        const val REASON_NO_PAUSE_EXECUTED = "no_pause_executed"
        const val REASON_SESSION_INTERRUPTED = "session_interrupted"
        const val REASON_NO_OVERLAP = "no_overlap"
        const val REASON_PAUSE_TIMEOUT_WITHOUT_RECORDING_START =
            "pause_timeout_without_recording_start"

        const val SAMPLE_DIVERGENCE_MIN_GAP_MS = 5_000L
        const val STALLED_TMP_MIN_GAP_MS = 10_000L
    }

    // ------------------------------------------------------------------
    // Banderas del incidente seg008 (§8 del diseño base)
    // ------------------------------------------------------------------

    /**
     * `DIVERGENCE_SUSPECTED`: dos muestras `RECORDING_SAMPLE`
     * consecutivas separadas ≥ 5 s sin avance de `segmentDurationMs` ni
     * del tamaño del `.tmp` con estado `recording`.
     * `STALLED_TMP`: dos muestras con el mismo tamaño separadas ≥ 10 s.
     * Son banderas para el análisis, no un veredicto (son inferidas).
     */
    fun detectSampleFlags(events: List<TestEvent>): List<String> {
        val samples = events.filter { it.event == TestEventType.RECORDING_SAMPLE }
        val flags = mutableListOf<String>()
        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val gap = curr.monotonicMs - prev.monotonicMs
            val prevDuration = (prev.detail["segmentDurationMs"] as? Number)?.toLong()
            val currDuration = (curr.detail["segmentDurationMs"] as? Number)?.toLong()
            val prevSize = (prev.detail["tmpSizeBytes"] as? Number)?.toLong()
            val currSize = (curr.detail["tmpSizeBytes"] as? Number)?.toLong()
            val state = curr.detail["dartState"] as? String
            if (gap >= SAMPLE_DIVERGENCE_MIN_GAP_MS &&
                prevDuration != null && currDuration != null &&
                prevSize != null && currSize != null &&
                currDuration <= prevDuration && currSize <= prevSize &&
                state == "recording"
            ) {
                if ("DIVERGENCE_SUSPECTED" !in flags) flags += "DIVERGENCE_SUSPECTED"
            }
            if (gap >= STALLED_TMP_MIN_GAP_MS &&
                prevSize != null && currSize != null && currSize == prevSize
            ) {
                if ("STALLED_TMP" !in flags) flags += "STALLED_TMP"
            }
        }
        return flags
    }

    // ------------------------------------------------------------------
    // Criterios C1 / C2 (Revisión 1 §4.3–§4.5, Revisión 2 §2.8)
    // ------------------------------------------------------------------

    /**
     * Evalúa C1 (aislamiento, P1) y C2 (protección del registro, P0)
     * sobre los eventos y el resumen de la pasada. Sin `evidence` (sin
     * resumen de reconciliación) ambos quedan `not_evaluable`: el
     * constructor no inventa el resumen.
     */
    fun evaluateCriteria(
        events: List<TestEvent>,
        evidence: ReconciliationEvidence?,
    ): List<CriterionEvaluation> {
        if (evidence == null) {
            return listOf(
                CriterionEvaluation(
                    "C1",
                    CriterionStatus.NOT_EVALUABLE,
                    emptyList(),
                    "Sin resumen de reconciliación para evaluar.",
                ),
                CriterionEvaluation(
                    "C2",
                    CriterionStatus.NOT_EVALUABLE,
                    emptyList(),
                    "Sin resumen de reconciliación para evaluar.",
                ),
            )
        }
        return listOf(
            evaluateCriterion(events, evidence, PausePoint.P1, "C1"),
            evaluateCriterion(events, evidence, PausePoint.P0, "C2"),
        )
    }

    private fun evaluateCriterion(
        events: List<TestEvent>,
        evidence: ReconciliationEvidence,
        point: PausePoint,
        name: String,
    ): CriterionEvaluation {
        // Puerta: PAUSE_ENTERED con punto, runId y orden monotónico.
        // Se prefiere el evento que lleva el runId de la pasada; si solo
        // hay uno sin runId, no se puede vincular con el resumen.
        val pauseEntered = events.firstOrNull {
            it.event == TestEventType.PAUSE_ENTERED &&
                it.detail["point"] == point.name &&
                (it.detail["runId"] as? String) == evidence.runId
        } ?: events.firstOrNull {
            it.event == TestEventType.PAUSE_ENTERED &&
                it.detail["point"] == point.name
        }
        if (pauseEntered == null) {
            return CriterionEvaluation(
                name,
                CriterionStatus.NOT_EVALUABLE,
                emptyList(),
                "Sin PAUSE_ENTERED en $point para runId ${evidence.runId}.",
            )
        }
        if ((pauseEntered.detail["runId"] as? String) != evidence.runId) {
            return CriterionEvaluation(
                name,
                CriterionStatus.NOT_EVALUABLE,
                listOf("PAUSE_ENTERED@${pauseEntered.monotonicMs}"),
                "El PAUSE_ENTERED no lleva el runId ${evidence.runId}.",
            )
        }
        val release = events.firstOrNull {
            (it.event == TestEventType.PAUSE_RELEASED ||
                it.event == TestEventType.PAUSE_TIMEOUT) &&
                it.detail["point"] == point.name &&
                it.monotonicMs > pauseEntered.monotonicMs
        }
        if (release == null) {
            return CriterionEvaluation(
                name,
                CriterionStatus.NOT_EVALUABLE,
                listOf("PAUSE_ENTERED@${pauseEntered.monotonicMs}"),
                "Sin liberación (PAUSE_RELEASED/PAUSE_TIMEOUT) tras la pausa.",
            )
        }
        // La grabación nueva debe empezar estrictamente entre pausa y
        // reanudación (orden monotónico estricto, Revisión 2 §2.8).
        val recordingStarted = events.firstOrNull {
            it.event == TestEventType.RECORDING_STARTED &&
                it.monotonicMs > pauseEntered.monotonicMs &&
                it.monotonicMs < release.monotonicMs
        }
        if (recordingStarted == null) {
            return CriterionEvaluation(
                name,
                CriterionStatus.INCONCLUSIVE,
                listOf(
                    "PAUSE_ENTERED@${pauseEntered.monotonicMs}",
                    "${release.event}@${release.monotonicMs}",
                ),
                "La pausa ocurrió pero ninguna grabación empezó en la ventana.",
            )
        }
        val newId = recordingStarted.recordingId
        if (newId == null) {
            return CriterionEvaluation(
                name,
                CriterionStatus.INCONCLUSIVE,
                listOf("RECORDING_STARTED@${recordingStarted.monotonicMs}"),
                "El RECORDING_STARTED no lleva recordingId.",
            )
        }
        val evidenceLines = mutableListOf(
            "PAUSE_ENTERED@${pauseEntered.monotonicMs} point=$point runId=${evidence.runId}",
            "RECORDING_STARTED@${recordingStarted.monotonicMs} id=$newId",
            "${release.event}@${release.monotonicMs}",
        )
        // Lo inferido nunca aprueba (Revisión 1 §3.3).
        val inferred = listOf(pauseEntered, recordingStarted, release).any {
            it.origin == TestEventOrigin.INFERRED
        }
        val finished = events.firstOrNull {
            it.event == TestEventType.RECONCILIATION_FINISHED &&
                (it.detail["runId"] as? String) == evidence.runId
        }
        val stopped = events.firstOrNull {
            it.event == TestEventType.RECORDING_STOPPED && it.recordingId == newId
        }
        if (finished != null && finished.origin == TestEventOrigin.INFERRED) {
            return CriterionEvaluation(
                name,
                CriterionStatus.INCONCLUSIVE,
                evidenceLines,
                "Evidencia inferida (RECONCILIATION_FINISHED inferido): no aprueba.",
            )
        }
        if (inferred) {
            return CriterionEvaluation(
                name,
                CriterionStatus.INCONCLUSIVE,
                evidenceLines,
                "Evidencia inferida: no aprueba (marca «inferido»).",
            )
        }
        if (finished == null) {
            return CriterionEvaluation(
                name,
                CriterionStatus.INCONCLUSIVE,
                evidenceLines,
                "Sin RECONCILIATION_FINISHED para runId ${evidence.runId}.",
            )
        }
        evidenceLines += "RECONCILIATION_FINISHED@${finished.monotonicMs} runId=${evidence.runId}"
        if (stopped != null) {
            evidenceLines += "RECORDING_STOPPED@${stopped.monotonicMs} id=$newId"
        }

        val failures = mutableListOf<String>()
        if (point == PausePoint.P0) {
            // C2: el id nuevo DEBE estar en skipped_active (colisión real).
            if (newId !in evidence.groupsSkippedActive) {
                failures += "id $newId ausente de groupsSkippedActive (se esperaba la colisión)"
            }
            // C2: la grabación seguía en curso cuando la pasada llegó a DONE.
            if (stopped != null && stopped.monotonicMs < finished.monotonicMs) {
                return CriterionEvaluation(
                    name,
                    CriterionStatus.INCONCLUSIVE,
                    evidenceLines,
                    "La grabación se detuvo antes de que la pasada llegara a DONE.",
                )
            }
        } else {
            // C1: el id nuevo NO estaba en el inventario: ausente de los
            // segmentos procesados Y de skipped_active.
            if (newId in evidence.groupsSkippedActive) {
                failures += "id $newId presente en groupsSkippedActive (en P1 no estaba en el inventario)"
            }
        }
        if (newId in evidence.processedRecordingIds) {
            failures += "id $newId presente en los segmentos procesados de la pasada"
        }
        if (newId in evidence.verifiedRecordingIds) {
            failures += "id $newId verificado por la pasada"
        }
        if (newId in evidence.renamedRecordingIds) {
            failures += "id $newId renombrado por la pasada"
        }
        val verifyOrRenameEvents = events.count {
            (it.event == TestEventType.SEGMENT_VERIFIED ||
                it.event == TestEventType.SEGMENT_RENAMED) &&
                it.recordingId == newId
        }
        if (verifyOrRenameEvents > 0) {
            failures += "$verifyOrRenameEvents eventos de verificación/renombre con id $newId"
        }
        if (evidence.conflicts != 0) {
            failures += "conflicts=${evidence.conflicts} (se esperaba 0)"
        }
        if (evidence.foreignReleaseRejected != 0L) {
            failures += "foreignReleaseRejected=${evidence.foreignReleaseRejected} (se esperaba 0)"
        }
        if (stopped == null) {
            return CriterionEvaluation(
                name,
                CriterionStatus.INCONCLUSIVE,
                evidenceLines,
                "Sin RECORDING_STOPPED para $newId: no se sabe cómo terminó.",
            )
        }
        if (stopped.result == TestEventResult.FAILED || stopped.errorCode != null) {
            failures += "RECORDING_STOPPED con fallo (${stopped.errorCode})"
        }
        if (failures.isNotEmpty()) {
            return CriterionEvaluation(name, CriterionStatus.FAILED, evidenceLines + failures)
        }
        return CriterionEvaluation(name, CriterionStatus.PASSED, evidenceLines)
    }

    // ------------------------------------------------------------------
    // Veredicto de sesión
    // ------------------------------------------------------------------

    /**
     * Motivo `INCOMPLETE` por el patrón exacto de eventos (Revisión 2
     * §2.5 + Revisión 3 §2.5), o `null` si ningún patrón aplica.
     * `armedIntent` es la intención que sigue en disco al construir el
     * informe (`null` si ya no existe): distingue `intent_not_consumed`
     * (sigue armada) de `intent_outcome_unknown` (desapareció sin dejar
     * rastro de consumo ni de caducidad).
     */
    fun incompleteReason(
        events: List<TestEvent>,
        armedIntent: ArmedPauseIntent? = null,
        blockedStarts: List<TestBlockedStart> = emptyList(),
        testRunId: String? = null,
    ): String? {
        val has = { type: TestEventType -> events.any { it.event == type } }
        if (events.isEmpty() &&
            blockedStarts.any { it.testRunId == testRunId && it.reason == "test_storage_full" }
        ) {
            return REASON_STORAGE_FULL
        }
        // El fallo de consumo y la caducidad son más específicos que
        // «sigue armada»: se comprueban antes (1c: el borrado falló y el
        // archivo sigue intacto, pero el motivo es intent_consume_failed).
        if (has(TestEventType.ARMED_PAUSE_CONSUME_FAILED)) {
            return REASON_INTENT_CONSUME_FAILED
        }
        if (has(TestEventType.ARMED_PAUSE_EXPIRED)) {
            return REASON_INTENT_EXPIRED
        }
        if (armedIntent != null && !has(TestEventType.INTENT_CONSUMED) &&
            !has(TestEventType.PAUSE_ENTERED)
        ) {
            return REASON_INTENT_NOT_CONSUMED
        }
        if (has(TestEventType.INTENT_CONSUMED) && !has(TestEventType.PAUSE_ENTERED)) {
            return REASON_INTENT_CONSUMED_WITHOUT_PAUSE
        }
        if (has(TestEventType.PAUSE_ARMED) && armedIntent == null &&
            !has(TestEventType.INTENT_CONSUMED) && !has(TestEventType.ARMED_PAUSE_EXPIRED) &&
            // Sin SESSION_ENDED: si la sesión terminó normalmente, la
            // intención pudo borrarse en el fin de sesión (disarm), así
            // que su ausencia no significa que el resultado sea
            // desconocido. Solo es desconocido si la sesión no cerró.
            !has(TestEventType.SESSION_ENDED)
        ) {
            return REASON_INTENT_OUTCOME_UNKNOWN
        }
        if (has(TestEventType.PAUSE_ENTERED) && has(TestEventType.PAUSE_CANCELLED)) {
            return REASON_PAUSE_CANCELLED
        }
        if (has(TestEventType.PAUSE_ENTERED) && has(TestEventType.PROCESS_INTERRUPTED) &&
            !has(TestEventType.PAUSE_RELEASED) && !has(TestEventType.PAUSE_TIMEOUT) &&
            !has(TestEventType.PAUSE_CANCELLED)
        ) {
            return REASON_PAUSED_THEN_INTERRUPTED
        }
        return null
    }

    /**
     * Informe por sesión. `reconciliationEvidence` es opcional: sin él,
     * C1/C2 quedan `not_evaluable` (el constructor no inventa resúmenes).
     */
    fun buildSessionReport(
        sessionId: String,
        testRunId: String?,
        events: List<TestEvent>,
        appVersion: String = "unknown",
        armedIntent: ArmedPauseIntent? = null,
        blockedStarts: List<TestBlockedStart> = emptyList(),
        reconciliationEvidence: ReconciliationEvidence? = null,
        corruptLines: Int = 0,
    ): BuiltReport {
        val criteria = evaluateCriteria(events, reconciliationEvidence)
        val reason = incompleteReason(events, armedIntent, blockedStarts, testRunId)
        val verdict = determineSessionVerdict(events, criteria, reason)
        val flags = detectSampleFlags(events)
        val recordings = events.mapNotNull { it.recordingId }.distinct()
        return assemble(
            sessionId = sessionId,
            testRunId = testRunId,
            events = events,
            criteria = criteria,
            verdict = verdict,
            reason = reason,
            flags = flags,
            recordings = recordings,
            appVersion = appVersion,
            corruptLines = corruptLines,
            blockedStarts = blockedStarts.filter { it.testRunId == testRunId },
        )
    }

    private fun determineSessionVerdict(
        events: List<TestEvent>,
        criteria: List<CriterionEvaluation>,
        incomplete: String?,
    ): Verdict {
        if (incomplete != null) return Verdict.INCOMPLETE
        if (criteria.any { it.status == CriterionStatus.FAILED }) return Verdict.FAILED
        val pauseEntered = events.any { it.event == TestEventType.PAUSE_ENTERED }
        if (!pauseEntered) return Verdict.INCOMPLETE // Sin pausa ejecutada ni intentada.
        if (criteria.any { it.status == CriterionStatus.PASSED } &&
            criteria.none {
                it.status == CriterionStatus.NOT_EVALUABLE ||
                    it.status == CriterionStatus.INCONCLUSIVE
            }
        ) {
            return Verdict.PASSED
        }
        return Verdict.INCONCLUSIVE
    }

    /** Motivo legible cuando el veredicto no viene de la taxonomía `INCOMPLETE`. */
    private fun sessionReasonFallback(
        events: List<TestEvent>,
        criteria: List<CriterionEvaluation>,
        verdict: Verdict,
    ): String? = when (verdict) {
        Verdict.INCONCLUSIVE -> {
            val timeout = events.any { it.event == TestEventType.PAUSE_TIMEOUT }
            val started = events.any { it.event == TestEventType.RECORDING_STARTED }
            if (timeout && !started) REASON_PAUSE_TIMEOUT_WITHOUT_RECORDING_START
            else if (events.none { it.event == TestEventType.PAUSE_ENTERED }) REASON_NO_PAUSE_EXECUTED
            else REASON_NO_OVERLAP
        }
        Verdict.INCOMPLETE -> REASON_NO_PAUSE_EXECUTED
        else -> null
    }

    // ------------------------------------------------------------------
    // Informe de campaña (Revisión 1 §2.6)
    // ------------------------------------------------------------------

    /**
     * Agrega todos los JSONL con ese `testRunId` + `blocked-starts.jsonl`.
     * El veredicto de campaña es el peor de sus sesiones (FAILED >
     * INCOMPLETE > INCONCLUSIVE > PASSED); una sesión sin `SESSION_ENDED`
     * deja la campaña `INCOMPLETE` (`session_interrupted`).
     */
    fun buildCampaignReport(
        testRunId: String,
        sessions: Map<String, List<TestEvent>>,
        blockedStarts: List<TestBlockedStart> = emptyList(),
        reconciliationEvidenceByRun: Map<String, ReconciliationEvidence> = emptyMap(),
        appVersion: String = "unknown",
    ): BuiltReport {
        val allEvents = sessions.values.flatten()
            .sortedWith(compareBy({ it.wallMs }, { it.monotonicMs }))
        val perSession = sessions.map { (sessionId, events) ->
            buildSessionReport(
                sessionId = sessionId,
                testRunId = testRunId,
                events = events,
                appVersion = appVersion,
                blockedStarts = blockedStarts,
                reconciliationEvidence = null,
            )
        }
        val criteria = reconciliationEvidenceByRun.values
            .flatMap { evaluateCriteria(allEvents, it) }
        val blockedForRun = blockedStarts.filter { it.testRunId == testRunId }
        val sessionsWithoutEnd = sessions.filter { (_, events) ->
            events.none { it.event == TestEventType.SESSION_ENDED }
        }.keys.toList()
        val verdict: Verdict
        val reason: String?
        when {
            perSession.any { it.verdict == Verdict.FAILED } ||
                criteria.any { it.status == CriterionStatus.FAILED } -> {
                verdict = Verdict.FAILED
                reason = null
            }
            blockedForRun.any { it.reason == "test_storage_full" } &&
                perSession.none { it.verdict == Verdict.PASSED } -> {
                verdict = Verdict.INCOMPLETE
                reason = REASON_STORAGE_FULL
            }
            sessionsWithoutEnd.isNotEmpty() -> {
                verdict = Verdict.INCOMPLETE
                reason = REASON_SESSION_INTERRUPTED
            }
            perSession.any { it.verdict == Verdict.INCOMPLETE } -> {
                verdict = Verdict.INCOMPLETE
                reason = perSession.first { it.verdict == Verdict.INCOMPLETE }.reason
            }
            perSession.any { it.verdict == Verdict.INCONCLUSIVE } ||
                criteria.any { it.status == CriterionStatus.INCONCLUSIVE } -> {
                verdict = Verdict.INCONCLUSIVE
                reason = REASON_NO_OVERLAP
            }
            criteria.any { it.status == CriterionStatus.PASSED } -> {
                verdict = Verdict.PASSED
                reason = null
            }
            else -> {
                verdict = Verdict.INCONCLUSIVE
                reason = REASON_NO_OVERLAP
            }
        }
        val recordings = allEvents.mapNotNull { it.recordingId }.distinct()
        return assemble(
            sessionId = null,
            testRunId = testRunId,
            events = allEvents,
            criteria = criteria.ifEmpty { perSession.flatMap { it.criteria } },
            verdict = verdict,
            reason = reason,
            flags = detectSampleFlags(allEvents),
            recordings = recordings,
            appVersion = appVersion,
            corruptLines = 0,
            blockedStarts = blockedForRun,
            extraSections = mapOf(
                "sessions" to perSession.map {
                    mapOf(
                        "sessionId" to it.sessionId,
                        "verdict" to it.verdict.name,
                        "reason" to it.reason,
                    )
                },
                "sessionsWithoutEnd" to sessionsWithoutEnd,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Ensamblado JSON + TXT
    // ------------------------------------------------------------------

    private fun assemble(
        sessionId: String?,
        testRunId: String?,
        events: List<TestEvent>,
        criteria: List<CriterionEvaluation>,
        verdict: Verdict,
        reason: String?,
        flags: List<String>,
        recordings: List<String>,
        appVersion: String,
        corruptLines: Int,
        blockedStarts: List<TestBlockedStart>,
        extraSections: Map<String, Any?> = emptyMap(),
    ): BuiltReport {
        val effectiveReason = reason ?: sessionReasonFallback(events, criteria, verdict)
        val startedAt = events.minOfOrNull { it.wallMs }
        val endedAt = events.filter { it.event == TestEventType.SESSION_ENDED }
            .maxOfOrNull { it.wallMs }
        val errors = events
            .filter { it.result == TestEventResult.FAILED || it.event == TestEventType.ERROR }
            .mapNotNull { it.errorCode ?: it.event.name }
            .distinct()
        val warnings = buildList {
            if (corruptLines > 0) add("corruptLines=$corruptLines (líneas JSONL saltadas)")
            if (events.any { it.origin == TestEventOrigin.INFERRED }) {
                add("contiene eventos inferidos (marca «inferido»): nunca aprueban")
            }
        }
        val jsonMap = linkedMapOf<String, Any?>(
            "sessionId" to sessionId,
            "testRunId" to testRunId,
            "startedAtWallMs" to startedAt,
            "endedAtWallMs" to endedAt,
            "appVersion" to appVersion,
            "testMode" to true,
            "verdict" to verdict.name,
            "reason" to effectiveReason,
            "criteria" to criteria.map { it.toJsonMap() },
            "flags" to flags,
            "events" to events.map { it.toJsonMap() },
            "recordings" to recordings,
            "errors" to errors,
            "warnings" to warnings,
            "blockedStarts" to blockedStarts.map { it.toJsonMap() },
        )
        jsonMap.putAll(extraSections)
        val json = TestJson.encode(jsonMap)
        val text = buildTextReport(
            sessionId = sessionId,
            testRunId = testRunId,
            verdict = verdict,
            reason = effectiveReason,
            criteria = criteria,
            flags = flags,
            events = events,
            recordings = recordings,
            errors = errors,
            warnings = warnings,
            blockedStarts = blockedStarts,
            extraSections = extraSections,
        )
        return BuiltReport(
            sessionId = sessionId,
            testRunId = testRunId,
            verdict = verdict,
            reason = effectiveReason,
            criteria = criteria,
            flags = flags,
            events = events,
            recordings = recordings,
            json = json,
            text = text,
        )
    }

    private fun formatWall(wallMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(wallMs))

    private fun buildTextReport(
        sessionId: String?,
        testRunId: String?,
        verdict: Verdict,
        reason: String?,
        criteria: List<CriterionEvaluation>,
        flags: List<String>,
        events: List<TestEvent>,
        recordings: List<String>,
        errors: List<String>,
        warnings: List<String>,
        blockedStarts: List<TestBlockedStart>,
        extraSections: Map<String, Any?>,
    ): String {
        val out = StringBuilder()
        out.appendLine("INFORME DE PRUEBAS TRANSMITI2")
        out.appendLine("Sesión: ${sessionId ?: "-"}")
        out.appendLine("Campaña (testRunId): ${testRunId ?: "-"}")
        out.appendLine("VEREDICTO: ${verdict.name}")
        if (reason != null) out.appendLine("Motivo: $reason")
        out.appendLine()
        out.appendLine("Criterios:")
        if (criteria.isEmpty()) {
            out.appendLine("- (sin criterios evaluados)")
        } else {
            for (c in criteria) {
                out.appendLine("- ${c.name}: ${c.status.name}" + (c.note?.let { " — $it" } ?: ""))
                for (line in c.evidence) out.appendLine("    evidencia: $line")
            }
        }
        if (flags.isNotEmpty()) {
            out.appendLine()
            out.appendLine("Banderas: ${flags.joinToString(", ")} (inferido: solo para análisis)")
        }
        if (errors.isNotEmpty()) {
            out.appendLine()
            out.appendLine("Errores: ${errors.joinToString(", ")}")
        }
        if (warnings.isNotEmpty()) {
            out.appendLine()
            for (w in warnings) out.appendLine("Advertencia: $w")
        }
        if (blockedStarts.isNotEmpty()) {
            out.appendLine()
            out.appendLine("Inicios bloqueados: ${blockedStarts.size}")
            for (b in blockedStarts) {
                out.appendLine("- ${b.testRunId} ${formatWall(b.wallMs)} (${b.reason})")
            }
        }
        @Suppress("UNCHECKED_CAST")
        val sessions = extraSections["sessions"] as? List<Map<String, Any?>>
        if (sessions != null) {
            out.appendLine()
            out.appendLine("Sesiones de la campaña:")
            for (s in sessions) {
                out.appendLine("- ${s["sessionId"]}: ${s["verdict"]}" + (s["reason"]?.let { " ($it)" } ?: ""))
            }
        }
        out.appendLine()
        out.appendLine("Grabaciones: ${if (recordings.isEmpty()) "-" else recordings.joinToString(", ")}")
        out.appendLine()
        out.appendLine("Cronología:")
        for (e in events) {
            val inferred = if (e.origin == TestEventOrigin.INFERRED) " [inferido]" else ""
            val rec = e.recordingId?.let { " $it" } ?: ""
            out.appendLine("${formatWall(e.wallMs)} ${e.event.name}$rec ${e.result.json}$inferred")
        }
        return out.toString()
    }
}
