package com.transmiti2.transmiti2.streaming

/**
 * Dato asociado (AAD) canónico para cifrar la clave de un destino.
 *
 * Espejo de `streamKeyAad` en Dart. El AAD ata cada cifrado a su destino:
 * un cifrado copiado a otro destino no descifra. No se usa
 * `AeadSerializer`: su `associatedData` es fijo por archivo y no permite
 * un AAD distinto por destino.
 */
object StreamAad {
    fun streamKeyAad(destinationId: Long): ByteArray =
        "transmiti2:stream-key:v1:$destinationId".toByteArray(Charsets.UTF_8)

    /** AAD canónico para un futuro token de Página de Facebook. Reservado. */
    fun facebookPageTokenAad(pageId: String): ByteArray =
        "transmiti2:facebook-page-token:v1:$pageId".toByteArray(Charsets.UTF_8)
}

/** Validador puro de URLs de ingesta RTMPS (sin red, sin estado). */
object RtmpUrl {
    fun isValidIngestUrl(url: String): Boolean {
        if (!url.startsWith("rtmps://")) {
            return false
        }
        val rest = url.removePrefix("rtmps://")
        return rest.isNotEmpty() && !rest.contains(' ')
    }
}

/**
 * Guarda de fallo seguro para Android Keystore.
 *
 * Se llama justo después de construir `AndroidKeysetManager`, antes de
 * guardar o leer ningún secreto. Si Keystore no está activo, la operación
 * falla con [SecureStoreUnavailableException] y nunca se guarda el keyset
 * en texto claro. No existe ninguna ruta en código de producción que
 * desactive el uso de Keystore.
 */
object KeystoreGuard {
    fun requireKeystoreActive(isUsingKeystore: Boolean) {
        if (!isUsingKeystore) {
            throw SecureStoreUnavailableException(
                StreamContract.KEYSTORE_NOT_ACTIVE,
            )
        }
    }
}

/** El almacén seguro no está disponible o Keystore no está activo. */
class SecureStoreUnavailableException(val code: String) : Exception(code)
