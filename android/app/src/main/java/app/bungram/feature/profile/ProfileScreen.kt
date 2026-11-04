package app.bungram.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bungram.core.design.BungramAvatar
import app.bungram.core.design.BungramBlue
import app.bungram.core.design.BungramBlueGhost
import app.bungram.core.design.Canvas
import app.bungram.core.design.Divider
import app.bungram.core.design.SurfaceMuted
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary

@Composable
fun ProfileScreen(
    name: String,
    phone: String,
    username: String,
    about: String,
    bottomPadding: Dp,
    onLogout: () -> Unit,
    avatarUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Canvas)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Profile",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 22.dp, top = 8.dp, bottom = 12.dp),
        )

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BungramAvatar(name = name, url = avatarUrl, size = 96.dp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Rounded.Verified, null, Modifier.size(15.dp), tint = BungramBlue)
            }
            if (phone.isNotBlank()) Text("+$phone", fontSize = 12.sp, color = TextSecondary)
            Spacer(Modifier.height(6.dp))
            if (username.isNotBlank()) {
                Surface(shape = RoundedCornerShape(50), color = BungramBlueGhost) {
                    Text(
                        "@$username",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 10.5.sp,
                        color = BungramBlue,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            if (about.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(about, fontSize = 11.5.sp, color = TextSecondary)
            }
        }

        Spacer(Modifier.height(22.dp))

        Surface(
            Modifier.padding(horizontal = 18.dp),
            shape = RoundedCornerShape(18.dp),
            color = SurfaceMuted,
        ) {
            Column {
                SettingRow(Icons.Rounded.Person, "Account", "Phone, username, about")
                RowDivider()
                SettingRow(Icons.Rounded.Lock, "Privacy", "Blocked contacts, last seen")
                RowDivider()
                SettingRow(Icons.Rounded.Notifications, "Notifications", "Message tones, badges")
                RowDivider()
                SettingRow(Icons.Rounded.Storage, "Storage & data", "Auto-download, cache")
                RowDivider()
                SettingRow(Icons.Rounded.Devices, "Linked devices", "1 device")
            }
        }

        Spacer(Modifier.height(14.dp))

        Surface(
            Modifier.padding(horizontal = 18.dp),
            shape = RoundedCornerShape(18.dp),
            color = SurfaceMuted,
        ) {
            SettingRow(Icons.Rounded.Logout, "Log out", null, tint = app.bungram.core.design.Danger, onClick = onLogout)
        }

        Spacer(Modifier.height(bottomPadding + 16.dp))

        Text(
            "Bungram 0.1.0 · end-to-end encrypted",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 10.sp,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    tint: Color = TextPrimary,
    onClick: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(30.dp)
                .background(Color.White, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = tint)
            if (subtitle != null) {
                Text(subtitle, fontSize = 10.sp, color = TextSecondary)
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = TextSecondary)
    }
}

@Composable
private fun RowDivider() {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 56.dp)
            .height(0.6.dp)
            .background(Divider)
    )
}
