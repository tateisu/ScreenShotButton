package jp.juggler.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

class TileDrawable(private val stepSize: Int, private val colorA: Int, private val colorB: Int) :
    Drawable() {

    private val paint = Paint()
    private val rect = Rect()

    override fun draw(canvas: Canvas) {
        val bounds = this.bounds
        val dirtyBounds = this.dirtyBounds
        val startX = bounds.left
        val startY = bounds.top
        val endX = bounds.right
        val endY = bounds.bottom
        var oddY = false
        for (y in startY until endY step stepSize) {
            rect.top = y
            rect.bottom = y + stepSize

            var oddX = oddY
            for (x in startX until endX step stepSize) {
                rect.left = x
                rect.right = x + stepSize

                if (Rect.intersects(dirtyBounds, rect)) {
                    paint.color = if (oddX) colorB else colorA
                    canvas.drawRect(rect, paint)
                }

                oddX = !oddX
            }

            oddY = !oddY
        }
    }

    // deprecated in API29. This method is no longer used in graphics optimizations
    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }
}
