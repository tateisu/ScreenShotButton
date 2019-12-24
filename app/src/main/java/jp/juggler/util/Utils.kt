package jp.juggler.util

import android.app.AppOpsManager
import android.content.Context
import android.graphics.*
import android.os.Binder.getCallingUid
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import jp.juggler.screenshotbutton.API_APPLICATION_OVERLAY


@Suppress("SameParameterValue")
fun clipInt(min: Int, max: Int, v: Int) = when {
    v < min -> min
    v > max -> max
    else -> v
}

fun Float.dp2px(dm: DisplayMetrics) =
    (this * dm.density + 0.5f).toInt()

fun Int.px2dp(dm: DisplayMetrics) =
    this.toFloat() / dm.density

fun View.vg(visible: Boolean): View? {
    return if (visible) {
        this.visibility = View.VISIBLE
        this
    } else {
        this.visibility = View.GONE
        null
    }
}

fun String?.notEmpty() =
    if (this?.isNotEmpty() == true) this else null

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
    return Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888)
        ?.also { dst ->
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
        ?: error("createBitmap returns null")
}


// Android 8.0 は Settings.canDrawOverlays(context) にバグがある
// https://stackoverflow.com/questions/46173460/why-in-android-o-method-settings-candrawoverlays-returns-false-when-user-has
fun canDrawOverlaysCompat(context: Context): Boolean {
    // Android 5 always allow overlay.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

    // (Android 7+) if Settings.canDrawOverlays(context) == true, it's reliable.
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M && Settings.canDrawOverlays(context)) return true

    // Android 6 と Android 8, 8.1 はバグがあるので
    // 許可されていても Settings.canDrawOverlays(context) がfalseを返す場合がある

    if( Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ){
        // AppOpsManager.checkOp() は API 29 でdeprecated

        (context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager)
            ?.let { manager ->
                try {
                    @Suppress("DEPRECATION")
                    val result = manager.checkOp(
                        AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                        getCallingUid(),
                        context.packageName
                    )
                    return result == AppOpsManager.MODE_ALLOWED
                } catch (_: Throwable) {
                }
            }
    }

    //id this fails, we definitely can't do it
    (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        ?.let { manager ->
            try {
                val viewToAdd = View(context)
                val params = WindowManager.LayoutParams(
                    0,
                    0,
                    if (Build.VERSION.SDK_INT >= API_APPLICATION_OVERLAY) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    },
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

