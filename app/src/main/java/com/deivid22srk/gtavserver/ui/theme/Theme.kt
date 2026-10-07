package com.deivid22srk.gtavserver.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF232F60),
    primaryContainer = Color(0xFF3B4778),
    onPrimaryContainer = Color(0xFFE0E5FF),
    secondary = Color(0xFFC3C3E7),
    onSecondary = Color(0xFF2C2C46),
    secondaryContainer = Color(0xFF43435F),
    onSecondaryContainer = Color(0xFFE0E0FA),
    tertiary = Color(0xFFE6BAD7),
    onTertiary = Color(0xFF44263D),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * Material You: cores dinâmicas no Android 12+ (fundo escuro sempre ativo,
 * conforme especificação do app); esquema escuro fixo no Android < 12.
 */
@Composable
fun GtavServerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme() // seguimos escuro; mantém respeito ao sistema p/ dynamic
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        DarkScheme
    }
    // O app pede tema escuro: força o escuro quando o sistema é claro no Android < 12
    MaterialTheme(
        colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) scheme else DarkScheme,
        content = content,
    )
}
