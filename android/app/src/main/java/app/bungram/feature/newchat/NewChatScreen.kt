package app.bungram.feature.newchat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bungram.core.design.BungramAvatar
import app.bungram.core.design.BungramBlue
import app.bungram.core.design.Canvas
import app.bungram.core.design.Danger
import app.bungram.core.design.Divider
import app.bungram.core.design.SurfaceMuted
import app.bungram.core.design.TextOnBlue
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary
import app.bungram.core.design.TextTertiary
import app.bungram.data.model.User
import app.bungram.data.remote.ApiException
import app.bungram.data.remote.BungramApi
import app.bungram.data.repo.RemoteChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewChatUiState(
    val phone: String = "",
    val busy: Boolean = false,
    val result: User? = null,
    val error: String? = null,
)

class NewChatViewModel(
    private val api: BungramApi,
    private val repository: RemoteChatRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NewChatUiState())
    val state: StateFlow<NewChatUiState> = _state.asStateFlow()

    fun onPhoneChange(value: String) {
        _state.update { it.copy(phone = value.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(20), error = null, result = null) }
    }

    fun search() {
        val phone = _state.value.phone.replace(" ", "")
        if (phone.length < 6) {
            _state.update { it.copy(error = "Enter a phone number with country code") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, result = null) }
            runCatching { api.lookup(phone) }
                .onSuccess { user -> _state.update { it.copy(busy = false, result = user) } }
                .onFailure { e ->
                    val msg = if (e is ApiException) "No Bungram account with that number" else e.message
                    _state.update { it.copy(busy = false, error = msg) }
                }
        }
    }

    fun start(onChat: (String) -> Unit) {
        val peer = _state.value.result ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { api.createChat(peer.id) }
                .onSuccess { chat ->
                    repository.syncNow()
                    _state.update { it.copy(busy = false) }
                    onChat(chat.id)
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Could not open the chat") } }
        }
    }
}

@Composable
fun NewChatScreen(
    state: NewChatUiState,
    onBack: () -> Unit,
    onPhoneChange: (String) -> Unit,
    onSearch: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier.size(22.dp).clickable(onClick = onBack),
                tint = TextPrimary,
            )
            Spacer(Modifier.width(14.dp))
            Text("New chat", style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "Type a phone number. Bungram never uploads your address book.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )

        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = state.phone,
            onValueChange = onPhoneChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("+62 822 000 002", color = TextTertiary) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BungramBlue,
                unfocusedBorderColor = Divider,
                cursorColor = BungramBlue,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
            ),
        )

        if (state.error != null) {
            Spacer(Modifier.height(10.dp))
            Text(state.error, style = MaterialTheme.typography.bodySmall, color = Danger)
        }

        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (state.busy) SurfaceMuted else BungramBlue)
                .clickable(enabled = !state.busy, onClick = onSearch),
            contentAlignment = Alignment.Center,
        ) {
            if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), color = BungramBlue, strokeWidth = 2.dp)
            else Text("Find account", color = TextOnBlue, fontWeight = FontWeight.SemiBold)
        }

        val result = state.result
        if (result != null) {
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceMuted)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BungramAvatar(
                    name = result.displayName,
                    url = result.avatarUrl,
                    size = 46.dp,
                    online = result.presence == app.bungram.data.model.Presence.ONLINE,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(result.displayName, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(
                        result.username?.let { "@$it" } ?: result.phone.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(BungramBlue)
                        .clickable(enabled = !state.busy, onClick = onStart)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text("Message", color = TextOnBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "End-to-end encryption keys are exchanged when the first message is sent.",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier.padding(bottom = 18.dp),
        )
    }
}
