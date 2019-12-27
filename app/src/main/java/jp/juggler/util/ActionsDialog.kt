package jp.juggler.util

import android.content.Context
import androidx.appcompat.app.AlertDialog
import jp.juggler.screenshotbutton.R

import java.util.ArrayList

@Suppress("unused")
class ActionsDialog {

    private val list = ArrayList<Action>()

    private class Action internal constructor(
        internal val caption: CharSequence,
        internal val r: () -> Unit
    )

    fun addAction(caption: CharSequence, r: () -> Unit): ActionsDialog {
        list.add(Action(caption, r))
        return this
    }

    fun show(context: Context, title: CharSequence? = null): ActionsDialog {
        val b = AlertDialog.Builder(context)
            .setNegativeButton(R.string.cancel, null)
            .setItems(Array(list.size) { i -> list[i].caption }) { _, which ->
                if (which in list.indices) list[which].r()
            }

        if (title != null && title.isNotEmpty()) b.setTitle(title)

        b.show()

        return this
    }
}
