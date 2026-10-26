package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.platform.KeyValueStore
import java.io.File
import java.util.Properties

/** [KeyValueStore] backed by a .properties file, written atomically on every change. */
class PropertiesStore(private val file: File) : KeyValueStore {
    private val props = Properties()
    private val lock = Any()

    init {
        if (file.exists()) runCatching { file.inputStream().use(props::load) }
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { props.store(it, null) }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun get(key: String): String? = synchronized(lock) { props.getProperty(key) }

    private fun set(key: String, value: String?) = synchronized(lock) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        save()
    }

    override fun getString(key: String) = get(key)
    override fun putString(key: String, value: String?) = set(key, value)
    override fun getBoolean(key: String, default: Boolean) = get(key)?.toBooleanStrictOrNull() ?: default
    override fun putBoolean(key: String, value: Boolean) = set(key, value.toString())
    override fun getInt(key: String, default: Int) = get(key)?.toIntOrNull() ?: default
    override fun putInt(key: String, value: Int) = set(key, value.toString())
    override fun getLong(key: String, default: Long) = get(key)?.toLongOrNull() ?: default
    override fun putLong(key: String, value: Long) = set(key, value.toString())
    override fun getFloat(key: String, default: Float) = get(key)?.toFloatOrNull() ?: default
    override fun putFloat(key: String, value: Float) = set(key, value.toString())

    // Sets are stored as newline-separated values (paths never contain newlines).
    override fun getStringSet(key: String): Set<String>? = get(key)?.split('\n')?.filter { it.isNotEmpty() }?.toSet()
    override fun putStringSet(key: String, value: Set<String>) = set(key, value.joinToString("\n"))
}
