import 'dart:async';

import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/data/recording_repository.dart';
import 'package:transmiti2/features/streaming/data/stream_destination_repository.dart';
import 'package:transmiti2/features/streaming/domain/permission_state.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';
import 'package:transmiti2/features/streaming/domain/stream_destination.dart';

/// Servicio de transmisión falso, solo para pruebas: guarda las claves en
/// memoria en vez de en el almacén seguro de Android. Nunca se usa en la app.
///
/// Los permisos se controlan desde la prueba: `permissionStates` es lo
/// que devuelve `checkPermissions()` y `requestOutcomes` fija el resultado
/// de `requestPermissions()` por permiso (los permisos sin resultado fijado
/// conservan su estado).
///
/// La vista previa (3.1.1) está soportada: `startPreview()` devuelve
/// `previewInfo` (una textura ficticia) y cuenta las llamadas, salvo que
/// `startPreviewError` fije un código de error para lanzar. Los eventos
/// se emiten con [emitEvent].
class FakeStreamingService implements StreamingService {
  final Map<int, String> keys = <int, String>{};

  PermissionStates permissionStates =
      const PermissionStates.allNotRequested();

  final Map<PermissionKind, PermissionStatus> requestOutcomes =
      <PermissionKind, PermissionStatus>{};

  int requestPermissionsCalls = 0;
  List<PermissionKind> lastRequestedPermissions = const <PermissionKind>[];
  int openAppSettingsCalls = 0;

  PreviewInfo previewInfo = const PreviewInfo(
    textureId: 1,
    width: 1280,
    height: 720,
    sensorOrientation: 90,
    rotationDegrees: 90,
    fps: 30,
    lensFacing: 'back',
  );
  String? startPreviewError;
  String? startPreviewErrorMessage;
  Map<String, Object?>? startPreviewErrorDetails;
  int startPreviewCalls = 0;
  int stopPreviewCalls = 0;
  int stopAllCalls = 0;

  /// Grabación (Fase B, 3.1.2): `startRecording` devuelve
  /// `recordingId` y cuenta las llamadas, salvo que
  /// `startRecordingError` fije un código de error para lanzar.
  /// `getRecordingStatus` devuelve `recordingStatus` (idle por
  /// defecto) y `recoverInterruptedRecordings` devuelve
  /// `recoveredSegments`.
  String recordingId = 'rec_20261118_120000_ab12';
  String? startRecordingError;
  String? startRecordingErrorMessage;
  Map<String, Object?>? startRecordingErrorDetails;
  String? stopRecordingError;
  int startRecordingCalls = 0;
  int stopRecordingCalls = 0;
  // Instrumentación de audio 3.1.3: buffers de entrada AAC
  // descartados que devuelve el método `stopRecording` (0 por defecto).
  int stopDroppedInputBuffers = 0;
  // Capacidad del buffer de entrada AAC en bytes que devuelve el
  // método `stopRecording` (ronda U; `null` por defecto: sin audio o
  // sin drenado).
  int? stopInputBufferCapacityBytes;
  // Instrumentación ronda 3 (grupo I): estado del micrófono y
  // contadores del lector que devuelve el método `stopRecording`
  // (defectos: sin arranque / sin lecturas / sin EOS).
  int? stopMicStateAtStart;
  int? stopMicStateAtStop;
  int stopAudioReadCount = 0;
  int? stopAudioLastReadCode;
  int? stopAudioLastReadOkAgeMs;
  bool stopAudioReaderAlive = false;
  bool stopAudioEos = false;
  // Grupo V: flag del watchdog del lector (V3) y si la espera de
  // EOS agotó los 5 s (V4) que devuelve el método `stopRecording`
  // (`false` por defecto).
  bool stopAudioReaderNoData2s = false;
  bool stopEosTimedOut = false;
  // Grupo S (S0): segundo más silencioso de la grabación que
  // devuelve el método `stopRecording` (`null` por defecto: aún
  // sin un segundo completo).
  int? stopQuietestSecondPeak;
  int? stopQuietestSecondNonZero;
  // Grupo S (S1): pico medido en la ventana silenciosa que disparó
  // el detector que devuelve el método `stopRecording` (`null` por
  // defecto: no hubo disparo).
  int? stopSilencePeak;
  RecordingOptions? lastRecordingOptions;
  RecordingStatusSnapshot recordingStatus =
      RecordingStatusSnapshot.idle();
  List<RecoveredSegmentInfo> recoveredSegments =
      const <RecoveredSegmentInfo>[];

  final StreamController<Map<String, Object?>> _eventsController =
      StreamController<Map<String, Object?>>.broadcast();

  void emitEvent(Map<String, Object?> event) {
    _eventsController.add(event);
  }

  @override
  Future<StreamStatus> getStatus() async {
    return const StreamStatus(
      foregroundServiceAvailable: false,
      cameraGranted: false,
      microphoneGranted: false,
      streamingSupported: true,
    );
  }

  @override
  Future<PermissionStates> checkPermissions() async {
    return permissionStates;
  }

