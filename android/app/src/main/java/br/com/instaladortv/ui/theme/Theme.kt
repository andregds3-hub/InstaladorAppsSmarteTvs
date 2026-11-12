package br.com.instaladortv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Success = Color(0xFF4CAF50)
val Warning = Color(0xFFFFB300)

private val colors = darkColorScheme(
    primary = Color(0xFF7FB2FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF0B4A86),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFF9CCBFB),
    background = Color(0xFF0F1418),
    surface = Color(0xFF0F1418),
    surfaceVariant = Color(0xFF1E262D),
    error = Color(0xFFFF8A80),
)

@Composable
fun InstaladorTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)
