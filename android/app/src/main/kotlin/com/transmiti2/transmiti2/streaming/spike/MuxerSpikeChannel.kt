// SPIKE 3.1.2 — TEMPORAL
package com.transmiti2.transmiti2.streaming.spike

import com.transmiti2.transmiti2.streaming.CameraException
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

/**
 * Manejador del canal propio del spike
 * `com.transmiti2.transmiti2/spike`, separado del canal de streaming:
 * no toca el contrato de producción.
 *
 * Métodos: `startPreview`, `stopPreview`, `probeEncoder`,
 * `recordWithMuxer`, `interruptWithMuxer`, `verifyFile`,
 * `listSpikeFiles`, `deleteSpikeFiles`.
 *
 * `startPreview` / `stopPreview` no estaban en la lista del plan
 * §12.2, pero la pantalla necesita la vista previa en vivo durante la
 * grabación (§12.3 paso 1) y el runner la crea con su propio
 * `SurfaceProducer`; son el camino para obtener su `textureId`.
 *
 * Los errores viajan con su código (`camera_*`, `encoder_error`,
 * `muxer_error`, `permission_denied`) o `spike_error` para el resto.
 */
// SPIKE 3.1.2 — TEMPORAL
class MuxerSpikeChannel(
    private val runner: MuxerSpikeRunner,
    private val scope: CoroutineScope,
) : MethodChannel.MethodCallHandler {

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            START_PREVIEW -> launchResult(result) { runner.startPreview() }
            STOP_PREVIEW -> launchResult(result) {
                runner.stopPreview()
                null
            }
            PROBE_ENCODER -> launchResult(result) { runner.probeEncoder() }
            RECORD_WITH_MUXER -> launchResult(result) {
                runner.recordWithMuxer(
                    kind = call.stringArgument("muxer"),
                    durationMs = call.longArgument("durationMs"),
                )
            }
            INTERRUPT_WITH_MUXER -> launchResult(result) {
                runner.interruptWithMuxer(
                    kind = call.stringArgument("muxer"),
                    durationMs = call.longArgument("durationMs"),
                )
            }
            VERIFY_FILE -> launchResult(result) {
                runner.verifyFile(call.stringArgument("fileName"))
            }
            LIST_SPIKE_FILES -> launchResult(result) {
                runner.listSpikeFiles()
            }
            DELETE_SPIKE_FILES -> launchResult(result) {
                runner.deleteSpikeFiles()
            }
            else -> result.notImplemented()
        }
    }

    private fun MethodCall.stringArgument(name: String): String {
        return argument<String>(name)
            ?: throw SpikeException(
                "spike_error",
                "Falta el argumento: $name",
            )
    }

    private fun MethodCall.longArgument(name: String): Long {
        val value = argument<Any>(name) as? Number
            ?: throw SpikeException(
                "spike_error",
                "Falta el argumento: $name",
            )
        return value.toLong()
    }

    private fun launchResult(
        result: MethodChannel.Result,
        block: suspend () -> Any?,
    ) {
        scope.launch {
            try {
                result.success(block())
            } catch (e: SpikeException) {
                result.error(e.code, e.message, null)
            } catch (e: CameraException) {
                result.error(e.code, e.message, e.details)
            } catch (e: TimeoutCancellationException) {
                result.error(
                    "spike_error",
                    "Tiempo de espera agotado.",
                    null,
                )
            } catch (e: CancellationException) {
                // Cancelación sin timeout: se responde con error en vez
                // de dejar el futuro de Dart colgado (el canal de
                // streaming hace lo mismo con «cancelado»).
                result.error(
                    "spike_error",
                    "Operación del spike cancelada.",
                    null,
                )
            } catch (e: IllegalArgumentException) {
                result.error("spike_error", e.message, null)
            } catch (e: Exception) {
                result.error("spike_error", e.message, null)
            }
        }
    }

    companion object {
        const val CHANNEL = "com.transmiti2.transmiti2/spike"
        const val START_PREVIEW = "startPreview"
        const val STOP_PREVIEW = "stopPreview"
        const val PROBE_ENCODER = "probeEncoder"
        const val RECORD_WITH_MUXER = "recordWithMuxer"
        const val INTERRUPT_WITH_MUXER = "interruptWithMuxer"
        const val VERIFY_FILE = "verifyFile"
        const val LIST_SPIKE_FILES = "listSpikeFiles"
        const val DELETE_SPIKE_FILES = "deleteSpikeFiles"
    }
}
