package dev.pocketpc.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PocketPcDarkColors =
    darkColorScheme(
        primary = Color(0xFF8EC5FF),
        onPrimary = Color(0xFF002B4C),
        primaryContainer = Color(0xFF174A70),
        onPrimaryContainer = Color(0xFFD1E8FF),
        secondary = Color(0xFFAFC7E8),
        onSecondary = Color(0xFF193149),
        secondaryContainer = Color(0xFF304861),
        onSecondaryContainer = Color(0xFFD7E7FF),
        tertiary = Color(0xFFA7D8C9),
        onTertiary = Color(0xFF0B372F),
        tertiaryContainer = Color(0xFF285047),
        onTertiaryContainer = Color(0xFFC3F3E5),
        background = Color(0xFF111318),
        onBackground = Color(0xFFE4E7ED),
        surface = Color(0xFF1A1C22),
        onSurface = Color(0xFFE4E7ED),
        surfaceDim = Color(0xFF10151D),
        surfaceBright = Color(0xFF35404E),
        surfaceContainerLowest = Color(0xFF0B1018),
        surfaceContainerLow = Color(0xFF151C26),
        surfaceContainer = Color(0xFF1C2531),
        surfaceContainerHigh = Color(0xFF263241),
        surfaceContainerHighest = Color(0xFF303E50),
        outlineVariant = Color(0xFF405064),
        surfaceVariant = Color(0xFF272A31),
        onSurfaceVariant = Color(0xFFC7CBD4),
        outline = Color(0xFF7D838E),
        error = Color(0xFFFFB4AB),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFFFDAD6),
    )

private val PocketPcLightColors =
    lightColorScheme(
        primary = Color(0xFF00639A),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCDE5FF),
        onPrimaryContainer = Color(0xFF001D32),
        secondary = Color(0xFF4E616F),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD1E5F5),
        onSecondaryContainer = Color(0xFF0A1E29),
        tertiary = Color(0xFF38665B),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFBBECDD),
        onTertiaryContainer = Color(0xFF002019),
        background = Color(0xFFF5F7FA),
        onBackground = Color(0xFF191C20),
        surface = Color(0xFFFCFCFF),
        onSurface = Color(0xFF191C20),
        surfaceDim = Color(0xFFD9E1EC),
        surfaceBright = Color(0xFFF9FBFF),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF1F5FC),
        surfaceContainer = Color(0xFFEAF0F9),
        surfaceContainerHigh = Color(0xFFE1EAF6),
        surfaceContainerHighest = Color(0xFFD7E3F1),
        outlineVariant = Color(0xFFC1CDDC),
        surfaceVariant = Color(0xFFE1E5EA),
        onSurfaceVariant = Color(0xFF44474E),
        outline = Color(0xFF74777F),
        error = Color(0xFFBA1A1A),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )

private val PocketPcShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(20.dp),
    )

private val PocketPcTypography =
    Typography(
        headlineSmall =
            TextStyle(
                fontSize = 20.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        titleLarge =
            TextStyle(
                fontSize = 18.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        titleMedium =
            TextStyle(
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        titleSmall =
            TextStyle(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
            ),
        bodyLarge =
            TextStyle(
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodyMedium =
            TextStyle(
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        bodySmall =
            TextStyle(
                fontSize = 11.sp,
                lineHeight = 15.sp,
            ),
        labelLarge =
            TextStyle(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
        labelMedium =
            TextStyle(
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        labelSmall =
            TextStyle(
                fontSize = 9.sp,
                lineHeight = 12.sp,
            ),
    )

@Composable
fun PocketPcTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme =
            if (darkTheme) {
                PocketPcDarkColors
            } else {
                PocketPcLightColors
            },
        typography = PocketPcTypography,
        shapes = PocketPcShapes,
        content = content,
    )
}
