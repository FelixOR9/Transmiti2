# Etapa 3 — Fase 3.0: infraestructura de transmisión

> Alcance: contrato Flutter-Kotlin, destino manual RTMPS guardado seguro,
> base de datos v2, reescritura de la prueba offline y CI nuevo.
> Sin cámara, sin vista previa, sin transmisión, sin grabación MP4 y sin
> Facebook Live todavía. Esas funciones llegan en las fases 3.1 y 3.2.

## Qué se implementó

| Pieza | Estado en 3.0 |
| --- | --- |
| Canal `com.transmiti2.transmiti2/streaming` (métodos) y `.../streaming/events` (eventos) | Contrato completo; los métodos de video devuelven `not_available_in_phase_3_0` |
| Destino manual (URL RTMPS + clave de Live Producer) | Guardado, listado, activación y borrado |
| Clave cifrada con Tink + Android Keystore | Guardado, borrado y comprobación de existencia |
| Base SQLite v2 (`stream_destinations`, `stream_sessions`, `recordings`) | Migración v1 -> v2 con prueba de conservación |
| Carpeta `<documentos>/recordings/` | Se crea vacía; los MP4 llegan en 3.2 |
| Pantalla Estudio de transmisión | Formulario de destino + botones de video deshabilitados con su fase |
| Workflow de GitHub | Solo verificación en `Etapa-3`, sin ZIP ni auto-commit ni releases |

## Almacenamiento seguro (Tink 1.24.0 + Android Keystore)

Tres capas, y solo la primera vive en Keystore:

1. **Llave maestra (KEK)** AES-256, generada y guardada en Android Keystore,
   alias `transmiti2_master_key`, URI `android-keystore://transmiti2_master_key`.
   Su material nunca sale de Keystore.
2. **Keyset de Tink** (llaves de cifrado de datos, AES256-GCM), guardado en
   SharedPreferences (`transmiti2_tink_keyset`) pero **cifrado con la llave
   maestra**, a través de `AndroidKeysetManager`.
3. **Texto cifrado de cada clave** en Preferences DataStore
   (`transmiti2_secrets`), cifrado con el AEAD de Tink y un AAD propio por
   destino. El archivo DataStore no está cifrado como un todo: cada valor
   lo está individualmente.

Dependencias (estables, sin alpha ni obsoletas):

- `androidx.datastore:datastore-preferences:1.3.0`
- `com.google.crypto.tink:tink-android:1.24.0`

No se usa `datastore-tink`: su `AeadSerializer` tiene un `associatedData`
fijo por archivo y no permite un AAD distinto por destino. No se usa
`EncryptedSharedPreferences` (obsoleto).

### AAD por destino

Cada secreto se cifra con Tink AEAD directamente:

```
aead.encrypt(clave, aad = "transmiti2:stream-key:v1:<destinationId>")
```

En DataStore solo se guarda el cifrado en Base64, con la llave
`stream_key_<destinationId>`. Un cifrado copiado a otro destino no
descifra, porque el AAD es distinto. No se mezcla el AAD del archivo
completo con el AAD individual de cada destino: no hay AAD de archivo.

### Fallo seguro si Keystore no está activo

Justo después de construir `AndroidKeysetManager` se comprueba
`isUsingKeystore()`. Si devuelve `false`, todas las operaciones lanzan
`SecureStoreUnavailableException` (`keystore_not_active`) y no se escribe
nada. Nunca se guarda el keyset en texto claro como alternativa
silenciosa y nunca se llama a desactivar Keystore en código de
producción (la prueba `secret_leak_test.dart` lo verifica escaneando el
código).

Limitación conocida: Tink advierte que Android Keystore puede no ser
fiable en algunos teléfonos. Si la llave maestra se pierde o se invalida,
el keyset no se puede desempaquetar y los secretos son irrecuperables:
la recuperación es borrar el keyset y los secretos y volver a pegar la
clave. La app nunca pierde el partido por esto: el partido, el marcador
y el reloj viven en SQLite y no dependen del almacén seguro.

### La clave pasa transitoriamente por Dart

Al guardar, la clave viaja una vez por MethodChannel (`saveStreamKey`).
En Dart no se guarda en ningún campo y el campo de la pantalla se limpia
al terminar. Limitación honesta: los `String` de Dart son inmutables y
no se pueden borrar de memoria con garantías. En Kotlin la clave viaja
como `ByteArray` y se borra con `fill(0)` después de cifrarla. La clave
nunca aparece en logs, errores (solo códigos), eventos, SQLite ni en el
repositorio.

## Servicio en primer plano

