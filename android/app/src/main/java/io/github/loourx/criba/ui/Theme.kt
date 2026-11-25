package io.github.loourx.criba.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0F1312)
val Panel = Color(0xFF161B19)
val Line = Color(0xFF28302D)
val Fg = Color(0xFFE8EDEA)
val Muted = Color(0xFF8E9994)
val Accent = Color(0xFF5FB3D0)
val Keep = Color(0xFF4CC07F)
val Del = Color(0xFFF07164)
val Aside = Color(0xFFB59CF0)
val Warn = Color(0xFFE0A640)

@Composable
fun CribaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF06161D),
            background = Bg,
            surface = Bg,
            surfaceVariant = Panel,
            onBackground = Fg,
            onSurface = Fg,
            onSurfaceVariant = Muted,
            outline = Line,
            error = Del,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Fg) { content() }
    }
}
