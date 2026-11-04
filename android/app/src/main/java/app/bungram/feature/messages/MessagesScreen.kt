package app.bungram.feature.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bungram.core.design.BungramAvatar
import app.bungram.core.design.BungramBlue
import app.bungram.core.design.BungramSearchField
import app.bungram.core.design.Canvas
import app.bungram.core.design.CircleIconButton
import app.bungram.core.design.DeliveryTicks
import app.bungram.core.design.SectionHeader
import app.bungram.core.design.SurfaceMuted
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary
import app.bungram.core.design.TextTertiary
import app.bungram.core.design.UnreadBadge
import app.bungram.core.util.TimeFormat
import app.bungram.data.model.Chat
import app.bungram.data.model.DeliveryState
import app.bungram.data.model.User

/**
 * Screen 1 of the reference: the conversation list.
 * Structure is fixed: header → search → "New Group / Archive" rail → chat rows.
 */
@Composable
fun MessagesScreen(
    state: MessagesUiState,
    onQueryChange: (String) -> Unit,
    onChatClick: (String) -> Unit,
    onNewGroup: () -> Unit,
    onArchive: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Canvas)) {

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 22.dp, end = 18.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Message",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.weight(1f),
            )
            CircleIconButton(Icons.Rounded.QrCodeScanner, "Scan QR", onClick = {})
            Spacer(Modifier.width(10.dp))
            CircleIconButton(Icons.Rounded.EditNote, "New message", onClick = onNewGroup)
        }

        BungramSearchField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            onFilterClick = {},
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionHeader(left = "New Group", right = "Archive (${state.archivedCount})", onRightClick = onArchive)
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.quickPeople, key = { it.id }) { person ->
                QuickPerson(person = person, hasStory = person.id.hashCode() % 3 != 0)
            }
        }

        Spacer(Modifier.height(6.dp))

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
        ) {
            items(state.chats, key = { it.id }) { chat ->
                ChatRow(chat = chat, onClick = { onChatClick(chat.id) })
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun QuickPerson(person: User, hasStory: Boolean) {
    Column(
        Modifier
            .width(60.dp)
            .clickable { },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BungramAvatar(
            name = person.displayName,
            url = person.avatarUrl,
            size = 52.dp,
            ring = if (hasStory) BungramBlue else null,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            person.displayName.substringBefore(" "),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChatRow(chat: Chat, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BungramAvatar(name = chat.title, url = chat.avatarUrl, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (chat.isVerified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Rounded.Verified,
                        null,
                        Modifier.size(13.dp),
                        tint = BungramBlue,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                chat.lastMessagePreview,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                TimeFormat.chatRowTime(chat.lastMessageAtEpochMs),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = TextTertiary,
            )
            Spacer(Modifier.height(6.dp))
            when {
                chat.unreadCount > 0 -> UnreadBadge(chat.unreadCount)
                chat.lastMessageFromMe -> DeliveryTicks(
                    if (chat.lastMessageRead) DeliveryState.READ else DeliveryState.DELIVERED,
                )
                else -> Spacer(Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ArchivePill() {
    Box(
        Modifier
            .clip(CircleShape)
            .background(SurfaceMuted)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text("Archive", fontSize = 11.sp, color = TextSecondary)
    }
}
