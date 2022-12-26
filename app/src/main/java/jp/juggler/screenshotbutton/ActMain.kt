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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import jp.juggler.screenshotbutton.databinding.ActMainBinding
import jp.juggler.util.*
import java.lang.ref.WeakReference
import kotlin.math.max

class ActMain : AppCompatActivity(), View.OnClickListener {

    companion object {
        private val log = LogCategory("${App1.tagPrefix}/ActMain")

        private var refActivity: WeakReference<ActMain>? = null

        fun getActivity() = refActivity?.get()
    }

    private val views by lazy {
        ActMainBinding.inflate(layoutInflater)
    }

    private var lastDialog: WeakReference<Dialog>? = null

    private var timeStartButtonTappedStill = 0L
    private var timeStartButtonTappedVideo = 0L

    private var videoCaptureEnabled = false

    private val arOverlay = ActivityResultHandler {
        mayContinueDispatch(handleOverlayResult())
    }

    private val arScreenCapture = ActivityResultHandler { r ->
        mayContinueDispatch(Capture.handleScreenCaptureIntentResult(this, r.resultCode, r.data))
    }

    private val arDocumentTree = ActivityResultHandler { r ->
        mayContinueDispatch(handleSaveTreeUriResult(r.resultCode, r.data))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        arOverlay.register(this)
        arScreenCapture.register(this)
        arDocumentTree.register(this)
        App1.prepareAppState(this)
        log.d("onCreate savedInstanceState=$savedInstanceState")
        refActivity = WeakReference(this)
        super.onCreate(savedInstanceState)
        initUI()
    }

    override fun onStart() {
        log.d("onStart")
        super.onStart()
        showSaveFolder()
        showButton()
        showCurrentCodec()
        dispatch()
    }

    override fun onClick(v: View?) {
        timeStartButtonTappedStill = 0L
        timeStartButtonTappedVideo = 0L
        when (v?.id) {
            R.id.btnSaveFolder ->
                openSaveTreeUriChooser()

            R.id.btnStartStopStill ->
                when (val service = CaptureServiceStill.getService()) {
                    null -> {
                        timeStartButtonTappedStill = SystemClock.elapsedRealtime()
                        dispatch()
                    }
                    else -> {
                        service.stopWithReason("StopButton")
                    }
                }

            R.id.btnResetPositionStill -> {
                App1.pref.edit()
                    .remove(Pref.fpCameraButtonXStill)
                    .remove(Pref.fpCameraButtonYStill)
                    .apply()
                CaptureServiceStill.getService()?.reloadPosition()
            }

            R.id.btnStartStopVideo ->
                when (val service = CaptureServiceVideo.getService()) {
                    null -> {
                        try {
                            Capture.loadVideoSetting(this, App1.pref)
                        } catch (ex: Throwable) {
                            log.eToast(this, ex, "Video setting error.")
                            return
                        }
                        timeStartButtonTappedVideo = SystemClock.elapsedRealtime()
                        dispatch()
                    }
                    else -> {
                        service.stopWithReason("StopButton")
                    }
                }

            R.id.btnResetPositionVideo -> {
                App1.pref.edit()
                    .remove(Pref.fpCameraButtonXVideo)
                    .remove(Pref.fpCameraButtonYVideo)
                    .apply()
                CaptureServiceVideo.getService()?.reloadPosition()
            }

            R.id.btnCodecEdit -> {
                val ad = ActionsDialog()
                for (codec in MediaCodecInfoAndType.getList(this)) {
                    ad.addAction(codec.toString()) {
                        App1.pref.edit().put(Pref.spCodec, codec.id).apply()
                        showCurrentCodec()
                    }
                }
                ad.show(this, getString(R.string.codec))
            }

            R.id.btnStopRecording ->
                CaptureServiceVideo.getService()
                    .runOnService(this) {
                        captureStop()
                    }

            R.id.btnExitReasons ->
                startActivity(Intent(this, ActExitReasons::class.java))
        }
    }

    /////////////////////////////////////////

