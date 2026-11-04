package app.bungram.feature.groupinfo

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bungram.core.design.BungramAvatar
import app.bungram.core.design.BungramBlue
import app.bungram.core.design.BungramBlueGhost
import app.bungram.core.design.Canvas
import app.bungram.core.design.Danger
import app.bungram.core.design.Divider
import app.bungram.core.design.SurfaceMuted
import app.bungram.core.design.TextOnBlue
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary
import app.bungram.core.design.TextTertiary

/**
 * Group / channel info: who is in it, what the invite link is, and the admin actions.
 *
 * Destructive actions are always behind a dialog — a mis-tap must never delete a chat for fifty
 * people.
 */
@Composable
fun GroupInfoScreen(
    state: GroupInfoUiState,
    meId: String,
    onBack: () -> Unit,
    onMintInvite: () -> Unit,
    onRevokeInvite: () -> Unit,
    onAddMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onToggleAdmin: (String, Boolean) -> Unit,
    onRename: (String) -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
    onCopyLink: (String) -> Unit,
) {
    val chat = state.chat ?: return
    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
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
            Spacer(Modifier.width(4.dp))
            Text(
                if (chat.isChannel) "Channel info" else "Group info",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BungramAvatar(name = chat.title, url = chat.avatarUrl, size = 84.dp)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chat.title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.canManage) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.Edit,
                            "Rename",
                            Modifier.size(15.dp).clickable { showRename = true },
                            tint = TextSecondary,
                        )
                    }
                }
                Text(
                    "${state.members.size} ${if (chat.isChannel) "subscribers" else "members"}",
                    fontSize = 11.5.sp,
                    color = TextSecondary,
                )
                if (chat.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        chat.description,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 34.dp),
                    )
                }
            }

            if (state.error != null) Notice(state.error, Danger)
            if (state.notice != null) Notice(state.notice, BungramBlue)

            Spacer(Modifier.height(8.dp))
            Hairline()

            if (state.canManage) {
                SectionRow(
                    icon = Icons.Rounded.Link,
                    title = if (state.inviteSlug == null) "Create invite link" else "Invite link",
                    subtitle = state.inviteSlug?.let { "bungram.app/+$it" } ?: "Anyone with the link can join",
                    onClick = {
                        if (state.inviteSlug == null) onMintInvite()
                        else onCopyLink("https://bungram.app/+${state.inviteSlug}")
                    },
                )
                if (state.inviteSlug != null) {
                    SectionRow(
                        icon = Icons.Rounded.Delete,
                        title = "Revoke link",
                        subtitle = "The current link stops working",
                        tint = Danger,
                        onClick = onRevokeInvite,
                    )
                }
                if (!chat.isChannel) {
                    SectionRow(
                        icon = Icons.Rounded.Add,
                        title = "Add member",
                        subtitle = "By phone number",
                        onClick = { showAdd = true },
                    )
                }
            }

            Hairline()
            // Telegram hides the numeric id inside the info page too: it is what you paste into
            // support tickets or a bot, and it is the only identifier that never changes when the
            // title or the invite link does.
            SectionRow(
                icon = Icons.Rounded.Tag,
                title = if (chat.isChannel) "Channel ID" else "Group ID",
                subtitle = chat.id,
                onClick = { onCopyLink(chat.id) },
            )

            Hairline()
            Text(
                if (chat.isChannel) "SUBSCRIBERS" else "MEMBERS",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextTertiary,
                modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
            )

            state.members.forEach { member ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BungramAvatar(name = member.user.displayName, url = member.user.avatarUrl, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (member.user.id == meId) "${member.user.displayName} (you)"
                            else member.user.displayName,
                            fontSize = 13.5.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            member.user.phone ?: member.user.username?.let { "@$it" } ?: "",
                            fontSize = 10.5.sp,
                            color = TextTertiary,
                        )
                    }
                    if (member.role != "MEMBER") {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(BungramBlueGhost)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                if (member.role == "OWNER") "Owner" else "Admin",
                                fontSize = 9.sp,
                                color = BungramBlue,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    if (state.canManage && member.role != "OWNER" && member.user.id != meId) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.Shield,
                            "Toggle admin",
                            Modifier.size(17.dp).clickable {
                                onToggleAdmin(member.user.id, member.role != "ADMIN")
                            },
                            tint = if (member.role == "ADMIN") BungramBlue else TextTertiary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            Icons.Rounded.PersonRemove,
                            "Remove",
                            Modifier.size(17.dp).clickable { onRemoveMember(member.user.id) },
                            tint = Danger.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Hairline()

            if (chat.myRole == app.bungram.data.model.ChatRole.OWNER) {
                ActionRow("Delete ${if (chat.isChannel) "channel" else "group"}", Danger) { confirmDelete = true }
            } else {
                ActionRow("Leave ${if (chat.isChannel) "channel" else "group"}", Danger, Icons.Rounded.ExitToApp) { onLeave() }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (showAdd) {
        AddMemberDialog(
            onDismiss = { showAdd = false },
            onAdd = {
                onAddMember(it)
                showAdd = false
            },
        )
    }

    if (showRename) {
        RenameDialog(
            current = chat.title,
            onDismiss = { showRename = false },
            onSave = {
                onRename(it)
                showRename = false
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete for everyone?") },
            text = { Text("This removes the chat and all its messages for every member. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = Danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

@Composable
private fun Notice(text: String, color: Color) {
    Text(
        text,
        fontSize = 11.5.sp,
        color = color,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun SectionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = TextPrimary,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(17.dp), tint = if (tint == TextPrimary) BungramBlue else tint)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 13.sp, color = tint, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 10.5.sp, color = TextTertiary)
        }
    }
}

@Composable
private fun ActionRow(label: String, color: Color, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(17.dp), tint = color)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, fontSize = 13.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AddMemberDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add member") },
        text = {
            Column {
                Text("Enter the full phone number with country code.", fontSize = 11.5.sp, color = TextSecondary)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceMuted)
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    BasicTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary),
                        cursorBrush = SolidColor(BungramBlue),
                        decorationBox = { inner ->
                            if (phone.isEmpty()) Text("+62 812 3456 7890", fontSize = 14.sp, color = TextTertiary)
                            inner()
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(phone) }) { Text("Add", color = BungramBlue) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceMuted)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary),
                    cursorBrush = SolidColor(BungramBlue),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) { Text("Save", color = BungramBlue) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
