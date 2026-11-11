package net.voipitalia.frnlite

import android.app.Application

/** Holds the single Controller/Settings for the process lifetime. */
class App : Application() {
    lateinit var settings: Settings
    lateinit var controller: Controller

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        controller = Controller(settings)
        // audio is started from MainActivity once the mic permission is granted
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
