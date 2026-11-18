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

  factory RecordingDiagnostics.fromStarted(RecordingStartedInfo info) {
    return RecordingDiagnostics(
      stage: 'recording',
      recordingId: info.recordingId,
      encoderName: info.encoderName,
      profile: info.profile,
      bitrateBps: info.bitrateBps,
      cbrSupported: info.cbrSupported,
      lowStorageWarning: info.lowStorageWarning,
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

/// Grabación local de video sin audio (Fase B, 3.1.2).
///
/// Orquesta `startRecording` / `stopRecording` del servicio, escucha
/// los eventos `recording_*`, guarda la fila de la grabación en SQLite
/// (plan §8.1: una fila por grabación, `recording` al iniciar,
/// `ready`/`corrupt` al detener) y clasifica las interrupciones al
/// volver a la pantalla (plan §7.3).
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
    text.writeln('Segmento actual: ${_segments.length}');
    text.writeln('Duración del segmento: ${_formatMs(segmentDurationMs)}');
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
      'CBR: ${diag.cbrSupported == null ? '—' : diag.cbrSupported! ? 'sí' : 'no'}',
    );
    text.writeln(
      'Poco espacio: ${diag.lowStorageWarning == null ? '—' : diag.lowStorageWarning! ? 'sí' : 'no'}',
    );
    text.writeln(
      'Clase de excepción: ${diag.exceptionClass ?? '—'}',
    );
    text.writeln('Última etapa con error: ${diag.lastErrorStage ?? '—'}');
    if (_lastRecovery.isNotEmpty) {
      text.writeln(
        'Recuperados: ${_lastRecovery.length} segmento(s) interrumpido(s)',
      );
    }
    text.write('Segmentos verificados: ${_segments.length}');
    return text.toString();
  }

  String _formatMs(int ms) {
    final int totalSeconds = ms ~/ 1000;
    final int minutes = totalSeconds ~/ 60;
    final int seconds = totalSeconds % 60;
    return '${minutes.toString().padLeft(2, '0')}:'
        '${seconds.toString().padLeft(2, '0')}';
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

  /// Inicia la grabación (sin audio). Solo desde `idle` o `error`;
  /// exige la vista previa activa (plan §6.2). El permiso de
  /// notificaciones se pide con el motivo (plan §9.1) pero la
  /// grabación no depende de él: si se deniega, el servicio arranca
  /// igual.
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
    notifyListeners();
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
          audioEnabled: false,
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
      _lastStopped = RecordingStoppedInfo(
        recordingId: info.recordingId,
        segments: List<RecordingSegmentInfo>.unmodifiable(_segments),
        totalDurationMs: info.durationMs,
        totalSizeBytes: info.sizeBytes,
        status: info.status,
      );
      _state = RecordingState.idle;
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

  void _onEvent(Map<String, Object?> event) {
    final String? type = event['type'] as String?;
    switch (type) {
      case StreamContract.eventRecordingStarted:
        final RecordingStartedInfo info =
            RecordingStartedInfo.fromMap(event);
        _recordingId = info.recordingId;
        _startedInfo = info;
        _diagnostics = RecordingDiagnostics.fromStarted(info);
        _startedAtMs ??= _nowMs();
        _segmentStartedAtMs ??= _nowMs();
        if (_state == RecordingState.starting ||
            _state == RecordingState.idle) {
          _state = RecordingState.recording;
          _startTimer();
        }
        notifyListeners();
      case StreamContract.eventRecordingSegment:
        final RecordingSegmentInfo segment =
            RecordingSegmentInfo.fromMap(event);
        final int existing = _segments.indexWhere(
          (s) => s.index == segment.index,
        );
        if (existing >= 0) {
          _segments[existing] = segment;
        } else {
          _segments.add(segment);
          _segments.sort((a, b) => a.index.compareTo(b.index));
        }
        // Un segmento nuevo empieza ahora (rotación por keyframe).
        _segmentStartedAtMs = _nowMs();
        if (_state == RecordingState.rotating) {
          _state = RecordingState.recording;
        }
        notifyListeners();
      case StreamContract.eventRecordingStopped:
        final RecordingStoppedInfo stopped =
            RecordingStoppedInfo.fromMap(event);
        _lastStopped = stopped;
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
        _stopTimer();
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
