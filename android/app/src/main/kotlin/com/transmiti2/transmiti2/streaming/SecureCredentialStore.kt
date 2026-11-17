package com.transmiti2.transmiti2.streaming

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Almacén seguro de claves de transmisión.
 *
 * Tres capas, y solo la primera vive en Android Keystore:
 * 1. Llave maestra (KEK) AES-256, generada y guardada en Android Keystore
 *    (`android-keystore://transmiti2_master_key`). Su material nunca sale
 *    de Keystore.
 * 2. Keyset de Tink (llaves de cifrado de datos, AES256-GCM), guardado en
 *    SharedPreferences pero CIFRADO con la llave maestra, a través de
 *    `AndroidKeysetManager`.
 * 3. El texto cifrado de cada clave vive en Preferences DataStore
 *    (`transmiti2_secrets`), cifrado con el AEAD de Tink y un AAD propio
 *    por destino (`transmiti2:stream-key:v1:<destinationId>`).
 *
 * El archivo DataStore NO está cifrado como un todo: cada valor lo está
 * individualmente. No se usa `AeadSerializer` (AAD fijo por archivo).
 *
 * Fallo seguro: si Keystore no está activo, todas las operaciones lanzan
 * [SecureStoreUnavailableException] y no se escribe nada. Nunca se guarda
 * un keyset en texto claro como alternativa silenciosa.
 *
 * La clave pasa como [ByteArray] para poder borrarla (`fill(0)`) después
 * de cifrarla. Si la llave maestra se pierde o se invalida, el keyset no
 * se puede desempaquetar y los secretos son irrecuperables: hay que volver
 * a pegar la clave.
 */
interface SecureCredentialStore {
    suspend fun save(destinationId: Long, plaintext: ByteArray)
    suspend fun load(destinationId: Long): ByteArray?
    suspend fun delete(destinationId: Long)
    suspend fun has(destinationId: Long): Boolean
}

private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "transmiti2_secrets",
)

class TinkSecureCredentialStore(
    context: Context,
    /** Inyectable solo para pruebas: en producción es el manager real. */
    keysetManager: AndroidKeysetManager? = null,
) : SecureCredentialStore {

    private val appContext: Context = context.applicationContext
    private val dataStore: DataStore<Preferences> = appContext.secretsDataStore
    private val aead: Aead

    init {
        val manager = keysetManager ?: AndroidKeysetManager.Builder()
            .withSharedPref(
                appContext,
                "transmiti2_tink_keyset",
                "transmiti2_tink_keyset",
            )
            .withKeyTemplate(
                KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM),
            )
            .withMasterKeyUri("android-keystore://transmiti2_master_key")
            .build()
        // Fallo seguro: antes de guardar o leer nada.
        KeystoreGuard.requireKeystoreActive(manager.isUsingKeystore())
        aead = manager.keysetHandle.getPrimitive(Aead::class.java)
    }

    private fun keyFor(destinationId: Long) =
        stringPreferencesKey("stream_key_$destinationId")

    override suspend fun save(destinationId: Long, plaintext: ByteArray) {
        try {
            val ciphertext = aead.encrypt(
                plaintext,
                StreamAad.streamKeyAad(destinationId),
            )
            val encoded = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            dataStore.edit { prefs ->
                prefs[keyFor(destinationId)] = encoded
            }
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun load(destinationId: Long): ByteArray? {
        val encoded = dataStore.data
            .map { prefs -> prefs[keyFor(destinationId)] }
            .first() ?: return null
        val ciphertext = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw SecureStoreUnavailableException(
                StreamContract.SECURE_STORE_UNAVAILABLE,
            )
        }
        return try {
            aead.decrypt(ciphertext, StreamAad.streamKeyAad(destinationId))
        } catch (e: Exception) {
            // AAD distinto, cifrado alterado o keyset perdido: no se
            // reintenta con otro AAD ni se devuelve nada en claro.
            throw SecureStoreUnavailableException(
                StreamContract.SECURE_STORE_UNAVAILABLE,
            )
        }
    }

    override suspend fun delete(destinationId: Long) {
        dataStore.edit { prefs ->
            prefs.remove(keyFor(destinationId))
        }
    }

    override suspend fun has(destinationId: Long): Boolean {
        return dataStore.data
            .map { prefs -> prefs.contains(keyFor(destinationId)) }
            .first()
    }
}
