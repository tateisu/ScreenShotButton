package jp.juggler.util

import android.graphics.Bitmap
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

fun <T:Any?> Bitmap.use(block:(Bitmap)->T):T{
    try{
        return block(this)
    }finally{
        this.recycle()
    }
}
