package app.bungram.feature.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bungram.AppGraph
import app.bungram.core.Sounds
import app.bungram.data.model.User
import app.bungram.data.remote.DeviceDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Which profile field the sheet is editing. One sheet, three fields — three near-identical screens
 * would triple the surface for no benefit.
 */
enum class ProfileField { NAME, USERNAME, ABOUT }

data class ProfileUiState(
    val user: User? = null,
    val editing: ProfileField? = null,
    val draft: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val devices: List<DeviceDto> = emptyList(),
    val devicesLoading: Boolean = false,
    val showDevices: Boolean = false,
    val soundsEnabled: Boolean = true,
) {
    /** Mirrors the server's rules so the user gets feedback before the round trip. */
    val draftValid: Boolean
        get() = when (editing) {
            ProfileField.NAME -> draft.trim().length in 2..64
            ProfileField.USERNAME -> draft.trim().length in 3..32 &&
                draft.trim().all { it.isLetterOrDigit() || it == '_' }
            ProfileField.ABOUT -> draft.length <= 140
            null -> false
        }

    val draftHint: String
        get() = when (editing) {
            ProfileField.NAME -> "2–64 characters. Shown to everyone you talk to."
            ProfileField.USERNAME -> "3–32 characters: letters, digits and underscores."
            ProfileField.ABOUT -> "${draft.length}/140"
            null -> ""
        }
}

/**
 * Profile edits go through `PATCH /v1/me`, which is the only endpoint allowed to change identity.
 * Everything the UI shows is re-read from the repository afterwards, so a rejected edit can never
 * leave the screen showing a value the server did not accept.
 */
class ProfileViewModel : ViewModel() {

    private val repository = AppGraph.repository
    private val prefs = AppGraph.appContext.getSharedPreferences("bungram_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        ProfileUiState(
            user = AppGraph.me(),
            soundsEnabled = prefs.getBoolean(KEY_SOUNDS, true),
        )
    )
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        Sounds.enabled = _state.value.soundsEnabled
    }

    fun beginEdit(field: ProfileField) {
        val user = _state.value.user
        _state.value = _state.value.copy(
            editing = field,
            draft = when (field) {
                ProfileField.NAME -> user?.displayName.orEmpty()
                ProfileField.USERNAME -> user?.username.orEmpty()
                ProfileField.ABOUT -> user?.about.orEmpty()
            },
            error = null,
        )
    }

    fun onDraftChange(value: String) {
        _state.value = _state.value.copy(draft = value, error = null)
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editing = null, draft = "", error = null)
    }

    fun save() {
        val current = _state.value
        val field = current.editing ?: return
        if (!current.draftValid || current.saving) return
        val value = current.draft.trim()
        _state.value = current.copy(saving = true, error = null)

        viewModelScope.launch {
            val result = runCatching {
                when (field) {
                    ProfileField.NAME -> repository.refreshProfile(name = value, username = null, about = null)
                    ProfileField.USERNAME -> repository.refreshProfile(name = null, username = value, about = null)
                    ProfileField.ABOUT -> repository.refreshProfile(name = null, username = null, about = value)
                }
            }
            result.onSuccess { user ->
                _state.value = _state.value.copy(
                    user = user,
                    editing = null,
                    draft = "",
                    saving = false,
                    notice = when (field) {
                        ProfileField.NAME -> "Name updated"
                        ProfileField.USERNAME -> "Username updated"
                        ProfileField.ABOUT -> "About updated"
                    },
                )
            }.onFailure { t ->
                _state.value = _state.value.copy(
                    saving = false,
                    error = t.message?.takeIf { it.isNotBlank() } ?: "Could not save that change",
                )
            }
        }
    }

    fun openDevices() {
        _state.value = _state.value.copy(showDevices = true, devicesLoading = true)
        viewModelScope.launch {
            val devices = runCatching { AppGraph.api.devices(AppGraph.deviceId) }.getOrDefault(emptyList())
            _state.value = _state.value.copy(devices = devices, devicesLoading = false)
        }
    }

    fun closeDevices() {
        _state.value = _state.value.copy(showDevices = false)
    }

    /** Signing out a device also drops its pre-keys server-side, so it stops receiving handshakes. */
    fun revokeDevice(deviceId: String) {
        viewModelScope.launch {
            runCatching { AppGraph.api.revokeDevice(deviceId) }
            openDevices()
        }
    }

    fun setSoundsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOUNDS, enabled).apply()
        Sounds.enabled = enabled
        if (enabled) Sounds.send()
        _state.value = _state.value.copy(soundsEnabled = enabled)
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    private companion object {
        const val KEY_SOUNDS = "sounds_enabled"
    }
}
