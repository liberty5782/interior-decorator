package app.roomplanner

import android.content.Context

/** Small persisted settings: which server to talk to and which room was last used. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_SERVER, "") ?: ""
        set(value) = sp.edit().putString(KEY_SERVER, value).apply()

    var roomId: Long?
        get() = sp.getLong(KEY_ROOM, -1L).takeIf { it >= 0 }
        set(value) {
            val e = sp.edit()
            if (value == null) e.remove(KEY_ROOM) else e.putLong(KEY_ROOM, value)
            e.apply()
        }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_ROOM = "room_id"
    }
}
