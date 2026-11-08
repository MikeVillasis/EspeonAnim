package io.github.mikevillasis.espeonanim.ui

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

val Purple = Color(0xFF7B4FD6)
val OkGreen = Color(0xFF2E9E5B)
val WarnAmber = Color(0xFFE0A100)
val BadRed = Color(0xFFD64545)

private val Dark = darkColorScheme(
    primary = Color(0xFFB79CFF), onPrimary = Color(0xFF1B0B45),
    secondary = Color(0xFF8FD6B0),
    background = Color(0xFF111015), surface = Color(0xFF1C1B22), onBackground = Color(0xFFEDEBF5), onSurface = Color(0xFFEDEBF5),
)
private val Light = lightColorScheme(
    primary = Purple, secondary = OkGreen,
    background = Color(0xFFFAF8FF), surface = Color.White,
)

// Large defaults: the phone is propped 3-4 m away while the performer reads it.
private val BigType = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun EspeonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = BigType,
        content = content,
    )
}
