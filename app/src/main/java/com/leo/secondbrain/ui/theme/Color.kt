package com.leo.secondbrain.ui.theme

import androidx.compose.ui.graphics.Color

internal data class Accent(val name: String, val light: Color, val dark: Color)

internal val accents = listOf(
    Accent("Clay", Color(0xFF9A4B32), Color(0xFFF8B29B)),
    Accent("Olive", Color(0xFF53633B), Color(0xFFBACF93)),
    Accent("Ochre", Color(0xFF80581D), Color(0xFFEAC080)),
    Accent("Iris", Color(0xFF69558C), Color(0xFFD4BDF1)),
    Accent("Lagoon", Color(0xFF306A67), Color(0xFF9BD0CA)),
    Accent("Rose", Color(0xFF934E68), Color(0xFFF0B0C8)),
    Accent("Cobalt", Color(0xFF345E9E), Color(0xFFAAC7F6)),
    Accent("Pine", Color(0xFF326248), Color(0xFF9BD5B3)),
    Accent("Coral", Color(0xFF9C433B), Color(0xFFF4A99D)),
    Accent("Plum", Color(0xFF7E416F), Color(0xFFE7AECE)),
    Accent("Copper", Color(0xFF8B512A), Color(0xFFE9B68E)),
    Accent("Dusk", Color(0xFF484C87), Color(0xFFB7B9E8)),
    Accent("Ocean", Color(0xFF2F6380), Color(0xFF99CBE7)),
    Accent("Mint", Color(0xFF426B4D), Color(0xFFB1D4AF)),
    Accent("Wine", Color(0xFF873B51), Color(0xFFEDAAC0)),
    Accent("Sand", Color(0xFF76643A), Color(0xFFDDD09A)),
    Accent("Smoke", Color(0xFF555C68), Color(0xFFBCC5D1)),
    Accent("Mulberry", Color(0xFF6C4760), Color(0xFFD8B5CF))
)
