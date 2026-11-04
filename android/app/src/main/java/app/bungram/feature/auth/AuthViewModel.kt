package app.bungram.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bungram.data.remote.ApiException
import app.bungram.data.remote.AuthVerify
import app.bungram.data.remote.BungramApi
import app.bungram.data.remote.ServerSettings
import app.bungram.data.remote.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthStep { PHONE, CODE }

data class AuthUiState(
    val step: AuthStep = AuthStep.PHONE,
    val phone: String = "",
    val code: String = "",
    val challengeId: String? = null,
    val devCode: String? = null,
    val serverUrl: String = "",
    val serverUrlEdited: Boolean = false,
    val showServerField: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Two-step OTP login. Deliberately dumb: no auto-read SMS (needs a permission Bungram will not
 * ask for), no retry storm — the server rate-limits per phone and the UI just surfaces the error.
 */
class AuthViewModel(
    private val api: BungramApi,
    private val settings: ServerSettings,
    private val tokens: TokenStore,
    private val onAuthenticated: () -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState(serverUrl = settings.baseUrl))
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onPhoneChange(value: String) {
        val cleaned = value.filter { it.isDigit() || it == '+' || it == ' ' }.take(20)
        _state.update { it.copy(phone = cleaned, error = null) }
    }

    fun onCodeChange(value: String) {
        _state.update { it.copy(code = value.filter(Char::isDigit).take(6), error = null) }
    }

    fun onServerUrlChange(value: String) {
        _state.update { it.copy(serverUrl = value.trim(), serverUrlEdited = true) }
    }

    fun toggleServerField() {
        _state.update { it.copy(showServerField = !it.showServerField) }
    }

    fun back() {
        _state.update { it.copy(step = AuthStep.PHONE, code = "", error = null) }
    }

    fun requestOtp() {
        val phone = _state.value.phone.replace(" ", "")
        if (phone.length < 6) {
            _state.update { it.copy(error = "Enter a valid phone number") }
            return
        }
        if (_state.value.serverUrlEdited) settings.baseUrl = _state.value.serverUrl

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                api.requestOtp(phone, "Bungram Android", tokens.deviceId)
            }.onSuccess { challenge ->
                _state.update {
                    it.copy(
                        step = AuthStep.CODE,
                        challengeId = challenge.challengeId,
                        devCode = challenge.devCode,
                        busy = false,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.friendly()) }
            }
        }
    }

    fun verify() {
        val s = _state.value
        val challengeId = s.challengeId ?: return
        if (s.code.length < 4) {
            _state.update { it.copy(error = "Enter the 6-digit code") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                api.verifyOtp(
                    AuthVerify(
                        challengeId = challengeId,
                        code = s.code,
                        identityKey = "",
                        signedPreKey = "",
                        oneTimePreKeys = emptyList(),
                    )
                )
            }.onSuccess {
                _state.update { it.copy(busy = false) }
                onAuthenticated()
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.friendly()) }
            }
        }
    }

    fun useDevCode() {
        _state.update { it.copy(code = it.devCode.orEmpty()) }
    }

    private fun Throwable.friendly(): String = when (this) {
        is ApiException -> message ?: "Request failed"
        is java.net.UnknownHostException -> "Server not found. Check the address."
        is java.net.ConnectException -> "Cannot reach the server. Is it running?"
        is java.net.SocketTimeoutException -> "Server took too long to answer."
        else -> message ?: "Something went wrong"
    }
}
