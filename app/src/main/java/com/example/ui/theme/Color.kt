package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Obsidian Dark Studio Palette
val ObsidianVoid = Color(0xFF070B14)
val ObsidianSurface = Color(0xFF0F172A)
val ObsidianSurfaceHigh = Color(0xFF1A2540)
val ElectricCyan = Color(0xFF00F5D4)
val CyberAzure = Color(0xFF3A86FF)
val AmberGold = Color(0xFFFFBE0B)
val CrimsonAlert = Color(0xFFFF4D6D)
val MistWhite = Color(0xFFF1F5F9)
val SlateMuted = Color(0xFF94A3B8)

// Daylight Studio (Light Mode)
val DaylightBg = Color(0xFFF4F7FB)
val DaylightSurface = Color(0xFFFFFFFF)
val DaylightSurfaceHigh = Color(0xFFE9EFF8)
val CobaltPrimary = Color(0xFF1D4ED8)
val EmeraldSecondary = Color(0xFF059669)
val WarmAmber = Color(0xFFD97706)
val InkDark = Color(0xFF0F172A)
val InkMuted = Color(0xFF475569)

// Hacker Mode GUI Palette (CRT Phosphor Matrix)
val HackerVoid = Color(0xFF020703)
val HackerSurface = Color(0xFF071309)
val HackerSurfaceHigh = Color(0xFF0D2311)
val MatrixGreen = Color(0xFF00FF41)
val TerminalCyan = Color(0xFF00E5FF)
val HackerAmber = Color(0xFFFFB000)
val HackerText = Color(0xFFC8FFD4)
val HackerMuted = Color(0xFF56B870)

fun parseHexColorOrFallback(hex: String, fallback: Color): Color {
    return try {
        val clean = hex.trim().removePrefix("#")
        val value = clean.toLong(16)
        when (clean.length) {
            6 -> Color(0xFF000000L or value)
            8 -> Color(value)
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}
