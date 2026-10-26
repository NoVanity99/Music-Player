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
    }
}
