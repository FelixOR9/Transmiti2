import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:transmiti2/core/time.dart';
import 'package:transmiti2/features/streaming/application/permission_controller.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/data/recording_repository.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';

/// Estado de la grabación en la UI (espejo de `RecordingState` en
/// Kotlin, plan §6.1).
enum RecordingState {
  /// Sin grabación (estado inicial y tras detener).
  idle,

  /// Configurando encoder, recreando la sesión de cámara y arrancando
  /// el FGS.
  starting,

  /// Grabando: el encoder drena al muxer del segmento actual.
  recording,

  /// Transición entre segmentos (rotación por keyframe).
  rotating,

  /// Deteniendo: EOS, cierre del segmento, restauración de la vista
  /// previa y parada del FGS.
  stopping,

  /// Fallo que impide seguir grabando (código en `errorCode`).
  error;

  static RecordingState fromWireName(String? value) {
    return switch (value) {
      'starting' => RecordingState.starting,
      'recording' => RecordingState.recording,
      'rotating_segment' => RecordingState.rotating,
      'stopping' => RecordingState.stopping,
      'error' => RecordingState.error,
      _ => RecordingState.idle,
    };
  }
}

/// Creación del temporizador de 1 s (inyectable para las pruebas).
typedef RecordingTimerFactory =
    Timer Function(Duration duration, void Function(Timer) callback);

/// Datos de diagnóstico de la grabación (para la tarjeta de
/// diagnóstico y el botón de copiar, patrón de la Fase A).
class RecordingDiagnostics {
  const RecordingDiagnostics({
    this.stage,
    this.recordingId,
    this.segmentIndex,
    this.segmentDurationMs,
    this.encoderName,
    this.profile,
    this.bitrateBps,
    this.cbrSupported,
    this.lowStorageWarning,
    this.exceptionClass,
    this.lastErrorStage,
    /// FPS esperado de la grabación (ronda 4, fix 3): el rango
    /// seleccionado para el segmento; se muestra en el diagnóstico
    /// para comparar con el FPS medido por segmento.
    this.fps,
  });

  /// Etapa alcanzada (`starting`, `recording`, `rotating`...).
  final String? stage;
  final String? recordingId;
  final int? segmentIndex;
  final int? segmentDurationMs;
  final String? encoderName;
  final int? profile;
  final int? bitrateBps;
  final bool? cbrSupported;
  final bool? lowStorageWarning;

  /// Clase de la excepción nativa, si la hubo.
  final String? exceptionClass;

  /// Etapa en la que falló el intento (`null` si no hubo error).
  final String? lastErrorStage;

  /// FPS esperado (del evento `recording_started`).
  final int? fps;

  factory RecordingDiagnostics.fromStarted(RecordingStartedInfo info) {
    return RecordingDiagnostics(
      stage: 'recording',
      recordingId: info.recordingId,
      encoderName: info.encoderName,
      profile: info.profile,
      bitrateBps: info.bitrateBps,
      cbrSupported: info.cbrSupported,
      lowStorageWarning: info.lowStorageWarning,
      fps: info.fps,
    );
  }

  factory RecordingDiagnostics.fromDetails(Map<String, Object?>? details) {
    if (details == null) {
      return const RecordingDiagnostics();
    }
    final String? stage = details['stage'] as String?;
    return RecordingDiagnostics(
      stage: stage,
      recordingId: details['recordingId'] as String?,
      segmentIndex: (details['segmentIndex'] as num?)?.toInt(),
      exceptionClass: details['exceptionClass'] as String?,
      lastErrorStage: stage,
    );
  }
}

/// Grabación local de video, con audio AAC desde la 3.1.3 (Fase B,
/// 3.1.2: video-only).
///
/// Orquesta `startRecording` / `stopRecording` del servicio, escucha
/// los eventos `recording_*`, guarda la fila de la grabación en SQLite
/// (plan §8.1: una fila por grabación, `recording` al iniciar,
/// `ready`/`corrupt` al detener) y clasifica las interrupciones al
/// volver a la pantalla (plan §7.3).
///
/// Audio (3.1.3, plan §6.2 D9): al tocar «GRABAR» se pide el
/// micrófono; concedido → `audioEnabled: true`; denegado → video-only
/// y la UI muestra «SOLO VIDEO». La denegación NO impide grabar: la
/// grabación arranca en ambos casos. Si el audio se pierde en caliente
/// (evento `recording_audio_lost`), el video continúa y el estado de
/// grabación no cambia a error.
///
/// Ciclo de vida (obligatorio, distinto de la vista previa):
/// - Si se sale de la pantalla con una grabación activa, la grabación
///   CONTINÚA por el FGS; al volver, la UI se re-sincroniza
///   consultando el estado al motor (`getRecordingStatus`), nunca se
///   detiene la grabación al salir.
/// - `start` solo desde `idle` o `error`; un duplicado se rechaza sin
///   tocar nada. `stop` desde `idle` es idempotente.
/// - La parada desde la notificación o por desconexión de cámara llega
///   por evento (`recording_stopped` / `recording_error`): la fila se
///   actualiza desde el evento cuando la UI no inició la parada.
class RecordingController extends ChangeNotifier {
  RecordingController({
    required StreamingService service,
    required PermissionController permissions,
    required RecordingRepository recordings,
    required bool Function() isPreviewActive,
    NowMs? nowMs,
    RecordingTimerFactory? createTimer,
  }) : _service = service,
       _permissions = permissions,
       _recordings = recordings,
       _isPreviewActive = isPreviewActive,
       _nowMs = nowMs ?? systemNowMs,
       _createTimer = createTimer ?? Timer.periodic;

