package jp.juggler.screenshotbutton

import android.annotation.SuppressLint
import android.app.*
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
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.max

abstract class CaptureServiceBase(
    val isVideo: Boolean
) : Service(), View.OnClickListener, View.OnTouchListener {

    companion object {
        val logCompanion = LogCategory("${App1.tagPrefix}/CaptureService")

        const val EXTRA_SCREEN_CAPTURE_INTENT = "screenCaptureIntent"

        private var captureJob: WeakReference<Job>? = null

        private var isVideoCaptureJob = false

        fun isCapturing() = captureJob?.get()?.isActive == true

        fun isVideoCapturing() = isCapturing() && isVideoCaptureJob

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
                logCompanion.d("showButtonAll")
                getServices().forEach { it.showButton() }
                ActMain.getActivity()?.showButton()
            }
        }

        fun getStopReason(serviceClass: Class<*>): String? {
            val key = "StopReason/${serviceClass.name}"
            return App1.pref.getString(key, null)
        }

        fun setStopReason(service: CaptureServiceBase, reason: String?) {
            val key = "StopReason/${service.javaClass.name}"
            App1.pref.edit().apply {
                if (reason == null) {
                    remove(key)
                } else {
                    putString(key, reason)
                }
            }.apply()
        }


    }

    private val log = LogCategory("${App1.tagPrefix}/${this.javaClass.simpleName}")

    val fpCameraButtonX = when {
        isVideo -> Pref.fpCameraButtonXVideo
        else -> Pref.fpCameraButtonXStill
    }

    val fpCameraButtonY = when {
        isVideo -> Pref.fpCameraButtonYVideo
        else -> Pref.fpCameraButtonYStill
    }

    @DrawableRes
    val startButtonId = when {
        isVideo -> R.drawable.ic_videocam
        else -> R.drawable.ic_camera
    }

    private val notificationId = when {
        isVideo -> NOTIFICATION_ID_RUNNING_VIDEO
        else -> NOTIFICATION_ID_RUNNING_STILL
    }

    private val pendingIntentRequestCodeRestart = when {
        isVideo -> PI_CODE_RESTART_VIDEO
        else -> PI_CODE_RESTART_STILL
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

    override fun onBind(intent: Intent): IBinder? = null

//    @Deprecated("Deprecated in API level 15")
//    override fun onStart(intent: Intent?, startId: Int) {
//        handleIntent(intent)
//    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleIntent(intent)
        return START_STICKY
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        log.d("onTaskRemoved")
        setStopReason(this, "onTaskRemoved")

        // restart service
        systemService<AlarmManager>(this)?.let { manager ->
            val pendingIntent = PendingIntent.getService(
                this,
                pendingIntentRequestCodeRestart,
                Intent(this, this.javaClass)
                    .apply {
                        val old = rootIntent?.getParcelableExtraCompat<Intent>(
                            EXTRA_SCREEN_CAPTURE_INTENT
                        )
                        if (old != null) putExtra(EXTRA_SCREEN_CAPTURE_INTENT, old)
                    },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= 23) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + 500,
                    pendingIntent
                )

            } else {
                manager.setExact(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + 500,
                    pendingIntent
                )
            }

        }

        super.onTaskRemoved(rootIntent)
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
        App1.prepareAppState(context)
        addActiveService(this)
        setStopReason(this, null)

        super.onCreate()

        notificationManager = systemService(context)!!
        windowManager = systemService(context)!!

        @SuppressLint("InflateParams")
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

        showButtonAll()
    }

    override fun onDestroy() {
        log.i("onDestroy start. stopReason=${getStopReason(this.javaClass)}")
        removeActiveService(this)
        isDestroyed = true
        windowManager.removeView(viewRoot)
        stopForeground(true)

        if (this is CaptureServiceVideo) Capture.stopVideo()

        if (getServices().isEmpty()) {
            log.i("onDestroy: captureJob join start")
            runBlocking {
                captureJob?.get()?.join()
            }
            log.i("onDestroy: captureJob join end")
            Capture.release("service.onDestroy")
        }

        showButtonAll()

        super.onDestroy()
        log.i("onDestroy end")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        reloadPosition()

        if (!isCapturing() && Capture.canCapture()) {
            try {
                Capture.updateMediaProjection("service.onConfigurationChanged")
            } catch (ex: Throwable) {
                log.eToast(this, ex, "updateMediaProjection failed.")
                stopWithReason("UpdateMediaProjectionFailedAtConfigurationChanged")
            }
        }
        /*
            プロセス生成直後、 onCreate の後 onStart** が呼ばれる前に onConfigurationChanged が何度か呼ばれる場合がある。
            この時は updateMediaProjection() を呼び出して screenCaptureIntent==null で例外を出してサービスを止めてしまってはいけない。
            既にmediaProjectionが存在する&& キャプチャ中ではない場合のみ updateMediaProjection() を呼び出すべきだ。
        */
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btnCamera -> captureStart()
        }
    }

    //////////////////////////////////////////////

    private fun handleIntent(intent: Intent?) {
        val screenCaptureIntent = intent?.getParcelableExtraCompat<Intent>(EXTRA_SCREEN_CAPTURE_INTENT)
        if (screenCaptureIntent != null && Capture.screenCaptureIntent == null) {
            Capture.handleScreenCaptureIntentResult(this, Activity.RESULT_OK, screenCaptureIntent)
        }
    }


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

        val hideByTouching = getServices().find { it.hideByTouching } != null

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


        val channelId = when {
            isVideo -> NOTIFICATION_CHANNEL_VIDEO
            else -> NOTIFICATION_CHANNEL_STILL
        }
        createNotificationChannel(channelId)

        return NotificationCompat.Builder(context, channelId)
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

    fun stopWithReason(reason: String) {
        setStopReason(this, reason)
        stopSelf()
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

        log.w("captureStart 1")

        hideByTouching = false

        Capture.isCapturing = true
        showButtonAll()
        isVideoCaptureJob = isVideo
        captureJob = WeakReference(EmptyScope.launch(Dispatchers.IO) {
            for (nTry in 1..3) {
                log.w("captureJob try $nTry")
                try {
                    val captureResult = Capture.capture(
                        context,
                        timeClick,
                        isVideo = this@CaptureServiceBase is CaptureServiceVideo
                    )
                    log.w("captureJob captureResult=$captureResult")
                    runOnMainThread {
                        if (Pref.bpShowPostView(App1.pref)) {
                            openPostView(captureResult)
                        }
                    }
                    break
                } catch (ex: Capture.ScreenCaptureIntentError) {

                    try {
                        log.e(ex, "captureJob failed. open activity…")
                        val state = suspendCoroutine<Capture.MediaProjectionState> { cont ->
                            ActScreenCaptureIntent.cont = cont
                            startActivity(
                                Intent(
                                    this@CaptureServiceBase,
                                    ActScreenCaptureIntent::class.java
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                                }
                            )
                        }
                        if (state != Capture.MediaProjectionState.HasScreenCaptureIntent) {
                            log.d("resumed state is $state")
                            break
                        }
                        Capture.updateMediaProjection("recovery")
                        delay(500L)
                        continue
                    } catch (ex: Throwable) {
                        log.eToast(context, ex, "recovery failed.")
                        break
                    }

                } catch (ex: Throwable) {
                    log.eToast(context, ex, "capture failed.")
                    break
                }
            }
        })
    }


    abstract fun createNotificationChannel(channelId: String)

    abstract fun arrangeNotification(
        builder: NotificationCompat.Builder,
        isRecording: Boolean
    ): IntArray

    abstract fun openPostView(captureResult: Capture.CaptureResult)

}

// サービスが存在しなければ通知を消す
// 存在するならコールバックを実行する
fun <T : CaptureServiceBase, R : Any?> T?.runOnService(
    context: Context,
    notificationId: Int? = null,
    block: T.() -> R
): R? = when (this) {
    null -> {
        CaptureServiceBase.logCompanion.eToast(context, false, "service not running.")
        if (notificationId != null) {
            systemService<NotificationManager>(context)?.cancel(notificationId)
        }
        null
    }
    else -> block.invoke(this)
}