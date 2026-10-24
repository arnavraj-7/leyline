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
 */
private val Dark = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF032B26),
    primaryContainer = Color(0xFF134E48),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF032B26),
    secondaryContainer = Color(0xFF134E48),
    onSecondaryContainer = Color(0xFFCCFBF1),
    error = Color(0xFFF43F5E),
    onError = Color.White,
    errorContainer = Color(0xFF4C1020),
    onErrorContainer = Color(0xFFFFE4E8),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF1A2540),
    onSurfaceVariant = Color(0xFF9AA8C2),
    surfaceContainerLowest = Color(0xFF080E1A),
    surfaceContainerLow = Color(0xFF0F1828),
    surfaceContainer = Color(0xFF131D31),
    surfaceContainerHigh = Color(0xFF18243B),
    surfaceContainerHighest = Color(0xFF1E2B45),
    outline = Color(0xFF34425F),
    outlineVariant = Color(0xFF243150),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0D9488),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF053B35),
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF053B35),
    error = Color(0xFFE11D48),
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E8),
    onErrorContainer = Color(0xFF4C0519),
    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF6F8FC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE9EEF6),
    onSurfaceVariant = Color(0xFF55627A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FAFD),
    surfaceContainer = Color(0xFFF1F4F9),
    surfaceContainerHigh = Color(0xFFEBEFF6),
    surfaceContainerHighest = Color(0xFFE4E9F2),
    outline = Color(0xFFC9D2E1),
    outlineVariant = Color(0xFFDDE3EE),
)

private val Type = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    )
}

@Composable
fun LeylineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
