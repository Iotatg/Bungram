package app.bungram.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallMissed
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bungram.core.design.BungramAvatar
import app.bungram.core.design.BungramBlue
import app.bungram.core.design.BungramSearchField
import app.bungram.core.design.Canvas
import app.bungram.core.design.CircleIconButton
import app.bungram.core.design.Danger
import app.bungram.core.design.Online
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary
import app.bungram.core.design.TextTertiary
import app.bungram.core.util.TimeFormat
import app.bungram.data.model.CallDirection
import app.bungram.data.model.CallLog
import app.bungram.data.model.CallType

@Composable
fun CallsScreen(
    calls: List<CallLog>,
    onCall: (CallLog) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Canvas)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 22.dp, end = 18.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Call", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
            CircleIconButton(Icons.Rounded.Call, "Start call", onClick = {})
        }

        BungramSearchField(
            value = "",
            onValueChange = {},
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        Spacer(Modifier.height(12.dp))

        LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
            items(calls, key = { it.id }) { call ->
                CallRow(call, onClick = { onCall(call) })
            }
        }
    }
}

@Composable
private fun CallRow(call: CallLog, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BungramAvatar(name = call.peer.displayName, url = call.peer.avatarUrl, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(call.peer.displayName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (call.direction) {
                        CallDirection.INCOMING -> Icons.Rounded.CallReceived
                        CallDirection.OUTGOING -> Icons.Rounded.CallMade
                        CallDirection.MISSED -> Icons.Rounded.CallMissed
                    },
                    null,
                    Modifier.size(13.dp),
                    tint = if (call.direction == CallDirection.MISSED) Danger else Online,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    buildString {
                        append(TimeFormat.chatRowTime(call.startedAtEpochMs))
                        if (call.durationSec > 0) append(" · ${TimeFormat.duration(call.durationSec)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (call.type == CallType.VIDEO) BungramBlue.copy(alpha = 0.1f) else Canvas),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (call.type == CallType.VIDEO) Icons.Rounded.Videocam else Icons.Rounded.Call,
                    null,
                    Modifier.size(18.dp),
                    tint = if (call.type == CallType.VIDEO) BungramBlue else TextTertiary,
                )
            }
        }
    }
}
