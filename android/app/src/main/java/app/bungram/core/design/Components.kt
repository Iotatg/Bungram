package app.bungram.core.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bungram.data.model.DeliveryState
import coil.compose.AsyncImage

/* -------------------------------------------------------------------------------------------------
 * Avatar
 * ----------------------------------------------------------------------------------------------- */

private val avatarGradients = listOf(
    listOf(Color(0xFF7C8BFF), Color(0xFF9FA9FF)),
    listOf(Color(0xFF6ED0C0), Color(0xFF9BE3D6)),
    listOf(Color(0xFFFFB37A), Color(0xFFFFD3A8)),
    listOf(Color(0xFFB49BFF), Color(0xFFD3C4FF)),
    listOf(Color(0xFFFF9BB0), Color(0xFFFFC3CE)),
    listOf(Color(0xFF7FB8FF), Color(0xFFB3D4FF)),
)

private fun gradientFor(seed: String): List<Color> {
    var h = 0
    for (c in seed) h = (h * 31 + c.code) and 0x7fffffff
    return avatarGradients[h % avatarGradients.size]
}

private fun initialsOf(name: String): String {
    val cleaned = name.trim().removePrefix("+").filter { it.isLetter() || it == ' ' }
    val parts = cleaned.split(' ').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "#"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

/**
 * Circular avatar with graceful fallback (gradient + initials) and optional story ring / presence dot.
 */
@Composable
fun BungramAvatar(
    name: String,
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    ring: Color? = null,
    ringGap: Dp = 3.dp,
    online: Boolean = false,
    onlineColor: Color = Online,
) {
    val shape = CircleShape
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (ring != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(width = 1.6.dp, color = ring, shape = shape)
            )
        }
        val inner = if (ring != null) size - ringGap * 2 - 3.dp else size
        Box(
            Modifier
                .size(inner)
                .clip(shape)
                .background(Brush.linearGradient(gradientFor(name))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initialsOf(name),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.34f).sp,
            )
            if (!url.isNullOrBlank()) {
                AsyncImage(
                    model = url,
                    contentDescription = name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (online) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.26f)
                    .clip(shape)
                    .background(Canvas)
                    .padding(2.dp)
                    .clip(shape)
                    .background(onlineColor)
            )
        }
    }
}

/* -------------------------------------------------------------------------------------------------
 * Buttons & fields
 * ----------------------------------------------------------------------------------------------- */

@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    background: Color = SurfaceMuted,
    tint: Color = TextPrimary,
    iconSize: Dp = 19.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = tint)
    }
}

@Composable
fun BungramSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search...",
    onFilterClick: (() -> Unit)? = null,
    height: Dp = 46.dp,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceMuted)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(19.dp), tint = TextTertiary)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (onFilterClick != null) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = "Filter",
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = onFilterClick),
                tint = TextPrimary,
            )
        }
    }
}

@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier, color: Color = BungramBlue) {
    if (count <= 0) return
    Box(
        modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 10.sp,
        )
    }
}

@Composable
fun DeliveryTicks(state: DeliveryState, onBlue: Boolean = false, modifier: Modifier = Modifier) {
    val tint = when {
        state == DeliveryState.READ && onBlue -> Color.White
        state == DeliveryState.READ -> BungramBlue
        onBlue -> Color.White.copy(alpha = 0.75f)
        else -> TextTertiary
    }
    when (state) {
        DeliveryState.QUEUED -> Icon(Icons.Rounded.Done, null, modifier.size(13.dp), tint = tint)
        DeliveryState.FAILED -> Icon(
            Icons.Rounded.ErrorOutline,
            null,
            modifier.size(13.dp),
            tint = Danger,
        )
        else -> Icon(
            if (state == DeliveryState.SENT) Icons.Rounded.Done else Icons.Rounded.DoneAll,
            contentDescription = null,
            modifier = modifier.size(14.dp),
            tint = tint,
        )
    }
}

@Composable
fun SectionHeader(
    left: String,
    right: String? = null,
    onRightClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(left, style = MaterialTheme.typography.labelLarge, color = BungramBlue)
        if (right != null) {
            Text(
                right,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                ),
                color = BungramBlue,
                modifier = Modifier.clickable(enabled = onRightClick != null) { onRightClick?.invoke() },
            )
        }
    }
}

/* -------------------------------------------------------------------------------------------------
 * Bottom navigation — floating compose button sits on top of a rounded white sheet.
 * ----------------------------------------------------------------------------------------------- */

enum class BungramTab(val label: String, val icon: ImageVector) {
    MESSAGE("Message", Icons.Rounded.ChatBubbleOutline),
    STATUS("Status", Icons.Rounded.GridView),
    CALL("Call", Icons.Rounded.Call),
    PROFILE("Profile", Icons.Rounded.PersonOutline),
}

@Composable
fun BungramBottomBar(
    selected: BungramTab,
    onSelect: (BungramTab) -> Unit,
    onCompose: () -> Unit,
    modifier: Modifier = Modifier,
    callBadge: Int = 1,
    bottomInset: Dp = 0.dp,
) {
    Box(modifier.fillMaxWidth()) {
        Surface(
            color = Canvas,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            shadowElevation = 24.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp + bottomInset)
                    .padding(bottom = bottomInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomItem(BungramTab.MESSAGE, selected, onSelect, Modifier.weight(1f))
                BottomItem(BungramTab.STATUS, selected, onSelect, Modifier.weight(1f))
                Spacer(Modifier.width(74.dp))
                BottomItem(
                    BungramTab.CALL, selected, onSelect, Modifier.weight(1f),
                    badge = callBadge,
                )
                BottomItem(BungramTab.PROFILE, selected, onSelect, Modifier.weight(1f))
            }
        }

        FloatingActionButton(
            onClick = onCompose,
            shape = CircleShape,
            containerColor = BungramBlue,
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 10.dp, pressedElevation = 4.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-18).dp)
                .size(56.dp),
        ) {
            Icon(Icons.Rounded.Add, "New chat", Modifier.size(27.dp))
        }
    }
}

@Composable
private fun BottomItem(
    tab: BungramTab,
    selected: BungramTab,
    onSelect: (BungramTab) -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = 0,
) {
    val active = tab == selected
    val tint by animateColorAsState(if (active) BungramBlue else TextTertiary, label = "tabTint")
    Column(
        modifier
            .height(64.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onSelect(tab) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(tab.icon, tab.label, Modifier.size(22.dp), tint = tint)
            if (badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-4).dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Danger),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        badge.toString(),
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 9.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            tab.label,
            fontSize = 9.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
