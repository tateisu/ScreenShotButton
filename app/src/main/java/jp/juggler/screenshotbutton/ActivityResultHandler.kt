package jp.juggler.screenshotbutton

import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/**
 * onActivityResultがDeprecatedになったので移行が必要
 * kotlin化した後に以下の手順で書き換えていく
 * (1) Activity/Fragment にプロパティを追加する。
 *    - val arなんたら = ActivityResultHandler{ result -> リザルト処理… }
 * (2) Activity/Fragment の onCreate のどこかで arなんたら.register(activity) を記載
 * (3) startActivityForResult を arなんたら.launch(intent) に変更
 *
 * Note: onActivityResult を使う場合と ActivityResultLauncher を使う場合とでは呼び出しタイミングが異なる。
 * ActivityResultLauncherを使うとonStartより後に呼び出される。
 */
class ActivityResultHandler(val handleResult: (ActivityResult) -> Unit) {
    private var launcher: ActivityResultLauncher<Intent>? = null

    fun register(activity: AppCompatActivity) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { handleResult(it) }
    }

    fun register(fragment: Fragment) {
        launcher = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { handleResult(it) }
    }

    fun launch(intent: Intent) {
        (launcher ?: error("ActivityResultHandler: not registered to activity!"))
            .launch(intent)
    }
}
