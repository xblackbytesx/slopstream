package com.xblackbytesx.slopstream.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.xblackbytesx.slopstream.core.SourceKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

enum class AccountStatus { SIGNED_OUT, SIGNED_IN, EXPIRED }

data class Account(val status: AccountStatus = AccountStatus.SIGNED_OUT, val username: String? = null)

/** A service sign-in as stored: the credential and who it belongs to. */
@Serializable
data class StoredAccount(val secret: String, val username: String)

/** Where sign-ins live. One entry per service. */
interface SecretStore {
    fun read(source: SourceKey): StoredAccount?
    fun write(source: SourceKey, account: StoredAccount)
    fun delete(source: SourceKey)
}

/** Where this device's sync server token lives. */
interface TokenStore {
    fun readToken(): String?
    fun writeToken(token: String)
    fun deleteToken()
}

/**
 * Signed-in service accounts. The credential (Floatplane's website session today) stays in
 * memory and in [SecretStore]; it is never logged or shown, and leaves the device only to the
 * user's own sync server (decision 18). A service that rejects it marks the account
 * [AccountStatus.EXPIRED] until the user signs in again.
 */
@Singleton
class Accounts @Inject constructor(private val store: SecretStore) {
    private val stored = HashMap<SourceKey, StoredAccount?>()
    private val _state = MutableStateFlow(SourceKey.entries.associateWith { key -> load(key).toAccount() })
    val state: StateFlow<Map<SourceKey, Account>> = _state.asStateFlow()

    private val _expired = MutableSharedFlow<SourceKey>(extraBufferCapacity = 8)

    /** A service rejected a session this device used (for the sync server to hear about). */
    val expired: SharedFlow<SourceKey> = _expired.asSharedFlow()

    fun account(source: SourceKey): Account = _state.value[source] ?: Account()

    /** The credential for API calls, null when signed out. */
    fun secret(source: SourceKey): String? = load(source)?.secret

    suspend fun save(source: SourceKey, secret: String, username: String) {
        val account = StoredAccount(secret, username)
        withContext(Dispatchers.IO) { store.write(source, account) }
        synchronized(stored) { stored[source] = account }
        _state.update { it + (source to Account(AccountStatus.SIGNED_IN, username)) }
    }

    suspend fun clear(source: SourceKey) {
        withContext(Dispatchers.IO) { store.delete(source) }
        synchronized(stored) { stored[source] = null }
        _state.update { it + (source to Account()) }
    }

    /** [report]: the service said so to this device (not the sync server relaying it). */
    fun markExpired(source: SourceKey, report: Boolean = true) {
        var changed = false
        _state.update { m ->
            val a = m[source] ?: return@update m
            if (a.status == AccountStatus.SIGNED_IN) {
                changed = true
                m + (source to a.copy(status = AccountStatus.EXPIRED))
            } else {
                m
            }
        }
        if (changed && report) _expired.tryEmit(source)
    }

    private fun load(source: SourceKey): StoredAccount? = synchronized(stored) {
        if (source in stored) stored[source] else runCatching { store.read(source) }.getOrNull().also { stored[source] = it }
    }

    private fun StoredAccount?.toAccount() =
        if (this == null) Account() else Account(AccountStatus.SIGNED_IN, username)
}

/**
 * Sign-ins encrypted with an AES-GCM key that lives in the Android Keystore and never leaves
 * it, in no-backup storage, so neither a backup nor a copied data folder carries them.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(@ApplicationContext context: Context) : SecretStore, TokenStore {
    private val dir = File(context.noBackupFilesDir, "accounts")
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(name: String) = File(dir, "$name.bin")

    override fun read(source: SourceKey): StoredAccount? =
        open(source.id)?.let { json.decodeFromString(StoredAccount.serializer(), it) }

    override fun write(source: SourceKey, account: StoredAccount) =
        seal(source.id, json.encodeToString(StoredAccount.serializer(), account))

    override fun delete(source: SourceKey) {
        file(source.id).delete()
    }

    override fun readToken(): String? = open(TOKEN)

    override fun writeToken(token: String) = seal(TOKEN, token)

    override fun deleteToken() {
        file(TOKEN).delete()
    }

    private fun open(name: String): String? {
        val bytes = file(name).takeIf { it.exists() }?.readBytes() ?: return null
        if (bytes.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
    }

    private fun seal(name: String, plain: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.encodeToByteArray())
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(sealed)
        check(tmp.renameTo(file(name))) { "Could not store the sign-in" }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "slopstream-accounts"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12

        /** The sync server token; never a SourceKey id. */
        const val TOKEN = "sync-token"
    }
}
