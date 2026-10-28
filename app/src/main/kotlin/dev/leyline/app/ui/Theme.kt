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
 * Soft neutrals, off-black and off-white rather than pure, with one muted teal accent:
 * calm to read for hours, and SOS red stays the one colour that stands out.
 */
private val Dark = darkColorScheme(
    primary = Color(0xFF5FB8AC),
    onPrimary = Color(0xFF0B1F1C),
    primaryContainer = Color(0xFF1A2E2B),
    onPrimaryContainer = Color(0xFFCDEBE6),
    secondary = Color(0xFF9A9CA3),
    onSecondary = Color(0xFF0E0F11),
    secondaryContainer = Color(0xFF1D1E22),
    onSecondaryContainer = Color(0xFFE0E0E4),
    error = Color(0xFFE5484D),
    onError = Color.White,
    errorContainer = Color(0xFF3B1517),
    onErrorContainer = Color(0xFFFBDCDD),
    background = Color(0xFF0E0F11),
    onBackground = Color(0xFFE6E6E9),
    surface = Color(0xFF0E0F11),
    onSurface = Color(0xFFE6E6E9),
    surfaceVariant = Color(0xFF1D1E22),
    onSurfaceVariant = Color(0xFF8B8D94),
    surfaceContainerLowest = Color(0xFF0B0C0E),
    surfaceContainerLow = Color(0xFF121316),
    surfaceContainer = Color(0xFF16171A),
    surfaceContainerHigh = Color(0xFF1D1E22),
    surfaceContainerHighest = Color(0xFF25262B),
    outline = Color(0xFF2C2D32),
    outlineVariant = Color(0xFF202125),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2F7F75),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEFEC),
    onPrimaryContainer = Color(0xFF0F3A35),
    secondary = Color(0xFF5F6168),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEEEEC),
    onSecondaryContainer = Color(0xFF1F2023),
    error = Color(0xFFD13B40),
    onError = Color.White,
    errorContainer = Color(0xFFFBE3E3),
    onErrorContainer = Color(0xFF4A1013),
    background = Color(0xFFF7F7F5),
    onBackground = Color(0xFF1F2023),
    surface = Color(0xFFF7F7F5),
    onSurface = Color(0xFF1F2023),
    surfaceVariant = Color(0xFFEEEEEC),
    onSurfaceVariant = Color(0xFF6B6E76),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3F3F1),
    surfaceContainer = Color(0xFFEEEEEC),
    surfaceContainerHigh = Color(0xFFE8E8E5),
    surfaceContainerHighest = Color(0xFFE1E1DE),
    outline = Color(0xFFD3D3CF),
    outlineVariant = Color(0xFFE4E4E0),
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
