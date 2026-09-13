package com.dante.zeekrbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val OpenAvmLightColors = lightColorScheme(
    primary = Color(0xFF006D62),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7EEE4),
    onPrimaryContainer = Color(0xFF004D43),
    secondary = Color(0xFF4A635D),
    secondaryContainer = Color(0xFFCDE8E0),
    tertiary = Color(0xFF426277),
    tertiaryContainer = Color(0xFFC5E7FF),
    background = Color(0xFFF4F7F8),
    onBackground = Color(0xFF172B31),
    surface = Color(0xFFFCFDFD),
    onSurface = Color(0xFF172B31),
    surfaceVariant = Color(0xFFE5EDEE),
    onSurfaceVariant = Color(0xFF54666B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEAF0F1),
    surfaceContainerHigh = Color(0xFFE1E9EB),
    surfaceContainerHighest = Color(0xFFD8E3E5),
    outline = Color(0xFF7A8C91),
    outlineVariant = Color(0xFFD3DEE0),
)

private val OpenAvmDarkColors = darkColorScheme(
    primary = Color(0xFF83D5C5),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF9FF2E1),
    secondary = Color(0xFFB1CCC4),
    secondaryContainer = Color(0xFF334B46),
    tertiary = Color(0xFFA9CBE3),
    tertiaryContainer = Color(0xFF294A5E),
    background = Color(0xFF0E161A),
    onBackground = Color(0xFFE1EAED),
    surface = Color(0xFF131E23),
    onSurface = Color(0xFFE1EAED),
    surfaceVariant = Color(0xFF2D3C42),
    onSurfaceVariant = Color(0xFFB2C3C9),
    surfaceContainerLowest = Color(0xFF0A1115),
    surfaceContainerLow = Color(0xFF19262C),
    surfaceContainer = Color(0xFF203037),
    surfaceContainerHigh = Color(0xFF293A42),
    surfaceContainerHighest = Color(0xFF32464F),
    outline = Color(0xFF81969D),
    outlineVariant = Color(0xFF354950),
)

private val OpenAvmShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
)

private val OpenAvmTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 31.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun OpenAvmTheme(content: @Composable () -> Unit) {
    val systemLocale = LocalConfiguration.current.locales[0]
    SideEffect { PhoneLanguage.updateSystemLocale(systemLocale) }
    CompositionLocalProvider(LocalLayoutDirection provides if (PhoneLanguage.language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) OpenAvmDarkColors else OpenAvmLightColors,
            shapes = OpenAvmShapes,
            typography = OpenAvmTypography,
            content = content,
        )
    }
}
