package dev.leyline.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Fixed brand colours rather than Android 12's wallpaper colours: an emergency
 * app should look the same on every phone, and SOS red must never be muted.
 * Neutral greys with one teal accent, so red is the only loud colour on screen.
 */
private val Dark = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF04201C),
    primaryContainer = Color(0xFF12302C),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFFA1A1AA),
    onSecondary = Color(0xFF09090B),
    secondaryContainer = Color(0xFF1F1F23),
    onSecondaryContainer = Color(0xFFE4E4E7),
    error = Color(0xFFF43F5E),
    onError = Color.White,
    errorContainer = Color(0xFF3A0D18),
    onErrorContainer = Color(0xFFFFE4E8),
    background = Color(0xFF09090B),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF09090B),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF18181B),
    onSurfaceVariant = Color(0xFF8E8E96),
    surfaceContainerLowest = Color(0xFF060607),
    surfaceContainerLow = Color(0xFF0E0E10),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF1A1A1D),
    surfaceContainerHighest = Color(0xFF232327),
    outline = Color(0xFF2E2E33),
    outlineVariant = Color(0xFF1E1E22),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0D9488),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F7F5),
    onPrimaryContainer = Color(0xFF053B35),
    secondary = Color(0xFF52525B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F1),
    onSecondaryContainer = Color(0xFF18181B),
    error = Color(0xFFE11D48),
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E8),
    onErrorContainer = Color(0xFF4C0519),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF09090B),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF09090B),
    surfaceVariant = Color(0xFFF0F0F1),
    onSurfaceVariant = Color(0xFF71717A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7F8),
    surfaceContainer = Color(0xFFF2F2F3),
    surfaceContainerHigh = Color(0xFFEBEBED),
    surfaceContainerHighest = Color(0xFFE4E4E7),
    outline = Color(0xFFD4D4D8),
    outlineVariant = Color(0xFFE7E7EA),
)

private val Type = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp),
    )
}

@Composable
fun LeylineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