  final StreamingService _service;
  final PermissionController _permissions;
  final RecordingRepository _recordings;
  final bool Function() _isPreviewActive;
  final NowMs _nowMs;
  final RecordingTimerFactory _createTimer;

  RecordingState _state = RecordingState.idle;
  RecordingState get state => _state;

  /// `true` mientras hay una grabación en curso (para la guarda de la
  /// vista previa: ni parada por segundo plano ni reinicio por giro).
  bool get isRecordingActive =>
      _state == RecordingState.starting ||
      _state == RecordingState.recording ||
      _state == RecordingState.rotating ||
      _state == RecordingState.stopping;

  RecordingStartedInfo? _startedInfo;
  RecordingStartedInfo? get startedInfo => _startedInfo;

  String? _recordingId;
  String? get recordingId => _recordingId;

  /// Segmentos verificados (en orden), del evento `recording_segment`.
  final List<RecordingSegmentInfo> _segments = <RecordingSegmentInfo>[];
  List<RecordingSegmentInfo> get segments =>
      List<RecordingSegmentInfo>.unmodifiable(_segments);

  /// Resultado de la última grabación detenida (para la confirmación
  /// de la UI: «N segmentos, duración total M:SS»).
  RecordingStoppedInfo? _lastStopped;
  RecordingStoppedInfo? get lastStopped => _lastStopped;

  String? _errorCode;
  String? get errorCode => _errorCode;

  String? _errorMessage;
  String? get errorMessage => _errorMessage;

  /// Estado de audio de la grabación en curso (3.1.3, plan §8.1).
  ///
  /// `audioEnabled` es lo que se pidió al arrancar (del permiso de
  /// micrófono y confirmado por `recording_started`); `audioActive`
  /// es si el audio se está capturando ahora mismo (`false` tras una
  /// pérdida en caliente); `audioReason` es el motivo de video-only
  /// (permiso denegado o motivo nativo). El chip de la pantalla
  /// refleja este estado real, no el permiso.
  bool _audioEnabled = false;
  bool get audioEnabled => _audioEnabled;

  bool _audioActive = false;
  bool get audioActive => _audioActive;

  String? _audioReason;
  String? get audioReason => _audioReason;

  /// Pérdida de audio en caliente (evento `recording_audio_lost`):
  /// motivo, índice del segmento en curso y mensaje del motor. La
  /// grabación sigue: el estado no cambia a error.
  String? _audioLostReason;
  String? get audioLostReason => _audioLostReason;

  int? _audioLostSegmentIndex;
  int? get audioLostSegmentIndex => _audioLostSegmentIndex;

  String? _audioLostMessage;
  String? get audioLostMessage => _audioLostMessage;

  /// Grupo S, S1: claves opcionales del evento `recording_audio_lost`
  /// con motivo `audio_silence_samples` (duración del silencio
  /// detectado, offset desde el ancla hasta la primera muestra
  /// silenciosa y pico medido en la ventana silenciosa). `null` sin
  /// disparo por silencio o sin las claves en el evento: nunca se
  /// inventan valores.
  int? _audioLostSilenceMs;
  int? get audioLostSilenceMs => _audioLostSilenceMs;

  int? _audioLostSilenceStartMs;
  int? get audioLostSilenceStartMs => _audioLostSilenceStartMs;

  int? _audioLostSilencePeak;
  int? get audioLostSilencePeak => _audioLostSilencePeak;

  RecordingDiagnostics? _diagnostics;
  RecordingDiagnostics? get diagnostics => _diagnostics;

  /// Segmentos interrumpidos clasificados en la última recuperación.
  List<RecoveredSegmentInfo> _lastRecovery = const <RecoveredSegmentInfo>[];
  List<RecoveredSegmentInfo> get lastRecovery =>
      List<RecoveredSegmentInfo>.unmodifiable(_lastRecovery);

  /// Instante (reloj de pared) en que empezó la grabación, para el
  /// tiempo transcurrido.
  int? _startedAtMs;

  /// Instante en que empezó el segmento actual, para su duración.
  int? _segmentStartedAtMs;

  /// Índice nativo (0-based) del segmento en curso, del motor
  /// (`getRecordingStatus` / eventos). `null` si se desconoce.
  /// (Fix duraciones 3.1.2, paso 3: la tarjeta mezclaba el conteo
  /// de segmentos con el índice nativo + 1 del indicador.)
  int? _nativeSegmentIndex;
  int? get nativeSegmentIndex => _nativeSegmentIndex;

  /// Duración nativa (PTS, ms) del segmento en curso. `null` si se
  /// desconoce. Se muestra junto a la de Dart para compararlas.
  int? _nativeSegmentDurationMs;
  int? get nativeSegmentDurationMs => _nativeSegmentDurationMs;

  /// Número de segmento actual para mostrar (1-based), o `null` si
  /// se desconoce. Unifica tarjeta e indicador: antes la tarjeta
  /// mostraba el conteo y el indicador `last.index + 1`.
  int? get currentSegmentDisplay =>
      _nativeSegmentIndex == null ? null : _nativeSegmentIndex! + 1;

  /// Tiempo transcurrido desde el inicio (ms). 0 sin grabación.
  int get elapsedMs {
    final int? started = _startedAtMs;
    if (started == null) {
      return 0;
    }
    final int elapsed = _nowMs() - started;
    return elapsed < 0 ? 0 : elapsed;
  }

  /// Duración del segmento en curso (ms). 0 sin grabación.
  int get segmentDurationMs {
    final int? started = _segmentStartedAtMs;
    if (started == null) {
      return 0;
    }
    final int duration = _nowMs() - started;
    return duration < 0 ? 0 : duration;
  }

