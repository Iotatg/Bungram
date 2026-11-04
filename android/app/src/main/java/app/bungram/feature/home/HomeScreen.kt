package app.bungram.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.bungram.AppGraph
import app.bungram.core.design.BungramBottomBar
import app.bungram.core.design.BungramTab
import app.bungram.core.design.Canvas
import app.bungram.core.design.SurfaceMuted
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary
import app.bungram.feature.calls.CallsScreen
import app.bungram.feature.messages.MessagesScreen
import app.bungram.feature.messages.MessagesViewModel
import app.bungram.feature.profile.ProfileScreen
import app.bungram.feature.status.StatusScreen
import app.bungram.feature.status.StatusViewModel
import kotlinx.coroutines.launch

/**
 * Hosts the four tabs behind the shared bottom bar. Tab state survives process death via
 * rememberSaveable; each tab keeps its own ViewModel so scroll/query state is independent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChat: (String) -> Unit,
    onOpenCall: (String) -> Unit,
    onNewChat: () -> Unit = {},
    onLogout: () -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onCreateChannel: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(BungramTab.MESSAGE) }
    var showComposeSheet by rememberSaveable { mutableStateOf(false) }

    val messagesVm: MessagesViewModel = viewModel(
        factory = viewModelFactory { initializer { MessagesViewModel(AppGraph.repository) } }
    )
    val statusVm: StatusViewModel = viewModel(
        factory = viewModelFactory { initializer { StatusViewModel(AppGraph.repository) } }
    )

    val messagesState by messagesVm.state.collectAsStateWithLifecycle()
    val statusState by statusVm.state.collectAsStateWithLifecycle()
    val calls by AppGraph.repository.calls.collectAsStateWithLifecycle()

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottom = 86.dp + navBottom

    Box(Modifier.fillMaxSize().background(Canvas)) {
        // Tabs slide in the direction of travel: moving right in the bar moves the content left.
        // A plain cross-fade would lose the sense of position that a four-tab bar implies.
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val distance = { full: Int -> if (forward) full / 12 else -full / 12 }
                (slideInHorizontally(animationSpec = tween(220)) { distance(it) } + fadeIn(tween(180)))
                    .togetherWith(
                        slideOutHorizontally(animationSpec = tween(220)) { -distance(it) } + fadeOut(tween(140))
                    )
            },
            label = "tab",
        ) { current ->
        when (current) {
            BungramTab.MESSAGE -> MessagesScreen(
                state = messagesState,
                onQueryChange = messagesVm::onQueryChange,
                onChatClick = onOpenChat,
                onNewGroup = { showComposeSheet = true },
                onArchive = { showComposeSheet = true },
                bottomPadding = contentBottom,
            )

            BungramTab.STATUS -> StatusScreen(
                state = statusState,
                onQueryChange = statusVm::onQueryChange,
                onOpen = statusVm::open,
                onAddStatus = { showComposeSheet = true },
                bottomPadding = contentBottom,
            )

            BungramTab.CALL -> CallsScreen(
                calls = calls,
                onCall = { onOpenCall(it.peer.id) },
                bottomPadding = contentBottom,
            )

            BungramTab.PROFILE -> ProfileScreen(
                bottomPadding = contentBottom,
                onLogout = onLogout,
            )
        }
        }

        BungramBottomBar(
            selected = tab,
            onSelect = { tab = it },
            onCompose = { showComposeSheet = true },
            callBadge = 1,
            bottomInset = navBottom,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showComposeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        ModalBottomSheet(
            onDismissRequest = { showComposeSheet = false },
            sheetState = sheetState,
            containerColor = Canvas,
            dragHandle = {
                Box(
                    Modifier
                        .padding(top = 10.dp, bottom = 6.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .background(SurfaceMuted, RoundedCornerShape(2.dp))
                )
            },
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                Text("Start something new", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                ComposeAction(Icons.Rounded.PersonAdd, "New chat", "Message a phone number or @username") {
                    showComposeSheet = false
                    onNewChat()
                }
                ComposeAction(Icons.Rounded.Group, "New group", "Up to 200 members, admin rights") {
                    showComposeSheet = false
                    onCreateGroup()
                }
                ComposeAction(Icons.Rounded.Campaign, "New channel", "Broadcast to unlimited subscribers") {
                    showComposeSheet = false
                    onCreateChannel()
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun ComposeAction(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .background(SurfaceMuted, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = TextPrimary)
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.Center) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(subtitle, fontSize = 10.5.sp, color = TextSecondary)
        }
    }
}
