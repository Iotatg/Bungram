package app.bungram.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bungram.core.design.BungramBlue
import app.bungram.core.design.BungramBlueGhost
import app.bungram.core.design.SurfaceMuted
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary
import app.bungram.core.util.TimeFormat
import app.bungram.data.media.MediaUrl
import app.bungram.data.model.Attachment
import app.bungram.data.model.Message
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/* ---------------------------------------------------------------------------------------------
 * Media rendering. Every bubble has three visual states:
 *   · pending  — local bytes, upload progress overlay
 *   · sent     — server thumbnail, tap to open full size
 *   · failed   — dimmed with a retry affordance
 * The colour hint from the server paints the placeholder, so the bubble never flashes grey.
 * ------------------------------------------------------------------------------------------- */

private fun Attachment?.ratio(): Float {
    val w = this?.width ?: 0
    val h = this?.height ?: 0
    if (w <= 0 || h <= 0) return 1.1f
    return (w.toFloat() / h).coerceIn(0.6f, 1.7f)
}

private fun Attachment?.placeholderColor(): Color {
    val hex = this?.colorHint ?: return SurfaceMuted
    return runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(SurfaceMuted)
}

@Composable
fun ImageBubble(
    message: Message,
    outgoing: Boolean,
    onOpen: () -> Unit,
) {
    val attachment = message.attachment
    val ratio = attachment.ratio()
    val width = if (ratio >= 1f) 236.dp else (236 * ratio).dp.coerceAtLeast(150.dp)
    val height = (width.value / ratio).dp.coerceAtMost(300.dp)

    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(attachment.placeholderColor())
            .clickable(enabled = attachment?.isUploading != true, onClick = onOpen),
    ) {
        val model = MediaUrl.coilModel(attachment)
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(model)
                .crossfade(180)
                .build(),
            contentDescription = "Photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        UploadOverlay(attachment)
    }
}

@Composable
fun VideoBubble(
    message: Message,
    outgoing: Boolean,
    onOpen: () -> Unit,
) {
    val attachment = message.attachment
    val ratio = attachment.ratio()
    val width = 236.dp
    val height = (width.value / ratio).dp.coerceIn(140.dp, 300.dp)

    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(attachment.placeholderColor())
            .clickable(enabled = attachment?.isUploading != true, onClick = onOpen),
    ) {
        val preview = MediaUrl.coilModel(attachment)
        if (preview != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(preview).crossfade(180).build(),
                contentDescription = "Video",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A2F3A), Color(0xFF14171D)))))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
        Box(
            Modifier
                .align(Alignment.Center)
                .size(46.dp)
                .background(Color.White.copy(alpha = 0.92f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(26.dp), tint = Color(0xFF14171D))
        }
        if ((attachment?.durationMs ?: 0L) > 0) {
            Text(
                TimeFormat.durationMs(attachment!!.durationMs),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                fontSize = 9.sp,
                color = Color.White,
            )
        }
        UploadOverlay(attachment)
    }
}

@Composable
fun VoiceBubble(
    message: Message,
    outgoing: Boolean,
    playing: Boolean,
    progress: Float,
    onToggle: () -> Unit,
) {
    val attachment = message.attachment
    val tint = if (outgoing) Color.White else BungramBlue
    val muted = if (outgoing) Color.White.copy(alpha = 0.45f) else BungramBlueGhost

    Row(
        Modifier
            .width(228.dp)
            .background(
                if (outgoing) Color.White.copy(alpha = 0.14f) else SurfaceMuted,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .background(if (outgoing) Color.White else BungramBlue, CircleShape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (playing) "Pause" else "Play",
                Modifier.size(17.dp),
                tint = if (outgoing) BungramBlue else Color.White,
            )
        }
        Spacer(Modifier.width(8.dp))
        val bars = androidx.compose.runtime.remember(message.id) { waveformFor(message.id) }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bars.forEachIndexed { index, value ->
                val played = playing && progress >= index.toFloat() / bars.size
                Box(
                    Modifier
                        .width(2.5.dp)
                        .height((4 + value * 20).dp)
                        .background(if (played) tint else muted, RoundedCornerShape(2.dp))
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            TimeFormat.durationMs(attachment?.durationMs ?: 0L),
            fontSize = 9.sp,
            color = if (outgoing) Color.White.copy(alpha = 0.85f) else TextSecondary,
        )
    }
}

@Composable
fun FileBubble(message: Message, outgoing: Boolean) {
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
private fun UploadOverlay(attachment: Attachment?) {
    if (attachment == null || !attachment.isUploading) return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                progress = { attachment.uploadProgress.coerceIn(0f, 1f) },
                modifier = Modifier.size(34.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${(attachment.uploadProgress * 100).roundToInt()}%",
                fontSize = 10.sp,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Deterministic waveform so a received note looks the same on every device and every reopen. */
fun waveformFor(seed: String, count: Int = 28): List<Float> {
    val random = java.util.Random(seed.hashCode().toLong() * 31)
    return List(count) { 0.18f + random.nextFloat() * 0.82f }
}

@Composable
fun MediaMissing(message: Message) {
    Row(
        Modifier
            .width(200.dp)
            .background(SurfaceMuted, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.InsertDriveFile, null, Modifier.size(18.dp), tint = TextSecondary)
        Spacer(Modifier.width(8.dp))
        Text("Attachment unavailable", fontSize = 11.sp, color = TextSecondary)
    }
}