  /// Texto del bloque de diagnóstico que muestra el Estudio: `null`
  /// cuando no hay ninguna grabación que mostrar.
  String? get diagnosticText {
    if (_state == RecordingState.idle && _lastStopped == null) {
      return null;
    }
    final RecordingDiagnostics diag =
        _diagnostics ?? const RecordingDiagnostics();
    final StringBuffer text = StringBuffer('Diagnóstico de grabación');
    text.writeln();
    text.writeln('Estado: ${_state.name}');
    text.writeln('Grabación: ${_recordingId ?? diag.recordingId ?? '—'}');
    text.writeln(
      'Segmento actual: ${currentSegmentDisplay?.toString() ?? '—'}',
    );
    text.writeln(
      'Duración del segmento: ${_formatMs(segmentDurationMs)} '
      '(nativa: ${_nativeSegmentDurationMs == null ? '—' : _formatMs(_nativeSegmentDurationMs!)})',
    );
    if (_errorCode != null) {
      text.writeln('Código de error: $_errorCode');
    }
    text.writeln('Mensaje técnico: ${_errorMessage ?? '—'}');
    text.writeln('Codificador: ${diag.encoderName ?? '—'}');
    text.writeln(
      'Perfil: ${diag.profile?.toString() ?? '—'}',
    );
    text.writeln(
      'Bitrate: ${diag.bitrateBps?.toString() ?? '—'} bps',
    );
    text.writeln(
      'FPS esperado: ${diag.fps?.toString() ?? '—'}',
    );
    text.writeln(
      'CBR: ${diag.cbrSupported == null ? '—' : diag.cbrSupported! ? 'sí' : 'no'}',
    );
    text.writeln(
      'Poco espacio: ${diag.lowStorageWarning == null ? '—' : diag.lowStorageWarning! ? 'sí' : 'no'}',
    );
    // Audio (3.1.3, plan §8.2): cabecera con/sin audio, frecuencia,
    // canales y motivo de video-only; y la pérdida en caliente, si la
    // hubo. Se muestra también tras la parada: describe la última
    // grabación.
    text.writeln('Audio: ${_audioHeader()}');
    // Instrumentación de audio 3.1.3: el origen de timestamps del
    // teléfono decide si un offset A/V es sospechoso (H2). `—` sin
    // el evento `recording_started` todavía.
    text.writeln(
      'Origen timestamps: ${_startedInfo?.timestampSource ?? '—'}',
    );
    if (_audioLostReason != null) {
      text.writeln(_audioLostLine());
    }
    text.writeln(
      'Clase de excepción: ${diag.exceptionClass ?? '—'}',
    );
    text.writeln('Última etapa con error: ${diag.lastErrorStage ?? '—'}');
    if (_lastRecovery.isNotEmpty) {
      text.writeln(
        'Recuperados: ${_lastRecovery.length} segmento(s) interrumpido(s)',
      );
    }
    // Segmentos verificados, uno por línea, con su estado, su duración
    // medida y su motivo (fix duraciones 3.1.2, paso 1: sin esto, el
    // motivo de `corrupt` es invisible en el teléfono). Y el resumen
    // de la última parada (del evento o del método, ya completo).
    // Ronda 3: la línea añade el FPS medido y las muestras, para
    // decidir la tolerancia con evidencia en el teléfono.
    // Ronda 4, fix 3: la línea añade el recuento del verificador
    // (`verif=`, distinto del `muestras=` del drenado) y la duración
    // de la fase 1 (`fase=`, en segundos con un decimal).
    // 3.1.3: la línea añade el audio (`audio=<duración>ms av=<offset>ms`
    // cuando hay pista AAC, `audio=no` en video-only).
    final List<String> tail = <String>[];
    tail.add('Segmentos verificados: ${_segments.length}');
    if (_segments.isNotEmpty) {
      tail.add('Segmentos:');
      for (final RecordingSegmentInfo segment in _segments) {
        tail.add(
          'seg${segment.index}: ${segment.status} '
          '${segment.durationMs?.toString() ?? '—'}ms '
          'motivo=${segment.reason ?? '—'} '
          'cerradoLimpio=${segment.closedCleanly ? 'sí' : 'no'} '
          'fps=${segment.fpsMeasured?.toStringAsFixed(1) ?? '—'} '
          'muestras=${segment.sampleCount?.toString() ?? '—'} '
          'verif=${segment.verifiedSampleCount?.toString() ?? '—'} '
          'fase=${segment.ioPhaseMs == null ? '—' : '${(segment.ioPhaseMs! / 1000).toStringAsFixed(1)}s'}'
          '${_segmentAudioSuffix(segment)}',
        );
      }
    }
    if (_lastStopped != null) {
      // Ronda 3 (grupo I): estado del micrófono y contadores del
      // lector al parar. `estadoMic` es `getRecordingState()` nativo
      // (3 = RECORDING, 1 = STOPPED; otro/`null` → `—`) y
      // `ultimaLecturaOk` la edad de la última lectura `> 0` en
      // segundos con un decimal (`—` si nunca hubo). Grupo V:
      // `lectorSinDatos2s` es el flag del watchdog (V3) y
      // `eosTimeout` si la espera de EOS agotó los 5 s (V4).
      // Grupo S (S0): `picoMin1s`/`noCeroMin1s` es el segundo más
      // silencioso de la grabación (`—` si aún no se completó
      // ningún segundo). Grupo S (S1): `silencioPico` es el pico
      // medido en la ventana silenciosa que disparó el detector
      // (`—` si no hubo disparo).
      tail.add(
        'Última parada: estado=${_lastStopped!.status} '
        'duración total=${_lastStopped!.totalDurationMs}ms '
        'segmentos=${_lastStopped!.segments.length} '
        'descartadasEntradaAAC=${_lastStopped!.droppedInputBuffers} '
        'capacidadEntradaAAC=${_lastStopped!.inputBufferCapacityBytes?.toString() ?? '—'} bytes '
        'estadoMic=${_micStateLabel(_lastStopped!.micStateAtStart)}/'
        '${_micStateLabel(_lastStopped!.micStateAtStop)} '
        'lecturas=${_lastStopped!.audioReadCount} '
        'ultimoRead=${_lastStopped!.audioLastReadCode?.toString() ?? '—'} '
        'ultimaLecturaOk=${_lastStopped!.audioLastReadOkAgeMs == null ? '—' : 'hace ${(_lastStopped!.audioLastReadOkAgeMs! / 1000).toStringAsFixed(1)}s'} '
        'lectorVivo=${_lastStopped!.audioReaderAlive ? 'sí' : 'no'} '
        'audioEos=${_lastStopped!.audioEos ? 'sí' : 'no'} '
        'lectorSinDatos2s=${_lastStopped!.audioReaderNoData2s ? 'sí' : 'no'} '
        'eosTimeout=${_lastStopped!.eosTimedOut ? 'sí' : 'no'} '
        'picoMin1s=${_lastStopped!.quietestSecondPeak?.toString() ?? '—'} '
        'noCeroMin1s=${_lastStopped!.quietestSecondNonZero?.toString() ?? '—'} '
        'silencioPico=${_lastStopped!.silencePeak?.toString() ?? '—'}',
      );
    }
    for (int i = 0; i < tail.length; i++) {
      if (i == tail.length - 1) {
        text.write(tail[i]);
      } else {
        text.writeln(tail[i]);
      }
    }
    return text.toString();
  }

