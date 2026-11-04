package app.bungram.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bungram.core.design.BungramBlue
import app.bungram.core.design.BungramBlueSoft
import app.bungram.core.design.Canvas
import app.bungram.core.design.SurfaceMuted
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary
import app.bungram.core.design.TextTertiary
import app.bungram.data.remote.StickerPackDto

/**
 * Sticker + emoji picker.
 *
 * A sticker here is an emoji glyph rendered large: it needs no asset download, works offline, and
 * scales to any screen density. The wire format (`kind = STICKER`, `text = glyph`) is the same one
 * a PNG sticker would use later, so swapping the renderer does not touch the protocol.
 */
@Composable
fun StickerPanel(
    packs: List<StickerPackDto>,
    onSticker: (String) -> Unit,
    onEmoji: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) } // 0 = stickers, 1 = emoji
    var packIndex by remember { mutableStateOf(0) }

    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = 300.dp)
            .background(Canvas)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabPill("Stickers", tab == 0) { tab = 0 }
            Spacer(Modifier.width(8.dp))
            TabPill("Emoji", tab == 1) { tab = 1 }
        }

        if (tab == 0) {
            if (packs.isEmpty()) {
                Text(
                    "Sticker packs load once you are online.",
                    fontSize = 11.5.sp,
                    color = TextTertiary,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(packs.size) { i ->
                        val pack = packs[i]
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (i == packIndex) BungramBlueSoft else SurfaceMuted)
                                .clickable { packIndex = i }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(pack.cover, fontSize = 18.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                val pack = packs.getOrNull(packIndex) ?: packs.first()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                ) {
                    items(pack.stickers) { sticker ->
                        Box(
                            Modifier
                                .height(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSticker(sticker.emoji) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(sticker.emoji, fontSize = 30.sp)
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
            ) {
                items(EmojiPalette.all) { emoji ->
                    Box(
                        Modifier
                            .height(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onEmoji(emoji) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = 22.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) BungramBlue else SurfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) androidx.compose.ui.graphics.Color.White else TextSecondary,
        )
    }
}

/** A flat, deduplicated emoji list — enough for quick reactions without a 3 MB asset pack. */
object EmojiPalette {
    val all: List<String> = (
        listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃", "😉", "😊", "😇", "🥰",
            "😍", "🤩", "😘", "😗", "😚", "😙", "🥲", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗",
            "🤭", "🤫", "🤔", "🤐", "🤨", "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "😮‍💨", "🤥",
            "😌", "😔", "😪", "🤤", "😴", "😷", "🤒", "🤕", "🤢", "🤮", "🥵", "🥶", "🥴", "😵",
            "🤯", "🤠", "🥳", "🥺", "😢", "😭", "😤", "😡", "🤬", "😈", "👿", "💀", "💩", "🤡",
            "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "☝️", "👋",
            "🤚", "🖐️", "✋", "🖖", "🙏", "🤝", "💪", "👏", "🙌", "👐", "🤲", "🫶", "✍️", "🫰",
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓",
            "💗", "💖", "💘", "💝", "✨", "⭐", "🌟", "💫", "🔥", "💥", "💯", "🎉", "🎊", "🎁",
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸",
            "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍒", "🍑", "🥭", "🍍",
            "⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🎱", "🏓", "🏸", "🥊", "🎯", "🎮", "🎲", "🎧",
            "🚗", "🚕", "🚙", "🚌", "🏍️", "🚲", "✈️", "🚀", "🚁", "⛵", "🏝️", "🏔️", "🌋", "🌈",
            "☀️", "🌙", "⛅", "🌧️", "⛈️", "❄️", "🌊", "🌍", "🌺", "🌸", "🌻", "🌹", "🍀", "🌵",
        )
    ).distinct()
}
