package net.voipitalia.frnlite

import android.content.Context
import net.voipitalia.frnlite.net.Account

/** Persistent settings backed by SharedPreferences. */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("frnlite", Context.MODE_PRIVATE)

    // account
    var email: String get() = sp.getString("email", "") ?: ""; set(v) = put("email", v)
    var password: String get() = sp.getString("password", "") ?: ""; set(v) = put("password", v)
    var callsign: String get() = sp.getString("callsign", "") ?: ""; set(v) = put("callsign", v)
    var name: String get() = sp.getString("name", "") ?: ""; set(v) = put("name", v)
    var city: String get() = sp.getString("city", "") ?: ""; set(v) = put("city", v)
    var country: String get() = sp.getString("country", "") ?: ""; set(v) = put("country", v)
    var clientType: Int get() = sp.getInt("clientType", 2); set(v) = sp.edit().putInt("clientType", v).apply()
    var bandChannel: String get() = sp.getString("band", "PC Only") ?: "PC Only"; set(v) = put("band", v)

    // server (single, fixed to Voip-Italia on locked builds)
    var serverName: String
        get() = if (Brand.LOCKED) Brand.FIXED_SERVER.name else sp.getString("srvName", Brand.FIXED_SERVER.name) ?: ""
        set(v) = put("srvName", v)
    var serverHost: String
        get() = if (Brand.LOCKED) Brand.FIXED_SERVER.host else sp.getString("srvHost", Brand.FIXED_SERVER.host) ?: ""
        set(v) = put("srvHost", v)
    var serverPort: Int
        get() = if (Brand.LOCKED) Brand.FIXED_SERVER.port else sp.getInt("srvPort", Brand.FIXED_SERVER.port)
        set(v) = sp.edit().putInt("srvPort", v).apply()
    var serverRoom: String
        get() = sp.getString("srvRoom", Brand.FIXED_SERVER.room) ?: Brand.FIXED_SERVER.room
        set(v) = put("srvRoom", v)

    var autoReconnect: Boolean get() = sp.getBoolean("autoReconnect", true); set(v) = sp.edit().putBoolean("autoReconnect", v).apply()

    /** Session-only: server editing unlocked with the password (never persisted). */
    @Volatile var unlocked: Boolean = false
    fun unlock(pw: String): Boolean { if (Brand.LOCKED && pw == Brand.UNLOCK_PASSWORD) unlocked = true; return unlocked }
    val serverEditable: Boolean get() = !Brand.LOCKED || unlocked

    fun account() = Account(
        email = email, password = password, callsign = callsign, name = name,
        clientType = clientType, bandChannel = bandChannel,
        description = "FRN Client Lite", country = country, city = city)

    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
}