  String _formatMs(int ms) {
    final int totalSeconds = ms ~/ 1000;
    final int minutes = totalSeconds ~/ 60;
    final int seconds = totalSeconds % 60;
    return '${minutes.toString().padLeft(2, '0')}:'
        '${seconds.toString().padLeft(2, '0')}';
  }

  /// Etiqueta del estado del micrófono (ronda 3, grupo I):
  /// `getRecordingState()` nativo (3 = `RECORDING`, 1 = `STOPPED`);
  /// cualquier otro valor o `null` es `—`.
  String _micStateLabel(int? state) {
    return switch (state) {
      3 => 'RECORDING',
      1 => 'STOPPED',
      _ => '—',
    };
  }

  /// Línea de pérdida de audio en caliente (Grupo S, S1): con motivo
  /// `audio_silence_samples` y offset conocido, dice desde cuándo
  /// (offset desde el ancla), cuánto duró y con qué pico; sin esas
  /// claves, la línea queda como antes (motivo + mensaje si lo hay).
  /// Los demás motivos no cambian de formato.
  String _audioLostLine() {
    final String segment = _audioLostSegmentIndex?.toString() ?? '—';
    if (_audioLostReason == 'audio_silence_samples' &&
        _audioLostSilenceStartMs != null) {
      final String seconds = _audioLostSilenceMs == null
          ? '—'
          : '${_audioLostSilenceMs! ~/ 1000}';
      final String peak = _audioLostSilencePeak?.toString() ?? '—';
      return 'Audio perdido en seg $segment: audio_silence_samples '
          '— silencio desde ${_audioLostSilenceStartMs}ms '
          '($seconds s, pico=$peak)';
    }
    return 'Audio perdido en seg $segment: $_audioLostReason'
        '${_audioLostMessage == null || _audioLostMessage!.isEmpty ? '' : ' — $_audioLostMessage'}';
  }

  /// Cabecera de audio del diagnóstico (3.1.3, plan §8.2):
  /// `sí (48 kHz mono)` con audio, `no (<motivo>)` en video-only.
  /// Sin el evento `recording_started` todavía, solo `sí`/`no`: la
  /// frecuencia y los canales los confirma el motor.
  String _audioHeader() {
    if (!_audioEnabled) {
      return _audioReason == null ? 'no' : 'no ($_audioReason)';
    }
    final RecordingStartedInfo? info = _startedInfo;
    final int? rate = info?.audioSampleRate;
    final int? channels = info?.audioChannels;
    if (rate == null || channels == null) {
      return 'sí';
    }
    final String rateText =
        rate % 1000 == 0 ? '${rate ~/ 1000} kHz' : '$rate Hz';
    final String channelsText = switch (channels) {
      1 => 'mono',
      2 => 'estéreo',
      _ => '$channels canales',
    };
    return 'sí ($rateText $channelsText)';
  }

