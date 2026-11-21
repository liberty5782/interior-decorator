package app.roomplanner

import android.content.Context
import androidx.core.content.edit

/** Small persisted settings: which server to talk to and which room was last used. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_SERVER, "") ?: ""
        set(value) = sp.edit { putString(KEY_SERVER, value) }

    var roomId: Long?
        get() = sp.getLong(KEY_ROOM, -1L).takeIf { it >= 0 }
        set(value) = sp.edit { if (value == null) remove(KEY_ROOM) else putLong(KEY_ROOM, value) }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_ROOM = "room_id"
    }
}
