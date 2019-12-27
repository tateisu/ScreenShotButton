package jp.juggler.screenshotbutton

import android.content.Context
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes

@Suppress("EqualsOrHashCode")
abstract class BasePref<T>(val key : String) {

    init {
        if( Pref.map[key] != null )
            error("Preference key duplicate: $key")
        else
            @Suppress("LeakingThis")
            Pref.map[key] = this
    }

    override fun equals(other : Any?) : Boolean {
        return this === other
    }

    fun remove(e : SharedPreferences.Editor) {
        e.remove(key)
    }

    abstract fun put(editor : SharedPreferences.Editor, v : T)
    abstract fun invoke(pref : SharedPreferences) : T

    operator fun invoke(context : Context) : T {
        return invoke(Pref.pref(context))
    }

}

@Suppress("unused")
fun SharedPreferences.Editor.remove(item : BasePref<*>) : SharedPreferences.Editor {
    item.remove(this)
    return this
}

class BooleanPref(
    key : String,
    private val defVal : Boolean,
    val id : Int
) : BasePref<Boolean>(key) {

    override operator fun invoke(pref : SharedPreferences) : Boolean {
        return pref.getBoolean(key, defVal)
    }

    override fun put(editor : SharedPreferences.Editor, v : Boolean) {
        editor.putBoolean(key, v)
    }

    fun bindSwitch(pref:SharedPreferences,btn:CompoundButton) {
        btn.isChecked = invoke(pref)
        btn.setOnCheckedChangeListener { _, isChecked ->
            val e =pref.edit()
            if( isChecked == defVal){
                e.remove(key)
            }else{
                e.putBoolean(key,isChecked)
            }
            e.apply()
        }
    }
}

class IntPref(key : String, @Suppress("MemberVisibilityCanBePrivate") val defVal : Int) : BasePref<Int>(key) {

    override operator fun invoke(pref : SharedPreferences) : Int {
        return pref.getInt(key, defVal)
    }

    override fun put(editor : SharedPreferences.Editor, v : Int) {
        editor.putInt(key, v)
    }

    fun saveIfModified(pref: SharedPreferences, iv: Int) {
        val e =pref.edit()
        if( iv == defVal){
            e.remove(key)
        }else{
            e.putInt(key,iv)
        }
        e.apply()
    }
}

class LongPref(key : String, private val defVal : Long) : BasePref<Long>(key) {

    override operator fun invoke(pref : SharedPreferences) : Long {
        return pref.getLong(key, defVal)
    }

    override fun put(editor : SharedPreferences.Editor, v : Long) {
        editor.putLong(key, v)
    }
}

class FloatPref(key : String, private val defVal : Float) : BasePref<Float>(key) {

    override operator fun invoke(pref : SharedPreferences) : Float {
        return pref.getFloat(key, defVal)
    }

    override fun put(editor : SharedPreferences.Editor, v : Float) {
        editor.putFloat(key, v)
    }
}

data class SpinnerValue(
    val data: String,
    val caption: String
) {
    @Suppress("unused")
    constructor(dataSrc: Int, caption: String) : this(
        data = dataSrc.toString(),
        caption = caption
    )
}

class StringPref(
    key : String,
    @Suppress("MemberVisibilityCanBePrivate") val defVal : String,
    val skipImport : Boolean = false
) : BasePref<String>(key) {

    override operator fun invoke(pref : SharedPreferences) : String {
        return pref.getString(key,defVal) ?: defVal
    }

    override fun put(editor : SharedPreferences.Editor, v : String) {
        editor.putString(key, v)
    }

    fun toInt(pref : SharedPreferences) = invoke(pref).toIntOrNull() ?: defVal.toInt()

    fun bindEditText(pref:SharedPreferences,editText: EditText) {
        editText.setText(invoke(pref))
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun afterTextChanged(e: Editable?) {
                e ?: return
                pref.edit().putString(key, e.toString()).apply()
            }
        })
    }
//    fun bindSpinner(
//        pref:SharedPreferences,
//        spinner: Spinner,
//        list: List<SpinnerValue>,
//        @LayoutRes layoutId:Int=android.R.layout.simple_spinner_item
//    ) {
//        spinner.adapter = ArrayAdapter(
//            spinner.context,
//            layoutId,
//            list.map { it.caption }.toTypedArray()
//        ).apply {
//            setDropDownViewResource(R.layout.lv_spinner_dropdown)
//        }
//
//        var initialSelection = -1
//        val oldValue = this.invoke(pref)
//        for ((index, item) in list.withIndex()) {
//            if (item.data == oldValue) {
//                initialSelection = index
//                break
//            }
//        }
//        if (initialSelection == -1) {
//            initialSelection = 0
//            App1.pref.edit().putString(key, list[initialSelection].data).apply()
//        }
//        spinner.setSelection(initialSelection, false)
//
//        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
//            override fun onNothingSelected(p0: AdapterView<*>?) {
//            }
//
//            override fun onItemSelected(
//                parent: AdapterView<*>?,
//                view: View?,
//                index: Int,
//                id: Long
//            ) {
//                pref.edit().putString(key, list[index].data).apply()
//            }
//        }
//    }
}

fun SharedPreferences.Editor.put(item : BooleanPref, v : Boolean) : SharedPreferences.Editor {
    item.put(this, v)
    return this
}

fun SharedPreferences.Editor.put(item : StringPref, v : String) : SharedPreferences.Editor {
    item.put(this, v)
    return this
}

fun SharedPreferences.Editor.put(item : IntPref, v : Int) : SharedPreferences.Editor {
    item.put(this, v)
    return this
}

fun SharedPreferences.Editor.put(item : LongPref, v : Long) : SharedPreferences.Editor {
    item.put(this, v)
    return this
}

fun SharedPreferences.Editor.put(item : FloatPref, v : Float) : SharedPreferences.Editor {
    item.put(this, v)
    return this
}

object Pref {

    fun pref(context : Context) :SharedPreferences =
        context.getSharedPreferences("Pref",Context.MODE_PRIVATE)


    // キー名と設定項目のマップ。インポートやアプリ設定で使う
    val map = HashMap<String, BasePref<*>>()

    val ipCameraButtonSize = IntPref(
        "cameraButtonSize",
        40
    )
    val fpCameraButtonXStill = FloatPref(
        "cameraButtonX",
        70f
    )
    val fpCameraButtonYStill = FloatPref(
        "cameraButtonY",
        70f
    )

    val fpCameraButtonXVideo = FloatPref(
        "cameraButtonXVideo",
        120f
    )

    val fpCameraButtonYVideo = FloatPref(
        "cameraButtonYVideo",
        70f
    )

    // true=PNG,  false=JPEG
    val bpSavePng = BooleanPref(
        "compressPng",
        false,
        R.id.swSavePng
    )

    val bpShowPostView = BooleanPref(
        "ShowPostVie",
        true,
        R.id.swShowPostView
    )

    val spSaveTreeUri = StringPref(
        "SaveTreeUri",
        ""
    )

    val spCodec = StringPref(
        "Codec",
        ""
    )

    val spFrameRate = StringPref(
        "FrameRate",
        "30"
    )

    val spBitRate = StringPref(
        "BitRate",
        "6000000"
    )
}
