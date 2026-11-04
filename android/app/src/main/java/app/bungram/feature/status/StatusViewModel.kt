package app.bungram.feature.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bungram.data.demo.DemoData
import app.bungram.data.model.StoryFeed
import app.bungram.data.repo.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StatusUiState(
    val query: String = "",
    val myStatus: StoryFeed? = null,
    val feeds: List<StoryFeed> = emptyList(),
)

class StatusViewModel(private val repository: ChatRepository) : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<StatusUiState> = combine(repository.stories, query) { feeds, q ->
        val filtered = if (q.isBlank()) feeds else feeds.filter {
            it.owner.displayName.contains(q, ignoreCase = true)
        }
        StatusUiState(
            query = q,
            myStatus = filtered.firstOrNull { it.owner.id == DemoData.ME_ID },
            feeds = filtered.filterNot { it.owner.id == DemoData.ME_ID },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun open(feed: StoryFeed) {
        viewModelScope.launch { feed.stories.forEach { repository.markStoryViewed(it.id) } }
    }
}
