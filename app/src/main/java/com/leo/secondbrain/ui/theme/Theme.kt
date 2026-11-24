package com.leo.secondbrain.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.leo.secondbrain.data.ColorTheme
import com.leo.secondbrain.data.DynamicIntensity
import com.leo.secondbrain.data.Preferences
import com.leo.secondbrain.ui.Motion

private val LocalDark = staticCompositionLocalOf { false }
private val brainShapes = Shapes(
    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(26.dp)
)

@Composable
internal fun accentColor(index: Int): Color {
    val accent = accents.getOrElse(index) { accents.first() }
    return if (LocalDark.current) accent.dark else accent.light
}

@Composable
internal fun SecondBrainTheme(night: Boolean, preferences: Preferences, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val target = remember(context, configuration, night, preferences.dynamicColor,
        preferences.dynamicIntensity, preferences.colorTheme) { resolveColors(context, night, preferences) }
    var previous by remember { mutableStateOf(target) }
    var destination by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target != destination) {
            previous = blend(previous, destination, progress.value)
            destination = target
            progress.snapTo(0f)
            progress.animateTo(1f, tween(Motion.THEME))
        }
    }
    val colors = if (progress.value >= 1f) destination else blend(previous, destination, progress.value)
    val typography = remember(preferences.typography) { brainTypography(preferences.typography) }
    CompositionLocalProvider(LocalDark provides night) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = brainShapes, content = content)
    }
}

internal fun resolveColors(context: Context, night: Boolean, preferences: Preferences): ColorScheme {
    val curated = curatedColors(preferences.colorTheme, night)
    if (!preferences.dynamicColor || Build.VERSION.SDK_INT < 31) return curated
    val wallpaper = if (night) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val neutral = curatedColors(ColorTheme.AMBER, night)
    val (surfaces, accents) = when (preferences.dynamicIntensity) {
        DynamicIntensity.LOW -> .15f to .70f
        DynamicIntensity.MEDIUM -> .54f to .92f
        DynamicIntensity.HIGH -> .92f to 1f
    }
    return blend(neutral, wallpaper, surfaces, accents)
}

internal fun curatedColors(theme: ColorTheme, night: Boolean): ColorScheme {
    val (ink, petal, mist) = when (theme) {
        ColorTheme.SAGE -> Triple(Color(0xFF405E4C), Color(0xFFB9D5BD), Color(0xFF8FA990))
        ColorTheme.OCEAN -> Triple(Color(0xFF315F70), Color(0xFFAFCEE0), Color(0xFF86AABB))
        ColorTheme.LAVENDER -> Triple(Color(0xFF5D5081), Color(0xFFD5C6E8), Color(0xFFAB9CC5))
        ColorTheme.ROSE -> Triple(Color(0xFF804B5C), Color(0xFFEBC0CC), Color(0xFFC39BA6))
        ColorTheme.AMBER -> Triple(Color(0xFF735223), Color(0xFFF2D3A5), Color(0xFFC5A877))
    }
    fun light(neutral: Long, amount: Float) = lerp(Color(neutral), mist, amount)
    fun dark(neutral: Long, amount: Float) = lerp(Color(neutral), mist, amount)
    return if (night) darkColorScheme(
        primary = petal, onPrimary = lerp(Color.Black, ink, .44f),
        primaryContainer = lerp(Color(0xFF292823), ink, .52f), onPrimaryContainer = petal,
        secondary = dark(0xFFCFD1C6, .36f), onSecondary = dark(0xFF222420, .09f),
        secondaryContainer = dark(0xFF303430, .15f), onSecondaryContainer = dark(0xFFE8E9E1, .24f),
        tertiary = dark(0xFFDED1BA, .25f), onTertiary = dark(0xFF282319, .12f),
        tertiaryContainer = dark(0xFF38322C, .18f), onTertiaryContainer = dark(0xFFEDE3D3, .20f),
        surfaceTint = petal, background = dark(0xFF151411, .035f), onBackground = dark(0xFFF5EFE5, .08f),
        surface = dark(0xFF151411, .035f), onSurface = dark(0xFFF5EFE5, .08f),
        surfaceVariant = dark(0xFF343129, .13f), onSurfaceVariant = dark(0xFFD5CDBF, .12f),
        outline = dark(0xFFA7A398, .12f), outlineVariant = dark(0xFF43443E, .16f),
        surfaceDim = dark(0xFF161713, .05f), surfaceBright = dark(0xFF373833, .18f),
        surfaceContainerLowest = dark(0xFF100F0D, .04f), surfaceContainerLow = dark(0xFF201D18, .07f),
        surfaceContainer = dark(0xFF26231D, .08f), surfaceContainerHigh = dark(0xFF302B23, .09f),
        surfaceContainerHighest = dark(0xFF39332B, .11f)
    ) else lightColorScheme(
        primary = ink, onPrimary = Color.White, primaryContainer = light(0xFFF6F2E9, .34f),
        onPrimaryContainer = lerp(Color(0xFF151A15), ink, .28f),
        secondary = lerp(Color(0xFF404842), ink, .30f), onSecondary = Color.White,
        secondaryContainer = light(0xFFF1F1E4, .25f), onSecondaryContainer = light(0xFF262921, .03f),
        tertiary = lerp(Color(0xFF5B4A38), ink, .40f), onTertiary = Color.White,
        tertiaryContainer = light(0xFFF5EADA, .27f), onTertiaryContainer = light(0xFF32271E, .06f),
        surfaceTint = ink, background = light(0xFFFAF7EF, .12f), onBackground = light(0xFF23241F, .08f),
        surface = light(0xFFFAF7EF, .12f), onSurface = light(0xFF23241F, .08f),
        surfaceVariant = light(0xFFF0EDE2, .23f), onSurfaceVariant = light(0xFF50544A, .09f),
        outline = light(0xFF74766C, .16f), outlineVariant = light(0xFFE2E0D6, .25f),
        surfaceDim = light(0xFFEBE6DB, .18f), surfaceBright = light(0xFFFDFBF5, .06f),
        surfaceContainerLowest = light(0xFFFFFFFC, .05f), surfaceContainerLow = light(0xFFFCFAF3, .10f),
        surfaceContainer = light(0xFFF3F0E6, .17f), surfaceContainerHigh = light(0xFFEEEAE0, .21f),
        surfaceContainerHighest = light(0xFFE9E6DC, .26f)
    )
}