  /// Sufijo de audio de una línea de segmento (3.1.3, plan §8.2):
  /// duración de audio y offset A/V cuando hay pista AAC, `audio=no`
  /// en video-only (incluidos los segmentos de 3.1.2, sin claves).
  /// Instrumentación de audio 3.1.3: `inicioAudio` es el offset de la
  /// primera muestra de audio de la sesión (`null` = ninguna muestra
  /// llegó, H1) y `huecosAudio` las muestras descartadas; `av=` sigue
  /// siendo el offset del verificador. Un `null` se muestra como `—`.
  String _segmentAudioSuffix(RecordingSegmentInfo segment) {
    final String inicioAudio =
        segment.audioStartOffsetMs?.toString() ?? '—';
    final String huecosAudio = segment.audioGapSamples.toString();
    // Grupo V (V2): muestras AAC leídas del segmento (`—` si el
    // motor no las envía, como en los segmentos de 3.1.2).
    final String muestrasAudio =
        segment.audioSampleCount?.toString() ?? '—';
    // Grupo S (S1): motivo de audio del segmento, cuando lo hay
    // (p. ej. `audio_silence_samples`); sin él, el sufijo es
    // idéntico a antes.
    final String motivoAudio = segment.audioReason == null
        ? ''
        : ' motivoAudio=${segment.audioReason}';
    if (!segment.hasAudio) {
      return ' audio=no inicioAudio=$inicioAudio huecosAudio=$huecosAudio '
          'muestrasAudio=$muestrasAudio$motivoAudio';
    }
    return ' audio=${segment.audioDurationMs?.toString() ?? '—'}ms '
        'av=${segment.avOffsetMs?.toString() ?? '—'}ms '
        'inicioAudio=$inicioAudio huecosAudio=$huecosAudio '
        'muestrasAudio=$muestrasAudio$motivoAudio';
  }

  StreamSubscription<Map<String, Object?>>? _eventsSubscription;
  Timer? _timer;
  int _attachedObservers = 0;

  /// La parada la inició la UI (`stop()`): el evento `recording_stopped`
  /// no actualiza la fila (ya lo hace `stop()` con el resultado del
  /// método). Sin este flag, una parada externa (notificación, cámara)
  /// dejaría la fila en `recording` para siempre.
  bool _stopInitiated = false;

  void attach() {
    _attachedObservers++;
    if (_attachedObservers == 1) {
      _eventsSubscription = _service.events.listen(_onEvent);
      // Re-sincronización al volver: el motor puede estar grabando
      // aunque esta UI no lo sepa (la grabación continuó por el FGS).
      unawaited(_resync());
      unawaited(_recoverInterrupted());
    }
  }

  void detach() {
    if (_attachedObservers == 0) {
      return;
    }
    _attachedObservers--;
    if (_attachedObservers == 0) {
      unawaited(_eventsSubscription?.cancel());
      _eventsSubscription = null;
      // Sin UI no hay nada que repintar: el temporizador se para
      // (el tiempo se recalcula desde `_startedAtMs` al volver y la
      // re-sincronización lo vuelve a arrancar si se sigue grabando).
      _stopTimer();
    }
  }

  /// Consulta el estado al motor y restaura el estado de la UI si el
  /// motor está grabando y esta UI no lo sabía. Nunca lanza. El
  /// temporizador se asegura mientras el motor graba (también cuando
  /// la UI ya sabía que grababa y vuelve tras un `detach`).
  Future<void> _resync() async {
    RecordingStatusSnapshot snapshot;
    try {
      snapshot = await _service.getRecordingStatus();
    } on StreamingException {
      return;
    }
    final RecordingState native = RecordingState.fromWireName(
      snapshot.state,
    );
    if (native == RecordingState.idle) {
      return;
    }
    // Índice y duración nativos del snapshot (paso 3): la UI no
    // sabía que grababa (o volvió tras un `detach`).
    _nativeSegmentIndex = snapshot.segmentIndex;
    _nativeSegmentDurationMs = snapshot.segmentDurationMs;
    // Estado de audio del snapshot (3.1.3, plan §8.1): la UI lo
    // recupera al volver sin haber visto los eventos. El motivo
    // nativo manda cuando lo hay; si el motor no dice nada y Dart ya
    // sabía por qué no hay audio (permiso denegado), se conserva.
    // Con audio activo no hay motivo de video-only.
    _audioEnabled = snapshot.audioEnabled;
    _audioActive = snapshot.audioActive;
    if (snapshot.audioEnabled) {
      _audioReason = null;
      if (!snapshot.audioActive) {
        // Pérdida en caliente mientras la UI no miraba: el motivo del
        // snapshot es el de la pérdida (Kotlin: `audioLostReason`).
        _audioLostReason = snapshot.audioReason;
      }
    } else {
      _audioReason = snapshot.audioReason ?? _audioReason;
    }
    // Segmentos verificados del snapshot (paso 6): al volver a la
    // pantalla tras un `detach`, los eventos se perdieron. Solo los
    // de la grabación que el motor está grabando.
    if (snapshot.recordingId == null ||
        _recordingId == null ||
        snapshot.recordingId == _recordingId) {
      _mergeSegments(snapshot.segments);
    }
    if (_state == RecordingState.idle || _state == RecordingState.error) {
      _recordingId = snapshot.recordingId;
      _startedAtMs = _nowMs() - snapshot.elapsedMs;
      _segmentStartedAtMs = _nowMs() - snapshot.segmentDurationMs;
      _state = native;
      notifyListeners();
    }
    _startTimer();
  }

  /// Clasifica los `.tmp.mp4` que quedaron de una muerte del proceso
  /// y actualiza sus filas (`ready` si hay algún segmento utilizable,
  /// `corrupt` si no). Nunca lanza ni borra nada.
  Future<void> _recoverInterrupted() async {
    List<RecoveredSegmentInfo> recovered;
    try {
      recovered = await _service.recoverInterruptedRecordings();
    } on StreamingException {
      return;
    }
    if (recovered.isEmpty) {
      return;
    }
    _lastRecovery = recovered;
    final Map<String, List<RecoveredSegmentInfo>> byRecording =
        <String, List<RecoveredSegmentInfo>>{};
    for (final RecoveredSegmentInfo segment in recovered) {
      byRecording
          .putIfAbsent(segment.recordingId, () => <RecoveredSegmentInfo>[])
          .add(segment);
    }
    for (final MapEntry<String, List<RecoveredSegmentInfo>> entry
        in byRecording.entries) {
      final bool usable = entry.value.any((s) => s.usable);
      final int duration = entry.value
          .where((s) => s.usable)
          .fold<int>(0, (sum, s) => sum + (s.durationMs ?? 0));
      final int size = entry.value
          .where((s) => s.usable)
          .fold<int>(0, (sum, s) => sum + s.sizeBytes);
      try {
        await _recordings.updateOnStop(
          recordingId: entry.key,
          status: usable ? 'ready' : 'corrupt',
          durationMs: duration,
          sizeBytes: size,
          nowMs: _nowMs(),
        );
      } on Object {
        // Mejor esfuerzo: la clasificación ya está en el evento y la
        // fila se puede corregir al reabrir.
      }
    }
    notifyListeners();
  }

