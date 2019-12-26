package jp.juggler.screenshotbutton

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import jp.juggler.util.*
import java.lang.ref.WeakReference
import kotlin.math.max


class ActMain : AppCompatActivity(), View.OnClickListener {

    companion object {
        private val log = LogCategory("${App1.tagPrefix}/ActMain")

        private const val REQUEST_CODE_SCREEN_CAPTURE = 1
        private const val REQUEST_CODE_OVERLAY = 2
        private const val REQUEST_CODE_DOCUMENT_TREE = 3

        private var refActivity: WeakReference<ActMain>? = null

        fun getActivity() = refActivity?.get()

        private fun isServiceAliveStill(): Boolean =
            CaptureServiceStill.getService() != null

        private fun isServiceAliveVideo(): Boolean =
            CaptureServiceVideo.getService() != null
    }

    private lateinit var btnStartStopStill: Button
    private lateinit var btnStartStopVideo: Button
    private lateinit var tvButtonSizeError: TextView
    private lateinit var tvSaveFolder: TextView

    private var timeStartButtonTappedStill = 0L
    private var timeStartButtonTappedVideo = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        refActivity = WeakReference(this)
        super.onCreate(savedInstanceState)
        App1.prepareAppState(this)
        initUI()
    }

    override fun onStart() {
        super.onStart()
        showSaveFolder()
        showButton()
        dispatch()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        val continueDispatch = when (requestCode) {
            REQUEST_CODE_OVERLAY ->
                handleOverlayResult()


            REQUEST_CODE_SCREEN_CAPTURE ->
                Capture.handleScreenCaptureIntentResult(this, resultCode, data)

            REQUEST_CODE_DOCUMENT_TREE ->
                handleSaveTreeUriResult(resultCode, data)

            else -> false
        }

        if (continueDispatch) {
            dispatch()
        } else {
            timeStartButtonTappedStill = 0L
            timeStartButtonTappedVideo = 0L
        }
    }

    override fun onClick(v: View?) {
        timeStartButtonTappedStill = 0L
        timeStartButtonTappedVideo = 0L
        when (v?.id) {
            R.id.btnSaveFolder ->
                openSaveTreeUriChooser()

            R.id.btnStartStopStill ->
                if (isServiceAliveStill()) {
                    stopService(Intent(this, CaptureServiceStill::class.java))
                } else {
                    timeStartButtonTappedStill = SystemClock.elapsedRealtime()
                    dispatch()
                }

            R.id.btnResetPositionStill -> {
                App1.pref.edit()
                    .remove(Pref.fpCameraButtonXStill)
                    .remove(Pref.fpCameraButtonYStill)
                    .apply()
                CaptureServiceStill.getService()?.reloadPosition()
            }

            R.id.btnStartStopVideo ->
                if (isServiceAliveVideo()) {
                    stopService(Intent(this, CaptureServiceVideo::class.java))
                } else {
                    timeStartButtonTappedVideo = SystemClock.elapsedRealtime()
                    dispatch()
                }

            R.id.btnResetPositionVideo -> {
                App1.pref.edit()
                    .remove(Pref.fpCameraButtonXVideo)
                    .remove(Pref.fpCameraButtonYVideo)
                    .apply()
                CaptureServiceVideo.getService()?.reloadPosition()
            }
        }
    }

    /////////////////////////////////////////

    private fun initUI() {

        setContentView(R.layout.act_main)

        // 設定UIの横幅が一定以上に広がらないようにする
        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val pageWidth = 360f.dp2px(dm)
        val remain = max(0, screenWidth - pageWidth)
        (findViewById<View>(R.id.svRoot).layoutParams as? ViewGroup.MarginLayoutParams)
            ?.marginEnd = remain

        btnStartStopStill = findViewById(R.id.btnStartStopStill)
        btnStartStopVideo = findViewById(R.id.btnStartStopVideo)

        arrayOf(
            btnStartStopStill,
            btnStartStopVideo,
            findViewById<View>(R.id.btnSaveFolder),
            findViewById<View>(R.id.btnResetPositionStill),
            findViewById<View>(R.id.btnResetPositionVideo)
        ).forEach {
            it?.setOnClickListener(this)
        }

        tvSaveFolder = findViewById(R.id.tvSaveFolder)
        tvButtonSizeError = findViewById(R.id.tvButtonSizeError)
        tvButtonSizeError.vg(false)

        val pref = App1.pref

        Pref.bpSavePng.bindUI(pref, findViewById(R.id.swSavePng))
        Pref.bpShowPostView.bindUI(pref, findViewById(R.id.swShowPostView))

        val etButtonSize: EditText = findViewById(R.id.etButtonSize)
        etButtonSize.setText(Pref.ipCameraButtonSize(pref).toString())
        etButtonSize.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun afterTextChanged(ed: Editable?) {
                val iv = ed?.toString()?.trim()?.toIntOrNull() ?: -1
                if (null == tvButtonSizeError.vg(iv < 1)) {
                    Pref.ipCameraButtonSize.saveIfModified(pref, iv)
                }
            }
        })

    }


    private fun showSaveFolder() {
        tvSaveFolder.text = Pref.spSaveTreeUri(App1.pref)
            .notEmpty()
            ?.let { pathFromDocumentUri(this, Uri.parse(it)) }
            ?: getString(R.string.not_selected)
    }

    // サービスからも呼ばれる
    fun showButton() {
        if (isDestroyed) return
        btnStartStopStill.setText(
            if (isServiceAliveStill()) {
                R.string.stop
            } else {
                R.string.start
            }
        )
        btnStartStopVideo.setText(
            if (isServiceAliveVideo()) {
                R.string.stop
            } else {
                R.string.start
            }
        )
    }

    // 権限のチェックと取得インタラクションの開始
    // 画面表示時や撮影ボタンの表示開始時に呼ばれる
    private fun dispatch() {
        if (!prepareOverlay()) return

        if (!prepareSaveTreeUri()) return

        if (timeStartButtonTappedStill > 0L) {

            if (!Capture.prepareScreenCaptureIntent(this, REQUEST_CODE_SCREEN_CAPTURE)) return

            timeStartButtonTappedStill = 0L
            ContextCompat.startForegroundService(
                this,
                Intent(this, CaptureServiceStill::class.java)
            )
        }

        if (timeStartButtonTappedVideo > 0L) {

            if (!Capture.prepareScreenCaptureIntent(this, REQUEST_CODE_SCREEN_CAPTURE)) return

            timeStartButtonTappedVideo = 0L
            ContextCompat.startForegroundService(
                this,
                Intent(this, CaptureServiceVideo::class.java)
            )
        }
    }

    ///////////////////////////////////////////////////////
    // 保存フォルダの選択と書き込み権限

    private fun openSaveTreeUriChooser() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                if (Build.VERSION.SDK_INT >= API_EXTRA_INITIAL_URI) {
                    val saveTreeUri = Pref.spSaveTreeUri(App1.pref)
                    if (saveTreeUri.isNotEmpty()) {
                        putExtra(
                            DocumentsContract.EXTRA_INITIAL_URI,
                            saveTreeUri
                        )
                    }
                }
            },
            REQUEST_CODE_DOCUMENT_TREE
        )
    }

    private fun prepareSaveTreeUri(): Boolean {
        val saveTreeUri = Pref.spSaveTreeUri(App1.pref)
        val uriPermission =
            contentResolver.persistedUriPermissions.find { it.uri?.toString() == saveTreeUri }
        if (uriPermission != null) return true

        AlertDialog.Builder(this)
            .setMessage(R.string.please_select_save_folder)
            .setPositiveButton(R.string.ok) { _, _ ->
                openSaveTreeUriChooser()
            }
            .setNegativeButton(R.string.cancel, null)
            .showEx()

        return false
    }

    private fun handleSaveTreeUriResult(resultCode: Int, data: Intent?): Boolean {
        try {
            if (resultCode == RESULT_OK) {
                val uri = data?.data ?: error("missing document tree URI")
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                App1.pref.edit().put(Pref.spSaveTreeUri, uri.toString()).apply()
                showSaveFolder()
                return true
            }
        } catch (ex: Throwable) {
            log.eToast(this, ex, "takePersistableUriPermission failed.")
        }
        return false
    }

    /////////////////////////////////////////////////////////////////
    // オーバーレイ表示の権限

    @SuppressLint("InlinedApi")
    private fun prepareOverlay(): Boolean {
        if (canDrawOverlaysCompat(this)) return true

        return AlertDialog.Builder(this)
            .setMessage(R.string.please_allow_overlay_permission)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ),
                    REQUEST_CODE_OVERLAY
                )
            }
            .showEx()
    }

    private fun handleOverlayResult(): Boolean {
        // 設定画面から戻るボタンなどで復帰するため、 resultCode が RESULT_OK になることはない
        return canDrawOverlaysCompat(this)
    }

    ///////////////////////////////////////////////////
    // ダイアログの多重表示を防止する

    private var lastDialog: WeakReference<Dialog>? = null

    private fun AlertDialog.Builder.showEx(): Boolean {
        if (lastDialog?.get()?.isShowing == true) {
            log.w("dialog is already showing.")
        } else {
            lastDialog = WeakReference(this.create().apply { show() })
        }
        return false
    }
}
