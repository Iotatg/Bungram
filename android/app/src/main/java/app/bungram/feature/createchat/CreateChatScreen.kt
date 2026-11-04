package app.bungram.feature.createchat

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bungram.core.design.BungramAvatar
import app.bungram.core.design.BungramBlue
import app.bungram.core.design.BungramBlueSoft
import app.bungram.core.design.Canvas
import app.bungram.core.design.Danger
import app.bungram.core.design.Divider
import app.bungram.core.design.SurfaceMuted
import app.bungram.core.design.TextOnBlue
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary
import app.bungram.core.design.TextTertiary

/**
 * New group / new channel.
 *
 * The mode switch is the first control because it changes what the thing *is*: a group is a
 * conversation, a channel is a broadcast. Everything below it is identical on purpose — the user
 * should not have to learn two forms.
 */
@Composable
fun CreateChatRoute(initialMode: CreateMode, nav: androidx.navigation.NavHostController) {
    val vm: CreateChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                CreateChatViewModel(app.bungram.AppGraph.api, app.bungram.AppGraph.repository)
            }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(initialMode) { vm.setMode(initialMode) }

    CreateChatScreen(
        state = state,
        onBack = { nav.popBackStack() },
        onModeChange = vm::setMode,
        onTitleChange = vm::onTitleChange,
        onDescriptionChange = vm::onDescriptionChange,
        onQueryChange = vm::onQueryChange,
        onAdd = vm::add,
        onRemove = vm::remove,
        onTogglePublic = vm::togglePublic,
        onCreate = {
            vm.create { chat ->
                nav.navigate(app.bungram.core.nav.Routes.chat(chat.id)) {
                    popUpTo(app.bungram.core.nav.Routes.HOME)
                }
            }
        },
    )
}

@Composable
fun CreateChatScreen(
    state: CreateChatUiState,
    onBack: () -> Unit,
    onModeChange: (CreateMode) -> Unit,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onAdd: (app.bungram.data.model.User) -> Unit,
    onRemove: (String) -> Unit,
    onTogglePublic: () -> Unit,
    onCreate: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ArrowBack, "Back", Modifier.size(20.dp), tint = TextPrimary)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                state.subtitle,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary,
            )
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(6.dp))
            ModeSwitch(state.mode, onModeChange)
            Spacer(Modifier.height(18.dp))

            FieldLabel("Name")
            InputField(
                value = state.title,
                onChange = onTitleChange,
                placeholder = if (state.mode == CreateMode.GROUP) "e.g. Design team" else "e.g. Bungram News",
                singleLine = true,
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel(if (state.mode == CreateMode.GROUP) "Description (optional)" else "About (optional)")
            InputField(
                value = state.description,
                onChange = onDescriptionChange,
                placeholder = "What is this chat about?",
                singleLine = false,
            )

            Spacer(Modifier.height(20.dp))

            if (state.mode == CreateMode.GROUP) {
                FieldLabel("Add members")
                InputField(
                    value = state.query,
                    onChange = onQueryChange,
                    placeholder = "Phone number",
                    singleLine = true,
                    keyboardType = KeyboardType.Phone,
                )
                if (state.suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    state.suggestions.forEach { user ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceMuted)
                                .clickable { onAdd(user) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BungramAvatar(name = user.displayName, url = user.avatarUrl, size = 34.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(user.displayName, fontSize = 13.sp, color = TextPrimary)
                            Spacer(Modifier.weight(1f))
                            Text("Add", fontSize = 12.sp, color = BungramBlue, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                if (state.selected.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.selected, key = { it.id }) { user ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box {
                                    BungramAvatar(name = user.displayName, url = user.avatarUrl, size = 52.dp)
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(TextPrimary.copy(alpha = 0.65f))
                                            .clickable { onRemove(user.id) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Rounded.Close, "Remove", Modifier.size(11.dp), tint = TextOnBlue)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    user.displayName.substringBefore(' '),
                                    fontSize = 10.sp,
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceMuted)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Public, null, Modifier.size(18.dp), tint = TextSecondary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Public channel", fontSize = 12.5.sp, color = TextPrimary)
                        Text(
                            "Anyone with the link can preview it",
                            fontSize = 10.sp,
                            color = TextTertiary,
                        )
                    }
                    Switch(
                        checked = state.isPublic,
                        onCheckedChange = { onTogglePublic() },
                        colors = SwitchDefaults.colors(checkedTrackColor = BungramBlue),
                    )
                }
            }

            if (state.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(state.error, fontSize = 11.5.sp, color = Danger)
            }
            Spacer(Modifier.height(24.dp))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (state.canCreate) BungramBlue else BungramBlue.copy(alpha = 0.35f))
                .clickable(enabled = state.canCreate, onClick = onCreate),
            contentAlignment = Alignment.Center,
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(20.dp), color = TextOnBlue, strokeWidth = 2.dp)
            } else {
                Text(
                    if (state.mode == CreateMode.GROUP) "Create group" else "Create channel",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextOnBlue,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ModeSwitch(mode: CreateMode, onChange: (CreateMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceMuted)
            .padding(4.dp),
    ) {
        ModeTab(
            label = "Group",
            icon = Icons.Rounded.Groups,
            selected = mode == CreateMode.GROUP,
            modifier = Modifier.weight(1f),
        ) { onChange(CreateMode.GROUP) }
        ModeTab(
            label = "Channel",
            icon = Icons.Rounded.Tag,
            selected = mode == CreateMode.CHANNEL,
            modifier = Modifier.weight(1f),
        ) { onChange(CreateMode.CHANNEL) }
    }
}

@Composable
private fun ModeTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Canvas else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            Modifier.size(16.dp),
            tint = if (selected) BungramBlue else TextSecondary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) BungramBlue else TextSecondary,
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextTertiary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun InputField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceMuted)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary),
            cursorBrush = SolidColor(BungramBlue),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, fontSize = 14.sp, color = TextTertiary)
                inner()
            },
        )
    }
}
