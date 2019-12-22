package jp.juggler.screenshotbutton

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import jp.juggler.util.LogCategory
import jp.juggler.util.dp2px
import jp.juggler.util.vg
import java.lang.ref.WeakReference
import kotlin.math.max

class ActMain : AppCompatActivity(), View.OnClickListener {

    companion object {
        private val log = LogCategory("${App1.tagPrefix}/ActMain")

        private const val PERMISSION_REQUEST_EXTERNAL_STORAGE = 1

        private const val REQUEST_CODE_SCREEN_CAPTURE = 1
        private const val REQUEST_CODE_OVERLAY = 2

        private val requiredPermissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        private var refActivity: WeakReference<ActMain>? = null

        fun getActivity() = refActivity?.get()

    }

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var etButtonSize : EditText
    private lateinit  var tvButtonSizeError: TextView
    private lateinit var swSavePng :Switch
    private lateinit var swShowPostView :Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        refActivity = WeakReference(this)
        super.onCreate(savedInstanceState)
        App1.prepareAppState(this)
        initUI()
    }

    override fun onStart() {
        super.onStart()
        showButton()
        dispatch()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_CODE_OVERLAY -> if(canOverlay() )
                dispatch()

            REQUEST_CODE_SCREEN_CAPTURE ->
                if(Capture.handleScreenCaptureIntentResult(this, resultCode, data))
                    dispatch()
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_EXTERNAL_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                dispatch()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private var timeStartButtonTapped = 0L

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btnStop -> {
                timeStartButtonTapped = 0L
                stopService(Intent(this, MyService::class.java))
            }


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
        val svRoot :View  = findViewById(R.id.svRoot)
        val screenWidth = dm.widthPixels
        val pageWidth = 360f.dp2px(dm)
        val remain = max(0,screenWidth - pageWidth)
        (svRoot.layoutParams as? ViewGroup.MarginLayoutParams)?.apply{
            this.marginEnd = remain
        }

        btnStart = findViewById(R.id.btnStart)
        btnStart.setOnClickListener(this)

        btnStop = findViewById(R.id.btnStop)
        btnStop.setOnClickListener(this)

        val pref = App1.pref

        etButtonSize= findViewById(R.id.etButtonSize)
        tvButtonSizeError= findViewById(R.id.tvButtonSizeError)

        swSavePng = findViewById<Switch>(R.id.swSavePng)
            .also{ Pref.bpSavePng.bindUI(pref,it) }

        swShowPostView= findViewById<Switch>(R.id.swShowPostView)
            .also{ Pref.bpShowPostView.bindUI(pref,it) }

        etButtonSize.setText( Pref.ipCameraButtonSize(pref).toString() )
        tvButtonSizeError.vg(false)

        etButtonSize.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun afterTextChanged(ed: Editable?) {
                val iv = ed?.toString()?.trim()?.toIntOrNull() ?: -1
                if( null == tvButtonSizeError.vg( iv < 1 ) ){
                    Pref.ipCameraButtonSize.saveIfModified(pref,iv)
                }
            }
        })


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
        if (!prepareWritePermission()) return
        if (!prepareOverlay()) return

        if (timeStartButtonTapped > 0L ) {

            if (!Capture.prepareScreenCaptureIntent(this, REQUEST_CODE_SCREEN_CAPTURE)) return

            timeStartButtonTapped = 0L
            ContextCompat.startForegroundService(this, Intent(this, MyService::class.java))
        }
    }

    private fun prepareWritePermission(): Boolean {
        if (PackageManager.PERMISSION_GRANTED ==
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) return true

        ActivityCompat.requestPermissions(
            this,
            requiredPermissions,
            PERMISSION_REQUEST_EXTERNAL_STORAGE
        )
        return false
    }

    private fun canOverlay() =
        if (Build.VERSION.SDK_INT >= 23) {
            Settings.canDrawOverlays(this)
        }else{
            true
        }

    @SuppressLint("InlinedApi")
    private fun prepareOverlay() =
        if(canOverlay()) {
            true
        }else{
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                REQUEST_CODE_OVERLAY
            )
            false
        }
}
