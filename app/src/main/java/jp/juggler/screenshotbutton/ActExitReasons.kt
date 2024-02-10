package jp.juggler.screenshotbutton

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import jp.juggler.screenshotbutton.databinding.LvTextBinding
import jp.juggler.util.LogCategory
import jp.juggler.util.cast
import jp.juggler.util.withCaption

class ActExitReasons : AppCompatActivity() {
    companion object {
        val log = LogCategory("ActExitReasons")
    }

    @Suppress("InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (layoutInflater.inflate(R.layout.listview, null, false) as ListView)
            .also {
                it.adapter = MyAdapter()
                setContentView(it)
            }

    }

    private inner class MyAdapter : BaseAdapter() {
        val list = try {
            getExitReasons()
        } catch (ex: Throwable) {
            listOf(ex.withCaption("getExitReasons failed."))
        }

        override fun getCount(): Int = list.size
        override fun getItemId(position: Int): Long = 0
        override fun getItem(position: Int) = list.elementAtOrNull(position)
        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup?,
        ): View =
            (convertView?.tag?.cast() ?: MyViewHolder(parent).also { it.views.root.tag = it })
                .apply { bind(list.elementAtOrNull(position)) }
                .views.root
    }

    private inner class MyViewHolder(parent: ViewGroup?) {
        val views = LvTextBinding.inflate(layoutInflater, parent, false)

        init {
            views.root.tag = this
        }

        fun bind(text: String?) {
            views.root.text = text ?: ""
        }
    }
}
