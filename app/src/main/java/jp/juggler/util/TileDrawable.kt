package jp.juggler.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable

class TileDrawable( private val src: Drawable ) : Drawable() {

    override fun draw(canvas: Canvas) {
        val bounds = this.bounds
        val dirtyBounds = this.dirtyBounds
        val startX = bounds.left
        val startY = bounds.top
        val endX = bounds.right
        val endY = bounds.bottom
        val stepX = src.intrinsicWidth
        val stepY = src.intrinsicHeight
        for( y in startY until endY step stepY ){
            val bottom = y+stepY

            for( x in startX until endX step stepX ){
                val right = x+stepX

                if( dirtyBounds.intersects(x, y,right,bottom)){
                    src.setBounds(x, y,right,bottom)
                    src.draw(canvas)
                }
            }
        }
    }

    // deprecated in API29. This method is no longer used in graphics optimizations
    @Suppress("DEPRECATION")
    override fun getOpacity() = src.opacity

    override fun setAlpha(alpha: Int) {
        src.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        src.colorFilter = colorFilter
    }
}