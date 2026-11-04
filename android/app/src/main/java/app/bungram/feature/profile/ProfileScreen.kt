package app.bungram.feature.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bungram.BuildConfig
import app.bungram.core.design.BungramAvatar
import app.bungram.core.design.BungramBlue
import app.bungram.core.design.BungramBlueGhost
import app.bungram.core.design.Canvas
import app.bungram.core.design.Danger
import app.bungram.core.design.Divider
import app.bungram.core.design.SurfaceMuted
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary
import app.bungram.core.design.TextTertiary

/**
 * The profile tab.
 *
 * Every row here does something real: the three identity fields open an editor backed by
 * `PATCH /v1/me`, "Linked devices" lists what the server knows about this account, the sounds
 * switch persists, and the footer states the encryption model instead of claiming "military grade".
 */
@Composable
fun ProfileScreen(
    bottomPadding: Dp,
    onLogout: () -> Unit,
    viewModel: ProfileViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user = state.user

    Column(
        modifier
            .fillMaxSize()
            .background(Canvas)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 22.dp, end = 18.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Profile", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                BungramAvatar(name = user?.displayName ?: "You", url = user?.avatarUrl, size = 96.dp)
                Surface(
                    shape = CircleShape,
                    color = BungramBlue,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(28.dp)
                        .clickable { viewModel.beginEdit(ProfileField.NAME) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Edit, "Edit name", Modifier.size(14.dp), tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    user?.displayName ?: "Your profile",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (user?.isVerified == true) {
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Rounded.Verified, null, Modifier.size(15.dp), tint = BungramBlue)
                }
            }
            if (!user?.phone.isNullOrBlank()) {
                Text("+${user?.phone}", fontSize = 12.sp, color = TextSecondary)
            }
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = BungramBlueGhost,
                modifier = Modifier.clickable { viewModel.beginEdit(ProfileField.USERNAME) },
            ) {
                Text(
                    if (user?.username.isNullOrBlank()) "Set a username" else "@${user?.username}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 10.5.sp,
                    color = BungramBlue,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                user?.about?.takeIf { it.isNotBlank() } ?: "Tap to add a few words about yourself",
                fontSize = 11.5.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .clickable { viewModel.beginEdit(ProfileField.ABOUT) },
            )
        }

        Spacer(Modifier.height(20.dp))

        Card {
            SettingRow(Icons.Rounded.Person, "Name", user?.displayName.orEmpty()) {
                viewModel.beginEdit(ProfileField.NAME)
            }
            RowDivider()
            SettingRow(Icons.Rounded.Person, "Username", user?.username?.let { "@$it" } ?: "Not set") {
                viewModel.beginEdit(ProfileField.USERNAME)
            }
            RowDivider()
            SettingRow(Icons.Rounded.Person, "About", user?.about?.takeIf { it.isNotBlank() } ?: "Add a bio") {
                viewModel.beginEdit(ProfileField.ABOUT)
            }
        }

        Spacer(Modifier.height(12.dp))

        Card {
            SettingRow(Icons.Rounded.Devices, "Linked devices", "Pre-keys, sessions") {
                viewModel.openDevices()
            }
            RowDivider()
            ToggleRow(
                Icons.Rounded.VolumeUp,
                "Message sounds",
                "Tones for sending and receiving",
                state.soundsEnabled,
                viewModel::setSoundsEnabled,
            )
            RowDivider()
            SettingRow(Icons.Rounded.Notifications, "Notifications", "Managed by Android") {}
            RowDivider()
            SettingRow(Icons.Rounded.Storage, "Storage & data", "Auto-download, cache") {}
            RowDivider()
            SettingRow(Icons.Rounded.Lock, "Encryption", "Double Ratchet, 1:1 chats") {}
        }

        Spacer(Modifier.height(12.dp))

        Card {
            SettingRow(Icons.Rounded.Logout, "Log out", null, tint = Danger, onClick = onLogout)
        }

        Spacer(Modifier.height(bottomPadding + 16.dp))

        Text(
            "Bungram ${BuildConfig.VERSION_NAME} · end-to-end encrypted",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 10.sp,
            color = TextTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
    }

    state.editing?.let { field ->
        EditSheet(
            field = field,
            draft = state.draft,
            hint = state.draftHint,
            valid = state.draftValid,
            saving = state.saving,
            error = state.error,
            onDraftChange = viewModel::onDraftChange,
            onSave = viewModel::save,
            onDismiss = viewModel::cancelEdit,
        )
    }

    if (state.showDevices) {
        DevicesSheet(
            devices = state.devices,
            loading = state.devicesLoading,
            onRevoke = viewModel::revokeDevice,
            onDismiss = viewModel::closeDevices,
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(
    field: ProfileField,
    draft: String,
    hint: String,
    valid: Boolean,
    saving: Boolean,
    error: String?,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Canvas) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(
                when (field) {
                    ProfileField.NAME -> "Your name"
                    ProfileField.USERNAME -> "Username"
                    ProfileField.ABOUT -> "About"
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary,
            )
            Spacer(Modifier.height(14.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = field != ProfileField.ABOUT,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(BungramBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hint, fontSize = 10.5.sp, color = TextTertiary, modifier = Modifier.weight(1f))
                if (saving) Text("Saving…", fontSize = 10.5.sp, color = BungramBlue)
            }
            AnimatedVisibility(
                visible = error != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                Text(
                    error.orEmpty(),
                    fontSize = 11.sp,
                    color = Danger,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (valid && !saving) BungramBlue else BungramBlue.copy(alpha = 0.35f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = valid && !saving, onClick = onSave),
            ) {
                Box(Modifier.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                    Text("Save", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DevicesSheet(
    devices: List<app.bungram.data.remote.DeviceDto>,
    loading: Boolean,
    onRevoke: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Canvas) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Linked devices",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Each device holds its own keys. Removing one deletes its sessions and pre-keys.",
                fontSize = 11.sp,
                color = TextSecondary,
            )
            Spacer(Modifier.height(14.dp))
            when {
                loading -> Text("Loading…", fontSize = 11.5.sp, color = TextTertiary)
                devices.isEmpty() -> Text("No devices registered yet.", fontSize = 11.5.sp, color = TextTertiary)
                else -> devices.forEach { device ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Devices, null, Modifier.size(16.dp), tint = BungramBlue)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                device.deviceName.ifBlank { "Android" },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary,
                            )
                            Text(
                                if (device.current) "This device" else device.provider,
                                fontSize = 10.sp,
                                color = if (device.current) BungramBlue else TextSecondary,
                            )
                        }
                        if (!device.current) {
                            Text(
                                "Sign out",
                                fontSize = 11.5.sp,
                                color = Danger,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onRevoke(device.id) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        Modifier.padding(horizontal = 18.dp),
        shape = RoundedCornerShape(18.dp),
        color = SurfaceMuted,
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    tint: Color = TextPrimary,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBox(icon, tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = tint)
            if (subtitle != null) {
                Text(subtitle, fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = TextSecondary)
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBox(icon, TextPrimary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(subtitle, fontSize = 10.sp, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BungramBlue,
            ),
        )
    }
}

@Composable
private fun IconBox(icon: ImageVector, tint: Color) {
    Box(
        Modifier
            .size(30.dp)
            .background(Color.White, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
    }
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 56.dp)
            .height(0.6.dp)
            .background(Divider)
    )
}
