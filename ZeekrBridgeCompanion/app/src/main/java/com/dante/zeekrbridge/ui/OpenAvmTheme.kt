package com.dante.zeekrbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val OpenAvmLightColors = lightColorScheme(
    primary = Color(0xFF006B5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9FF2E1),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635D),
    secondaryContainer = Color(0xFFCDE8E0),
    tertiary = Color(0xFF426277),
    tertiaryContainer = Color(0xFFC5E7FF),
    surface = Color(0xFFF7FAF8),
    surfaceVariant = Color(0xFFDAE5E1),
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
    surface = Color(0xFF101413),
    surfaceVariant = Color(0xFF3F4946),
)

private val OpenAvmShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun OpenAvmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) OpenAvmDarkColors else OpenAvmLightColors,
        shapes = OpenAvmShapes,
        content = content,
    )
}