  /// Inicia la grabación (con audio si el micrófono está concedido).
  /// Solo desde `idle` o `error`; exige la vista previa activa
  /// (plan §6.2). Al tocar «GRABAR» se pide el micrófono (plan §6.2
  /// D9): concedido → `audioEnabled: true`; denegado → video-only.
  /// La denegación NO impide grabar: la grabación arranca en ambos
  /// casos. El permiso de notificaciones se pide con el motivo
  /// (plan §9.1) pero la grabación no depende de él: si se deniega,
  /// el servicio arranca igual.
  Future<void> start({int? matchId}) async {
    if (_state != RecordingState.idle && _state != RecordingState.error) {
      return;
    }
    if (!_isPreviewActive()) {
      _state = RecordingState.error;
      _errorCode = StreamContract.cameraUnavailable;
      _errorMessage = 'Inicia la vista previa antes de grabar.';
      _diagnostics = const RecordingDiagnostics(
        stage: 'startRecording',
        lastErrorStage: 'startRecording',
      );
      notifyListeners();
      return;
    }
    _state = RecordingState.starting;
    _errorCode = null;
    _errorMessage = null;
    _diagnostics = null;
    _lastStopped = null;
    _segments.clear();
    // Estado de audio a cero: una segunda grabación no hereda el de
    // la anterior (igual que los relojes, fix duraciones 3.1.2,
    // paso 2). El evento `recording_started` lo confirma después.
    _startedInfo = null;
    _audioEnabled = false;
    _audioActive = false;
    _audioReason = null;
    _audioLostReason = null;
    _audioLostSegmentIndex = null;
    _audioLostMessage = null;
    _audioLostSilenceMs = null;
    _audioLostSilenceStartMs = null;
    _audioLostSilencePeak = null;
    // Relojes a cero: una segunda grabación en la misma sesión no debe
    // heredar el tiempo de la anterior (fix duraciones 3.1.2, paso 2:
    // la base nunca se limpiaba y el contador corría en vista previa y
    // en `idle`). Se fijan de nuevo al confirmarse el inicio.
    _startedAtMs = null;
    _segmentStartedAtMs = null;
    // El primer segmento es el nativo 0 (paso 3).
    _nativeSegmentIndex = 0;
    _nativeSegmentDurationMs = 0;
    notifyListeners();
    // Micrófono (3.1.3, D9): si ya está concedido o denegado de
    // forma permanente, `request` no muestra ningún diálogo y
    // devuelve el estado actual. Si la solicitud falla, se graba
    // sin audio: el video es la prioridad.
    bool audioEnabled = false;
    PermissionStatus? micStatus;
    try {
      micStatus = await _permissions.request(PermissionKind.microphone);
    } on Object {
      micStatus = null;
    }
    audioEnabled = micStatus == PermissionStatus.granted;
    if (micStatus == PermissionStatus.denied ||
        micStatus == PermissionStatus.permanentlyDenied) {
      // Motivo de video-only que conoce Dart: el motor no sabe por
      // qué se le pidió sin audio. `notRequested` (sin respuesta del
      // diálogo) no es una denegación: no se inventa el motivo.
      _audioReason = StreamContract.permissionDenied;
    }
    _audioEnabled = audioEnabled;
    _audioActive = audioEnabled;
    // Notificaciones: mejor esfuerzo (la grabación no depende de
    // este permiso; sin él la notificación solo se ve en el
    // Administrador de tareas).
    try {
      await _permissions.request(PermissionKind.notifications);
    } on Object {
      // Se sigue grabando sin el permiso.
    }
    try {
      final String id = await _service.startRecording(
        options: RecordingOptions(
          matchId: matchId,
          resolution: '720p',
          fps: 30,
          videoBitrateBps: 2500000,
          audioEnabled: audioEnabled,
        ),
      );
      _recordingId = id;
      // La fila se crea al iniciar (plan §8.1). Si la base falla, la
      // grabación sigue: se registra en el diagnóstico y la fila se
      // puede reconstruir desde los archivos (§5.3).
      try {
        await _recordings.insertRecording(
          recordingId: id,
          matchId: matchId,
          resolution: '720p',
          fps: 30,
          videoBitrateBps: 2500000,
          nowMs: _nowMs(),
        );
      } on Object catch (e) {
        _diagnostics = RecordingDiagnostics(
          stage: 'startRecording',
          recordingId: id,
          exceptionClass: e.runtimeType.toString(),
        );
      }
      if (_state == RecordingState.starting) {
        // El evento `recording_started` puede llegar después del
        // resultado del método; el estado ya es `recording`.
        _state = RecordingState.recording;
        _startedAtMs ??= _nowMs();
        _segmentStartedAtMs ??= _nowMs();
        _startTimer();
      }
      notifyListeners();
    } on StreamingException catch (e) {
      _errorCode = e.code;
      _errorMessage = e.message;
      _diagnostics = RecordingDiagnostics.fromDetails(e.details);
      _state = RecordingState.error;
      notifyListeners();
    }
  }

