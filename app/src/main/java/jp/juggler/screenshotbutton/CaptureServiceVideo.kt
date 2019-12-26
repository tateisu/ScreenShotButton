package jp.juggler.screenshotbutton

import android.annotation.SuppressLint
import java.lang.ref.WeakReference

@SuppressLint("InflateParams")
class CaptureServiceVideo : CaptureServiceBase(
    Pref.fpCameraButtonXStill,
    Pref.fpCameraButtonYStill,
    R.drawable.ic_videocam
) {

    companion object {
        private var refService: WeakReference<CaptureServiceVideo>? = null
        fun getService() = refService?.get()
    }

    override fun onCreate() {
        super.onCreate()
        refService = WeakReference(this)
    }

    override fun onDestroy() {
        refService = null
        super.onDestroy()
    }


}