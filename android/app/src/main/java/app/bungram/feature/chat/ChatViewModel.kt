package app.bungram.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bungram.data.media.VoiceRecorder
import app.bungram.data.model.Chat
import app.bungram.data.model.Message
import app.bungram.data.model.MessageKind
import app.bungram.data.model.User
import app.bungram.data.repo.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ChatUiState(
    val chat: Chat? = null,
    val peer: User? = null,
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    val attachmentMenuOpen: Boolean = false,
    val isRecording: Boolean = false,
    val recordingMs: Long = 0L,
    val replyTo: Message? = null,
    val error: String? = null,
    /** True while a picked file is being uploaded — disables the send button. */
    val isSendingMedia: Boolean = false,
    /** True while the peer is composing — the header shows "typing…" instead of "Online". */
    val isPeerTyping: Boolean = false,
    val stickerPacks: List<app.bungram.data.remote.StickerPackDto> = emptyList(),
    /** A live ratchet session exists — the header shows a lock. */
    val isEncrypted: Boolean = false,
    /** Safety number for the verification sheet; null until the handshake completes. */
    val safetyNumber: String? = null,
)

/** Composer idle window: after this long without a keystroke we tell the peer we stopped. */
private const val TYPING_IDLE_MS = 2_500L

class ChatViewModel(
    private val repository: ChatRepository,
    private val recorder: VoiceRecorder,
    private val chatId: String,
) : ViewModel() {

    private val draft = MutableStateFlow("")
    private val menuOpen = MutableStateFlow(false)
    private val recording = MutableStateFlow(false)
    private val recordingMs = MutableStateFlow(0L)
    private val replyTo = MutableStateFlow<Message?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val sendingMedia = MutableStateFlow(false)
    private val stickers = MutableStateFlow<List<app.bungram.data.remote.StickerPackDto>>(emptyList())

    private var ticker: Job? = null
    private var typingJob: Job? = null
    private var typingAnnounced = false

    private data class RecState(val active: Boolean, val ms: Long, val peerTyping: Boolean)
    private data class AuxState(
        val reply: Message?,
        val error: String?,
        val sending: Boolean,
        val packs: List<app.bungram.data.remote.StickerPackDto>,
    )

    val state: StateFlow<ChatUiState> = combine(
        repository.messages(chatId),
        draft,
        menuOpen,
        combine(recording, recordingMs, repository.typing(chatId)) { active, ms, typing ->
            RecState(active, ms, typing)
        },
        combine(replyTo, error, sendingMedia, stickers) { reply, err, sending, packs ->
            AuxState(reply, err, sending, packs)
        },
    ) { messages, text, menu, rec, aux ->
        val chat = repository.chat(chatId)
        ChatUiState(
            chat = chat,
            peer = chat?.peerId?.let(repository::user),
            messages = messages,
            draft = text,
            attachmentMenuOpen = menu,
            isRecording = rec.active,
            recordingMs = rec.ms,
            replyTo = aux.reply,
            error = aux.error,
            isSendingMedia = aux.sending,
            isPeerTyping = rec.peerTyping,
            stickerPacks = aux.packs,
            isEncrypted = repository.isEncrypted(chatId),
            safetyNumber = repository.safetyNumber(chatId),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            runCatching { repository.stickers() }.getOrNull()?.let { stickers.value = it }
        }
    }

    fun sendSticker(emoji: String) {
        app.bungram.core.Sounds.send()
        viewModelScope.launch { repository.send(chatId, emoji, MessageKind.STICKER) }
    }

    fun onDraftChange(value: String) {
        draft.value = value
        if (value.isBlank()) {
            stopTyping()
            return
        }
        // One "started typing" frame per burst, then silence — a frame per keystroke would be
        // 20 requests for a one-line message.
        if (!typingAnnounced) {
            typingAnnounced = true
            viewModelScope.launch { repository.sendTyping(chatId, true) }
        }
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(TYPING_IDLE_MS)
            stopTyping()
        }
    }

    private fun stopTyping() {
        typingJob?.cancel()
        typingJob = null
        if (!typingAnnounced) return
        typingAnnounced = false
        viewModelScope.launch { repository.sendTyping(chatId, false) }
    }

    fun toggleAttachmentMenu() { menuOpen.value = !menuOpen.value }

    fun closeAttachmentMenu() { menuOpen.value = false }

    fun setReply(message: Message?) { replyTo.value = message }

    fun delete(message: Message) {
        viewModelScope.launch { repository.deleteMessage(chatId, message.id) }
    }

    fun dismissError() { error.value = null }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty()) return
        draft.value = ""
        menuOpen.value = false
        stopTyping()
        app.bungram.core.Sounds.send()
        viewModelScope.launch { repository.send(chatId, text) }
    }

    /* ------------------------------------------------------------------------------ media */

    fun sendMedia(uri: String, kind: MessageKind, durationMs: Long = 0L) {
        menuOpen.value = false
        stopTyping()
        sendingMedia.value = true
        viewModelScope.launch {
            runCatching { repository.sendMedia(chatId, uri, kind, durationMs) }
                .onFailure { error.value = it.message ?: "could not send that file" }
            sendingMedia.value = false
        }
    }

    /* ----------------------------------------------------------------------------- voice */

    /** Returns false when the microphone could not be opened (permission or busy device). */
    fun startRecording(): Boolean {
        if (recording.value) return true
        val ok = recorder.start()
        if (!ok) {
            error.value = "microphone unavailable"
            return false
        }
        recording.value = true
        recordingMs.value = 0L
        val startedAt = System.currentTimeMillis()
        ticker = viewModelScope.launch {
            while (isActive) {
                recordingMs.value = System.currentTimeMillis() - startedAt
                delay(100)
            }
        }
        return true
    }

    fun stopRecording() {
        if (!recording.value) return
        ticker?.cancel()
        ticker = null
        recording.value = false
        val result = recorder.stop()
        recordingMs.value = 0L
        if (result == null) {
            error.value = "recording was too short"
            return
        }
        sendingMedia.value = true
        viewModelScope.launch {
            runCatching {
                repository.sendMedia(chatId, "file://${result.file.absolutePath}", MessageKind.VOICE, result.durationMs)
            }.onFailure { error.value = it.message ?: "could not send the voice note" }
            sendingMedia.value = false
        }
    }

    fun cancelRecording() {
        ticker?.cancel()
        ticker = null
        recording.value = false
        recordingMs.value = 0L
        recorder.cancel()
    }

    fun markRead() {
        viewModelScope.launch { repository.markRead(chatId) }
    }

    override fun onCleared() {
        ticker?.cancel()
        stopTyping()
        if (recording.value) recorder.cancel()
        super.onCleared()
    }
}
