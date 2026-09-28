package com.hermannpr.kyoto.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Índigo (como el resto del proyecto Kyoto) con acento ámbar.
private val Light = lightColorScheme(
    primary = Color(0xFF3F51B5), onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE0FF), onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5B5D72), secondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFF7D5700), tertiaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFFFBFAFF), surface = Color(0xFFFBFAFF),
    surfaceContainer = Color(0xFFEFEDF4), surfaceContainerHigh = Color(0xFFE9E7EF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFBAC3FF), onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF293CA0), onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC4C5DD), secondaryContainer = Color(0xFF434659),
    tertiary = Color(0xFFF8BD4B), tertiaryContainer = Color(0xFF5F4100),
    background = Color(0xFF121318), surface = Color(0xFF121318),
    surfaceContainer = Color(0xFF1F1F25), surfaceContainerHigh = Color(0xFF292A2F),
)

@Composable
fun KyotoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
