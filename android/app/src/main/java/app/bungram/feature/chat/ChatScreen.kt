package app.bungram.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bungram.AppGraph
import app.bungram.core.design.BungramAvatar
import app.bungram.core.design.BungramBlue
import app.bungram.core.design.BungramBlueGhost
import app.bungram.core.design.Canvas as CanvasColor
import app.bungram.core.design.ChatCanvas
import app.bungram.core.design.ChatPatternDot
import app.bungram.core.design.DeliveryTicks
import app.bungram.core.design.Danger
import app.bungram.core.design.SurfaceMuted
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary
import app.bungram.core.design.TextTertiary
import app.bungram.core.util.TimeFormat
import app.bungram.data.model.DeliveryState
import app.bungram.data.model.Message
import app.bungram.data.model.MessageKind
import java.io.File
import java.util.UUID

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
    onSendMedia: (uri: String, kind: MessageKind, durationMs: Long) -> Unit,
    onStartRecording: () -> Boolean,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onVideoCall: () -> Unit,
    onVoiceCall: () -> Unit,
    onClearReply: () -> Unit,
    onDismissError: () -> Unit,
    onOpenInfo: () -> Unit = {},
    onSendSticker: (String) -> Unit = {},
    stickerPacks: List<app.bungram.data.remote.StickerPackDto> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var showStickers by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var viewer by remember { mutableStateOf<Message?>(null) }
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }

    val playingId by AppGraph.audio.playingId.collectAsStateWithLifecycle()
    val playbackProgress by AppGraph.audio.progress.collectAsStateWithLifecycle()

    if (showSafety) {
        SafetyNumberSheet(
            number = state.safetyNumber,
            peerName = state.chat?.title ?: "this contact",
            onDismiss = { showSafety = false },
        )
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(state.messages.size) }
        }
    }

    /* ---------------------------------------------------------------------------- pickers */

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val isVideo = context.contentResolver.getType(uri)?.startsWith("video") == true
        onSendMedia(uri.toString(), if (isVideo) MessageKind.VIDEO else MessageKind.IMAGE, 0L)
    }

    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        onSendMedia(uri.toString(), MessageKind.FILE, 0L)
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraTarget
        cameraTarget = null
        if (ok && uri != null) onSendMedia(uri.toString(), MessageKind.IMAGE, 0L)
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStartRecording()
    }

    fun openCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "cap_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        cameraTarget = uri
        takePicture.launch(uri)
    }

    fun toggleMic() {
        if (state.isRecording) {
            onStopRecording()
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onStartRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /* ------------------------------------------------------------------------------- body */

    Column(
        modifier
            .fillMaxSize()
            .background(ChatCanvas)
            .imePadding()
    ) {
        ChatHeader(
            name = state.chat?.title ?: "Chat",
            avatarUrl = state.chat?.avatarUrl,
            online = state.peer?.presence == app.bungram.data.model.Presence.ONLINE,
            subtitle = when {
                state.isRecording -> "recording…"
                state.isPeerTyping -> "typing…"
                state.peer?.presence == app.bungram.data.model.Presence.ONLINE -> "Online"
                else -> "last seen recently"
            },
            subtitleAccent = state.isPeerTyping,
            onBack = onBack,
            onVideoCall = onVideoCall,
            onVoiceCall = onVoiceCall,
            onOpenInfo = onOpenInfo,
            encrypted = state.isEncrypted,
            onOpenEncryption = { showSafety = true },
            showInfo = state.chat?.isGroup == true,
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
                            Surface(shape = RoundedCornerShape(50), color = Color.White, shadowElevation = 1.dp) {
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
                    MessageBubble(
                        message = message,
                        playing = playingId == message.id,
                        playbackProgress = playbackProgress,
                        onOpenMedia = { viewer = message },
                        onToggleVoice = { AppGraph.audio.toggle(message.id, message.attachment!!, AppGraph.tokens.access) },
                        showSenderName = state.chat?.isGroup == true && !message.isOutgoing,
                    )
                }
                if (state.isPeerTyping) {
                    item(key = "typing") { TypingDots() }
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
            AttachmentMenu(
                onCamera = ::openCamera,
                onGallery = { pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onDocument = { pickDocument.launch(arrayOf("*/*")) },
            )
        }

        AnimatedVisibility(
            visible = showStickers && !state.isRecording,
            enter = slideInVertically(animationSpec = tween(240, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(160)),
            exit = slideOutVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(120)),
        ) {
            StickerPanel(
                packs = stickerPacks,
                onSticker = {
                    onSendSticker(it)
                },
                onEmoji = { emoji ->
                    onDraftChange(state.draft + emoji)
                },
            )
        }

        if (state.isRecording) {
            RecordingBar(
                elapsedMs = state.recordingMs,
                onCancel = onCancelRecording,
                onStop = onStopRecording,
            )
        } else {
            Composer(
                draft = state.draft,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onToggleAttachment = onToggleAttachment,
                onToggleRecording = ::toggleMic,
                isRecording = false,
                busy = state.isSendingMedia,
                onToggleStickers = { showStickers = !showStickers },
                stickersOpen = showStickers,
            )
        }

        if (state.error != null) {
            ErrorBar(state.error, onDismissError)
        }
    }

    val open = viewer
    if (open != null) {
        val attachment = open.attachment
        when {
            attachment == null -> viewer = null
            open.kind == MessageKind.VIDEO -> VideoViewer(attachment) { viewer = null }
            else -> ImageViewer(attachment) { viewer = null }
        }
    }
}

@Composable
private fun ChatHeader(
    name: String,
    avatarUrl: String?,
    online: Boolean,
    subtitle: String,
    subtitleAccent: Boolean = false,
    onBack: () -> Unit,
    onVideoCall: () -> Unit,
    onVoiceCall: () -> Unit,
    onOpenInfo: () -> Unit = {},
    showInfo: Boolean = false,
    encrypted: Boolean = false,
    onOpenEncryption: () -> Unit = {},
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
        BungramAvatar(name = name, url = avatarUrl, size = 40.dp, online = online)
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .weight(1f)
                .then(if (showInfo) Modifier.clickable(onClick = onOpenInfo) else Modifier)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (encrypted) {
                    Spacer(Modifier.width(4.dp))
                    // Tapping the lock is the discoverable path to the safety number — the same
                    // gesture Telegram uses for its "encryption" row.
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = "End-to-end encrypted",
                        tint = BungramBlue,
                        modifier = Modifier
                            .size(13.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onOpenEncryption),
                    )
                }
            }
            Text(
                subtitle,
                fontSize = 10.sp,
                color = if (subtitleAccent) BungramBlue else TextSecondary,
                fontWeight = if (subtitleAccent) FontWeight.Medium else FontWeight.Normal,
            )
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
private fun MessageBubble(
    message: Message,
    playing: Boolean,
    playbackProgress: Float,
    onOpenMedia: () -> Unit,
    onToggleVoice: () -> Unit,
    showSenderName: Boolean = false,
) {
    if (message.kind == MessageKind.SYSTEM) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.9f), shadowElevation = 0.dp) {
                Text(
                    message.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    fontSize = 9.5.sp,
                    color = TextSecondary,
                )
            }
        }
        return
    }

    if (message.kind == MessageKind.STICKER) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
        ) {
            Text(
                message.text.ifBlank { "🙂" },
                fontSize = 62.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(TimeFormat.messageTime(message.createdAtEpochMs), fontSize = 8.5.sp, color = TextTertiary)
                if (message.isOutgoing) {
                    Spacer(Modifier.width(3.dp))
                    DeliveryTicks(message.delivery, onBlue = true, modifier = Modifier.size(10.dp))
                }
            }
        }
        return
    }

    val outgoing = message.isOutgoing
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (outgoing) 18.dp else 5.dp,
        bottomEnd = if (outgoing) 5.dp else 18.dp,
    )
    val textColor = if (outgoing) Color.White else TextPrimary
    val metaColor = if (outgoing) Color.White.copy(alpha = 0.72f) else TextTertiary

    // A photo or a video is edge-to-edge inside its bubble; text and files keep the padding.
    val media = message.kind == MessageKind.IMAGE || message.kind == MessageKind.VIDEO
    val innerPadding = if (media) 4.dp else 13.dp

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
            Column(Modifier.padding(horizontal = innerPadding, vertical = innerPadding)) {
                if (showSenderName) {
                    Text(
                        message.senderName,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BungramBlue,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                when (message.kind) {
                    MessageKind.IMAGE -> ImageBubble(message, outgoing, onOpenMedia)
                    MessageKind.VIDEO -> VideoBubble(message, outgoing, onOpenMedia)
                    MessageKind.VOICE -> VoiceBubble(message, outgoing, playing, playbackProgress, onToggleVoice)
                    MessageKind.FILE -> FileBubble(message, outgoing)
                    else -> if (message.decryptFailed) DecryptFailed()
                    else if (message.attachment != null) MediaMissing(message)
                }

                if (message.text.isNotEmpty()) {
                    Text(
                        message.text,
                        modifier = Modifier.padding(
                            start = if (media) 9.dp else 0.dp,
                            end = if (media) 9.dp else 0.dp,
                            top = if (media) 6.dp else 0.dp,
                        ),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = textColor,
                    )
                }

                Row(
                    Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp, end = if (media) 6.dp else 0.dp),
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
        if (message.delivery == DeliveryState.FAILED) {
            Text("Not sent · tap to retry", fontSize = 9.sp, color = Danger, modifier = Modifier.padding(top = 2.dp))
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
        Box(Modifier.width(3.dp).height(28.dp).background(BungramBlue, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 11.sp, color = TextSecondary, maxLines = 1, modifier = Modifier.weight(1f))
        IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Rounded.Close, "Cancel reply", Modifier.size(15.dp), tint = TextSecondary)
        }
    }
}

@Composable
private fun RecordingBar(elapsedMs: Long, onCancel: () -> Unit, onStop: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(Danger.copy(alpha = pulse), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            TimeFormat.durationMs(elapsedMs),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(Modifier.width(10.dp))
        Text("Recording voice note", fontSize = 11.sp, color = TextSecondary, modifier = Modifier.weight(1f))
        Text("Cancel", fontSize = 11.5.sp, color = TextSecondary, modifier = Modifier.clickable(onClick = onCancel).padding(8.dp))
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(40.dp).background(BungramBlue, CircleShape).clickable(onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Send, "Send voice note", Modifier.size(18.dp), tint = Color.White)
        }
    }
}

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Danger.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, fontSize = 11.sp, color = Danger, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss, modifier = Modifier.size(22.dp)) {
            Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(14.dp), tint = Danger)
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
    busy: Boolean,
    onToggleStickers: () -> Unit = {},
    stickersOpen: Boolean = false,
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
                    Text(if (busy) "Sending…" else "Type Here...", fontSize = 12.5.sp, color = TextTertiary)
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
                    .clickable(onClick = onToggleStickers),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.EmojiEmotions,
                    contentDescription = "Stickers and emoji",
                    modifier = Modifier.size(19.dp),
                    tint = if (stickersOpen) BungramBlue else TextSecondary,
                )
            }
            val press = remember { MutableInteractionSource() }
            val pressed by press.collectIsPressedAsState()
            // 0.88 on press: enough to feel mechanical, small enough that the icon never clips.
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.88f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "sendScale",
            )
            Box(
                Modifier
                    .size(32.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clickable(
                        interactionSource = press,
                        indication = null,
                    ) { if (draft.isBlank()) onToggleRecording() else onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (draft.isBlank()) Icons.Rounded.Mic else Icons.Rounded.Send,
                    contentDescription = if (draft.isBlank()) "Record voice note" else "Send",
                    modifier = Modifier.size(18.dp),
                    tint = if (isRecording) Danger else if (draft.isBlank()) TextSecondary else BungramBlue,
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------------------------------
 * Attachment menu — the six actions from the reference. The first three are live; the rest are
 * visibly marked so nothing looks broken.
 * ----------------------------------------------------------------------------------------------- */

private data class AttachmentAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun AttachmentMenu(
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDocument: () -> Unit,
) {
    val actions = listOf(
        AttachmentAction(Icons.Rounded.CameraAlt, "Camera", true, onCamera),
        AttachmentAction(Icons.Rounded.InsertDriveFile, "Document", true, onDocument),
        AttachmentAction(Icons.Rounded.PhotoCamera, "Photo & Video", true, onGallery),
        AttachmentAction(Icons.Rounded.LocationOn, "Location · soon", false, {}),
        AttachmentAction(Icons.Rounded.ContactPage, "Contact · soon", false, {}),
        AttachmentAction(Icons.Rounded.Poll, "Polling · soon", false, {}),
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
                        .clickable(enabled = action.enabled, onClick = action.onClick)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .background(if (action.enabled) SurfaceMuted else SurfaceMuted.copy(alpha = 0.5f), RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            action.icon,
                            null,
                            Modifier.size(16.dp),
                            tint = if (action.enabled) TextPrimary else TextTertiary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        action.label,
                        fontSize = 12.5.sp,
                        color = if (action.enabled) TextPrimary else TextTertiary,
                    )
                }
            }
        }
    }
}

