package app.bungram.core.design

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val BungramColors = lightColorScheme(
    primary = BungramBlue,
    onPrimary = TextOnBlue,
    primaryContainer = BungramBlueSoft,
    onPrimaryContainer = BungramBluePressed,
    secondary = TextPrimary,
    onSecondary = Canvas,
    background = Canvas,
    onBackground = TextPrimary,
    surface = Canvas,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = TextSecondary,
    outline = Divider,
    error = Danger,
)

/**
 * Bungram ships light-only by design (the reference UI has no dark variant yet).
 * `darkTheme` is accepted so a dark token set can be dropped in later without touching screens.
 */
@Composable
fun BungramTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }
    MaterialTheme(
        colorScheme = BungramColors,
        typography = BungramTypography,
        content = content,
    )
}
