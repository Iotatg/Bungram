package app.bungram.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Where long-term keys live.
 *
 * The interface exists so the crypto engine never touches Android APIs directly: the real app
 * backs it with [EncryptedSharedPreferences] (AES-256-GCM values, keys held in the Android
 * Keystore), while unit tests use [MemorySecretStorage] and run on a plain JVM.
 */
interface SecretStorage {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

/**
 * Keystore-backed storage. Values are encrypted with a key that never leaves the TEE/StrongBox,
 * so a `adb pull` of the app's data directory yields ciphertext and nothing else.
 */
class PrefsSecretStorage(context: Context) : SecretStorage {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    private companion object {
        const val FILE_NAME = "bungram_secrets"
    }
}

/** Test double. Deliberately not persisted — a JVM test should never see the previous run's keys. */
class MemorySecretStorage : SecretStorage {
    private val map = ConcurrentHashMap<String, String>()
    override fun read(key: String): String? = map[key]
    override fun write(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