  @override
  Future<PermissionStates> requestPermissions({
    required List<PermissionKind> permissions,
  }) async {
    requestPermissionsCalls++;
    lastRequestedPermissions = List<PermissionKind>.unmodifiable(permissions);
    PermissionStates updated = permissionStates;
    for (final PermissionKind kind in permissions) {
      final PermissionStatus? outcome = requestOutcomes[kind];
      if (outcome != null) {
        updated = updated.copyWithStatus(kind, outcome);
      }
    }
    permissionStates = updated;
    return permissionStates;
  }

  @override
  Future<void> openAppSettings() async {
    openAppSettingsCalls++;
  }

  @override
  Stream<Map<String, Object?>> get events => _eventsController.stream;

  @override
  Future<void> saveStreamKey({
    required int destinationId,
    required String key,
  }) async {
    if (key.isEmpty) {
      throw const StreamingException(StreamContract.emptyKey);
    }
    keys[destinationId] = key;
  }

  @override
  Future<void> deleteStreamKey({required int destinationId}) async {
    keys.remove(destinationId);
  }

  @override
  Future<bool> hasStreamKey({required int destinationId}) async {
    return keys.containsKey(destinationId);
  }

  @override
  Future<PreviewInfo> startPreview() async {
    startPreviewCalls++;
    final String? error = startPreviewError;
    if (error != null) {
      throw StreamingException(
        error,
        startPreviewErrorMessage,
        startPreviewErrorDetails,
      );
    }
    return previewInfo;
  }

  @override
  Future<void> stopPreview() async {
    stopPreviewCalls++;
  }

  @override
  Future<void> startStream() async {
    throw const StreamingException(StreamContract.notAvailableInPhase30);
  }

  @override
  Future<String> startRecording({
    RecordingOptions options = const RecordingOptions(),
  }) async {
    startRecordingCalls++;
    lastRecordingOptions = options;
    final String? error = startRecordingError;
    if (error != null) {
      throw StreamingException(
        error,
        startRecordingErrorMessage,
        startRecordingErrorDetails,
      );
    }
    return recordingId;
  }

  @override
  Future<RecordingInfo> stopRecording() async {
    stopRecordingCalls++;
    final String? error = stopRecordingError;
    if (error != null) {
      throw StreamingException(error);
    }
    // El método devuelve la lista completa de segmentos (paso 5):
    // un segmento `ready` de 12 s, coherente con `durationMs`.
    return RecordingInfo(
      recordingId: recordingId,
      filePath: '/docs/recordings/${recordingId}_seg000.mp4',
      durationMs: 12000,
      sizeBytes: 3750000,
      status: 'ready',
      droppedInputBuffers: stopDroppedInputBuffers,
      inputBufferCapacityBytes: stopInputBufferCapacityBytes,
      micStateAtStart: stopMicStateAtStart,
      micStateAtStop: stopMicStateAtStop,
      audioReadCount: stopAudioReadCount,
      audioLastReadCode: stopAudioLastReadCode,
      audioLastReadOkAgeMs: stopAudioLastReadOkAgeMs,
      audioReaderAlive: stopAudioReaderAlive,
      audioEos: stopAudioEos,
      audioReaderNoData2s: stopAudioReaderNoData2s,
      eosTimedOut: stopEosTimedOut,
      quietestSecondPeak: stopQuietestSecondPeak,
      quietestSecondNonZero: stopQuietestSecondNonZero,
      silencePeak: stopSilencePeak,
      segments: <RecordingSegmentInfo>[
        RecordingSegmentInfo(
          recordingId: recordingId,
          index: 0,
          fileName: '${recordingId}_seg000.mp4',
          filePath: '/docs/recordings/${recordingId}_seg000.mp4',
          status: 'ready',
          durationMs: 12000,
          sizeBytes: 3750000,
          sampleCount: 360,
          keyframeCount: 6,
          firstSampleKeyframe: true,
          ptsMonotonic: true,
          profileIdc: 66,
          closedCleanly: true,
        ),
      ],
    );
  }

  @override
  Future<RecordingStatusSnapshot> getRecordingStatus() async {
    return recordingStatus;
  }

  @override
  Future<List<RecoveredSegmentInfo>> recoverInterruptedRecordings() async {
    return List<RecoveredSegmentInfo>.unmodifiable(recoveredSegments);
  }

  /// Ruta canónica (subetapa 0, 3.1.4): por defecto devuelve
  /// [recordingsDirPath]. `recordingsDirScript` guioniza las
  /// llamadas: cada llamada consume un elemento (una ruta `String?`
  /// o una `Exception` a lanzar); sin guion devuelve la ruta por
  /// defecto. `getRecordingsDirAttempts` registra el intento que
  /// Dart pasó en cada llamada.
  String recordingsDirPath = '/fake/recordings';
  List<Object?> recordingsDirScript = <Object?>[];
  int getRecordingsDirCalls = 0;
  List<int> getRecordingsDirAttempts = <int>[];

