package jp.juggler.screenshotbutton

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class App1 : Application() {
    companion object {
        const val tagPrefix = "ScreenShotButton"

        lateinit var pref: SharedPreferences

        private var isPrepared = false

        fun prepareAppState(contextArg: Context) {
            if (!isPrepared) {
                val context = contextArg.applicationContext
                isPrepared = true
                pref = Pref.pref(context)
                Capture.onInitialize(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prepareAppState(applicationContext)
    }
}