  /// Detiene la grabación y actualiza su fila (`ready` si hay al menos
  /// un segmento utilizable, `corrupt` si no, plan §7.3). Idempotente
  /// desde `idle`.
  Future<void> stop() async {
    if (_state == RecordingState.idle ||
        _state == RecordingState.stopping) {
      return;
    }
    _state = RecordingState.stopping;
    _stopInitiated = true;
    notifyListeners();
    try {
      final RecordingInfo info = await _service.stopRecording();
      await _updateRowFromInfo(info);
      // La lista completa viene del método (paso 5): la confirmación
      // y la tarjeta usan estos segmentos, no la lista parcial de
      // Dart (que puede haber perdido eventos).
      _lastStopped = RecordingStoppedInfo(
        recordingId: info.recordingId,
        segments: List<RecordingSegmentInfo>.unmodifiable(info.segments),
        totalDurationMs: info.durationMs,
        totalSizeBytes: info.sizeBytes,
        status: info.status,
        audioEnabled: info.audioEnabled,
        droppedInputBuffers: info.droppedInputBuffers,
        inputBufferCapacityBytes: info.inputBufferCapacityBytes,
        micStateAtStart: info.micStateAtStart,
        micStateAtStop: info.micStateAtStop,
        audioReadCount: info.audioReadCount,
        audioLastReadCode: info.audioLastReadCode,
        audioLastReadOkAgeMs: info.audioLastReadOkAgeMs,
        audioReaderAlive: info.audioReaderAlive,
        audioEos: info.audioEos,
        audioReaderNoData2s: info.audioReaderNoData2s,
        eosTimedOut: info.eosTimedOut,
        quietestSecondPeak: info.quietestSecondPeak,
        quietestSecondNonZero: info.quietestSecondNonZero,
        silencePeak: info.silencePeak,
      );
      _applyStoppedSegments(_lastStopped!);
      _state = RecordingState.idle;
      // La captura terminó: el audio ya no está activo, pero el
      // estado (`audioEnabled`, motivo) se conserva para el
      // diagnóstico de la última grabación.
      _audioActive = false;
      // Relojes a cero al llegar a `idle`: el diagnóstico en `idle`
      // no debe seguir contando (fix duraciones 3.1.2, paso 2).
      _startedAtMs = null;
      _segmentStartedAtMs = null;
      _stopTimer();
      notifyListeners();
    } on StreamingException catch (e) {
      _errorCode = e.code;
      _errorMessage = e.message;
      _diagnostics = RecordingDiagnostics.fromDetails(e.details);
      _state = RecordingState.error;
      _stopTimer();
      notifyListeners();
    } finally {
      _stopInitiated = false;
    }
  }

  /// Actualiza la fila de la grabación con el resultado de la parada.
  /// `partial` es utilizable y la fila queda `ready` (el `CHECK` de la
  /// tabla no admite `partial`).
  Future<void> _updateRowFromInfo(RecordingInfo info) async {
    if (info.status == 'idle' || info.recordingId.isEmpty) {
      return;
    }
    final String status = info.status == 'corrupt' ? 'corrupt' : 'ready';
    try {
      await _recordings.updateOnStop(
        recordingId: info.recordingId,
        status: status,
        durationMs: info.durationMs,
        sizeBytes: info.sizeBytes,
        nowMs: _nowMs(),
      );
    } on Object {
      // Mejor esfuerzo: los archivos ya están verificados y la fila se
      // puede corregir al reabrir (la recuperación la clasifica).
    }
  }

  void _startTimer() {
    _timer?.cancel();
    _timer = _createTimer(const Duration(seconds: 1), (_) {
      notifyListeners();
    });
  }

  void _stopTimer() {
    _timer?.cancel();
    _timer = null;
  }

  /// Fusiona segmentos verificados en `_segments` por índice (los ya
  /// conocidos se actualizan, los nuevos se añaden en orden). La lista
  /// de Dart puede haber perdido eventos; la del motor es completa.
  void _mergeSegments(Iterable<RecordingSegmentInfo> incoming) {
    bool changed = false;
    for (final RecordingSegmentInfo segment in incoming) {
      final int existing = _segments.indexWhere(
        (s) => s.index == segment.index,
      );
      if (existing >= 0) {
        _segments[existing] = segment;
      } else {
        _segments.add(segment);
        changed = true;
      }
    }
    if (changed) {
      _segments.sort((a, b) => a.index.compareTo(b.index));
    }
  }

  /// Sincroniza `_segments` y el índice nativo con el resultado
  /// completo de una parada (paso 5): la lista del motor es completa,
  /// la de Dart puede haber perdido eventos.
  void _applyStoppedSegments(RecordingStoppedInfo stopped) {
    _segments
      ..clear()
      ..addAll(stopped.segments);
    _applyStoppedNative(stopped);
  }

  /// Índice y duración nativos del último segmento verificado, desde
  /// el resultado completo de una parada (paso 3). Sin segmentos, el
  /// índice es desconocido.
  void _applyStoppedNative(RecordingStoppedInfo stopped) {
    if (stopped.segments.isNotEmpty) {
      _nativeSegmentIndex = stopped.segments.last.index;
      _nativeSegmentDurationMs = stopped.segments.last.durationMs;
    } else {
      _nativeSegmentIndex = null;
      _nativeSegmentDurationMs = null;
    }
  }

