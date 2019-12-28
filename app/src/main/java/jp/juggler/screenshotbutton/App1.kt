package jp.juggler.screenshotbutton

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import jp.juggler.util.LogCategory

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
                LogCategory.onInitialize(context)
                Capture.onInitialize(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prepareAppState(applicationContext)
    }
}
