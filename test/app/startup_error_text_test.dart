import 'package:flutter_test/flutter_test.dart';
import 'package:transmiti2/app/bootstrap.dart';
import 'package:transmiti2/features/streaming/application/streaming_service.dart';
import 'package:transmiti2/features/streaming/domain/stream_contract.dart';

/// Pantalla de arranque (subetapa 0, grupo 0-b): `startupErrorText`
/// es pura y testeable (U64). Para cada código de canal muestra el
/// mensaje específico + el código + el `stage` (+ `attempts` en
/// canal-no-disponible); el título ya no presupone disco.
void main() {
  test('U64 canal no disponible muestra mensaje, codigo, stage y attempts',
      () {
    final StartupErrorText shown = startupErrorText(
      const StreamingException(
        StreamContract.recordingsChannelUnavailable,
        'No se pudo contactar con el sistema de grabaciones del teléfono.',
        <String, Object?>{
          'stage': 'timeout',
          'attempts': 3,
          'lastErrorCode': null,
          'exceptionClass': 'TimeoutException',
        },
      ),
    );
    expect(
      shown.title,
      'No se pudo contactar con el sistema de grabaciones del teléfono.',
    );
    expect(shown.detail, contains('recordings_channel_unavailable'));
    expect(shown.detail, contains('stage: timeout'));
    expect(shown.detail, contains('intentos: 3'));
  });

  test('U64 error de contrato muestra su mensaje y su stage', () {
    final StartupErrorText shown = startupErrorText(
      const StreamingException(
        StreamContract.recordingsChannelContractError,
        'La respuesta del sistema de grabaciones no es válida.',
        <String, Object?>{
          'stage': 'null_response',
          'attempts': 1,
          'exceptionClass': null,
        },
      ),
    );
    expect(
      shown.title,
      'La respuesta del sistema de grabaciones no es válida.',
    );
    expect(shown.detail, contains('recordings_channel_contract_error'));
    expect(shown.detail, contains('stage: null_response'));
  });

  test('U64 fallo de directorio muestra su mensaje y su stage', () {
    final StartupErrorText shown = startupErrorText(
      const StreamingException(
        StreamContract.recordingsDirUnavailable,
        'No se pudo preparar la carpeta de grabaciones.',
        <String, Object?>{'stage': 'mkdirs_failed'},
      ),
    );
    expect(shown.title, 'No se pudo preparar la carpeta de grabaciones.');
    expect(shown.detail, contains('recordings_dir_unavailable'));
    expect(shown.detail, contains('stage: mkdirs_failed'));
    expect(shown.detail, isNot(contains('intentos')));
  });

  test('U64 error desconocido conserva el titulo generico con el codigo',
      () {
    final StartupErrorText shown = startupErrorText(
      const StreamingException('otro_codigo'),
    );
    expect(
      shown.title,
      'No se pudo abrir el almacenamiento del teléfono.',
    );
    expect(shown.detail, contains('otro_codigo'));
  });

  test('U64 error no StreamingException conserva el titulo generico', () {
    final StartupErrorText shown = startupErrorText(
      StateError('boom'),
    );
    expect(
      shown.title,
      'No se pudo abrir el almacenamiento del teléfono.',
    );
    expect(shown.detail, contains('boom'));
  });
}
