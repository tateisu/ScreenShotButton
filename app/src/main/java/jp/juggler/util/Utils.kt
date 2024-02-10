package jp.juggler.util

import android.app.AppOpsManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.net.Uri
import android.os.Binder.getCallingUid
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.getSystemService
import jp.juggler.screenshotbutton.App1
import java.util.*

private val log = LogCategory("${App1.tagPrefix}/Utils")

@Suppress("SameParameterValue")
fun Int.clip(min: Int, max: Int) = when {
    this < min -> min
    this > max -> max
    else -> this
}

fun Float.dp2px(dm: DisplayMetrics) =
    (this * dm.density + 0.5f).toInt()

fun Int.px2dp(dm: DisplayMetrics) =
    this.toFloat() / dm.density

fun <T : View> T?.vg(visible: Boolean): T? {
    this?.visibility = if (visible) View.VISIBLE else View.GONE
    return if (visible) this else null
}

fun String?.notEmpty() =
    if (this?.isNotEmpty() == true) this else null

fun Int?.notZero() =
    if (this != null && this != 0) this else null

suspend fun <T : Any?> Bitmap.use(block: suspend (Bitmap) -> T): T {
    try {
        return block(this)
    } finally {
        this.recycle()
    }
}

fun createResizedBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int): Bitmap {
    val srcW = src.width
    val srcH = src.height
    return Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888).also { dst ->
        val canvas = Canvas(dst)
        val paint = Paint()
        paint.isFilterBitmap = true
        val matrix = Matrix()
        matrix.postScale(
            (dstWidth.toFloat() + 1f) / srcW.toFloat(),
            (dstHeight.toFloat() + 1f) / srcH.toFloat()
        )
        canvas.drawBitmap(src, matrix, paint)
    }
}

// 型推論できる文脈だと型名を書かずにすむ
inline fun <reified T : Any> Any?.cast(): T? = this as? T

// 型推論できる文脈だと型名を書かずにすむ
inline fun <reified T> Context.systemService(): T? =
    /* ContextCompat. */ getSystemService(this, T::class.java)

// Android 8.0 は Settings.canDrawOverlays(context) にバグがある
// https://stackoverflow.com/questions/46173460/why-in-android-o-method-settings-candrawoverlays-returns-false-when-user-has
fun Context.canDrawOverlaysCompat(): Boolean {
    if (Settings.canDrawOverlays(this)) return true

    // Android 6 と Android 8, 8.1 はバグがあるので
    // 許可されていても Settings.canDrawOverlays(context) がfalseを返す場合がある

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        // AppOpsManager.checkOp() は API 29 でdeprecated

        systemService<AppOpsManager>()?.let { manager ->
            try {
                @Suppress("DEPRECATION")
                val result = manager.checkOp(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    getCallingUid(),
                    packageName
                )
                return result == AppOpsManager.MODE_ALLOWED
            } catch (_: Throwable) {
            }
        }
    }

    //id this fails, we definitely can't do it
    systemService<WindowManager>()?.let { manager ->
        try {
            val viewToAdd = View(this)
            val params = WindowManager.LayoutParams(
                0,
                0,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT
            )
            viewToAdd.layoutParams = params
            manager.addView(viewToAdd, params)
            manager.removeView(viewToAdd)
            return true
        } catch (_: Throwable) {
        }
    }

    return false

}

fun runOnMainThread(block: () -> Unit) {
    val mainLooper = Looper.getMainLooper()
    if (mainLooper.thread == Thread.currentThread()) {
        block()
    } else {
        Handler(mainLooper).post { block() }
    }
}

fun Context.getScreenSize() = Point().also {

    //    systemService<WindowManager>(context)!!
    //        .defaultDisplay!!
    //        .getRealSize(it)

    // https://github.com/google/grafika/blob/master/app/src/main/java/com/android/grafika/ScreenRecordActivity.java
    // grafika のサンプルでは DisplayManager.getDisplay を使っていた
    @Suppress("DEPRECATION")
    systemService<DisplayManager>()!!
        .getDisplay(Display.DEFAULT_DISPLAY)!!
        .getRealSize(it)
}

fun MediaCodec.suspend(suspend: Boolean) =
    setParameters(Bundle().apply {
        putInt(
            MediaCodec.PARAMETER_KEY_SUSPEND,
            if (suspend) 1 else 0
        )
    })

fun String?.toUriOrNull() =
    if (this?.isEmpty() != false) {
        null
    } else try {
        Uri.parse(this)
    } catch (ex: Throwable) {
        log.w("not Uri. $this ${ex.javaClass.simpleName} ${ex.message}")
        null
    }

var View.isEnabledWithColor: Boolean
    get() = isEnabled
    set(value) {
        isEnabled = value
        alpha = if (value) 1f else 0.5f
    }

fun getCurrentTimeString(): String {
    val cal = Calendar.getInstance()
    return "%d%02d%02d-%02d%02d%02d".format(
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE),
        cal.get(Calendar.SECOND),
    )
}

fun Throwable.withCaption(caption: String = "error.") =
    "$caption ${javaClass.simpleName} : $message"

fun AppCompatActivity.addBackPressed(block: () -> Unit) {
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = block()
    })
}
