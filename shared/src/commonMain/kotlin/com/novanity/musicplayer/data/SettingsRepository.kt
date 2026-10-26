package com.novanity.musicplayer.data

import com.novanity.musicplayer.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(private val prefs: KeyValueStore) {

    private val _themeMode = MutableStateFlow(
        enumValueOr(prefs.getString(KEY_THEME), ThemeMode.DARK)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _sortOrder = MutableStateFlow(
        enumValueOr(prefs.getString(KEY_SORT), SortOrder.TITLE)
    )
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    /** Folders the user picked to pull music from (subfolders included). Empty = not set up yet. */
    private val _includedFolders = MutableStateFlow(prefs.getStringSet(KEY_FOLDERS).orEmpty().toSet())
    val includedFolders: StateFlow<Set<String>> = _includedFolders.asStateFlow()

    /** Folders (and their subfolders) to leave out, even if they sit inside an included folder. */
    private val _excludedFolders = MutableStateFlow(prefs.getStringSet(KEY_EXCLUDED).orEmpty().toSet())
    val excludedFolders: StateFlow<Set<String>> = _excludedFolders.asStateFlow()

    // ---- Playback ----

    private val _volume = MutableStateFlow(prefs.getFloat(KEY_VOLUME, 1f))
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _speed = MutableStateFlow(prefs.getFloat(KEY_SPEED, 1f))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** Crossfade between songs in seconds (0 = off). */
    private val _crossfadeSeconds = MutableStateFlow(prefs.getInt(KEY_CROSSFADE, 0))
    val crossfadeSeconds: StateFlow<Int> = _crossfadeSeconds.asStateFlow()

    // ---- Equalizer (Android) ----

    private val _eqEnabled = MutableStateFlow(prefs.getBoolean(KEY_EQ_ENABLED, false))
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    /** Selected preset index, or -1 for the custom band levels below. */
    private val _eqPreset = MutableStateFlow(prefs.getInt(KEY_EQ_PRESET, -1))
    val eqPreset: StateFlow<Int> = _eqPreset.asStateFlow()

    /** Custom band levels in millibels. */
    private val _eqBands = MutableStateFlow(prefs.getString(KEY_EQ_BANDS)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty())
    val eqBands: StateFlow<List<Int>> = _eqBands.asStateFlow()

    fun setEqEnabled(enabled: Boolean) {
        _eqEnabled.value = enabled
        prefs.putBoolean(KEY_EQ_ENABLED, enabled)
    }

    fun setEqPreset(index: Int) {
        _eqPreset.value = index
        prefs.putInt(KEY_EQ_PRESET, index)
    }

    fun setEqBands(levels: List<Int>) {
        _eqBands.value = levels
        _eqPreset.value = -1
        prefs.putString(KEY_EQ_BANDS, levels.joinToString(","))
        prefs.putInt(KEY_EQ_PRESET, -1)
    }

    // ---- Updates & integrations ----

    private val _autoUpdateCheck = MutableStateFlow(prefs.getBoolean(KEY_AUTO_UPDATE, true))
    val autoUpdateCheck: StateFlow<Boolean> = _autoUpdateCheck.asStateFlow()

    private val _lastPromptedVersion = MutableStateFlow(prefs.getString(KEY_LAST_PROMPTED))
    val lastPromptedVersion: StateFlow<String?> = _lastPromptedVersion.asStateFlow()

    /** Discord Rich Presence (desktop). */
    private val _discordPresence = MutableStateFlow(prefs.getBoolean(KEY_DISCORD, true))
    val discordPresence: StateFlow<Boolean> = _discordPresence.asStateFlow()

    fun setAutoUpdateCheck(enabled: Boolean) {
        _autoUpdateCheck.value = enabled
        prefs.putBoolean(KEY_AUTO_UPDATE, enabled)
    }

    fun setLastPromptedVersion(version: String) {
        _lastPromptedVersion.value = version
        prefs.putString(KEY_LAST_PROMPTED, version)
    }

    fun setDiscordPresence(enabled: Boolean) {
        _discordPresence.value = enabled
        prefs.putBoolean(KEY_DISCORD, enabled)
    }

    fun setVolume(value: Float) {
        _volume.value = value.coerceIn(0f, 1f)
        prefs.putFloat(KEY_VOLUME, _volume.value)
    }

    fun setSpeed(value: Float) {
        _speed.value = value.coerceIn(0.5f, 2f)
        prefs.putFloat(KEY_SPEED, _speed.value)
    }

    fun setCrossfadeSeconds(value: Int) {
        _crossfadeSeconds.value = value.coerceIn(0, 12)
        prefs.putInt(KEY_CROSSFADE, _crossfadeSeconds.value)
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.putString(KEY_THEME, mode.name)
    }

    fun setDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
        prefs.putBoolean(KEY_DYNAMIC, enabled)
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        prefs.putString(KEY_SORT, order.name)
    }

    fun setIncludedFolders(folders: Set<String>) {
        val normalized = normalize(folders)
        _includedFolders.value = normalized
        prefs.putStringSet(KEY_FOLDERS, normalized)
    }

    fun setExcludedFolders(folders: Set<String>) {
        val normalized = normalize(folders)
        _excludedFolders.value = normalized
        prefs.putStringSet(KEY_EXCLUDED, normalized)
    }

    private fun normalize(folders: Set<String>) = folders.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.toSet()

    private inline fun <reified T : Enum<T>> enumValueOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_SORT = "sort_order"
        const val KEY_FOLDERS = "included_folders"
        const val KEY_EXCLUDED = "excluded_folders"
        const val KEY_VOLUME = "volume"
        const val KEY_SPEED = "speed"
        const val KEY_CROSSFADE = "crossfade_seconds"
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_AUTO_UPDATE = "auto_update_check"
        const val KEY_LAST_PROMPTED = "last_prompted_version"
        const val KEY_DISCORD = "discord_presence"
        const val KEY_EQ_PRESET = "eq_preset"
        const val KEY_EQ_BANDS = "eq_bands"
    }
}
