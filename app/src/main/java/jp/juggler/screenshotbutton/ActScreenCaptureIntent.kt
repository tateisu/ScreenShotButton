package jp.juggler.screenshotbutton

import android.content.Intent
import android.os.Bundle
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import jp.juggler.util.LogCategory
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


class ActScreenCaptureIntent :AppCompatActivity(){

    companion object{
        private val log = LogCategory("ActScreenCaptureIntent")
        private const val REQUEST_CODE_SCREEN_CAPTURE = 1

        var cont : Continuation<String>? = null
            set(value){
                try {
                    field?.resumeWithException(RuntimeException("overwrite by new creation"))
                }catch(_:Throwable){
                }
                field = value
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        Capture.startScreenCaptureIntent(this, REQUEST_CODE_SCREEN_CAPTURE)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Capture.startScreenCaptureIntent(this, REQUEST_CODE_SCREEN_CAPTURE)
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        log.d("onActivityResult")
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_CODE_SCREEN_CAPTURE ->{
                Capture.handleScreenCaptureIntentResult(this, resultCode, data)
                if( Capture.mediaProjectionState != Capture.MediaProjectionState.RequestingScreenCaptureIntent){
                    cont?.resume("OVER")
                    finish()
                }
            }
        }
    }

}