package app.bungram.data.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Tokens never touch plain SharedPreferences: the file is AES-256-GCM encrypted with a
 * hardware-backed key when the device supports it.
 */
class TokenStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "bungram_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var access: String?
        get() = prefs.getString(KEY_ACCESS, null)
        private set(value) = prefs.edit().putString(KEY_ACCESS, value).apply()

    var refresh: String?
        get() = prefs.getString(KEY_REFRESH, null)
        private set(value) = prefs.edit().putString(KEY_REFRESH, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER, null)
        private set(value) = prefs.edit().putString(KEY_USER, value).apply()

    /**
     * Stable per-install id. Survives logout on purpose: it identifies the device, not the account,
     * and the server uses it for multi-device fan-out and per-device key bundles.
     */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE, it).apply()
        }

    fun save(access: String, refresh: String, userId: String) {
        this.access = access
        this.refresh = refresh
        this.userId = userId
    }

    fun clear() = prefs.edit()
        .remove(KEY_ACCESS)
        .remove(KEY_REFRESH)
        .remove(KEY_USER)
        .apply()

    private companion object {
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_USER = "user"
        const val KEY_DEVICE = "device"
    }
}
