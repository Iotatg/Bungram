package app.bungram.feature.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bungram.data.model.Chat
import app.bungram.data.model.User
import app.bungram.data.repo.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MessagesUiState(
    val query: String = "",
    val chats: List<Chat> = emptyList(),
    val quickPeople: List<User> = emptyList(),
    val archivedCount: Int = 0,
    val isLoading: Boolean = false,
)

class MessagesViewModel(private val repository: ChatRepository) : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<MessagesUiState> =
        combine(repository.chats, query) { chats, q ->
            MessagesUiState(
                query = q,
                chats = if (q.isBlank()) chats else chats.filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.lastMessagePreview.contains(q, ignoreCase = true)
                },
                // The avatar row is built from real conversations, never from a fixture.
                quickPeople = chats.mapNotNull { it.peerId?.let(repository::user) }.distinctBy { it.id }.take(10),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessagesUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun openChat(chatId: String) {
        viewModelScope.launch { repository.markRead(chatId) }
    }
}