  @override
  Future<String?> getRecordingsDir({int attempt = 1}) async {
    getRecordingsDirCalls++;
    getRecordingsDirAttempts.add(attempt);
    if (recordingsDirScript.isNotEmpty) {
      final Object? next = recordingsDirScript.removeAt(0);
      if (next is Exception) {
        throw next;
      }
      return next as String?;
    }
    return recordingsDirPath;
  }

  @override
  Future<void> stopAll() async {
    stopAllCalls++;
  }
}

/// Repositorio de destinos en memoria, solo para pruebas.
class InMemoryStreamDestinationRepository
    implements StreamDestinationRepository {
  final List<StreamDestination> _destinations = <StreamDestination>[];
  int _nextId = 1;

  @override
  Future<List<StreamDestination>> all() async {
    return List<StreamDestination>.unmodifiable(_destinations);
  }

  @override
  Future<StreamDestination?> byId(int id) async {
    for (final StreamDestination d in _destinations) {
      if (d.id == id) {
        return d;
      }
    }
    return null;
  }

  @override
  Future<StreamDestination?> active() async {
    for (final StreamDestination d in _destinations) {
      if (d.isActive) {
        return d;
      }
    }
    return null;
  }

  @override
  Future<StreamDestination> insertManual({
    required String label,
    required String ingestUrl,
    required int nowMs,
  }) async {
    if (label.trim().isEmpty) {
      throw ArgumentError('La etiqueta del destino no puede estar vacía.');
    }
    if (!StreamDestination.isValidIngestUrl(ingestUrl)) {
      throw ArgumentError('La URL de ingesta debe empezar por rtmps://.');
    }
    final StreamDestination created = StreamDestination(
      id: _nextId++,
      kind: StreamDestinationKind.manual,
      label: label.trim(),
      ingestUrl: ingestUrl.trim(),
      hasKey: false,
      isActive: false,
      createdAtMs: nowMs,
      updatedAtMs: nowMs,
    );
    _destinations.add(created);
    return created;
  }

  @override
  Future<void> setHasKey(int id, bool hasKey, {required int nowMs}) async {
    final int index = _destinations.indexWhere((d) => d.id == id);
    if (index >= 0) {
      _destinations[index] = _destinations[index].copyWith(
        hasKey: hasKey,
        updatedAtMs: nowMs,
      );
    }
  }

  @override
  Future<void> setActive(int id, {required int nowMs}) async {
    bool found = false;
    for (int i = 0; i < _destinations.length; i++) {
      final bool active = _destinations[i].id == id;
      if (active) {
        found = true;
      }
      _destinations[i] = _destinations[i].copyWith(
        isActive: active,
        updatedAtMs: nowMs,
      );
    }
    if (!found) {
      throw StateError('Destino no encontrado: $id');
    }
  }

  @override
  Future<void> delete(int id) async {
    _destinations.removeWhere((d) => d.id == id);
  }
}

/// Repositorio de grabaciones en memoria, solo para pruebas.
class InMemoryRecordingRepository implements RecordingRepository {
  final List<RecordingRow> _rows = <RecordingRow>[];
  int _nextId = 1;

  List<RecordingRow> get rows => List<RecordingRow>.unmodifiable(_rows);

  @override
  Future<RecordingRow> insertRecording({
    required String recordingId,
    int? matchId,
    required String resolution,
    required int fps,
    required int videoBitrateBps,
    required int nowMs,
  }) async {
    final String fileName = '${recordingId}_final.mp4';
    final RecordingRow created = RecordingRow(
      id: _nextId++,
      matchId: matchId,
      filePath: '/docs/recordings/$fileName',
      fileName: fileName,
      resolution: resolution,
      fps: fps,
      videoBitrateBps: videoBitrateBps,
      status: 'recording',
      createdAtMs: nowMs,
      updatedAtMs: nowMs,
    );
    _rows.add(created);
    return created;
  }

  @override
  Future<void> updateOnStop({
    required String recordingId,
    required String status,
    int? durationMs,
    int? sizeBytes,
    required int nowMs,
  }) async {
    if (!recordingRowStatuses.contains(status)) {
      throw ArgumentError('Estado de grabación no admitido: $status');
    }
    final int index = _rows.indexWhere(
      (r) => r.fileName == '${recordingId}_final.mp4',
    );
    if (index >= 0) {
      _rows[index] = _rows[index].copyWith(
        durationMs: durationMs,
        sizeBytes: sizeBytes,
        status: status,
        updatedAtMs: nowMs,
      );
    }
  }

  @override
  Future<List<RecordingRow>> interrupted() async {
    return _rows
        .where((r) => r.status == 'recording' || r.status == 'finalizing')
        .toList();
  }

  @override
  Future<RecordingRow?> byRecordingId(String recordingId) async {
    return byFileName('${recordingId}_final.mp4');
  }

  @override
  Future<RecordingRow?> byFileName(String fileName) async {
    for (final RecordingRow row in _rows) {
      if (row.fileName == fileName) {
        return row;
      }
    }
    return null;
  }
}
