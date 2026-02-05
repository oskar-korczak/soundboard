package com.soundboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.absoluteValue

object RecentSoundsManager {

    private const val PREFS_NAME = "soundboard_prefs"
    private const val KEY_RECENT_SOUNDS = "recent_sounds"
    private const val MAX_SOUNDS = 1000

    private val COLORS = listOf(
        "#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4", "#FFEAA7",
        "#DDA0DD", "#98D8C8", "#F7DC6F", "#BB8FCE", "#85C1E9",
        "#F8B500", "#FF8C00", "#00CED1", "#FF69B4", "#32CD32",
        "#FFD700", "#FF4500", "#1E90FF", "#FF1493", "#00FA9A"
    )

    private lateinit var prefs: SharedPreferences
    private val recentSounds = LinkedHashMap<String, RecentSound>()
    private val listeners = mutableListOf<() -> Unit>()
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromPrefs()
        }
    }

    fun addSound(filename: String) {
        synchronized(lock) {
            val displayName = filename.substringBeforeLast(".")
            val color = getColorForSound(filename)
            val sound = RecentSound(
                filename = filename,
                displayName = displayName,
                color = color,
                playedAt = System.currentTimeMillis()
            )

            recentSounds.remove(filename)
            recentSounds[filename] = sound

            while (recentSounds.size > MAX_SOUNDS) {
                val oldest = recentSounds.keys.first()
                recentSounds.remove(oldest)
            }

            saveToPrefs()
        }
        notifyListeners()
    }

    fun getRecentSounds(): List<RecentSound> {
        synchronized(lock) {
            return recentSounds.values.toList().reversed()
        }
    }

    fun addChangeListener(listener: () -> Unit) {
        synchronized(lock) {
            listeners.add(listener)
        }
    }

    fun removeChangeListener(listener: () -> Unit) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    private fun getColorForSound(filename: String): String {
        val hash = filename.hashCode().absoluteValue
        return COLORS[hash % COLORS.size]
    }

    private fun loadFromPrefs() {
        recentSounds.clear()
        val json = prefs.getString(KEY_RECENT_SOUNDS, null) ?: return

        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val sound = RecentSound(
                    filename = obj.getString("filename"),
                    displayName = obj.getString("displayName"),
                    color = obj.getString("color"),
                    playedAt = obj.getLong("playedAt")
                )
                recentSounds[sound.filename] = sound
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveToPrefs() {
        val array = JSONArray()
        for (sound in recentSounds.values) {
            val obj = JSONObject().apply {
                put("filename", sound.filename)
                put("displayName", sound.displayName)
                put("color", sound.color)
                put("playedAt", sound.playedAt)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_RECENT_SOUNDS, array.toString()).apply()
    }

    private fun notifyListeners() {
        val listenersCopy: List<() -> Unit>
        synchronized(lock) {
            listenersCopy = listeners.toList()
        }
        listenersCopy.forEach { it() }
    }

    fun toJson(): String {
        val sounds = getRecentSounds()
        val array = JSONArray()
        for (sound in sounds) {
            val obj = JSONObject().apply {
                put("filename", sound.filename)
                put("displayName", sound.displayName)
                put("color", sound.color)
                put("playedAt", sound.playedAt)
            }
            array.put(obj)
        }
        return JSONObject().apply {
            put("sounds", array)
            put("count", sounds.size)
        }.toString()
    }
}
