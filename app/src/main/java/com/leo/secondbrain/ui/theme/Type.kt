package com.leo.secondbrain.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.leo.secondbrain.R
import com.leo.secondbrain.data.TypographyPreset

@OptIn(ExperimentalTextApi::class)
private val softTypeface = FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
    .flatMap { weight ->
        val axes = FontVariation.Settings(FontVariation.weight(weight.weight))
        listOf(Font(R.font.nunito, weight, variationSettings = axes),
            Font(R.font.nunito_italic, weight, FontStyle.Italic, variationSettings = axes))
    })
private val handwrittenTypeface = FontFamily(Font(R.font.kalam))

internal fun brainTypography(preset: TypographyPreset): Typography {
    val typeface = when (preset) {
        TypographyPreset.HANDWRITTEN -> handwrittenTypeface
        TypographyPreset.ELEGANT -> FontFamily.Serif
        TypographyPreset.SOFT -> softTypeface
        TypographyPreset.MODERN -> FontFamily.SansSerif
    }
    val tracking = if (preset == TypographyPreset.MODERN) (-.25).sp else 0.sp
    val heading = TextStyle(fontFamily = typeface,
        fontWeight = if (preset == TypographyPreset.HANDWRITTEN) FontWeight.Normal else FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = tracking)
    val item = TextStyle(fontFamily = typeface, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = tracking)
    val body = TextStyle(fontFamily = typeface, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val secondary = body.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    return Typography(
        displayLarge = heading.copy(fontSize = 36.sp, lineHeight = 44.sp),
        displayMedium = heading.copy(fontSize = 32.sp, lineHeight = 40.sp),
        displaySmall = heading.copy(fontSize = 30.sp, lineHeight = 38.sp),
        headlineLarge = heading.copy(fontSize = 28.sp, lineHeight = 36.sp),
        headlineMedium = heading.copy(fontSize = 26.sp, lineHeight = 34.sp),
        headlineSmall = heading,
        titleLarge = item.copy(fontSize = 18.sp, lineHeight = 24.sp),
        titleMedium = item, titleSmall = item.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        bodyLarge = body.copy(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = body, bodySmall = body.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = body.copy(fontWeight = FontWeight.Medium),
        labelMedium = secondary, labelSmall = secondary
    )
}
