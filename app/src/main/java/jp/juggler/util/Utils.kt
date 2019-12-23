package jp.juggler.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.DisplayMetrics
import android.view.View

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

fun createResizedBitmap(src: Bitmap,dstWidth:Int,dstHeight:Int): Bitmap {
    val srcW = src.width
    val srcH = src.height
    return Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888)
        ?.also{ dst->
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
