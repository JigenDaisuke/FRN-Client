package net.voipitalia.frnlite

/**
 * Build-time branding and server lock — mirrors the desktop build.
 *
 *  LOCKED = true  -> build for friends: the Voip-Italia server is fixed and the
 *                    server settings can be changed only with UNLOCK_PASSWORD.
 *  LOCKED = false -> your build: everything is freely editable.
 *
 * Two product flavours (see app/build.gradle) set this at build time:
 *   ./gradlew assembleIoRelease      -> LOCKED = false
 *   ./gradlew assembleAmiciRelease   -> LOCKED = true
 * BuildConfig.LOCKED carries the flavour value; this object just reads it.
 */
object Brand {
    val LOCKED: Boolean = BuildConfig.LOCKED
    const val UNLOCK_PASSWORD = "FRISIETEDELLESOLE"

    data class Server(val name: String, val host: String, val port: Int, val room: String)

    val FIXED_SERVER = Server("Voip-Italia", "server.voip-italia.net", 10024, "Nazionale")
}
