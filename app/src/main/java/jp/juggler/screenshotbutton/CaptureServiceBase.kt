package jp.juggler.screenshotbutton

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.*
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import jp.juggler.util.*
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.*
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("InflateParams")
abstract class CaptureServiceBase(
    val fpCameraButtonX: FloatPref,
    val fpCameraButtonY: FloatPref,
    @DrawableRes val startButtonId: Int,
    val notificationId: Int
) : Service(), View.OnClickListener, View.OnTouchListener {

    companion object {
        private val log = LogCategory("${App1.tagPrefix}/CaptureServiceStill")

        private var captureJob: WeakReference<Job>? = null

        private fun isCapturing() = captureJob?.get()?.isActive == true

        private val serviceList = LinkedList<WeakReference<CaptureServiceBase>>()

        private fun addActiveService(service: CaptureServiceBase) {
            serviceList.add(WeakReference(service))
        }

        private fun removeActiveService(service: CaptureServiceBase) {
            val it = serviceList.iterator()
            while (it.hasNext()) {
                val ref = it.next()
                val s = ref.get()
                if (s == null || s == service) it.remove()
            }
        }

        fun getServices() = serviceList.mapNotNull { it.get() }

        fun showButtonAll() {
            runOnMainThread {
                log.d("showButtonAll")
                getServices().forEach { it.showButton() }
                ActMain.getActivity()?.showButton()
            }
        }
    }

    protected val context: Context
        get() = this

    protected lateinit var notificationManager: NotificationManager
    private lateinit var windowManager: WindowManager

    private lateinit var btnCamera: MyImageButton
    private lateinit var layoutParam: WindowManager.LayoutParams
    private lateinit var viewRoot: View

    private var startLpX = 0
    private var startLpY = 0
    private var startMotionX = 0f
    private var startMotionY = 0f
    private var isDragging = false
    private var draggingThreshold = 0f
    private var maxX = 0
    private var maxY = 0
    private var buttonSize = 0

    @Volatile
    protected var isDestroyed = false

//    private lateinit var serviceJob: Job
//    override val coroutineContext: CoroutineContext
//        get() = Dispatchers.Main + serviceJob
    // serviceJob = Job()

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStart(intent: Intent, startId: Int) {
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        log.d("onTaskRemoved")
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onLowMemory() {
        log.d("onLowMemory")
        super.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        log.d("onTrimMemory $level")
        super.onTrimMemory(level)
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    override fun onCreate() {
        log.d("onCreate")
        addActiveService(this)
        super.onCreate()
        App1.prepareAppState(context)

        notificationManager = getNotificationManager()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        viewRoot = LayoutInflater.from(context).inflate(R.layout.service_overlay, null)

        startForeground(notificationId, createRunningNotification(false))

        btnCamera = viewRoot.findViewById(R.id.btnCamera)
        btnCamera.setOnClickListener(this)
        btnCamera.setOnTouchListener(this)

        layoutParam = WindowManager.LayoutParams(
            0, // 後で上書きする。 loadButtonPosition()
            0,
            if (Build.VERSION.SDK_INT >= API_APPLICATION_OVERLAY) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                // TYPE_SYSTEM_OVERLAYはロック画面にもViewを表示できますが、タッチイベントを取得できません
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    // WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.LEFT or Gravity.TOP
        }

        loadButtonPosition()

        btnCamera.windowLayoutParams = layoutParam
        windowManager.addView(viewRoot, layoutParam)

        if (!isCapturing()) {
            try {
                Capture.updateMediaProjection()
            } catch (ex: Throwable) {
                log.eToast(this, ex, "updateMediaProjection failed.")
                stopSelf()
                return
            }
        }

        showButtonAll()
    }

    override fun onDestroy() {
        log.i("onDestroy start")
        removeActiveService(this)
        isDestroyed = true
        windowManager.removeView(viewRoot)
        stopForeground(true)

        if (this is CaptureServiceVideo) Capture.stopVideo()

        if (getServices().isEmpty() ) {
            log.i("onDestroy: captureJob join start")
            runBlocking {
                captureJob?.get()?.join()
            }
            log.i("onDestroy: captureJob join end")
            Capture.release()
        }

        showButtonAll()

        super.onDestroy()
        log.i("onDestroy end")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        reloadPosition()

        if (!isCapturing()) {
            try {
                Capture.updateMediaProjection()
            } catch (ex: Throwable) {
                log.eToast(this, ex, "updateMediaProjection failed.")
                stopSelf()
            }
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btnCamera -> captureStart()
        }
    }

    //////////////////////////////////////////////

    // 設定からボタン位置を読み直す
    // ただし反映はされない
    private fun loadButtonPosition() {
        val dm = resources.displayMetrics

        buttonSize = Pref.ipCameraButtonSize(App1.pref).toFloat().dp2px(dm)
        layoutParam.width = buttonSize
        layoutParam.height = buttonSize

        layoutParam.x = clipInt(
            0,
            dm.widthPixels - buttonSize,
            fpCameraButtonX(App1.pref).dp2px(dm)
        )

        layoutParam.y = clipInt(
            0,
            dm.heightPixels - buttonSize,
            fpCameraButtonY(App1.pref).dp2px(dm)
        )
    }

    // UIから呼ばれる
    fun reloadPosition() {
        loadButtonPosition()
        windowManager.updateViewLayout(viewRoot, layoutParam)
        btnCamera.updateExclusion()
    }

    //////////////////////////////////////////////
    // 撮影ボタンのドラッグ操作

    private var hideByTouching = false

    fun showButton() {
        if (isDestroyed) return

        val isCapturing = Capture.isCapturing

        // キャプチャ中は通知を変える
        notificationManager.notify(notificationId, createRunningNotification(isCapturing))

        // キャプチャ中はボタンを隠す(操作できない)
        btnCamera.vg(!isCapturing)

        val hideByTouching = getServices ().find{ it.hideByTouching } != null

        // タッチ中かつ非ドラッグ状態ならボタンを隠す(操作はできる)
        if (hideByTouching) {
            btnCamera.background = null
            btnCamera.setImageDrawable(null)
        } else {
            btnCamera.setBackgroundResource(R.drawable.btn_bg_round)
            btnCamera.setImageResource(startButtonId)
        }
    }

    private fun updateDragging(
        ev: MotionEvent,
        save: Boolean = false
    ): Boolean {
        val deltaX = ev.rawX - startMotionX
        val deltaY = ev.rawY - startMotionY
        if (!isDragging) {
            if (max(abs(deltaX), abs(deltaY)) < draggingThreshold) return false
            isDragging = true
            hideByTouching = false
            showButtonAll()
        }
        layoutParam.x = clipInt(0, maxX, startLpX + deltaX.toInt())
        layoutParam.y = clipInt(0, maxY, startLpY + deltaY.toInt())
        if (save) {
            val dm = resources.displayMetrics
            App1.pref.edit()
                .put(fpCameraButtonX, layoutParam.x.px2dp(dm))
                .put(fpCameraButtonY, layoutParam.y.px2dp(dm))
                .apply()
        }
        windowManager.updateViewLayout(viewRoot, layoutParam)
        btnCamera.updateExclusion()
        return true
    }

    override fun onTouch(v: View?, ev: MotionEvent): Boolean {
        if (v?.id != R.id.btnCamera) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dm = resources.displayMetrics
                startLpX = layoutParam.x
                startLpY = layoutParam.y
                startMotionX = ev.rawX
                startMotionY = ev.rawY
                draggingThreshold = resources.displayMetrics.density * 8f
                maxX = dm.widthPixels - buttonSize
                maxY = dm.heightPixels - buttonSize

                isDragging = false
                hideByTouching = true
                showButtonAll()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateDragging(ev)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!updateDragging(ev, save = true)) {
                    v.performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!updateDragging(ev, save = true)) {
                    hideByTouching = false
                    showButtonAll()
                }
                return true
            }
        }
        return false
    }

    ///////////////////////////////////////////////////////////////


    private fun createRunningNotification(isRecording: Boolean): Notification {

        val notificationChannelId = createNotificationChannel()

        return NotificationCompat.Builder(context, notificationChannelId)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .apply {
                val actionIndexes = arrangeNotification(this, isRecording)
                setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(*actionIndexes)
                )

            }
            .build()


    }

    fun captureStop() {
        Capture.stopVideo()
    }

    fun captureStart() {
        val timeClick = SystemClock.elapsedRealtime()

        // don't allow if service is not running
        if (isDestroyed) {
            log.e("captureStart(): service is already destroyed.")
            return
        }

        // don't allow other job is running.
        if (isCapturing()) {
            log.e("captureStart(): previous capture job is not complete.")
            return
        }

        hideByTouching = false

        Capture.isCapturing = true
        showButtonAll()
        captureJob = WeakReference(GlobalScope.launch(Dispatchers.IO) {
            try {
                val captureResult = Capture.capture(
                    context,
                    timeClick,
                    isVideo = this@CaptureServiceBase is CaptureServiceVideo
                )
                runOnMainThread {
                    afterCapture(captureResult)
                }
            } catch (ex: Throwable) {
                log.eToast(context, ex, "capture failed.")
            }
        })
    }

    abstract fun createNotificationChannel(): String

    abstract fun arrangeNotification(
        builder: NotificationCompat.Builder,
        isRecording: Boolean
    ): IntArray

    abstract fun afterCapture(captureResult: Capture.CaptureResult)


}