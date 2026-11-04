package app.bungram.data.remote

import android.content.Context
import app.bungram.BuildConfig

/**
 * Where the app talks to. Kept out of [TokenStore] on purpose: the server address is not a secret,
 * and users need to change it (emulator -> 10.0.2.2, real device -> LAN IP, production -> https).
 *
 * Resolution order: user override -> build type default.
 */
class ServerSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("bungram_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE, null)?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: BuildConfig.BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE, value.trim().trimEnd('/')).apply()

    /** http://host:8080 -> ws://host:8080/ws  (https -> wss) */
    val wsUrl: String
        get() = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/') + "/ws"

    val isDefault: Boolean get() = prefs.getString(KEY_BASE, null).isNullOrBlank()

    fun reset() = prefs.edit().remove(KEY_BASE).apply()

    private companion object {
        const val KEY_BASE = "base_url"
    }
}