  void _onEvent(Map<String, Object?> event) {
    final String? type = event['type'] as String?;
    switch (type) {
      case StreamContract.eventRecordingStarted:
        final RecordingStartedInfo info =
            RecordingStartedInfo.fromMap(event);
        _recordingId = info.recordingId;
        _startedInfo = info;
        _diagnostics = RecordingDiagnostics.fromStarted(info);
        // Audio real de la grabación (3.1.3): el evento manda sobre
        // lo pedido. Si el motor degradó a video-only, el motivo
        // nativo manda; si no dice nada y Dart ya sabía por qué no
        // hay audio (permiso denegado), se conserva. Con audio no
        // hay motivo de video-only.
        _audioEnabled = info.audioEnabled;
        _audioActive = info.audioEnabled;
        _audioReason = info.audioEnabled
            ? null
            : (info.audioReason ?? _audioReason);
        // Asignación directa (no `??=`): el inicio de una grabación
        // fija su propia base; si la base anterior no se limpió, no
        // debe reutilizarse (fix duraciones 3.1.2, paso 2).
        _startedAtMs = _nowMs();
        _segmentStartedAtMs = _nowMs();
        if (_state == RecordingState.starting ||
            _state == RecordingState.idle) {
          _state = RecordingState.recording;
          _startTimer();
        }
        notifyListeners();
      case StreamContract.eventRecordingSegment:
        final RecordingSegmentInfo segment =
            RecordingSegmentInfo.fromMap(event);
        _mergeSegments(<RecordingSegmentInfo>[segment]);
        // Un segmento nuevo empieza ahora (rotación por keyframe).
        _segmentStartedAtMs = _nowMs();
        // Índice nativo del segmento en curso (paso 3): el evento
        // avisa de que el segmento `index` se cerró; si la parada
        // está en curso, ese era el último; si no, el siguiente
        // (index + 1) ya está grabando.
        if (_state == RecordingState.stopping) {
          _nativeSegmentIndex = segment.index;
          _nativeSegmentDurationMs = segment.durationMs;
        } else {
          _nativeSegmentIndex = segment.index + 1;
          _nativeSegmentDurationMs = 0;
        }
        if (_state == RecordingState.rotating) {
          _state = RecordingState.recording;
        }
        notifyListeners();
      case StreamContract.eventRecordingStopped:
        final RecordingStoppedInfo stopped =
            RecordingStoppedInfo.fromMap(event);
        _lastStopped = stopped;
        _applyStoppedSegments(stopped);
        if (!_stopInitiated) {
          // Parada externa (notificación, cámara): la fila la
          // actualiza el evento, no `stop()`.
          unawaited(
            _updateRowFromInfo(
              RecordingInfo(
                recordingId: stopped.recordingId,
                filePath: stopped.filePath,
                durationMs: stopped.totalDurationMs,
                sizeBytes: stopped.totalSizeBytes,
                status: stopped.status,
              ),
            ),
          );
        }
        _state = RecordingState.idle;
        // La captura terminó: el audio ya no está activo, pero el
        // estado se conserva para el diagnóstico de la última
        // grabación.
        _audioActive = false;
        // Relojes a cero al llegar a `idle` (paso 2; igual que en
        // `stop()`: la grabación terminó y el diagnóstico no debe
        // seguir contando).
        _startedAtMs = null;
        _segmentStartedAtMs = null;
        _stopTimer();
        notifyListeners();
      case StreamContract.eventRecordingAudioLost:
        // Pérdida de audio en caliente (3.1.3, D7): el video
        // continúa y el estado de grabación NO cambia a error. Solo
        // se actualiza el estado de audio y el diagnóstico. Grupo S
        // (S1): con motivo `audio_silence_samples` el evento lleva
        // las claves opcionales del silencio detectado (lecturas
        // nulas-seguras: sin ellas, `null`).
        _audioActive = false;
        _audioLostReason = event['reason'] as String?;
        _audioLostSegmentIndex =
            (event['segmentIndex'] as num?)?.toInt();
        _audioLostMessage = event['message'] as String?;
        _audioLostSilenceMs = (event['silenceMs'] as num?)?.toInt();
        _audioLostSilenceStartMs =
            (event['silenceStartOffsetMs'] as num?)?.toInt();
        _audioLostSilencePeak = (event['silencePeak'] as num?)?.toInt();
        notifyListeners();
      case StreamContract.eventRecordingError:
        _errorCode =
            event['code'] as String? ?? event['errorCode'] as String?;
        _errorMessage = event['message'] as String?;
        _diagnostics = RecordingDiagnostics.fromDetails(
          event['details'] as Map<String, Object?>?,
        );
        // Al detener con error el evento trae el resultado completo.
        if (event['segments'] is List) {
          final RecordingStoppedInfo stopped =
              RecordingStoppedInfo.fromMap(event);
          _lastStopped = stopped;
          _applyStoppedSegments(stopped);
          if (!_stopInitiated) {
            unawaited(
              _updateRowFromInfo(
                RecordingInfo(
                  recordingId: stopped.recordingId,
                  filePath: stopped.filePath,
                  durationMs: stopped.totalDurationMs,
                  sizeBytes: stopped.totalSizeBytes,
                  status: stopped.status,
                ),
              ),
            );
          }
        }
        _state = RecordingState.error;
        // Relojes a cero: la grabación terminó con error (la parada
        // con error emite este evento y detiene el motor); el
        // diagnóstico no debe seguir contando (paso 2).
        _startedAtMs = null;
        _segmentStartedAtMs = null;
        _stopTimer();
        notifyListeners();
    }
  }

  @override
  void dispose() {
    _stopTimer();
    unawaited(_eventsSubscription?.cancel());
    super.dispose();
  }
}
