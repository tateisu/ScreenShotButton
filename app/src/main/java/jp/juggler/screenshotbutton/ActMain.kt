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
import android.widget.*
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

    }

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var etButtonSize: EditText
    private lateinit var tvButtonSizeError: TextView
    private lateinit var swSavePng: Switch
    private lateinit var swShowPostView: Switch
    private lateinit var tvSaveFolder: TextView
    private lateinit var btnSaveFolder: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        refActivity = WeakReference(this)
        super.onCreate(savedInstanceState)
        App1.prepareAppState(this)
        initUI()
        showSaveFolder()
    }

    override fun onStart() {
        super.onStart()
        showButton()
        dispatch()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (
            when (requestCode) {
                REQUEST_CODE_OVERLAY ->
                    canOverlay()

                REQUEST_CODE_SCREEN_CAPTURE ->
                    Capture.handleScreenCaptureIntentResult(this, resultCode, data)

                REQUEST_CODE_DOCUMENT_TREE ->
                    handleSaveTreeUriResult(resultCode, data)

                else -> false
            }
        ) dispatch()

        super.onActivityResult(requestCode, resultCode, data)
    }

    private var timeStartButtonTapped = 0L

    override fun onClick(v: View?) {
        timeStartButtonTapped = 0L
        when (v?.id) {
            R.id.btnSaveFolder ->
                openSaveTreeUriChooser()

            R.id.btnStop ->
                stopService(Intent(this, MyService::class.java))

            R.id.btnStart -> {
                timeStartButtonTapped = SystemClock.elapsedRealtime()
                dispatch()
            }
        }
    }

    /////////////////////////////////////////

    private fun initUI() {
        setContentView(R.layout.act_main)

        val dm = resources.displayMetrics
        val svRoot: View = findViewById(R.id.svRoot)
        val screenWidth = dm.widthPixels
        val pageWidth = 360f.dp2px(dm)
        val remain = max(0, screenWidth - pageWidth)
        (svRoot.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            this.marginEnd = remain
        }

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSaveFolder = findViewById(R.id.btnSaveFolder)

        arrayOf(btnStart, btnStop, btnSaveFolder).forEach {
            it.setOnClickListener(this)
        }

        val pref = App1.pref

        etButtonSize = findViewById(R.id.etButtonSize)
        tvButtonSizeError = findViewById(R.id.tvButtonSizeError)

        swSavePng = findViewById<Switch>(R.id.swSavePng)
            .also { Pref.bpSavePng.bindUI(pref, it) }

        swShowPostView = findViewById<Switch>(R.id.swShowPostView)
            .also { Pref.bpShowPostView.bindUI(pref, it) }

        etButtonSize.setText(Pref.ipCameraButtonSize(pref).toString())
        tvButtonSizeError.vg(false)

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

        tvSaveFolder = findViewById(R.id.tvSaveFolder)
    }

    private fun showSaveFolder() {

        tvSaveFolder.text = Pref.spSaveTreeUri(App1.pref)
            .notEmpty()
            ?.let { pathFromDocumentUri(this, Uri.parse(it)) }
            ?: getString(R.string.not_selected)
    }

    // サービスからも呼ばれる
    fun showButton() {
        try {
            if (isDestroyed) return
            val serviceAlive = MyService.getService() != null
            btnStart.vg(!serviceAlive)
            btnStop.vg(serviceAlive)
        } catch (ex: Throwable) {
            log.e(ex, "showButton() failed.")
        }
    }

    private fun dispatch() {
        if (!prepareOverlay()) return

        if (!prepareSaveTreeUri()) return

        if (timeStartButtonTapped > 0L) {

            if (!Capture.prepareScreenCaptureIntent(this, REQUEST_CODE_SCREEN_CAPTURE)) return

            timeStartButtonTapped = 0L
            ContextCompat.startForegroundService(this, Intent(this, MyService::class.java))
        }
    }

    // ダイアログを多重に開かないようにする
    private var lastDialog: Dialog? = null

    private fun AlertDialog.Builder.showEx() {
        if (lastDialog?.isShowing == true) {
            log.w("dialog is already showing.")
            return
        }
        lastDialog = this.create().apply { show() }
    }

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

    private fun canOverlay() =
        if (Build.VERSION.SDK_INT >= API_OVERLAY_PERMISSION_CHECK) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

    @SuppressLint("InlinedApi")
    private fun prepareOverlay(): Boolean {
        if (canOverlay()) return true

        AlertDialog.Builder(this)
            .setMessage(R.string.please_allow_overlay_permission)
            .setPositiveButton(R.string.ok) { _, _ ->
                startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ),
                    REQUEST_CODE_OVERLAY
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .showEx()
        return false
    }
}
