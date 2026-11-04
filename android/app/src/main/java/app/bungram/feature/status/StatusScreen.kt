package app.bungram.feature.status

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bungram.core.design.BungramAvatar
import app.bungram.core.design.BungramBlue
import app.bungram.core.design.BungramSearchField
import app.bungram.core.design.Canvas
import app.bungram.core.design.CircleIconButton
import app.bungram.core.design.SurfaceMuted
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary
import app.bungram.core.design.TextTertiary
import app.bungram.data.model.StoryFeed
import coil.compose.AsyncImage

/**
 * Screen 3 of the reference: status / stories.
 * Header → search → people rail → staggered 2-column story grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatusScreen(
    state: StatusUiState,
    onQueryChange: (String) -> Unit,
    onOpen: (StoryFeed) -> Unit,
    onAddStatus: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Canvas)) {

        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            CircleIconButton(
                Icons.Rounded.GridView,
                "Layout",
                onClick = {},
                background = Color.Transparent,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                "Status",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.align(Alignment.Center),
            )
            CircleIconButton(
                Icons.Rounded.Add,
                "Add status",
                onClick = onAddStatus,
                background = SurfaceMuted,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        BungramSearchField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        Spacer(Modifier.height(10.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "my_status") {
                MyStatusItem(
                    name = state.myStatus?.owner?.displayName ?: "My status",
                    avatarUrl = state.myStatus?.owner?.avatarUrl,
                    onClick = onAddStatus,
                )
            }
            items(state.feeds, key = { it.owner.id }) { feed ->
                StatusPerson(feed = feed, onClick = { onOpen(feed) })
            }
        }

        Spacer(Modifier.height(14.dp))

        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp,
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(state.feeds, key = { _, feed -> feed.owner.id }) { index, feed ->
                StoryCard(feed = feed, tall = index % 3 != 1, onClick = { onOpen(feed) })
            }
        }
    }
}

@Composable
private fun MyStatusItem(name: String, avatarUrl: String?, onClick: () -> Unit) {
    Column(
        Modifier.width(62.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            BungramAvatar(name = name, url = avatarUrl, size = 54.dp)
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Canvas)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(BungramBlue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(11.dp), tint = Color.White)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "My status",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusPerson(feed: StoryFeed, onClick: () -> Unit) {
    Column(
        Modifier.width(58.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BungramAvatar(
            name = feed.owner.displayName,
            url = feed.owner.avatarUrl,
            size = 52.dp,
            ring = if (feed.allViewed) TextTertiary else BungramBlue,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            feed.owner.displayName.substringBefore(" "),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StoryCard(feed: StoryFeed, tall: Boolean, onClick: () -> Unit) {
    val story = feed.latest ?: return
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (tall) 0.72f else 0.86f)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceMuted)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = story.thumbnailUrl ?: story.mediaUrl,
            contentDescription = story.caption,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.62f),
                    )
                )
        )
        if (!feed.allViewed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(1.5.dp, BungramBlue, RoundedCornerShape(16.dp))
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.28f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(13.dp), tint = Color.White)
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
        ) {
            Text(
                story.caption.ifBlank { feed.owner.displayName },
                color = Color.White,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "@" + (feed.owner.username ?: feed.owner.displayName.replace(" ", "").lowercase()),
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 8.5.sp,
            )
        }
    }
}
