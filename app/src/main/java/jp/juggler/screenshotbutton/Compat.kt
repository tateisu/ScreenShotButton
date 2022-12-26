package jp.juggler.screenshotbutton

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

inline fun <reified T : Parcelable> Bundle.getParcelableCompat(key: String) =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(key)
    }

inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String) =
    extras?.getParcelableCompat<T>(key)


object EmptyScope : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = EmptyCoroutineContext + Dispatchers.Main.immediate
}