private fun blend(from: ColorScheme, to: ColorScheme, surfaces: Float, accents: Float = surfaces): ColorScheme =
    from.copy(
        primary = lerp(from.primary, to.primary, accents),
        onPrimary = lerp(from.onPrimary, to.onPrimary, accents),
        primaryContainer = lerp(from.primaryContainer, to.primaryContainer, accents),
        onPrimaryContainer = lerp(from.onPrimaryContainer, to.onPrimaryContainer, accents),
        inversePrimary = lerp(from.inversePrimary, to.inversePrimary, accents),
        secondary = lerp(from.secondary, to.secondary, accents),
        onSecondary = lerp(from.onSecondary, to.onSecondary, accents),
        secondaryContainer = lerp(from.secondaryContainer, to.secondaryContainer, accents),
        onSecondaryContainer = lerp(from.onSecondaryContainer, to.onSecondaryContainer, accents),
        tertiary = lerp(from.tertiary, to.tertiary, accents),
        onTertiary = lerp(from.onTertiary, to.onTertiary, accents),
        tertiaryContainer = lerp(from.tertiaryContainer, to.tertiaryContainer, accents),
        onTertiaryContainer = lerp(from.onTertiaryContainer, to.onTertiaryContainer, accents),
        background = lerp(from.background, to.background, surfaces),
        onBackground = lerp(from.onBackground, to.onBackground, surfaces),
        surface = lerp(from.surface, to.surface, surfaces),
        onSurface = lerp(from.onSurface, to.onSurface, surfaces),
        surfaceVariant = lerp(from.surfaceVariant, to.surfaceVariant, surfaces),
        onSurfaceVariant = lerp(from.onSurfaceVariant, to.onSurfaceVariant, surfaces),
        surfaceTint = lerp(from.surfaceTint, to.surfaceTint, accents),
        inverseSurface = lerp(from.inverseSurface, to.inverseSurface, surfaces),
        inverseOnSurface = lerp(from.inverseOnSurface, to.inverseOnSurface, surfaces),
        error = lerp(from.error, to.error, surfaces),
        onError = lerp(from.onError, to.onError, surfaces),
        errorContainer = lerp(from.errorContainer, to.errorContainer, surfaces),
        onErrorContainer = lerp(from.onErrorContainer, to.onErrorContainer, surfaces),
        outline = lerp(from.outline, to.outline, surfaces),
        outlineVariant = lerp(from.outlineVariant, to.outlineVariant, surfaces),
        scrim = lerp(from.scrim, to.scrim, surfaces),
        surfaceBright = lerp(from.surfaceBright, to.surfaceBright, surfaces),
        surfaceDim = lerp(from.surfaceDim, to.surfaceDim, surfaces),
        surfaceContainerLowest = lerp(from.surfaceContainerLowest, to.surfaceContainerLowest, surfaces),
        surfaceContainerLow = lerp(from.surfaceContainerLow, to.surfaceContainerLow, surfaces),
        surfaceContainer = lerp(from.surfaceContainer, to.surfaceContainer, surfaces),
        surfaceContainerHigh = lerp(from.surfaceContainerHigh, to.surfaceContainerHigh, surfaces),
        surfaceContainerHighest = lerp(from.surfaceContainerHighest, to.surfaceContainerHighest, surfaces)
    )