/** Three dots that rise in sequence — the same rhythm every messenger uses for "typing". */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val offset by transition.animateFloat(
                initialValue = 0f,
                targetValue = -4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(420, delayMillis = i * 120, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .offset(y = offset.dp)
                    .size(6.dp)
                    .background(TextSecondary.copy(alpha = 0.75f), CircleShape)
            )
        }
    }
}

/**
 * The safety-number sheet. Two people read the same 60 digits out loud; if they match, nobody is
 * sitting in the middle. The number is derived from both identity keys, never sent anywhere.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SafetyNumberSheet(number: String?, peerName: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CanvasColor) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = CircleShape, color = BungramBlueGhost, modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Lock, null, tint = BungramBlue, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Encryption",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Messages with $peerName are sealed with the Double Ratchet. " +
                    "Compare these 60 digits in person — if they match, the conversation is private.",
                fontSize = 11.5.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            if (number == null) {
                Text(
                    "The safety number appears after the first message.",
                    fontSize = 11.sp,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                )
            } else {
                // Six rows of ten digits: readable, and easy to compare out loud.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    number.chunked(10).forEach { row ->
                        Text(
                            row.chunked(5).joinToString(" "),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A payload we cannot open. Honest placeholder beats a blank bubble or a wall of base64. */
@Composable
private fun DecryptFailed() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Lock, null, tint = TextTertiary, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Message not available on this device",
            fontSize = 12.sp,
            color = TextTertiary,
            fontWeight = FontWeight.Normal,
        )
    }
}