`StreamForegroundService` existe como esqueleto sin registrar en el
manifiesto y nunca se arranca en 3.0. En la Fase 3.1 se registrará con
`foregroundServiceType="camera|microphone"`, con los permisos
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`,
`FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` y `WAKE_LOCK`, y
se arrancará solo desde un toque del usuario con cámara y micrófono ya
concedidos. `startForeground()` debe llamarse dentro de los **5 segundos**
siguientes a `startForegroundService()`, como primera operación y con la
notificación ya construida; si se supera, el sistema detiene el servicio
y declara la app ANR.

## Permisos

En la Fase 3.0 **no se pide cámara ni micrófono al usuario**. El
manifiesto declara `CAMERA` y `RECORD_AUDIO` (declarar no es pedir ni
conceder) y el contrato tiene `checkPermissions` de solo lectura.
`requestPermissions` existe en el contrato pero devuelve
`permission_request_deferred_to_phase_3_1` y la interfaz nunca lo llama.
En la Fase 3.1 se pedirá en el momento de usar la vista previa, después
de explicar el motivo en pantalla.

## Base de datos v2

`AppDatabase.version` 1 -> 2, `kSchemaV1` intacto, migración en
`kMigrations[2]`, sin `DROP`. Tablas nuevas: `stream_destinations`
(solo `has_key`, nunca la clave), `stream_sessions` y `recordings`,
con sus índices. La prueba `sqlite_migration_v1_to_v2_test.dart` siembra
una base v1 real con los repositorios de Etapa 2 (2 equipos, jugadores,
2 patrocinadores, partido de 45 + 15 minutos, reloj en el segundo tiempo
en marcha, gol en el minuto 23, gol en el 41, un gol anulado y una
corrección) y verifica que todo se conserva y que el marcador sigue
siendo 1-1.

## Compatibilidad y Tink 1.24.0

- `minSdk = maxOf(flutter.minSdkVersion, 23)` en
  `android/app/build.gradle.kts`. La tarea `printTransmiti2SdkVersions`
  imprime `flutter.minSdkVersion`, el minSdk efectivo, `targetSdk` y
  `compileSdk`, y falla si el efectivo no es exactamente ese máximo.
- Tink 1.24.0 es totalmente compatible desde API 24 y la mayoría de sus
  partes funcionan desde API 23. Las partes que exigen API 24 son la
  librería JWT y `StreamingAead`; ninguna se usa aquí. AEAD AES256-GCM y
  `AndroidKeysetManager` con Keystore funcionan desde API 23, que es el
  piso que fija el minSdk efectivo. En el Samsung Galaxy S24 Ultra
  (API 34) no hay ningún problema.
- Se añadió el Gradle Wrapper (`android/gradlew`, `android/gradlew.bat`),
  que faltaba en el repositorio, para que el CI pueda ejecutar la tarea
  de verificación de SDK.

## Preparación para Facebook Login + Graph API (futuro, no implementado)

- El proveedor de destino (`StreamDestinationProvider` en el diseño) está
  separado del motor RTMP: el motor solo recibe
  `RtmpEndpoint {ingestUrl, keyRef, destinationId}`.
- `StreamDestinationKind.facebookGraph` existe como valor reservado; no
  se crea ningún destino de ese tipo ni hay código de Graph API o Login.
- El AAD canónico para un futuro token de Página ya está definido
  (`transmiti2:facebook-page-token:v1:<pageId>`), sin implementación.
- Requisitos de Meta documentados para esa fase (a verificar en el panel
  de Meta antes de implementarla): para una Página, `pages_show_list`,
  `pages_manage_posts`, `pages_read_engagement`; para un perfil personal,
  `publish_video`; endpoint `POST /{page-id}/live_videos` con token de
  Página, que devuelve `id`, `stream_url` y `secure_stream_url`; cuenta
  de desarrollador de Meta y app de tipo Business, Facebook Login /
  Login for Business, revisión de la app + acceso avanzado + verificación
  de la empresa para usuarios sin rol en la app. El modo manual seguirá
  funcionando cuando se añada el automático.

## Workflow de GitHub

`.github/workflows/compilar-apk.yml` solo verifica: se dispara en `push`
y `pull_request` a `Etapa-3` y a mano; permisos `contents: read`;
pasos: checkout, Java 17, Flutter stable, `libsqlite3-dev`,
`flutter --version`, `flutter pub get`, verificación de SDK,
`flutter analyze --no-fatal-infos`, `flutter test`,
`flutter build apk --debug` y artefacto `transmiti2-debug-apk-etapa3`
(7 días). Sin ZIP, sin agregado automático de dependencias, sin
auto-commit y sin releases automáticos. El archivo lo aplica el
propietario desde la web de GitHub, porque la API no permite subir
archivos de `.github/workflows`.

## Pruebas

- `flutter test`: migración v1 -> v2, destinos y flujo sin huérfanos,
  AAD canónico, ausencia de filtraciones de claves, minSdk y offline
  reescrita. Las pruebas existentes de Etapas 1 y 2 siguen pasando.
- `./gradlew :app:testDebugUnitTest`: pruebas unitarias Kotlin puras
  (AAD, validación RTMPS, guarda de Keystore).
- `./gradlew :app:printTransmiti2SdkVersions`: verificación de SDK.
- `flutter build apk --debug`: APK de depuración en CI como artefacto.

## Checklist manual en el Samsung Galaxy S24 Ultra

Ver `docs/ETAPA3_FASE30.md` (esta misma sección se entrega también en
el informe final de la fase): guardar un destino con una clave de
prueba, comprobar que no se pide cámara ni micrófono, que los botones
de video están deshabilitados con su fase y que al cerrar y reabrir la
app el destino sigue guardado.
