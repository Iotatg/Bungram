package app.bungram.feature.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bungram.data.remote.ApiException
import app.bungram.data.remote.AuthVerify
import app.bungram.data.remote.BungramApi
import app.bungram.data.remote.ServerSettings
import app.bungram.data.remote.TokenStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AuthStep { PHONE, CODE }

data class AuthUiState(
    val step: AuthStep = AuthStep.PHONE,
    val country: Country? = null,
    /** National part only; the dial code lives in [country]. */
    val national: String = "",
    val code: String = "",
    val challengeId: String? = null,
    val devCode: String? = null,
    val serverUrl: String = "",
    val serverUrlEdited: Boolean = false,
    val showServerField: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** Seconds until "Resend code" is allowed again. */
    val resendInSec: Int = 0,
    /** Bumped on every failed verify so the code boxes can shake exactly once. */
    val shakeTick: Int = 0,
) {
    val e164: String get() = country?.let { Countries.e164(it, national) }.orEmpty()
    val canContinue: Boolean get() = national.filter(Char::isDigit).length >= 6
}

/**
 * Phone -> code login.
 *
 * The code is never read from SMS automatically: Bungram does not ask for the SMS permission, so
 * the user types six digits. Everything else (rate limit, expiry, resend window) is enforced by
 * the server; this class only mirrors it so the UI can disable a button instead of showing a 429.
 */
class AuthViewModel(
    private val api: BungramApi,
    private val settings: ServerSettings,
    private val tokens: TokenStore,
    private val onAuthenticated: () -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState(serverUrl = settings.baseUrl))
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private var resendJob: Job? = null

    /** Called once from the screen with a Context so the SIM/locale can pick the country. */
    fun detectCountry(context: Context) {
        if (_state.value.country != null) return
        _state.update { it.copy(country = Countries.detect(context)) }
    }

    fun selectCountry(country: Country) {
        _state.update { it.copy(country = country, error = null) }
    }

    fun onNationalChange(value: String) {
        val digits = value.filter(Char::isDigit).take(12)
        _state.update { it.copy(national = digits, error = null) }
    }

    fun onCodeChange(value: String) {
        val digits = value.filter(Char::isDigit).take(6)
        _state.update { it.copy(code = digits, error = null) }
    }

    fun onServerUrlChange(value: String) {
        _state.update { it.copy(serverUrl = value.trim(), serverUrlEdited = true) }
    }

    fun toggleServerField() {
        _state.update { it.copy(showServerField = !it.showServerField) }
    }

    fun back() {
        resendJob?.cancel()
        _state.update { it.copy(step = AuthStep.PHONE, code = "", error = null, resendInSec = 0) }
    }

    fun requestOtp() {
        val s = _state.value
        if (!s.canContinue) {
            _state.update { it.copy(error = "Enter a valid phone number") }
            return
        }
        if (s.serverUrlEdited) settings.baseUrl = s.serverUrl

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                api.requestOtp(s.e164, "Bungram Android", tokens.deviceId)
            }.onSuccess { challenge ->
                _state.update {
                    it.copy(
                        step = AuthStep.CODE,
                        challengeId = challenge.challengeId,
                        devCode = challenge.devCode,
                        busy = false,
                    )
                }
                startResendWindow()
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.friendly(), shakeTick = it.shakeTick + 1) }
            }
        }
    }

    fun verify() {
        val s = _state.value
        val challengeId = s.challengeId ?: return
        if (s.code.length < 6) {
            _state.update { it.copy(error = "Enter the 6-digit code", shakeTick = it.shakeTick + 1) }
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
                _state.update {
                    it.copy(busy = false, code = "", error = e.friendly(), shakeTick = it.shakeTick + 1)
                }
            }
        }
    }

    /** Resend re-issues a challenge; the old one is dropped server-side by the rate limiter. */
    fun resend() {
        if (_state.value.resendInSec > 0) return
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { api.requestOtp(s.e164, "Bungram Android", tokens.deviceId) }
                .onSuccess { challenge ->
                    _state.update {
                        it.copy(
                            busy = false,
                            challengeId = challenge.challengeId,
                            devCode = challenge.devCode,
                            code = "",
                        )
                    }
                    startResendWindow()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.friendly()) } }
        }
    }

    private fun startResendWindow() {
        resendJob?.cancel()
        resendJob = viewModelScope.launch {
            _state.update { it.copy(resendInSec = RESEND_SECONDS) }
            while (isActive && _state.value.resendInSec > 0) {
                delay(1_000)
                _state.update { it.copy(resendInSec = (it.resendInSec - 1).coerceAtLeast(0)) }
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

    private companion object {
        const val RESEND_SECONDS = 60
    }
}
