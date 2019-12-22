package jp.juggler.screenshotbutton

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.widget.ImageButton

class MyImageButton :ImageButton{
    constructor(context: Context) : super(context, null)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private val myExclusionRects = listOf(Rect())
    override fun onLayout(changedCanvas: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changedCanvas, left, top, right, bottom)
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
            // 座標系はビュー座標らしい。ボタンの左上が0,0
            myExclusionRects[0].set(0,0,right-left,bottom-top)
            systemGestureExclusionRects = myExclusionRects
        }
    }
}
