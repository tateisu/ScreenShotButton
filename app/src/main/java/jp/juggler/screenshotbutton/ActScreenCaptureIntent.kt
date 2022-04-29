package jp.juggler.screenshotbutton

import android.content.Intent
import android.os.Bundle
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


class ActScreenCaptureIntent : AppCompatActivity() {

    companion object {
        var cont: Continuation<Capture.MediaProjectionState>? = null
            set(value) {
                try {
                    field?.resumeWithException(RuntimeException("overwrite by new creation"))
                } catch (_: Throwable) {
                }
                field = value
            }
    }

    private val arScreenCapture = ActivityResultHandler { r ->
        Capture.handleScreenCaptureIntentResult(this, r.resultCode, r.data)
        try {
            cont?.resume(Capture.mediaProjectionState)
        } catch (ignored: Throwable) {
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        arScreenCapture.register(this)
        Capture.startScreenCaptureIntent(arScreenCapture)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Capture.startScreenCaptureIntent(arScreenCapture)
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
    }
}
