package app.bungram.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bungram.data.model.Attachment
import app.bungram.data.model.Chat
import app.bungram.data.model.Message
import app.bungram.data.model.MessageKind
import app.bungram.data.model.User
import app.bungram.data.repo.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val chat: Chat? = null,
    val peer: User? = null,
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    val attachmentMenuOpen: Boolean = false,
    val isRecording: Boolean = false,
    val replyTo: Message? = null,
)

class ChatViewModel(
    private val repository: ChatRepository,
    private val chatId: String,
) : ViewModel() {

    private val draft = MutableStateFlow("")
    private val menuOpen = MutableStateFlow(false)
    private val recording = MutableStateFlow(false)
    private val replyTo = MutableStateFlow<Message?>(null)

    val state: StateFlow<ChatUiState> = combine(
        repository.messages(chatId),
        draft,
        menuOpen,
        recording,
        replyTo,
    ) { messages, text, menu, rec, reply ->
        val chat = repository.chat(chatId)
        ChatUiState(
            chat = chat,
            peer = chat?.peerId?.let(repository::user),
            messages = messages,
            draft = text,
            attachmentMenuOpen = menu,
            isRecording = rec,
            replyTo = reply,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun onDraftChange(value: String) { draft.value = value }

    fun toggleAttachmentMenu() { menuOpen.value = !menuOpen.value }

    fun closeAttachmentMenu() { menuOpen.value = false }

    fun setReply(message: Message?) { replyTo.value = message }

    fun toggleRecording() { recording.value = !recording.value }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty()) return
        draft.value = ""
        menuOpen.value = false
        viewModelScope.launch { repository.send(chatId, text) }
    }

    fun sendAttachment(kind: MessageKind, attachment: Attachment, caption: String = "") {
        menuOpen.value = false
        viewModelScope.launch { repository.send(chatId, caption, kind, attachment) }
    }

    fun markRead() {
        viewModelScope.launch { repository.markRead(chatId) }
    }
}
