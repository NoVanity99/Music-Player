package com.novanity.musicplayer.platform

import android.content.Context
import androidx.core.content.edit

/** [KeyValueStore] over SharedPreferences (same files and keys as earlier versions). */
class SharedPrefsStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) = prefs.edit { putString(key, value) }
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.edit { putInt(key, value) }
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) = prefs.edit { putLong(key, value) }
    override fun getFloat(key: String, default: Float) = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) = prefs.edit { putFloat(key, value) }
    override fun getStringSet(key: String): Set<String>? = prefs.getStringSet(key, null)?.toSet()
    override fun putStringSet(key: String, value: Set<String>) = prefs.edit { putStringSet(key, value) }
}
