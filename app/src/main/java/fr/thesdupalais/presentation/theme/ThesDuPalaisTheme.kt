package fr.thesdupalais.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TeaPalette = lightColorScheme(
    primary = Color(0xFF315C46),
    onPrimary = Color.White,
    secondary = Color(0xFFB8893E),
    onSecondary = Color.White,
    background = Color(0xFFF7F4EC),
    onBackground = Color(0xFF28352D),
    surface = Color.White,
    onSurface = Color(0xFF28352D),
    surfaceVariant = Color(0xFFE5EDE5),
    onSurfaceVariant = Color(0xFF666B62),
    outline = Color(0xFFDADDD5),
)

@Composable
fun ThesDuPalaisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TeaPalette,
        typography = Typography(),
        content = content,
    )
}
