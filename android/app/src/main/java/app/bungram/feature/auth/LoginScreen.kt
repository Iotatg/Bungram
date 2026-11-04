package app.bungram.feature.auth

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Sign-in. Phone -> code, with the server address behind an explicit toggle so a normal user never
 * sees it, but a developer on a LAN can point the app anywhere without rebuilding.
 */
@Composable
fun LoginScreen(
    state: AuthUiState,
    onPhoneChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onToggleServerField: () -> Unit,
    onRequestOtp: () -> Unit,
    onVerify: () -> Unit,
    onBack: () -> Unit,
    onUseDevCode: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.height(56.dp))

            Box(
                Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(BungramBlue),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "B",
                    color = TextOnBlue,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(26.dp))

            Text(
                text = if (state.step == AuthStep.PHONE) "Your number" else "Enter the code",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (state.step == AuthStep.PHONE) {
                    "Bungram uses your number only to find your account. No contacts are uploaded."
                } else {
                    "We sent a 6-digit code to ${state.phone.ifBlank { "your phone" }}."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )

            Spacer(Modifier.height(28.dp))

            if (state.step == AuthStep.PHONE) {
                OutlinedTextField(
                    value = state.phone,
                    onValueChange = onPhoneChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("+62 812 3456 7890", color = TextTertiary) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = fieldColors(),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(onClick = onBack),
                        tint = TextPrimary,
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = onCodeChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("000000", color = TextTertiary) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = fieldColors(),
                    )
                }

                if (state.devCode != null) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(BungramBlueSoft)
                            .clickable(onClick = onUseDevCode)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Dev code ", style = MaterialTheme.typography.labelMedium, color = BungramBlue)
                        Text(
                            state.devCode,
                            style = MaterialTheme.typography.labelLarge,
                            color = BungramBlue,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("tap to fill", style = MaterialTheme.typography.labelSmall, color = BungramBlue)
                    }
                }
            }

            if (state.error != null) {
                Spacer(Modifier.height(14.dp))
                Text(state.error, style = MaterialTheme.typography.bodySmall, color = Danger)
            }

            Spacer(Modifier.height(24.dp))

            PrimaryButton(
                label = if (state.step == AuthStep.PHONE) "Continue" else "Verify",
                enabled = !state.busy,
                onClick = { if (state.step == AuthStep.PHONE) onRequestOtp() else onVerify() },
            )

            Spacer(Modifier.weight(1f))

            if (state.showServerField) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "SERVER",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = state.serverUrl,
                        onValueChange = onServerUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = fieldColors(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Emulator: http://10.0.2.2:8080 · Phone on same Wi-Fi: http://<your-lan-ip>:8080",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
            } else {
                Text(
                    "Server · ${state.serverUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    modifier = Modifier
                        .clickable(onClick = onToggleServerField)
                        .padding(vertical = 10.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
            Spacer(Modifier.height(16.dp))
            Text(
                "By continuing you agree that messages are stored on your own Bungram server.",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) BungramBlue else SurfaceMuted)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) {
            Text(label, color = TextOnBlue, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        } else {
            CircularProgressIndicator(Modifier.size(20.dp), color = BungramBlue, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BungramBlue,
    unfocusedBorderColor = Divider,
    focusedContainerColor = Canvas,
    unfocusedContainerColor = Canvas,
    cursorColor = BungramBlue,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
)

/** Shown while the stored token is being validated against the cache. */
@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Canvas), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(BungramBlue),
                contentAlignment = Alignment.Center,
            ) {
                Text("B", color = TextOnBlue, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            CircularProgressIndicator(Modifier.size(18.dp), color = BungramBlue, strokeWidth = 2.dp)
        }
    }
}
