package com.leo.secondbrain.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.leo.secondbrain.capture.MicWidget

internal enum class Appearance(val label: String) {
    SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark")
}

internal enum class DynamicIntensity(val label: String) {
    LOW("Low"), MEDIUM("Medium"), HIGH("High")
}

internal enum class ColorTheme(val label: String) {
    SAGE("Sage"), OCEAN("Ocean"), LAVENDER("Lavender"), ROSE("Rose"), AMBER("Amber");

    companion object {
        fun restored(name: String?) = when (name) {
            "CLAY" -> AMBER
            "OLIVE" -> SAGE
            "SLATE" -> OCEAN
            "BERRY" -> ROSE
            else -> entries.firstOrNull { it.name == name }
        }
    }
}

internal enum class SlimeFace(val label: String) {
    CLASSIC("Classic"), MINIMAL("Minimal"), PLAYFUL("Playful")
}

internal enum class WidgetAlignment(val label: String) {
    LEFT("Left"), CENTER("Center"), RIGHT("Right")
}

internal enum class AssistantVoice(val label: String, val providerId: String) {
    CEDAR("Cedar · calm", "cedar"), MARIN("Marin · warm", "marin"),
    ECHO("Echo · clear", "echo"), VERSE("Verse · lively", "verse")
}

internal enum class TypographyPreset(val label: String) {
    MODERN("Modern"), SOFT("Soft"), HANDWRITTEN("Handwritten"), ELEGANT("Elegant")
}

internal data class Preferences(
    val appearance: Appearance = Appearance.SYSTEM,
    val dynamicColor: Boolean = false,
    val dynamicIntensity: DynamicIntensity = DynamicIntensity.MEDIUM,
    val grid: Boolean = true,
    val slimeFace: SlimeFace = SlimeFace.CLASSIC,
    val voiceLookup: Boolean = true,
    val colorTheme: ColorTheme = ColorTheme.AMBER,
    val widgetTheme: ColorTheme? = null,
    val widgetAppearance: Appearance? = null,
    val widgetAlignment: WidgetAlignment = WidgetAlignment.CENTER,
    val assistantVoice: AssistantVoice = AssistantVoice.CEDAR,
    val showFolderTitles: Boolean = true,
    val folderOrder: List<String> = emptyList(),
    val showSubfolderIndicators: Boolean = false,
    val typography: TypographyPreset = TypographyPreset.SOFT,
    val selectedFolders: Map<String, String> = emptyMap(),
    val folderCarousel: Boolean = false,
    val pinOrder: List<String> = emptyList(),
    val simplifyMotion: Boolean = false
)

internal class PreferenceStore(private val context: Context) {
    private val storage = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        Preferences(
            appearance = Appearance.entries.find { it.name == storage.getString("theme", null) }
                ?: Appearance.SYSTEM,
            dynamicColor = storage.getBoolean("dynamic", false),
            dynamicIntensity = DynamicIntensity.entries.find { it.name == storage.getString("dynamicIntensity", null) }
                ?: DynamicIntensity.MEDIUM,
            grid = storage.getBoolean("grid", true),
            slimeFace = SlimeFace.entries.find { it.name == storage.getString("slimeFace", null) } ?: SlimeFace.CLASSIC,
            voiceLookup = storage.getBoolean("voiceLookup", true),
            colorTheme = ColorTheme.restored(storage.getString("colorTheme", null)) ?: ColorTheme.AMBER,
            widgetTheme = ColorTheme.restored(storage.getString("widgetTheme", null)),
            widgetAppearance = Appearance.entries.find { it.name == storage.getString("widgetAppearance", null) },
            widgetAlignment = WidgetAlignment.entries.find { it.name == storage.getString("widgetAlignment", null) }
                ?: WidgetAlignment.CENTER,
            assistantVoice = AssistantVoice.entries.find { it.name == storage.getString("assistantVoice", null) }
                ?: AssistantVoice.CEDAR,
            showFolderTitles = storage.getBoolean("showFolderTitles", true),
            showSubfolderIndicators = storage.getBoolean("showSubfolderIndicators", false),
            typography = TypographyPreset.entries.find { it.name == storage.getString("typography", null) }
                ?: TypographyPreset.SOFT,
            selectedFolders = storage.getString("selectedFolders", null)?.let { encoded ->
                runCatching {
                    val data = org.json.JSONObject(encoded)
                    data.keys().asSequence().associateWith(data::getString)
                }.getOrDefault(emptyMap())
            }.orEmpty(),
            folderOrder = storage.getString("folderOrder", null)?.let {
                runCatching { org.json.JSONArray(it).let { ids ->
                    List(ids.length()) { index -> ids.getString(index) }
                } }.getOrDefault(emptyList())
            }.orEmpty(),
            folderCarousel = storage.getBoolean("folderCarousel", false),
            pinOrder = storage.getString("pinOrder", null)?.let { value ->
                runCatching {
                    val ids = org.json.JSONArray(value)
                    List(ids.length(), ids::getString)
                }.getOrDefault(emptyList())
            }.orEmpty(),
            simplifyMotion = storage.getBoolean("simplifyMotion", false)
        )
    )
    val preferences = state.asStateFlow()

    fun update(preferences: Preferences): Boolean {
        val saved = storage.edit()
            .putString("theme", preferences.appearance.name)
            .putBoolean("dynamic", preferences.dynamicColor)
            .putString("dynamicIntensity", preferences.dynamicIntensity.name)
            .putBoolean("grid", preferences.grid)
            .putString("slimeFace", preferences.slimeFace.name)
            .putBoolean("voiceLookup", preferences.voiceLookup)
            .putString("colorTheme", preferences.colorTheme.name)
            .putString("widgetTheme", preferences.widgetTheme?.name)
            .putString("widgetAppearance", preferences.widgetAppearance?.name)
            .putString("widgetAlignment", preferences.widgetAlignment.name)
            .putString("assistantVoice", preferences.assistantVoice.name)
            .putBoolean("showFolderTitles", preferences.showFolderTitles)
            .putBoolean("showSubfolderIndicators", preferences.showSubfolderIndicators)
            .putString("typography", preferences.typography.name)
            .putString("selectedFolders", org.json.JSONObject(preferences.selectedFolders).toString())
            .putString("folderOrder", org.json.JSONArray(preferences.folderOrder).toString())
            .putBoolean("folderCarousel", preferences.folderCarousel)
            .putString("pinOrder", org.json.JSONArray(preferences.pinOrder).toString())
            .putBoolean("simplifyMotion", preferences.simplifyMotion)
            .commit()
        if (saved) {
            val before = state.value
            state.value = preferences
            if (before.appearance != preferences.appearance || before.colorTheme != preferences.colorTheme ||
                before.dynamicColor != preferences.dynamicColor || before.dynamicIntensity != preferences.dynamicIntensity ||
                before.widgetTheme != preferences.widgetTheme || before.widgetAppearance != preferences.widgetAppearance ||
                before.widgetAlignment != preferences.widgetAlignment) {
                MicWidget.refresh(context)
            }
        }
        return saved
    }
}
