package app.bungram.feature.createchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bungram.data.model.Chat
import app.bungram.data.model.User
import app.bungram.data.remote.BungramApi
import app.bungram.data.repo.RemoteChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CreateMode { GROUP, CHANNEL }

data class CreateChatUiState(
    val mode: CreateMode = CreateMode.GROUP,
    val title: String = "",
    val description: String = "",
    val query: String = "",
    val selected: List<User> = emptyList(),
    val suggestions: List<User> = emptyList(),
    val isPublic: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canCreate: Boolean get() = title.trim().length >= 2 && !busy
    val subtitle: String get() = if (mode == CreateMode.GROUP) {
        "New group"
    } else {
        "New channel"
    }
}

/**
 * Creates a group or a broadcast channel. The only difference the user sees is whether other
 * members can post; the server enforces it, the UI just hides the composer.
 */
class CreateChatViewModel(
    private val api: BungramApi,
    private val repository: RemoteChatRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateChatUiState())
    val state: StateFlow<CreateChatUiState> = _state.asStateFlow()

    fun setMode(mode: CreateMode) = _state.update { it.copy(mode = mode, error = null) }

    fun onTitleChange(value: String) = _state.update { it.copy(title = value.take(64), error = null) }

    fun onDescriptionChange(value: String) = _state.update { it.copy(description = value.take(140)) }

    fun togglePublic() = _state.update { it.copy(isPublic = !it.isPublic) }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        val digits = value.filter(Char::isDigit)
        if (digits.length < 6) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        viewModelScope.launch {
            runCatching { api.lookup("+$digits") }.getOrNull()?.let { user ->
                _state.update { s ->
                    if (s.selected.any { it.id == user.id }) s
                    else s.copy(suggestions = listOf(user))
                }
            }
        }
    }

    fun add(user: User) = _state.update { s ->
        if (s.selected.any { it.id == user.id }) s
        else s.copy(selected = s.selected + user, query = "", suggestions = emptyList())
    }

    fun remove(userId: String) = _state.update { s ->
        s.copy(selected = s.selected.filterNot { it.id == userId })
    }

    fun create(onCreated: (Chat) -> Unit) {
        val s = _state.value
        if (!s.canCreate) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                repository.createGroup(
                    title = s.title.trim(),
                    description = s.description.trim(),
                    memberIds = s.selected.map { it.id },
                    kind = s.mode.name,
                    isPublic = s.isPublic,
                )
            }.onSuccess { chat ->
                _state.update { it.copy(busy = false) }
                onCreated(chat)
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Could not create the chat") }
            }
        }
    }
}
