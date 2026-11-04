package app.bungram.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bungram.core.design.BungramAvatar
import app.bungram.core.design.BungramBlue
import app.bungram.core.design.BungramBlueGhost
import app.bungram.core.design.Canvas as CanvasColor
import app.bungram.core.design.ChatCanvas
import app.bungram.core.design.ChatPatternDot
import app.bungram.core.design.CircleIconButton
import app.bungram.core.design.DeliveryTicks
import app.bungram.core.design.Divider
import app.bungram.core.design.SurfaceMuted
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary
import app.bungram.core.design.TextTertiary
import app.bungram.core.util.TimeFormat
import app.bungram.data.model.DeliveryState
import app.bungram.data.model.Message
import app.bungram.data.model.MessageKind

/**
 * Screen 2 of the reference: 1:1 chat.
 * Header → patterned message canvas → composer. The attachment sheet is an inline card that
 * rises from the composer, exactly like the reference, instead of a modal bottom sheet.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleAttachment: () -> Unit,
    onPickAttachment: (MessageKind) -> Unit,
    onToggleRecording: () -> Unit,
    onVideoCall: () -> Unit,
    onVoiceCall: () -> Unit,
    onClearReply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(state.messages.size) }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(ChatCanvas)
            .imePadding()
    ) {
        ChatHeader(
            name = state.chat?.title ?: "Chat",
            online = state.peer?.presence == app.bungram.data.model.Presence.ONLINE,
            subtitle = if (state.peer?.presence == app.bungram.data.model.Presence.ONLINE) "Online" else "last seen recently",
            onBack = onBack,
            onVideoCall = onVideoCall,
            onVoiceCall = onVoiceCall,
        )

        Box(Modifier.weight(1f)) {
            ChatPattern(Modifier.fillMaxSize())
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (state.messages.isNotEmpty()) {
                    item(key = "date") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = Color.White,
                                shadowElevation = 1.dp,
                            ) {
                                Text(
                                    TimeFormat.dayLabel(state.messages.first().createdAtEpochMs),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    fontSize = 9.5.sp,
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }
        }

        if (state.replyTo != null) {
            ReplyPreview(state.replyTo.text, onClearReply)
        }

        AnimatedVisibility(
            visible = state.attachmentMenuOpen,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut(),
        ) {
            AttachmentMenu(onPick = onPickAttachment)
        }

        Composer(
            draft = state.draft,
            onDraftChange = onDraftChange,
            onSend = onSend,
            onToggleAttachment = onToggleAttachment,
            onToggleRecording = onToggleRecording,
            isRecording = state.isRecording,
        )
    }
}

@Composable
private fun ChatHeader(
    name: String,
    online: Boolean,
    subtitle: String,
    onBack: () -> Unit,
    onVideoCall: () -> Unit,
    onVoiceCall: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CanvasColor)
            .statusBarsPadding()
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ChevronLeft, "Back", Modifier.size(26.dp), tint = TextPrimary)
        }
        BungramAvatar(name = name, url = null, size = 40.dp, online = online)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, fontSize = 10.sp, color = TextSecondary)
        }
        IconButton(onClick = onVideoCall) {
            Icon(Icons.Rounded.Videocam, "Video call", Modifier.size(22.dp), tint = TextPrimary)
        }
        IconButton(onClick = onVoiceCall) {
            Icon(Icons.Rounded.Call, "Voice call", Modifier.size(20.dp), tint = TextPrimary)
        }
    }
}

@Composable
private fun ChatPattern(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val step = 24.dp.toPx()
        val radius = 0.9.dp.toPx()
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else step / 2f
            while (x < size.width) {
                drawCircle(ChatPatternDot, radius = radius, center = Offset(x, y))
                x += step
            }
            y += step
            row++
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val outgoing = message.isOutgoing
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (outgoing) 18.dp else 5.dp,
        bottomEnd = if (outgoing) 5.dp else 18.dp,
    )
    val textColor = if (outgoing) Color.White else TextPrimary
    val metaColor = if (outgoing) Color.White.copy(alpha = 0.72f) else TextTertiary

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = shape,
            color = if (outgoing) BungramBlue else Color.White,
            shadowElevation = if (outgoing) 0.dp else 1.5.dp,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                if (message.kind == MessageKind.FILE) {
                    FileCard(message, outgoing)
                    Spacer(Modifier.height(8.dp))
                }
                if (message.text.isNotEmpty()) {
                    Text(
                        message.text,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = textColor,
                    )
                }
                Row(
                    Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(TimeFormat.messageTime(message.createdAtEpochMs), fontSize = 8.5.sp, color = metaColor)
                    if (outgoing) {
                        Spacer(Modifier.width(3.dp))
                        DeliveryTicks(message.delivery, onBlue = true, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FileCard(message: Message, outgoing: Boolean) {
    val attachment = message.attachment
    Row(
        Modifier
            .width(238.dp)
            .background(
                if (outgoing) Color.White.copy(alpha = 0.14f) else SurfaceMuted,
                RoundedCornerShape(12.dp),
            )
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(if (outgoing) Color.White.copy(alpha = 0.2f) else BungramBlueGhost, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (attachment?.mimeType?.contains("pdf") == true) Icons.Rounded.PictureAsPdf else Icons.Rounded.InsertDriveFile,
                null,
                Modifier.size(18.dp),
                tint = if (outgoing) Color.White else BungramBlue,
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                attachment?.fileName ?: "File",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (outgoing) Color.White else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                TimeFormat.fileSize(attachment?.sizeBytes ?: 0L),
                fontSize = 9.sp,
                color = if (outgoing) Color.White.copy(alpha = 0.7f) else TextSecondary,
            )
        }
        Box(
            Modifier
                .size(24.dp)
                .background(if (outgoing) Color.White.copy(alpha = 0.22f) else Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Download,
                "Download",
                Modifier.size(14.dp),
                tint = if (outgoing) Color.White else TextPrimary,
            )
        }
    }
}

@Composable
private fun ReplyPreview(text: String, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(28.dp)
                .background(BungramBlue, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 11.sp, color = TextSecondary, maxLines = 1, modifier = Modifier.weight(1f))
        IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Rounded.Close, "Cancel reply", Modifier.size(15.dp), tint = TextSecondary)
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleAttachment: () -> Unit,
    onToggleRecording: () -> Unit,
    isRecording: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CanvasColor)
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .background(BungramBlue, CircleShape)
                .clickable(onClick = onToggleAttachment),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, "Attach", Modifier.size(22.dp), tint = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier
                .weight(1f)
                .height(44.dp)
                .background(SurfaceMuted, RoundedCornerShape(22.dp))
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) {
                    Text("Type Here...", fontSize = 12.5.sp, color = TextTertiary)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                    cursorBrush = SolidColor(BungramBlue),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                Modifier
                    .size(32.dp)
                    .clickable { if (draft.isBlank()) onToggleRecording() else onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (draft.isBlank()) Icons.Rounded.Mic else Icons.Rounded.Send,
                    contentDescription = if (draft.isBlank()) "Record voice note" else "Send",
                    modifier = Modifier.size(18.dp),
                    tint = if (isRecording) app.bungram.core.design.Danger else if (draft.isBlank()) TextSecondary else BungramBlue,
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------------------------------
 * Attachment menu — floating white card with the six actions from the reference.
 * ----------------------------------------------------------------------------------------------- */

private data class AttachmentAction(val icon: ImageVector, val label: String, val kind: MessageKind?)

@Composable
private fun AttachmentMenu(onPick: (MessageKind) -> Unit) {
    val actions = listOf(
        AttachmentAction(Icons.Rounded.CameraAlt, "Camera", MessageKind.IMAGE),
        AttachmentAction(Icons.Rounded.InsertDriveFile, "Document", MessageKind.FILE),
        AttachmentAction(Icons.Rounded.PhotoCamera, "Photo & Video", MessageKind.IMAGE),
        AttachmentAction(Icons.Rounded.LocationOn, "Location", MessageKind.LOCATION),
        AttachmentAction(Icons.Rounded.ContactPage, "Contact", MessageKind.CONTACT),
        AttachmentAction(Icons.Rounded.Poll, "Polling", MessageKind.POLL),
    )
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        shadowElevation = 16.dp,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            actions.forEach { action ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(action.kind ?: MessageKind.TEXT) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .background(SurfaceMuted, RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(action.icon, null, Modifier.size(16.dp), tint = TextPrimary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(action.label, fontSize = 12.5.sp, color = TextPrimary)
                }
            }
        }
    }
}
