package app.bungram.feature.groupinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bungram.data.model.Chat
import app.bungram.data.remote.BungramApi
import app.bungram.data.remote.MemberDto
import app.bungram.data.repo.RemoteChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupInfoUiState(
    val chat: Chat? = null,
    val members: List<MemberDto> = emptyList(),
    val canManage: Boolean = false,
    val inviteSlug: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val closed: Boolean = false,
)

/**
 * Group / channel info and administration.
 *
 * Every action here is optimistic in the UI and authoritative on the server: the member list is
 * replaced by whatever the server returns, so a rejected removal simply does not happen.
 */
class GroupInfoViewModel(
    private val api: BungramApi,
    private val repository: RemoteChatRepository,
    private val chatId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(GroupInfoUiState(chat = repository.chat(chatId)))
    val state: StateFlow<GroupInfoUiState> = _state.asStateFlow()

    private val meId: String get() = api.tokens.userId.orEmpty()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repository.chatDetail(chatId) }
                .onSuccess { detail ->
                    _state.update {
                        it.copy(
                            chat = detail.chat,
                            members = detail.members,
                            canManage = detail.canManage,
                            inviteSlug = detail.chat.inviteSlug,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun mintInvite() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { repository.mintInvite(chatId) }
                .onSuccess { inv -> _state.update { it.copy(busy = false, inviteSlug = inv.slug, notice = "Invite link ready") } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    fun revokeInvite() {
        viewModelScope.launch {
            runCatching { repository.revokeInvite(chatId) }
            _state.update { it.copy(inviteSlug = null, notice = "Invite link revoked") }
        }
    }

    fun addByPhone(phone: String) {
        val digits = phone.filter(Char::isDigit)
        if (digits.length < 6) {
            _state.update { it.copy(error = "Enter a full phone number") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                val user = api.lookup("+$digits")
                repository.addMembers(chatId, listOf(user.id))
            }.onSuccess { members ->
                _state.update { it.copy(busy = false, members = members, notice = "Member added") }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Could not add that number") }
            }
        }
    }

    fun remove(userId: String) {
        viewModelScope.launch {
            runCatching { repository.removeMember(chatId, userId) }
                .onSuccess { members -> _state.update { it.copy(members = members) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun toggleAdmin(userId: String, makeAdmin: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setRole(chatId, userId, if (makeAdmin) "ADMIN" else "MEMBER") }
                .onSuccess { members -> _state.update { it.copy(members = members) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun rename(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            runCatching { repository.updateChat(chatId, title.trim(), null) }
                .onSuccess { chat -> _state.update { it.copy(chat = chat, notice = "Name updated") } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun leave() {
        viewModelScope.launch {
            repository.leaveChat(chatId)
            _state.update { it.copy(closed = true) }
        }
    }

    fun delete() {
        viewModelScope.launch {
            repository.deleteChat(chatId)
            _state.update { it.copy(closed = true) }
        }
    }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }
}