    private fun initUI() {
        setContentView(views.root)

        // 設定UIの横幅が一定以上に広がらないようにする
        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val pageWidth = 360f.dp2px(dm)
        val remain = max(0, screenWidth - pageWidth)

        views.svRoot.layoutParams
            .cast<ViewGroup.MarginLayoutParams>()
            ?.marginEnd = remain

        arrayOf(
            views.btnStartStopStill,
            views.btnStartStopVideo,
            views.btnStopRecording,
            views.btnSaveFolder,
            views.btnResetPositionStill,
            views.btnResetPositionVideo,
            views.btnCodecEdit,
            views.btnExitReasons,
        ).forEach {
            it.setOnClickListener(this)
        }

        views.tvButtonSizeError.vg(false)

        val pref = App1.pref

        Pref.bpSavePng.bindSwitch(pref, views.swSavePng)
        Pref.bpShowPostView.bindSwitch(pref, views.swShowPostView)

        Pref.bpLogToFile.bindSwitch(pref, views.swLogToFile) {
            LogCategory.setLogToFile(this@ActMain, it)
        }

        val etButtonSize = views.etButtonSize
        etButtonSize.setText(Pref.ipCameraButtonSize(pref).toString())
        etButtonSize.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun afterTextChanged(ed: Editable?) {
                val iv = ed?.toString()?.trim()?.toIntOrNull() ?: -1
                if (null == views.tvButtonSizeError.vg(iv < 1)) {
                    Pref.ipCameraButtonSize.saveIfModified(pref, iv)
                }
            }
        })

        Pref.spFrameRate.bindEditText(pref, views.etFrameRate)
        Pref.spBitRate.bindEditText(pref, views.etBitRate)

        // 動作環境により動画キャプチャができない場合、エラーを表示する
        val message = when {

            Build.VERSION.SDK_INT < API_MEDIA_MUXER_FILE_DESCRIPTER ->
                getString(R.string.media_muxer_too_old)

            MediaCodecInfoAndType.getList(this).isEmpty() ->
                getString(R.string.video_codec_missing)

            else -> null
        }

        videoCaptureEnabled = message == null

        if (!videoCaptureEnabled) {
            views.tvStatusVideo.setTextColor(ContextCompat.getColor(this, R.color.colorTextError))
            views.tvStatusVideo.text = message

            views.btnStopRecording.visibility = View.INVISIBLE
        }

        views.tvLogToFileDesc.text = getString(
            R.string.output_log_to_file_desc,
            LogCategory.getLogDirectory(this).canonicalPath
        )

        views.swStartBootStill.isChecked = Pref.bpStartBootStill(pref)
        views.swStartBootVideo.isChecked = Pref.bpStartBootVideo(pref)
        views.swStartBootStill.setOnCheckedChangeListener { _, isChecked ->
            App1.pref.edit()
                .put(Pref.bpStartBootStill, isChecked)
                .apply()
        }
        views.swStartBootVideo.setOnCheckedChangeListener { _, isChecked ->
            App1.pref.edit()
                .put(Pref.bpStartBootVideo, isChecked)
                .apply()
        }
    }

    private fun showSaveFolder() {
        views.tvSaveFolder.text = Pref.spSaveTreeUri(App1.pref)
            .notEmpty()
            ?.let { pathFromDocumentUri(this, Uri.parse(it)) }
            ?: getString(R.string.not_selected)
    }

    private fun showCurrentCodec() {
        val codecList = MediaCodecInfoAndType.getList(this)
        if (codecList.isEmpty()) {
            log.eToast(this, false, "Oops! this device has no MediaCodec!!")
            return
        }
        val id = Pref.spCodec(App1.pref)
        var codec = codecList.find { it.id == id }
        if (codec == null) {
            codec = codecList[0]
            App1.pref.edit().put(Pref.spCodec, codec.id).apply()
        }
        views.tvCodec.text = codec.toString()
    }

    // サービスからも呼ばれる
    fun showButton() {
        log.d("showButton")
        if (isDestroyed) return
        views.btnStartStopStill.setText(
            if (CaptureServiceStill.isAlive()) {
                R.string.stop
            } else {
                R.string.start
            }
        )
        views.btnStartStopVideo.setText(
            if (CaptureServiceVideo.isAlive()) {
                R.string.stop
            } else {
                R.string.start
            }
        )

        val isCapturing = Capture.isCapturing

        views.btnStartStopStill.isEnabledWithColor = !isCapturing

        views.btnStartStopVideo.isEnabledWithColor = !isCapturing && videoCaptureEnabled

        views.btnStopRecording.isEnabledWithColor = CaptureServiceBase.isVideoCapturing()

        views.tvStatusStill.text = getString(
            R.string.status_is,
            when {
                CaptureServiceStill.isAlive() ->
                    getString(R.string.status_running)
                else ->
                    getString(
                        R.string.stopped_by,
                        CaptureServiceBase.getStopReason(CaptureServiceStill::class.java)
                            ?: ""
                    )
            }
        )

        if (videoCaptureEnabled) {
            views.tvStatusVideo.text = getString(
                R.string.status_is,
                when {
                    CaptureServiceVideo.isAlive() ->
                        getString(R.string.status_running)
                    else ->
                        getString(
                            R.string.stopped_by,
                            CaptureServiceBase.getStopReason(CaptureServiceVideo::class.java)
                                ?: ""
                        )
                }
            )
        }
    }

    // ActivityResultの処理結果によってdispatchを再実行する
    private fun mayContinueDispatch(r: Boolean) {
        if (r) {
            dispatch()
        } else {
            timeStartButtonTappedStill = 0L
            timeStartButtonTappedVideo = 0L
        }
    }

    // 権限のチェックと取得インタラクションの開始
    // 画面表示時や撮影ボタンの表示開始時に呼ばれる
    private fun dispatch() {
        log.d("dispatch")

        if (!prepareOverlay()) return

        if (!prepareSaveTreeUri()) return

        if (timeStartButtonTappedStill > 0L) {

            if (!Capture.prepareScreenCaptureIntent(arScreenCapture)) return

            timeStartButtonTappedStill = 0L
            ContextCompat.startForegroundService(
                this,
                Intent(this, CaptureServiceStill::class.java).apply {
                    Capture.screenCaptureIntent?.let {
                        putExtra(CaptureServiceBase.EXTRA_SCREEN_CAPTURE_INTENT, it)
                    }
                }
            )
        }

        if (timeStartButtonTappedVideo > 0L) {

            if (!Capture.prepareScreenCaptureIntent(arScreenCapture)) return

            timeStartButtonTappedVideo = 0L
            ContextCompat.startForegroundService(
                this,
                Intent(this, CaptureServiceVideo::class.java).apply {
                    Capture.screenCaptureIntent?.let {
                        putExtra(CaptureServiceBase.EXTRA_SCREEN_CAPTURE_INTENT, it)
                    }
                }
            )
        }
    }

    ///////////////////////////////////////////////////////
    // 保存フォルダの選択と書き込み権限

    private fun openSaveTreeUriChooser() {
        arDocumentTree.launch(
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
            }
        )
    }

    private fun prepareSaveTreeUri(): Boolean {
        val treeUri = Pref.spSaveTreeUri(App1.pref).toUriOrNull()

        if (treeUri != null) {
            if (!contentResolver.persistedUriPermissions.any { it.uri == treeUri }) {
                log.eToast(this, true, "missing access permission $treeUri")
            } else {
                try {
                    // pathの検証。例外を出す
                    pathFromDocumentUriOrThrow(this, treeUri)
                    return true
                } catch (ex: Throwable) {
                    log.eToast(this, ex, "can't use this folder.")
                }
            }
        }

        AlertDialog.Builder(this)
            .setMessage(R.string.please_select_save_folder)
            .setPositiveButton(R.string.ok)
            { _, _ ->
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
                arOverlay.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
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


    private fun AlertDialog.Builder.showEx(): Boolean {
        if (lastDialog?.get()?.isShowing == true) {
            log.w("dialog is already showing.")
        } else {
            lastDialog = WeakReference(this.create().apply { show() })
        }
        return false
    }
}
