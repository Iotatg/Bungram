package app.bungram.core

import android.content.Context
import android.os.Build
import app.bungram.BuildConfig
import java.util.UUID

/**
 * A stable, per-install device id.
 *
 * It is *not* a security identifier — it never leaves the device in a form that could be used to
 * derive a key. Its job is to let the server route pushes to the right phone and to keep one
 * pre-key bundle per device, which is the minimum for multi-device E2EE.
 *
 * Deliberately stored in plain preferences: if the user clears app data the id changes, and that is
 * correct — a reinstalled app is a new device and must not inherit the old one's sessions.
 */
object DeviceIdentity {

    private const val PREFS = "bungram_device"
    private const val KEY_ID = "device_id"

    fun of(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ID, fresh).apply()
        return fresh
    }

    /** Shown in the "Devices" settings screen; "Pixel 7" reads better than a UUID. */
    fun name(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        if (model.startsWith(manufacturer, ignoreCase = true)) return model
        return listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "Android" }
    }

    fun appVersion(): String = BuildConfig.VERSION_NAME
}
