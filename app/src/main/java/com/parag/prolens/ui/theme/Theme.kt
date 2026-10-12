package com.parag.prolens.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Pro {
    val Gold = Color(0xFFF2C25B)
    val GoldDeep = Color(0xFFD99A35)
    val Ink = Color(0xFF000000)
    val Panel = Color(0xFF141418)
    val PanelHigh = Color(0xFF1E1E24)
    val Glass = Color(0x99101014)
    val Line = Color(0x33FFFFFF)
    val Text = Color(0xFFF5F5F7)
    val TextDim = Color(0xFFA1A1AA)
    val Good = Color(0xFF5BE49B)
    val Warn = Color(0xFFFF8A5B)
    val Rec = Color(0xFFFF5A4E)
}

@Composable
fun ProLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pro.Gold,
            onPrimary = Color.Black,
            secondary = Pro.GoldDeep,
            background = Pro.Ink,
            surface = Pro.Panel,
            onSurface = Pro.Text,
            onBackground = Pro.Text,
        ),
        content = content,
    )
}
